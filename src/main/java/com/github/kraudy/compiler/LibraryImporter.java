package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileOutputStream;

/*
 * --import <selection> -o <dir>: turns source members into an MC repository.
 * The selection is a comma-separated list of LIB, LIB/SRCPF or LIB/SRCPF/MBR (MBR may end with *).
 * One library writes <dir>/<SRCPF>/..., several write <dir>/<LIB>/<SRCPF>/...
 *
 * Every member is written as a UTF-8 stream file, <dir>/<SRCPF>/<name>, named with the MC
 * convention from the object the IBM i says was built from it:
 *   OBJECT_STATISTICS   OPM programs, DDS files, commands (object description source)
 *   BOUND_MODULE_INFO   ILE programs and service programs (source of each bound module)
 *   PROGRAM_INFO        service program binder source (EXPORT source)
 *   SQL objects         SQL source matched by object name (table.sql, view.sql, ...)
 * Members no object points at: DDS by member type (CUSTPF.pf.dds), members other sources
 * /COPY or /INCLUDE become NAME.include.<type> (scan skips them), the rest keep their source
 * type and are flagged in the report. Then the directory is scanned into build.yaml.
 */
public class LibraryImporter {
  private static final Logger logger = LoggerFactory.getLogger(LibraryImporter.class);

  private static final Pattern NOMAIN = Pattern.compile("\\bNOMAIN\\b", Pattern.CASE_INSENSITIVE);
  private static final List<String> DDS_TYPES = Arrays.asList("PF", "LF", "DSPF", "PRTF");

  private final AS400 system;
  private final Connection connection;
  private final boolean verbose;
  private boolean keepExisting;        // import_source into the repository: never overwrite a file
  private boolean writeReportFile = true;
  private boolean dryRun;                // import_source dryRun: name and check everything, write nothing
  private List<String> searched = new ArrayList<String>();  // libraries looked in for objects built from the members

  public LibraryImporter(AS400 system, Connection connection, boolean verbose) {
    this.system = system;
    this.connection = connection;
    this.verbose = verbose;
  }

  /* One member in the import report */
  public static class ImportedMember {
    public String library;
    public String sourceFile;
    public String member;
    public String sourceType;
    public String file;     // written path, relative to the output directory
    public String object;   // object built from it, when known (e.g. HOLA *PGM)
    public String how;      // object_statistics, bound_module, program_info, sql_object, member_type, dds_content, copybook, assumed, other
    public String note;
    public Boolean kept;    // the file was already there and was left as it is
  }

  public static class ImportReport {
    public List<String> selection = new ArrayList<String>();
    public String output;
    public int members;
    public int fromObjects;
    public int copybooks;
    public int assumed;
    public int errors;
    public List<ImportedMember> files = new ArrayList<ImportedMember>();
  }

  /* Object a member was compiled into */
  static final class Built {
    final String name;
    final String objectType;   // MC object type, lower case (pgm, module, pf, ...)
    final String how;
    Built(String name, String objectType, String how) {
      this.name = name;
      this.objectType = objectType;
      this.how = how;
    }
  }

