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

  private static final Pattern COPY_DIRECTIVE = Pattern.compile(
      "^\\s*/(?:COPY|INCLUDE)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
  private static final List<String> DDS_TYPES = Arrays.asList("PF", "LF", "DSPF", "PRTF");

  private final AS400 system;
  private final Connection connection;
  private final boolean verbose;

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
  private static final class Built {
    final String name;
    final String objectType;   // MC object type, lower case (pgm, module, pf, ...)
    final String how;
    Built(String name, String objectType, String how) {
      this.name = name;
      this.objectType = objectType;
      this.how = how;
    }
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
      if (members.isEmpty()) throw new CompilerException("No source members selected in " + library + " " + lib.getValue());
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
      Set<String> copied = copiedMembers(sources.values());

      for (Map.Entry<String, List<String>> source : sources.entrySet()) {
        String[] fm = source.getKey().split("/");
        String sourceType = members.get(source.getKey());
        ImportedMember entry = member(library, fm[0], fm[1], sourceType);
        Built object = built.get(source.getKey());
        if (object == null && "SQL".equals(sourceType)) object = built.get("SQL:" + fm[1]);
        if (object == null && "DDS".equals(sourceType)) object = ddsFromContent(fm[1], source.getValue());
        name(entry, object, copied.contains(source.getKey()));

        write(base + "/" + entry.file, source.getValue());
        if (perLibrary) entry.file = library + "/" + entry.file;
        report.files.add(entry);
        report.members++;
        if (entry.object != null && !"dds_content".equals(entry.how)) report.fromObjects++;
        if ("copybook".equals(entry.how)) report.copybooks++;
        if ("assumed".equals(entry.how)) report.assumed++;
        if (verbose) logger.info("{}/{}/{} -> {} ({})", library, fm[0], fm[1], entry.file, entry.how);
      }
    }

    writeReport(report, outDir + "/mc-import.json");
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
  private void name(ImportedMember entry, Built object, boolean isCopied) {
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
      entry.file = dir + entry.member + ".include." + type;
      entry.how = "copybook";
      return;
    }
    entry.file = dir + entry.member + "." + type;
    if (SourceNaming.parseFileName(entry.file).isPresent()) {
      entry.how = "assumed";
      entry.note = "No object in the library was built from this member; the scan treats it as a "
          + SourceNaming.parseFileName(entry.file).get().objectType.name().toLowerCase() + " target";
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
  private Map<String, Built> builtObjects(String library) throws SQLException {
    Map<String, Built> built = new HashMap<String, Built>();
    Map<String, String> sqlObjects = new HashMap<String, String>();

    try (Statement stmt = connection.createStatement()) {
      try (ResultSet rs = stmt.executeQuery(
          "SELECT OBJNAME, OBJTYPE, COALESCE(OBJATTRIBUTE, '') AS ATTR, COALESCE(SOURCE_LIBRARY, '') AS SRCLIB, " +
          "COALESCE(SOURCE_FILE, '') AS SRCPF, COALESCE(SOURCE_MEMBER, '') AS MBR, COALESCE(SQL_OBJECT_TYPE, '') AS SQLTYPE " +
          "FROM TABLE(QSYS2.OBJECT_STATISTICS('" + library + "', '*ALL'))")) {
        while (rs.next()) {
          String name = rs.getString("OBJNAME").trim();
          String sqlType = rs.getString("SQLTYPE").trim();
          if (!sqlType.isEmpty()) sqlObjects.put(name, sqlType.toLowerCase());
          if (!library.equals(rs.getString("SRCLIB").trim()) || rs.getString("MBR").trim().isEmpty()) continue;
          String type = objectType(rs.getString("OBJTYPE").trim(), rs.getString("ATTR").trim());
          if (type != null) {
            built.putIfAbsent(rs.getString("SRCPF").trim() + "/" + rs.getString("MBR").trim(),
                new Built(name, type, "object_statistics"));
          }
        }
      }

      /* ILE: the program's object description has no source; its bound modules do.
         A module named like its *PGM is a CRTBND* program, any other bound module a *MODULE. */
      try (ResultSet rs = stmt.executeQuery(
          "SELECT B.PROGRAM_NAME, B.OBJECT_TYPE, B.BOUND_MODULE, B.SOURCE_FILE, B.SOURCE_FILE_MEMBER " +
          "FROM TABLE(QSYS2.OBJECT_STATISTICS('" + library + "', '*PGM *SRVPGM')) O " +
          "INNER JOIN QSYS2.BOUND_MODULE_INFO B " +
            "ON B.PROGRAM_LIBRARY = '" + library + "' AND B.PROGRAM_NAME = O.OBJNAME AND B.OBJECT_TYPE = O.OBJTYPE " +
          "WHERE B.SOURCE_FILE_LIBRARY = '" + library + "' AND B.SOURCE_FILE_MEMBER IS NOT NULL")) {
        while (rs.next()) {
          String program = rs.getString("PROGRAM_NAME").trim();
          String module = rs.getString("BOUND_MODULE").trim();
          boolean bndProgram = "*PGM".equals(rs.getString("OBJECT_TYPE").trim()) && program.equals(module);
          built.putIfAbsent(rs.getString("SOURCE_FILE").trim() + "/" + rs.getString("SOURCE_FILE_MEMBER").trim(),
              new Built(bndProgram ? program : module, bndProgram ? "pgm" : "module", "bound_module"));
        }
      }

      try (ResultSet rs = stmt.executeQuery(
          "SELECT PROGRAM_NAME, EXPORT_SOURCE_FILE, EXPORT_SOURCE_FILE_MEMBER FROM QSYS2.PROGRAM_INFO " +
          "WHERE PROGRAM_LIBRARY = '" + library + "' AND OBJECT_TYPE = '*SRVPGM' " +
          "AND EXPORT_SOURCE_LIBRARY = '" + library + "' AND EXPORT_SOURCE_FILE_MEMBER IS NOT NULL")) {
        while (rs.next()) {
          built.putIfAbsent(rs.getString("EXPORT_SOURCE_FILE").trim() + "/" + rs.getString("EXPORT_SOURCE_FILE_MEMBER").trim(),
              new Built(rs.getString("PROGRAM_NAME").trim(), "srvpgm", "program_info"));
        }
      } catch (SQLException e) {
        logger.info("Could not read service program binder sources: {}", e.getMessage());
      }
    }

    /* SQL source: the object with the member's name, if it is an SQL object */
    for (String key : new ArrayList<String>(sqlObjects.keySet())) {
      built.putIfAbsent("SQL:" + key, new Built(key, sqlObjects.get(key), "sql_object"));
    }
    return built;
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

  private static String objectType(String objType, String attribute) {
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

  /* Source lines in arrival order, trailing blanks removed */
  private List<String> readMember(String library, String file, String member) throws SQLException {
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

  /* "SRCPF/MBR" of every member named by a /COPY or /INCLUDE (FILE,MBR, LIB/FILE,MBR or MBR) */
  private static Set<String> copiedMembers(Iterable<List<String>> sources) {
    Set<String> copied = new HashSet<String>();
    for (List<String> lines : sources) {
      for (String line : lines) {
        if (line.trim().startsWith("//")) continue;
        Matcher m = COPY_DIRECTIVE.matcher(line);
        if (!m.find()) continue;
        String operand = m.group(1).replace("'", "").toUpperCase();
        if (operand.contains(".") ) continue;  // stream file path, not a member
        String file = "QRPGLESRC";
        String member = operand;
        if (operand.contains(",")) {
          file = operand.substring(0, operand.indexOf(','));
          member = operand.substring(operand.indexOf(',') + 1);
          if (file.contains("/")) file = file.substring(file.indexOf('/') + 1);
        }
        copied.add(file + "/" + member);
      }
    }
    return copied;
  }

  /* On the IBM i, write through the IFS so the file is tagged UTF-8 (CCSID 1208) */
  private void write(String path, List<String> lines) throws Exception {
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
