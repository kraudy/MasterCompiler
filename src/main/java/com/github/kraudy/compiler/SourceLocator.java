package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

/*
 * find_source: where the IBM i says an object was compiled from, so an agent can read (or import) the
 * source of a program the project uses but does not build.
 *
 *   OPM programs, DDS files, commands   OBJECT_STATISTICS (the object description's source member)
 *   ILE programs, service programs      BOUND_MODULE_INFO (each bound module's member or stream file)
 *   service program binder source       PROGRAM_INFO
 *   SQL tables, views, indexes          no member: their DDL is generated (QSYS2.GENERATE_SQL) on import
 *
 * Unqualified names are looked up on the job's library list. Each member is checked against the
 * source timestamp the compile recorded: a member changed since then may not match the object.
 * The members' /COPY, /INCLUDE and EXEC SQL INCLUDE members are listed too (resolved like the compiler).
 */
public class SourceLocator {
  /* Columns 1-6 may hold a sequence/change marker or the spec type (D/COPY, C/COPY); free form starts anywhere */
  private static final Pattern RPG_COPY = Pattern.compile("^.{0,6}?\\s*/(?:COPY|INCLUDE)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SQL_INCLUDE = Pattern.compile("\\bEXEC\\s+SQL\\s+INCLUDE\\s+(\\S+?)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final List<String> DDL_TYPES = Arrays.asList("TABLE", "VIEW", "INDEX");
  private static final String DEFAULT_TYPES = "*PGM *SRVPGM *MODULE *FILE *CMD *BNDDIR";
  private static final int COPY_DEPTH = 5;

  private final Connection connection;

  public SourceLocator(Connection connection) {
    this.connection = connection;
  }

  /* One source of an object: a member, a stream file, or generated DDL */
  public static class SourceRef {
    public String library;
    public String sourceFile;
    public String member;
    public String streamFile;
    public String sourceType;
    public String module;        // ILE: the bound module built from it
    public String status;        // ok, changed_since_compile, missing, stream_file, ddl
    public String note;
    @JsonIgnore LibraryImporter.Built built;
    @JsonIgnore String ddlName;  // GENERATE_SQL object name
    @JsonIgnore String ddlType;

    String key() {
      return library + "/" + sourceFile + "/" + member;
    }
  }

  public static class Located {
    public String request;
    public String object;        // LIB/NAME *TYPE
    public String attribute;
    public String sqlType;
    public List<SourceRef> sources = new ArrayList<SourceRef>();
    public List<SourceRef> copybooks = new ArrayList<SourceRef>();
    public List<String> boundServicePrograms;   // *PGM / *SRVPGM: what it binds to
    public List<String> bindingDirectoryEntries; // *BNDDIR: what it holds
    public String note;
  }

  /* Each request is NAME, LIB/NAME, optionally followed by the type: "CUSTSRV *SRVPGM" (the build report's external names) */
  public List<Located> locate(List<String> requests, boolean withCopybooks) throws SQLException {
    List<Located> all = new ArrayList<Located>();
    for (String request : requests) {
      String[] parts = request.trim().toUpperCase().split("\\s+");
      String qualified = parts[0];
      String types = parts.length > 1 ? parts[1] : DEFAULT_TYPES;
      String library = qualified.contains("/") ? qualified.substring(0, qualified.indexOf('/')) : "*LIBL";
      String name = qualified.substring(qualified.indexOf('/') + 1);

      List<Located> found = objects(request.trim(), library, name, types);
      if (found.isEmpty()) {
        Located missing = new Located();
        missing.request = request.trim();
        missing.note = "Not found in " + (library.equals("*LIBL") ? "the library list" : library) + " as " + types;
        all.add(missing);
        continue;
      }
      for (Located located : found) {
        if (withCopybooks) copybooks(located);
        all.add(located);
      }
    }
    return all;
  }

  /* The object in its first library-list library (or the given one), one entry per type found there */
  private List<Located> objects(String request, String library, String name, String types) throws SQLException {
    List<Object[]> rows = new ArrayList<Object[]>();
    int first = Integer.MAX_VALUE;
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(O.OBJLIB), TRIM(O.OBJNAME), TRIM(O.OBJTYPE), COALESCE(TRIM(O.OBJATTRIBUTE), ''), " +
        "COALESCE(TRIM(O.SQL_OBJECT_TYPE), ''), COALESCE(TRIM(O.SOURCE_LIBRARY), ''), COALESCE(TRIM(O.SOURCE_FILE), ''), " +
        "COALESCE(TRIM(O.SOURCE_MEMBER), ''), O.SOURCE_TIMESTAMP, COALESCE(O.OBJLONGNAME, O.OBJNAME), " +
        "COALESCE(L.ORDINAL_POSITION, 0) " +
        "FROM TABLE(QSYS2.OBJECT_STATISTICS(?, ?, OBJECT_NAME => ?)) O " +
        "LEFT JOIN QSYS2.LIBRARY_LIST_INFO L ON L.SYSTEM_SCHEMA_NAME = O.OBJLIB")) {
      stmt.setString(1, library);
      stmt.setString(2, types);
      stmt.setString(3, name);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          Object[] row = new Object[11];
          for (int i = 0; i < 11; i++) row[i] = rs.getObject(i + 1);
          rows.add(row);
          first = Math.min(first, ((Number) row[10]).intValue());
        }
      }
    } catch (SQLException e) {
      if ("42704".equals(e.getSQLState()) || e.getErrorCode() == -443) return new ArrayList<Located>();  // library not found
      throw e;
    }

    List<Located> found = new ArrayList<Located>();
    for (Object[] row : rows) {
      if (((Number) row[10]).intValue() != first) continue;  // a same-named object later in the library list
      Located located = new Located();
      located.request = request;
      String lib = (String) row[0], obj = (String) row[1], type = (String) row[2];
      located.object = lib + "/" + obj + " " + type;
      located.attribute = (String) row[3];
      located.sqlType = ((String) row[4]).isEmpty() ? null : (String) row[4];
      sources(located, lib, obj, type, (String) row[5], (String) row[6], (String) row[7], (Timestamp) row[8],
          ((String) row[9]).trim());
      found.add(located);
    }
    return found;
  }

  private void sources(Located located, String lib, String obj, String type, String srcLib, String srcFile,
      String srcMbr, Timestamp compiled, String longName) throws SQLException {
    if (type.equals("*BNDDIR")) {
      located.bindingDirectoryEntries = list(
          "SELECT TRIM(ENTRY_LIBRARY) || '/' || TRIM(ENTRY) || ' ' || TRIM(ENTRY_TYPE) FROM QSYS2.BINDING_DIRECTORY_INFO " +
          "WHERE BINDING_DIRECTORY_LIBRARY = ? AND BINDING_DIRECTORY = ?", lib, obj);
      located.note = "A binding directory has no source: its entries are listed";
      return;
    }
    if (type.equals("*PGM") || type.equals("*SRVPGM")) {
      located.boundServicePrograms = list(
          "SELECT TRIM(BOUND_SERVICE_PROGRAM_LIBRARY) || '/' || TRIM(BOUND_SERVICE_PROGRAM) FROM QSYS2.BOUND_SRVPGM_INFO " +
          "WHERE PROGRAM_LIBRARY = ? AND PROGRAM_NAME = ? AND OBJECT_TYPE = '" + type + "'", lib, obj);
      boundModules(located, lib, obj, type);
      if (type.equals("*SRVPGM")) binderSource(located, lib, obj);
      if (!located.sources.isEmpty()) return;
    }
    if (!srcMbr.isEmpty()) {
      SourceRef ref = member(srcLib, srcFile, srcMbr, compiled);
      String mcType = LibraryImporter.objectType(type, located.attribute);
      if (mcType != null) ref.built = new LibraryImporter.Built(obj, mcType, "object_statistics");
      located.sources.add(ref);
      return;
    }
    String ddlType = located.sqlType != null ? located.sqlType
        : type.equals("*FILE") && "PF".equals(located.attribute) ? "TABLE"
        : type.equals("*FILE") && "LF".equals(located.attribute) ? "VIEW" : null;
    if (ddlType != null && DDL_TYPES.contains(ddlType)) {
      SourceRef ddl = new SourceRef();
      ddl.library = lib;
      ddl.member = obj;
      ddl.sourceType = "SQL";
      ddl.status = "ddl";
      ddl.note = "No source member recorded: import_source generates its " + ddlType + " DDL (QSYS2.GENERATE_SQL)";
      ddl.ddlName = longName;
      ddl.ddlType = ddlType;
      ddl.built = new LibraryImporter.Built(obj, ddlType.toLowerCase(), "generate_sql");
      located.sources.add(ddl);
      return;
    }
    located.note = located.sqlType != null
        ? "SQL " + located.sqlType.toLowerCase() + ": no source member recorded; read its definition with SQL (Db2 for i)"
        : "The object description names no source member";
  }

  /* One text column per row; null when the catalog view is not there (older releases) */
  private List<String> list(String sql, String a, String b) {
    List<String> rows = new ArrayList<String>();
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, a);
      stmt.setString(2, b);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) rows.add(rs.getString(1));
      }
    } catch (SQLException e) {
      return null;
    }
    return rows;
  }

  /* ILE: one source per bound module (a module named like its *PGM is a CRTBND* program) */
  private void boundModules(Located located, String lib, String obj, String type) throws SQLException {
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(BOUND_MODULE_LIBRARY), TRIM(BOUND_MODULE), COALESCE(TRIM(SOURCE_FILE_LIBRARY), ''), " +
        "COALESCE(TRIM(SOURCE_FILE), ''), COALESCE(TRIM(SOURCE_FILE_MEMBER), ''), SOURCE_STREAM_FILE_PATH, SOURCE_CHANGE_TIMESTAMP " +
        "FROM QSYS2.BOUND_MODULE_INFO WHERE PROGRAM_LIBRARY = ? AND PROGRAM_NAME = ? AND OBJECT_TYPE = ?")) {
      stmt.setString(1, lib);
      stmt.setString(2, obj);
      stmt.setString(3, type);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          String module = rs.getString(2);
          boolean bndProgram = type.equals("*PGM") && module.equals(obj);
          boolean ownModule = type.equals("*SRVPGM") && module.equals(obj);  // NAME.srvpgm.rpgle builds both
          SourceRef ref;
          if (!rs.getString(5).isEmpty()) {
            ref = member(rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(7));
          } else if (rs.getString(6) != null && !rs.getString(6).trim().isEmpty()) {
            ref = new SourceRef();
            ref.streamFile = rs.getString(6).trim();
            ref.status = "stream_file";
            ref.note = "Compiled from a stream file on the IFS";
          } else {
            ref = new SourceRef();
            ref.status = "missing";
            ref.note = "The module records no source";
          }
          ref.module = rs.getString(1) + "/" + module;
          ref.built = new LibraryImporter.Built(bndProgram || ownModule ? obj : module,
              bndProgram ? "pgm" : ownModule ? "srvpgm" : "module", "bound_module");
          located.sources.add(ref);
        }
      }
    }
  }

  private void binderSource(Located located, String lib, String obj) {
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT COALESCE(TRIM(EXPORT_SOURCE_LIBRARY), ''), COALESCE(TRIM(EXPORT_SOURCE_FILE), ''), " +
        "COALESCE(TRIM(EXPORT_SOURCE_FILE_MEMBER), '') FROM QSYS2.PROGRAM_INFO " +
        "WHERE PROGRAM_LIBRARY = ? AND PROGRAM_NAME = ? AND OBJECT_TYPE = '*SRVPGM'")) {
      stmt.setString(1, lib);
      stmt.setString(2, obj);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next() && !rs.getString(3).isEmpty()) {
          SourceRef ref = member(rs.getString(1), rs.getString(2), rs.getString(3), null);
          ref.built = new LibraryImporter.Built(obj, "srvpgm", "program_info");
          located.sources.add(ref);
        }
      }
    } catch (SQLException e) {
      located.note = "Binder source not read: " + e.getMessage();
    }
  }

  /* A member and whether it changed after the compile that recorded the given source timestamp */
  private SourceRef member(String library, String file, String member, Timestamp compiled) throws SQLException {
    SourceRef ref = new SourceRef();
    ref.library = library;
    ref.sourceFile = file;
    ref.member = member;
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT COALESCE(TRIM(SOURCE_TYPE), ''), LAST_SOURCE_UPDATE_TIMESTAMP FROM QSYS2.SYSPARTITIONSTAT " +
        "WHERE SYSTEM_TABLE_SCHEMA = ? AND SYSTEM_TABLE_NAME = ? AND SYSTEM_TABLE_MEMBER = ?")) {
      stmt.setString(1, library);
      stmt.setString(2, file);
      stmt.setString(3, member);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          ref.status = "missing";
          ref.note = "The member no longer exists";
          return ref;
        }
        ref.sourceType = rs.getString(1);
        Timestamp updated = rs.getTimestamp(2);
        boolean changed = compiled != null && updated != null && updated.getTime() / 1000 > compiled.getTime() / 1000;
        ref.status = changed ? "changed_since_compile" : "ok";
        if (changed) ref.note = "Changed after the object was compiled (" + updated + "): it may not match the object";
      }
    }
    return ref;
  }

  /* Members the sources copy in, followed into the copybooks' own copies */
  private void copybooks(Located located) throws SQLException {
    Set<String> seen = new LinkedHashSet<String>();
    List<SourceRef> pending = new ArrayList<SourceRef>();
    for (SourceRef ref : located.sources) {
      if (ref.member != null && ref.sourceFile != null) {
        seen.add(ref.key());
        pending.add(ref);
      }
    }
    for (int depth = 0; depth < COPY_DEPTH && !pending.isEmpty(); depth++) {
      List<SourceRef> next = new ArrayList<SourceRef>();
      for (SourceRef ref : pending) {
        if (!"ok".equals(ref.status) && !"changed_since_compile".equals(ref.status)) continue;
        for (String[] copy : copyDirectives(LibraryImporter.readMember(connection, ref.library, ref.sourceFile, ref.member), ref.sourceFile)) {
          SourceRef copybook = resolveCopy(copy[0], copy[1], copy[2], ref.library);
          if (!seen.add(copybook.key())) continue;
          located.copybooks.add(copybook);
          next.add(copybook);
        }
      }
      pending = next;
    }
  }

  /* {library or null, file, member} of each copy directive; stream file paths are skipped */
  static List<String[]> copyDirectives(List<String> lines, String ownFile) {
    List<String[]> copies = new ArrayList<String[]>();
    for (String line : lines) {
      if (line.trim().startsWith("//") || (line.length() > 6 && line.charAt(6) == '*')) continue;
      Matcher rpg = RPG_COPY.matcher(line);
      Matcher sql = SQL_INCLUDE.matcher(line);
      String operand;
      String defaultFile;
      if (rpg.find()) {
        operand = rpg.group(1);
        defaultFile = "QRPGLESRC";
      } else if (sql.find()) {
        operand = sql.group(1);
        defaultFile = ownFile;  // INCFILE(*SRCFILE)
      } else {
        continue;
      }
      operand = operand.replace("'", "").replace("\"", "").toUpperCase();
      if (operand.contains(".") || operand.startsWith("/")) continue;  // stream file
      String library = null;
      String file = defaultFile;
      String member = operand;
      if (operand.contains(",")) {
        file = operand.substring(0, operand.indexOf(','));
        member = operand.substring(operand.indexOf(',') + 1);
        if (file.contains("/")) {
          library = file.substring(0, file.indexOf('/'));
          file = file.substring(file.indexOf('/') + 1);
        }
      }
      copies.add(new String[] { library, file, member });
    }
    return copies;
  }

  /* Explicit library, else the including source's library, else the first library-list library that has it */
  private SourceRef resolveCopy(String library, String file, String member, String sourceLibrary) throws SQLException {
    String found = null;
    int best = Integer.MAX_VALUE;
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(P.SYSTEM_TABLE_SCHEMA), COALESCE(L.ORDINAL_POSITION, 0) FROM QSYS2.SYSPARTITIONSTAT P " +
        "LEFT JOIN QSYS2.LIBRARY_LIST_INFO L ON L.SYSTEM_SCHEMA_NAME = P.SYSTEM_TABLE_SCHEMA " +
        "WHERE P.SYSTEM_TABLE_NAME = ? AND P.SYSTEM_TABLE_MEMBER = ? AND (P.SYSTEM_TABLE_SCHEMA = ? OR " +
        "P.SYSTEM_TABLE_SCHEMA IN (SELECT SYSTEM_SCHEMA_NAME FROM QSYS2.LIBRARY_LIST_INFO))")) {
      stmt.setString(1, file);
      stmt.setString(2, member);
      stmt.setString(3, library != null ? library : sourceLibrary);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          String lib = rs.getString(1);
          int rank = lib.equals(library != null ? library : sourceLibrary) ? -1 : library != null ? Integer.MAX_VALUE : rs.getInt(2);
          if (rank < best) {
            best = rank;
            found = lib;
          }
        }
      }
    }
    if (found == null) {
      SourceRef ref = new SourceRef();
      ref.library = library != null ? library : "*LIBL";
      ref.sourceFile = file;
      ref.member = member;
      ref.status = "missing";
      ref.note = "Copy member not found in " + (library != null ? library : "the source library or the library list");
      return ref;
    }
    SourceRef ref = member(found, file, member, null);
    ref.built = null;  // named as a copybook on import
    return ref;
  }
}