  public void setKeepExisting(boolean keepExisting) {
    this.keepExisting = keepExisting;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public void setWriteReportFile(boolean writeReportFile) {
    this.writeReportFile = writeReportFile;
  }

  public ImportReport run(String selection, String outDir) throws Exception {
    /* library -> selectors ("SRCPF", "SRCPF/MBR", "SRCPF/ART*"; empty = the whole library) */
    Map<String, List<String>> libraries = new LinkedHashMap<String, List<String>>();
    ImportReport report = new ImportReport();
    report.output = outDir;
    for (String item : selection.split(",")) {
      String sel = item.trim().toUpperCase();
      if (sel.isEmpty()) continue;
      report.selection.add(sel);
      int slash = sel.indexOf('/');
      String library = slash < 0 ? sel : sel.substring(0, slash);
      List<String> selectors = libraries.computeIfAbsent(library, k -> new ArrayList<String>());
      if (slash >= 0) selectors.add(sel.substring(slash + 1));
      else selectors.add("*");
    }
    boolean perLibrary = libraries.size() > 1;

    for (Map.Entry<String, List<String>> lib : libraries.entrySet()) {
      String library = lib.getKey();
      Map<String, String> members = listMembers(library, lib.getValue());  // "SRCPF/MBR" -> source type
      if (members.isEmpty()) throw new CompilerException(missing(library, lib.getValue()));
      Map<String, Built> built = builtObjects(library);
      String base = perLibrary ? outDir + "/" + library : outDir;

      /* Read every member first: copybooks are recognised by the /COPY statements of the others */
      Map<String, List<String>> sources = new LinkedHashMap<String, List<String>>();
      for (String key : members.keySet()) {
        String[] fm = key.split("/");
        try {
          sources.put(key, readMember(library, fm[0], fm[1]));
        } catch (SQLException e) {
          ImportedMember failed = member(library, fm[0], fm[1], members.get(key));
          failed.how = "error";
          failed.note = e.getMessage();
          report.files.add(failed);
          report.errors++;
        }
      }
      Set<String> copied = copiedMembers(sources);

      for (Map.Entry<String, List<String>> source : sources.entrySet()) {
        String[] fm = source.getKey().split("/");
        String sourceType = members.get(source.getKey());
        ImportedMember entry = member(library, fm[0], fm[1], sourceType);
        Built object = built.get(source.getKey());
        if (object == null && "SQL".equals(sourceType)) object = built.get("SQL:" + fm[1]);
        if (object == null && "DDS".equals(sourceType)) object = ddsFromContent(fm[1], source.getValue());
        name(entry, object, copied.contains(source.getKey()), source.getValue());

        if (keepExisting && exists(base + "/" + entry.file)) entry.kept = true;
        else write(base + "/" + entry.file, source.getValue());
        if (perLibrary) entry.file = library + "/" + entry.file;
        report.files.add(entry);
        report.members++;
        if (entry.object != null && !"dds_content".equals(entry.how)) report.fromObjects++;
        if ("copybook".equals(entry.how)) report.copybooks++;
        if ("assumed".equals(entry.how)) report.assumed++;
        if (verbose) logger.info("{}/{}/{} -> {} ({})", library, fm[0], fm[1], entry.file, entry.how);
      }
    }

    if (writeReportFile) writeReport(report, outDir + "/mc-import.json");
    logger.info("Imported {} members from {} into {}: {} from objects, {} copybooks, {} assumed, {} errors",
        report.members, report.selection, outDir, report.fromObjects, report.copybooks, report.assumed, report.errors);
    return report;
  }

  private ImportedMember member(String library, String file, String member, String sourceType) {
    ImportedMember entry = new ImportedMember();
    entry.library = library;
    entry.sourceFile = file;
    entry.member = member;
    entry.sourceType = sourceType;
    return entry;
  }

  /* MC file name: OBJECT.objecttype.sourcetype when the object type is known */
  private void name(ImportedMember entry, Built object, boolean isCopied, List<String> lines) {
    String type = entry.sourceType.isEmpty() ? "txt" : entry.sourceType.toLowerCase();
    String dir = entry.sourceFile + "/";

    if (object != null) {
      String sourceType = DDS_TYPES.contains(entry.sourceType) ? "dds" : type;
      String candidate = object.name + "." + object.objectType + "." + sourceType;
      if (SourceNaming.parseFileName(candidate).isPresent()) {
        entry.file = dir + candidate;
        entry.how = object.how;
        if ("dds_content".equals(object.how)) {
          entry.note = "Member type is DDS; " + object.objectType.toUpperCase() + " inferred from its keywords";
        } else {
          entry.object = object.name + " *" + object.objectType.toUpperCase();
        }
        return;
      }
    }
    if (DDS_TYPES.contains(entry.sourceType)) {
      entry.file = dir + entry.member + "." + type + ".dds";
      entry.how = "member_type";
      return;
    }
    if (isCopied) {
      /* TXT / untyped members used as RPG copybooks: .include.rpgle, so editors and the scan read them as RPG */
      String copyType = type.equals("txt") || type.isEmpty() ? "rpgle" : type;
      entry.file = dir + entry.member + ".include." + copyType;
      entry.how = "copybook";
      return;
    }
    if (isDocumentation(lines)) {
      /* only comments: a description member kept next to the program, not something to compile */
      entry.file = dir + entry.member + ".doc.txt";
      entry.how = "documentation";
      return;
    }
    entry.file = dir + entry.member + "." + type;
    if (isNoMain(lines)) {
      /* NOMAIN (ctl-opt nomain / H NOMAIN): procedures only, compiled as a module */
      entry.file = dir + entry.member + ".module." + type;
      if (SourceNaming.parseFileName(entry.file).isPresent()) {
        entry.how = "nomain";
        entry.note = "No object names this member as its source; it is NOMAIN, so it is imported as a *MODULE";
        return;
      }
      entry.file = dir + entry.member + "." + type;
    }
    if (SourceNaming.parseFileName(entry.file).isPresent()) {
      entry.how = "assumed";
      entry.note = "No object in " + String.join(", ", searched) + " (the source library and the library list) "
          + "names this member as its source, so the name is assumed from the member type: the scan treats it as a "
          + SourceNaming.parseFileName(entry.file).get().objectType.name().toLowerCase() + " target. If the object is "
          + "elsewhere, import it by object name (objects) to name the file after it";
    } else {
      entry.how = "other";
      entry.note = "Not a source type MC compiles; the scan skips it";
    }
  }

  /* Members of the library's source files (FILE_TYPE S) matching any selector */
  private Map<String, String> listMembers(String library, List<String> selectors) throws SQLException {
    Map<String, String> members = new LinkedHashMap<String, String>();
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(
           "SELECT P.SYSTEM_TABLE_NAME AS SRCPF, P.SYSTEM_TABLE_MEMBER AS MBR, COALESCE(P.SOURCE_TYPE, '') AS SRCTYPE " +
           "FROM QSYS2.SYSPARTITIONSTAT P " +
           "INNER JOIN QSYS2.SYSTABLES T " +
             "ON T.SYSTEM_TABLE_SCHEMA = P.SYSTEM_TABLE_SCHEMA AND T.SYSTEM_TABLE_NAME = P.SYSTEM_TABLE_NAME " +
           "WHERE P.SYSTEM_TABLE_SCHEMA = '" + library + "' AND T.FILE_TYPE = 'S' " +
           "ORDER BY P.SYSTEM_TABLE_NAME, P.SYSTEM_TABLE_MEMBER")) {
      while (rs.next()) {
        String file = rs.getString("SRCPF").trim();
        String member = rs.getString("MBR").trim();
        if (selected(file, member, selectors)) members.put(file + "/" + member, rs.getString("SRCTYPE").trim());
      }
    }
    return members;
  }

  /* "*" = everything, "SRCPF" = one source file, "SRCPF/MBR" or "SRCPF/PREFIX*" = members */
  private static boolean selected(String file, String member, List<String> selectors) {
    for (String sel : selectors) {
      if (sel.equals("*")) return true;
      String[] parts = sel.split("/");
      if (!parts[0].equals(file)) continue;
      if (parts.length == 1) return true;
      String pattern = parts[1];
      if (pattern.endsWith("*") ? member.startsWith(pattern.substring(0, pattern.length() - 1)) : member.equals(pattern)) {
        return true;
      }
    }
    return false;
  }

  /* "SRCPF/MBR" -> object built from it, for objects in the library whose source is in the library */
  /*
   * Objects whose source is in this library are looked for in the library itself and in the job's
   * library list (programs are often compiled into another library than their sources)
   */
  private Map<String, Built> builtObjects(String library) throws SQLException {
    Map<String, Built> built = new HashMap<String, Built>();
    Map<String, String> sqlObjects = new HashMap<String, String>();
    java.util.LinkedHashSet<String> libs = new java.util.LinkedHashSet<String>();
    libs.add(library);
    libs.addAll(userLibraryList());
    searched = new ArrayList<String>(libs);

    try (Statement stmt = connection.createStatement()) {
      for (String objLib : searched) {
        boolean own = objLib.equals(library);
        try (ResultSet rs = stmt.executeQuery(
            "SELECT OBJNAME, OBJTYPE, COALESCE(OBJATTRIBUTE, '') AS ATTR, COALESCE(SOURCE_LIBRARY, '') AS SRCLIB, " +
            "COALESCE(SOURCE_FILE, '') AS SRCPF, COALESCE(SOURCE_MEMBER, '') AS MBR, COALESCE(SQL_OBJECT_TYPE, '') AS SQLTYPE " +
            "FROM TABLE(QSYS2.OBJECT_STATISTICS('" + objLib + "', '" + (own ? "*ALL" : "*PGM *MODULE *FILE *CMD *MNU *QMQRY") + "'))")) {
          while (rs.next()) {
            String name = rs.getString("OBJNAME").trim();
            String sqlType = rs.getString("SQLTYPE").trim();
            if (own && !sqlType.isEmpty()) sqlObjects.put(name, sqlType.toLowerCase());
            if (!library.equals(rs.getString("SRCLIB").trim()) || rs.getString("MBR").trim().isEmpty()) continue;
            String type = objectType(rs.getString("OBJTYPE").trim(), rs.getString("ATTR").trim());
            if (type != null) {
              built.putIfAbsent(rs.getString("SRCPF").trim() + "/" + rs.getString("MBR").trim(),
                  new Built(name, type, "object_statistics"));
            }
          }
        } catch (SQLException e) {
          logger.info("Objects of {} not read: {}", objLib, e.getMessage());
        }

        /* ILE: the program's object description has no source; its bound modules do.
           A module named like its *PGM is a CRTBND* program (or the *SRVPGM's own module), any other a *MODULE. */
        try (ResultSet rs = stmt.executeQuery(
            "SELECT B.PROGRAM_NAME, B.OBJECT_TYPE, B.BOUND_MODULE, B.SOURCE_FILE, B.SOURCE_FILE_MEMBER " +
            "FROM TABLE(QSYS2.OBJECT_STATISTICS('" + objLib + "', '*PGM *SRVPGM')) O " +
            "INNER JOIN QSYS2.BOUND_MODULE_INFO B " +
              "ON B.PROGRAM_LIBRARY = '" + objLib + "' AND B.PROGRAM_NAME = O.OBJNAME AND B.OBJECT_TYPE = O.OBJTYPE " +
            "WHERE B.SOURCE_FILE_LIBRARY = '" + library + "' AND B.SOURCE_FILE_MEMBER IS NOT NULL")) {
          while (rs.next()) {
            String program = rs.getString("PROGRAM_NAME").trim();
            String module = rs.getString("BOUND_MODULE").trim();
            boolean bndProgram = "*PGM".equals(rs.getString("OBJECT_TYPE").trim()) && program.equals(module);
            boolean ownModule = "*SRVPGM".equals(rs.getString("OBJECT_TYPE").trim()) && program.equals(module);
            built.putIfAbsent(rs.getString("SOURCE_FILE").trim() + "/" + rs.getString("SOURCE_FILE_MEMBER").trim(),
                new Built(bndProgram || ownModule ? program : module, bndProgram ? "pgm" : ownModule ? "srvpgm" : "module",
                    "bound_module"));
          }
        } catch (SQLException e) {
          logger.info("Bound modules of {} not read: {}", objLib, e.getMessage());
        }

        try (ResultSet rs = stmt.executeQuery(
            "SELECT PROGRAM_NAME, EXPORT_SOURCE_FILE, EXPORT_SOURCE_FILE_MEMBER FROM QSYS2.PROGRAM_INFO " +
            "WHERE PROGRAM_LIBRARY = '" + objLib + "' AND OBJECT_TYPE = '*SRVPGM' " +
            "AND EXPORT_SOURCE_LIBRARY = '" + library + "' AND EXPORT_SOURCE_FILE_MEMBER IS NOT NULL")) {
          while (rs.next()) {
            built.putIfAbsent(rs.getString("EXPORT_SOURCE_FILE").trim() + "/" + rs.getString("EXPORT_SOURCE_FILE_MEMBER").trim(),
                new Built(rs.getString("PROGRAM_NAME").trim(), "srvpgm", "program_info"));
          }
        } catch (SQLException e) {
          logger.info("Could not read service program binder sources: {}", e.getMessage());
        }
      }
    }

    /* SQL source: the object with the member's name, if it is an SQL object */
    for (String key : new ArrayList<String>(sqlObjects.keySet())) {
      built.putIfAbsent("SQL:" + key, new Built(key, sqlObjects.get(key), "sql_object"));
    }
    return built;
  }

  /* Every non-blank line is a comment (RPG * in column 7 or //, CL and C style block comments, SQL --) */
  static boolean isDocumentation(List<String> lines) {
    boolean any = false;
    boolean inBlock = false;
    for (String line : lines) {
      String code = line.trim();
      if (code.isEmpty()) continue;
      any = true;
      if (inBlock) {
        if (code.contains("*/")) inBlock = false;
        continue;
      }
      if (line.length() > 6 && line.charAt(6) == '*' && line.substring(0, 6).trim().length() <= 5) continue;
      if (code.startsWith("//") || code.startsWith("--") || code.startsWith("*")) continue;
      if (code.startsWith("/*")) {
        if (!code.contains("*/")) inBlock = true;
        continue;
      }
      return false;
    }
    return any;
  }

  /* ctl-opt nomain / H NOMAIN outside comments */
  static boolean isNoMain(List<String> lines) {
    for (String line : lines) {
      String code = line.trim();
      if (code.startsWith("//") || code.startsWith("*") || (line.length() > 6 && line.charAt(6) == '*')) continue;
      int comment = code.indexOf("//");
      if (comment >= 0) code = code.substring(0, comment);
      if (NOMAIN.matcher(code).find()) return true;
    }
    return false;
  }

  /* Member typed plain DDS: tell PF, LF, DSPF and PRTF apart by their keywords */
  private static Built ddsFromContent(String member, List<String> lines) {
    String type = "pf";
    for (String line : lines) {
      if (line.length() > 6 && line.charAt(6) == '*') continue;  // comment
      String upper = line.toUpperCase();
      if (upper.contains("PFILE(") || upper.contains("JFILE(")) { type = "lf"; break; }
      if (upper.contains("DSPSIZ(")) { type = "dspf"; break; }
      if (upper.matches(".*\\b(SKIPB|SKIPA|SPACEB|SPACEA)\\(.*")) { type = "prtf"; break; }
    }
    return new Built(member, type, "dds_content");
  }

  static String objectType(String objType, String attribute) {
    switch (objType) {
      case "*PGM":    return "pgm";
      case "*MODULE": return "module";
      case "*SRVPGM": return "srvpgm";
      case "*CMD":    return "cmd";
      case "*MNU":    return "mnu";
      case "*QMQRY":  return "qmqry";
      case "*FILE":   return DDS_TYPES.contains(attribute) ? attribute.toLowerCase() : null;
      default:        return null;
    }
  }

  private List<String> readMember(String library, String file, String member) throws SQLException {
    return readMember(connection, library, file, member);
  }

  /* Source lines in arrival order, trailing blanks removed */
  static List<String> readMember(Connection connection, String library, String file, String member) throws SQLException {
    List<String> lines = new ArrayList<String>();
    try (Statement stmt = connection.createStatement()) {
      /* Delimited names: members may contain periods (EXPAT.H) */
      stmt.execute("CREATE OR REPLACE ALIAS QTEMP.MCIMPORT FOR \"" + library + "\".\"" + file + "\" (\"" + member + "\")");
      try (ResultSet rs = stmt.executeQuery("SELECT S.SRCDTA FROM QTEMP.MCIMPORT S ORDER BY RRN(S)")) {
        boolean binary = rs.getMetaData().getColumnTypeName(1).toUpperCase().contains("BIT");
        if (!binary) {
          while (rs.next()) lines.add(rtrim(rs.getString(1)));
          return lines;
        }
      }
      /* CCSID 65535 source file: read it as the invariant CCSID */
      try (ResultSet rs = stmt.executeQuery(
          "SELECT CAST(S.SRCDTA AS VARCHAR(32000) CCSID " + MasterCompiler.INVARIANT_CCSID + ") " +
          "FROM QTEMP.MCIMPORT S ORDER BY RRN(S)")) {
        while (rs.next()) lines.add(rtrim(rs.getString(1)));
      }
    }
    return lines;
  }

  /*
   * import_source: members picked one by one (found by SourceLocator), written as <outDir>/<LIB>/<SRCPF>/<name>
   * (reference copies) or <outDir>/<SRCPF>/<name> (into the repository). With keepExisting a file already
   * there is left as it is. SQL objects without a member get their DDL from QSYS2.GENERATE_SQL.
   */
  public ImportReport importSources(List<SourceLocator.SourceRef> sources, String outDir, boolean perLibrary)
      throws Exception {
    ImportReport report = new ImportReport();
    report.output = outDir;
    for (SourceLocator.SourceRef ref : sources) {
      ImportedMember entry = member(ref.library, ref.sourceFile, ref.member,
          ref.sourceType == null ? "" : ref.sourceType);
      List<String> lines;
      try {
        lines = ref.ddlType != null ? generateSql(ref.library, ref.ddlName, ref.ddlType)
            : readMember(ref.library, ref.sourceFile, ref.member);
      } catch (SQLException e) {
        entry.how = "error";
        entry.note = e.getMessage();
        report.files.add(entry);
        report.errors++;
        continue;
      }
      if (ref.ddlType != null) {
        entry.sourceFile = "QSQLSRC";  // where the generated DDL is filed
        entry.member = ref.member;
      }
      name(entry, ref.built, ref.built == null, lines);
      if (ref.ddlType != null) entry.note = "DDL generated by QSYS2.GENERATE_SQL; the object has no source member";
      String path = (perLibrary ? ref.library + "/" : "") + entry.file;
      entry.file = path;
      if (keepExisting && exists(outDir + "/" + path)) {
        entry.kept = true;
      } else {
        write(outDir + "/" + path, lines);
      }
      report.files.add(entry);
      report.members++;
      if ("copybook".equals(entry.how)) report.copybooks++;
    }
    return report;
  }

  /* CREATE OR REPLACE DDL of a table, view or index */
  private List<String> generateSql(String library, String name, String type) throws SQLException {
    List<String> lines = new ArrayList<String>();
    try (java.sql.CallableStatement call = connection.prepareCall(
        "CALL QSYS2.GENERATE_SQL(DATABASE_OBJECT_NAME => ?, DATABASE_OBJECT_LIBRARY_NAME => ?, DATABASE_OBJECT_TYPE => ?, " +
        "CREATE_OR_REPLACE_OPTION => '1', HEADER_OPTION => '0')")) {
      call.setString(1, name);
      call.setString(2, library);
      call.setString(3, type);
      boolean hasResult = call.execute();
      while (!hasResult && call.getUpdateCount() != -1) hasResult = call.getMoreResults();
      if (!hasResult) throw new SQLException("GENERATE_SQL returned no source for " + library + "/" + name);
      try (ResultSet rs = call.getResultSet()) {
        while (rs.next()) lines.add(rtrim(rs.getString("SRCDTA")));
      }
    }
    return lines;
  }

  private boolean exists(String path) throws Exception {
    return runningOnIbmi() ? new IFSFile(system, path).exists() : new File(path).exists();
  }

  /* Write a file (e.g. .mc/.gitignore) the way imported sources are written */
  void writeFile(String path, String content) throws Exception {
    write(path, Arrays.asList(content));
  }

  /* "SRCPF/MBR" of every member the sources copy in (/COPY, /INCLUDE, D/COPY, EXEC SQL INCLUDE; see SourceLocator) */
  private static Set<String> copiedMembers(Map<String, List<String>> sources) {
    Set<String> copied = new HashSet<String>();
    for (Map.Entry<String, List<String>> source : sources.entrySet()) {
      String ownFile = source.getKey().substring(0, source.getKey().indexOf('/'));
      for (String[] copy : SourceLocator.copyDirectives(source.getValue(), ownFile)) copied.add(copy[1] + "/" + copy[2]);
    }
    return copied;
  }

  /* "member ORD1 not found in MYLIB/QRPGLESRC", "no member matching ORD* in ...", "no source file ..." */
  static String missing(String library, List<String> selectors) {
    List<String> parts = new ArrayList<String>();
    for (String sel : selectors) {
      String[] fm = sel.split("/");
      if (sel.equals("*")) parts.add("no source members in library " + library);
      else if (fm.length == 1) parts.add("source file " + library + "/" + fm[0] + " not found, or it has no members");
      else if (fm[1].endsWith("*")) parts.add("no member matching " + fm[1] + " in " + library + "/" + fm[0]);
      else parts.add("member " + fm[1] + " not found in " + library + "/" + fm[0]);
    }
    return String.join("; ", parts);
  }

  /* The job's current library and user library list */
  private List<String> userLibraryList() {
    List<String> libs = new ArrayList<String>();
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(
           "SELECT TRIM(SYSTEM_SCHEMA_NAME) FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE IN ('CURRENT', 'USER') " +
           "AND SYSTEM_SCHEMA_NAME NOT IN ('QTEMP', 'QGPL')")) {
      while (rs.next()) libs.add(rs.getString(1));
    } catch (SQLException e) {
      logger.info("Library list not read: {}", e.getMessage());
    }
    return libs;
  }

  /* On the IBM i, write through the IFS so the file is tagged UTF-8 (CCSID 1208) */
  private void write(String path, List<String> lines) throws Exception {
    if (dryRun) return;
    byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    if (runningOnIbmi()) {
      IFSFile target = new IFSFile(system, path);
      IFSFile parent = target.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      if (target.exists()) target.delete();
      try (OutputStream out = new IFSFileOutputStream(system, path, IFSFileOutputStream.SHARE_ALL, false,
          Integer.parseInt(MasterCompiler.UTF8_CCSID))) {
        out.write(content);
      }
      return;
    }
    File target = new File(path);
    if (target.getParentFile() != null) target.getParentFile().mkdirs();
    try (OutputStream out = new FileOutputStream(target)) {
      out.write(content);
    }
  }

  private void writeReport(ImportReport report, String path) throws Exception {
    byte[] json = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .writeValueAsBytes(report);
    write(path, Arrays.asList(new String(json, StandardCharsets.UTF_8)));
  }

  private static boolean runningOnIbmi() {
    return "OS/400".equalsIgnoreCase(System.getProperty("os.name"));
  }

  private static String rtrim(String value) {
    if (value == null) return "";
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') end--;
    return value.substring(0, end);
  }
}
