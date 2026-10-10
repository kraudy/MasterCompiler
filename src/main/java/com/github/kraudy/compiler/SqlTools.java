package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;

/*
 * The sql and find_object MCP tools.
 *
 * sql: one SELECT / VALUES / WITH statement on a connection the driver opens read only (JT400
 * access "read only": anything else is refused by the driver), with the spec's library list
 * (system naming, so unqualified names resolve as the programs see them), a row and a column limit
 * and a timeout.
 *
 * find_object: every library holding NAME (of a type), the one the library list picks, and whether
 * the user is authorized to use it.
 */
final class SqlTools {
  static final int DEFAULT_ROWS = 100;
  static final int MAX_ROWS = 1000;
  static final int DEFAULT_COLUMNS = 40;
  static final int MAX_VALUE_CHARS = 500;
  private static final int TIMEOUT_SECONDS = 60;

  private final ObjectMapper mapper = new ObjectMapper();
  private final int rowCap;            // a read-only connection's maxRows (0: MAX_ROWS)
  private final List<String> mask;     // its maskColumns: values shown as MASKED

  SqlTools() {
    this(0, new ArrayList<String>());
  }

  SqlTools(int rowCap, List<String> mask) {
    this.rowCap = rowCap;
    this.mask = mask;
  }

  static final String MASKED = "(masked)";
  /* Catalogs any connection may read besides its own libraries */
  static final List<String> CATALOGS = java.util.Arrays.asList("QSYS2", "SYSIBM", "SYSIBMADM", "SYSTOOLS", "QSYS");
  private static final String SQL_NAME = "[A-Z$#@][A-Z0-9_$#@]*";
  private static final Pattern SLASH_QUALIFIED = Pattern.compile("(?<![A-Z0-9_$#@])(" + SQL_NAME + ")/[A-Z$#@\"]",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern DOT_QUALIFIED = Pattern.compile("\\b(?:(?:FROM|JOIN)\\s+|TABLE\\s*\\(\\s*)(" + SQL_NAME
      + ")\\s*\\.\\s*[A-Z$#@\"]", Pattern.CASE_INSENSITIVE);

  /*
   * A read-only connection reads only its libraries (and the catalogs): LIB/NAME anywhere, LIB.NAME after
   * FROM / JOIN / TABLE(. A guard against reading the wrong library by mistake; the user's authority on that
   * IBM i is what really limits it.
   */
  static void checkLibraries(String sql, List<String> allowed) {
    String text = outsideQuotes(sql);
    List<String> refused = new ArrayList<String>();
    for (Pattern p : new Pattern[] { SLASH_QUALIFIED, DOT_QUALIFIED }) {
      java.util.regex.Matcher m = p.matcher(text);
      while (m.find()) {
        String lib = m.group(1).toUpperCase(Locale.ROOT);
        if (!allowed.contains(lib) && !CATALOGS.contains(lib) && !refused.contains(lib)) refused.add(lib);
      }
    }
    if (!refused.isEmpty()) {
      throw new IllegalArgumentException("This connection reads only " + allowed + " (and the catalogs " + CATALOGS
          + "), not " + refused + ". For a division write it with spaces (A / B)");
    }
  }

  /* Leading comments removed, then it must start with SELECT, VALUES or WITH and be one statement */
  static String checkReadOnly(String sql) {
    String text = sql.trim();
    while (true) {
      if (text.startsWith("--")) {
        int nl = text.indexOf('\n');
        text = nl < 0 ? "" : text.substring(nl + 1).trim();
      } else if (text.startsWith("/*")) {
        int end = text.indexOf("*/");
        text = end < 0 ? "" : text.substring(end + 2).trim();
      } else {
        break;
      }
    }
    while (text.endsWith(";")) text = text.substring(0, text.length() - 1).trim();
    String first = text.split("[\\s(]", 2)[0].toUpperCase(Locale.ROOT);
    if (!first.equals("SELECT") && !first.equals("VALUES") && !first.equals("WITH")) {
      throw new IllegalArgumentException("Only SELECT, VALUES or WITH queries are allowed (got " + first + ")");
    }
    if (outsideQuotes(text).contains(";")) {
      throw new IllegalArgumentException("One statement per call");
    }
    return text;
  }

  /* The statement with string literals and quoted names blanked, to look for ';' */
  private static String outsideQuotes(String sql) {
    StringBuilder out = new StringBuilder();
    char quote = 0;
    for (char c : sql.toCharArray()) {
      if (quote != 0) {
        if (c == quote) quote = 0;
        out.append(' ');
      } else if (c == '\'' || c == '"') {
        quote = c;
        out.append(' ');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /* A read-only connection with this library list (current library first) */
  static Connection readOnlyConnection(AS400 system, List<String> libraryList) throws SQLException {
    AS400JDBCDataSource ds = new AS400JDBCDataSource(system);
    ds.setAccess("read only");
    ds.setNaming("system");
    ds.setDateFormat("iso");  // dates and times as text that inserts back unchanged (copy_rows)
    ds.setTimeFormat("iso");
    if (!libraryList.isEmpty()) ds.setLibraries(String.join(",", libraryList));
    Connection connection = ds.getConnection();
    connection.setReadOnly(true);
    return connection;
  }

  /* The job's current library and user library list, in order */
  static List<String> libraryList(Connection connection) throws SQLException {
    List<String> libs = new ArrayList<String>();
    java.util.TreeMap<Integer, String> byPosition = new java.util.TreeMap<Integer, String>();
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(
           "SELECT TRIM(SYSTEM_SCHEMA_NAME), ORDINAL_POSITION FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE IN ('CURRENT', 'USER')")) {
      while (rs.next()) {
        if (!"QTEMP".equals(rs.getString(1))) byPosition.put(rs.getInt(2), rs.getString(1));
      }
    }
    libs.addAll(byPosition.values());
    return libs;
  }

  ObjectNode query(Connection readOnly, String sql, int maxRows, int maxColumns) throws SQLException {
    String statement = checkReadOnly(sql);
    int cap = rowCap > 0 ? Math.min(rowCap, MAX_ROWS) : MAX_ROWS;
    int rows = Math.max(1, Math.min(maxRows <= 0 ? Math.min(DEFAULT_ROWS, cap) : maxRows, cap));
    int columns = Math.max(1, maxColumns <= 0 ? DEFAULT_COLUMNS : maxColumns);
    ObjectNode result = mapper.createObjectNode();
    try (Statement stmt = readOnly.createStatement()) {
      stmt.setMaxRows(rows + 1);  // one more tells whether there were more
      stmt.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = stmt.executeQuery(statement)) {
        ResultSetMetaData meta = rs.getMetaData();
        int shown = Math.min(columns, meta.getColumnCount());
        ArrayNode names = result.putArray("columns");
        boolean[] masked = new boolean[shown + 1];
        for (int c = 1; c <= shown; c++) {
          names.add(meta.getColumnLabel(c));
          masked[c] = mask.contains(meta.getColumnLabel(c).toUpperCase(Locale.ROOT))
              || mask.contains(String.valueOf(meta.getColumnName(c)).toUpperCase(Locale.ROOT));
          if (masked[c]) result.withArray("maskedColumns").add(meta.getColumnLabel(c));
        }
        if (shown < meta.getColumnCount()) {
          result.put("columnsOmitted", meta.getColumnCount() - shown);
        }
        ArrayNode data = result.putArray("rows");
        int count = 0;
        boolean more = false;
        while (rs.next()) {
          if (count == rows) {
            more = true;
            break;
          }
          ArrayNode row = data.addArray();
          for (int c = 1; c <= shown; c++) {
            String value = rs.getString(c);
            if (value == null) row.addNull();
            else if (masked[c]) row.add(MASKED);
            else {
              value = value.replaceAll("\\s+$", "");
              row.add(value.length() > MAX_VALUE_CHARS ? value.substring(0, MAX_VALUE_CHARS) + "..." : value);
            }
          }
          count++;
        }
        result.put("rowCount", count);
        if (more) result.put("moreRows", "Only the first " + rows + " rows are shown: add a WHERE or raise maxRows (up to " + cap + ")");
      }
    }
    return result;
  }

  /* Libraries holding the object, the one the library list resolves to, and authority to use it */
  ObjectNode findObject(Connection connection, String name, String type) throws SQLException {
    String object = name.trim().toUpperCase(Locale.ROOT);
    String types = type == null || type.trim().isEmpty() ? "*ALL" : type.trim().toUpperCase(Locale.ROOT);
    List<String> libl = libraryList(connection);
    ObjectNode result = mapper.createObjectNode();
    result.put("object", object);
    result.put("type", types);
    ArrayNode found = result.putArray("libraries");
    String winner = null;
    int best = Integer.MAX_VALUE;
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(OBJLIB), TRIM(OBJTYPE), COALESCE(TRIM(OBJATTRIBUTE), ''), COALESCE(TRIM(OBJTEXT), ''), " +
        "COALESCE(TRIM(SQL_OBJECT_TYPE), '') FROM TABLE(QSYS2.OBJECT_STATISTICS('*ALL', ?, OBJECT_NAME => ?)) X")) {
      stmt.setString(1, types);
      stmt.setString(2, object);
      stmt.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          ObjectNode entry = found.addObject();
          String lib = rs.getString(1);
          entry.put("library", lib);
          entry.put("type", rs.getString(2));
          if (!rs.getString(3).isEmpty()) entry.put("attribute", rs.getString(3));
          if (!rs.getString(5).isEmpty()) entry.put("sqlType", rs.getString(5));
          if (!rs.getString(4).isEmpty()) entry.put("text", rs.getString(4));
          int position = libl.indexOf(lib);
          if (position >= 0) {
            entry.put("libraryListPosition", position + 1);
            if (position < best) {
              best = position;
              winner = lib + "/" + object + " " + rs.getString(2);
            }
          }
          entry.put("authorized", authorized(connection, lib, object, rs.getString(2)));
        }
      }
    }
    result.put("resolvesTo", winner != null ? winner : "not on the library list");
    result.put("libraryList", String.join(" ", libl));
    if (found.size() == 0) result.put("note", "No such object in any library you can see");
    return result;
  }

  /*
   * Who exports a procedure or data symbol: service programs on the library list (and the binding
   * directories there that list them), and project sources that export it (dcl-proc ... export, P ... B EXPORT)
   */
  ObjectNode findExport(Connection connection, String symbol, java.util.Map<String, String> projectSources) throws SQLException {
    List<String> libl = libraryList(connection);
    ObjectNode result = mapper.createObjectNode();
    result.put("symbol", symbol);
    ArrayNode exporters = result.putArray("servicePrograms");
    List<String> srvpgms = new ArrayList<String>();
    if (!libl.isEmpty()) {
      String in = "'" + String.join("','", libl) + "'";
      try (PreparedStatement stmt = connection.prepareStatement(
          "SELECT TRIM(PROGRAM_LIBRARY), TRIM(PROGRAM_NAME), TRIM(SYMBOL_NAME), TRIM(SYMBOL_USAGE) " +
          "FROM QSYS2.PROGRAM_EXPORT_IMPORT_INFO WHERE PROGRAM_LIBRARY IN (" + in + ") AND OBJECT_TYPE = '*SRVPGM' " +
          "AND UPPER(SYMBOL_NAME) = UPPER(?) AND SYMBOL_USAGE IN ('*PROCEXP', '*DATAEXP')")) {
        stmt.setString(1, symbol.trim());
        stmt.setQueryTimeout(TIMEOUT_SECONDS);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            ObjectNode e = exporters.addObject();
            e.put("serviceProgram", rs.getString(1) + "/" + rs.getString(2));
            e.put("symbol", rs.getString(3));
            e.put("kind", "*DATAEXP".equals(rs.getString(4)) ? "data" : "procedure");
            int position = libl.indexOf(rs.getString(1));
            if (position >= 0) e.put("libraryListPosition", position + 1);
            srvpgms.add(rs.getString(2));
          }
        }
      }
      if (!srvpgms.isEmpty()) {
        ArrayNode dirs = result.putArray("bindingDirectories");
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT TRIM(BINDING_DIRECTORY_LIBRARY), TRIM(BINDING_DIRECTORY), TRIM(ENTRY_LIBRARY), TRIM(ENTRY) " +
            "FROM QSYS2.BINDING_DIRECTORY_INFO WHERE BINDING_DIRECTORY_LIBRARY IN (" + in + ") AND ENTRY_TYPE = '*SRVPGM' " +
            "AND ENTRY IN ('" + String.join("','", srvpgms) + "')")) {
          try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) dirs.add(rs.getString(1) + "/" + rs.getString(2) + " lists " + rs.getString(3) + "/" + rs.getString(4));
          }
        } catch (SQLException e) {
          result.put("bindingDirectoriesNote", "Not read: " + e.getMessage());
        }
      }
    }
    ArrayNode project = result.putArray("projectSources");
    Pattern free = Pattern.compile("^\\s*DCL-PROC\\s+" + Pattern.quote(symbol.trim()) + "\\b[^;]*\\bEXPORT\\b", Pattern.CASE_INSENSITIVE);
    Pattern fixed = Pattern.compile("^.{5}P" + Pattern.quote(symbol.trim().toUpperCase(Locale.ROOT)) + "\\s+B\\b.*\\bEXPORT\\b",
        Pattern.CASE_INSENSITIVE);
    for (java.util.Map.Entry<String, String> source : projectSources.entrySet()) {
      for (String line : source.getValue().split("\\R")) {
        if (free.matcher(line).find() || fixed.matcher(line).find()) {
          project.add(source.getKey());
          break;
        }
      }
    }
    if (exporters.size() == 0 && project.size() == 0) {
      result.put("note", "No service program on the library list and no project source exports it: a CPD5D02 "
          + "'Definition not found' means the module or service program is missing from the bind");
    }
    return result;
  }

  /* What one service program exports (LIB/NAME, or NAME resolved on the library list) */
  ObjectNode exportsOf(Connection connection, String serviceProgram) throws SQLException {
    String name = serviceProgram.trim().toUpperCase(Locale.ROOT).replace('.', '/');
    String lib;
    if (name.contains("/")) {
      lib = name.substring(0, name.indexOf('/'));
      name = name.substring(name.indexOf('/') + 1);
    } else {
      String resolved = findObject(connection, name, "*SRVPGM").path("resolvesTo").asText("");
      if (!resolved.contains("/")) throw new IllegalArgumentException("Service program " + name + " is not on the library list");
      lib = resolved.substring(0, resolved.indexOf('/'));
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("serviceProgram", lib + "/" + name);
    ArrayNode procedures = result.putArray("procedures");
    ArrayNode data = result.putArray("data");
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(SYMBOL_NAME), TRIM(SYMBOL_USAGE) FROM QSYS2.PROGRAM_EXPORT_IMPORT_INFO " +
        "WHERE PROGRAM_LIBRARY = ? AND PROGRAM_NAME = ? AND OBJECT_TYPE = '*SRVPGM' AND SYMBOL_USAGE IN ('*PROCEXP', '*DATAEXP')")) {
      stmt.setString(1, lib);
      stmt.setString(2, name);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) ("*DATAEXP".equals(rs.getString(2)) ? data : procedures).add(rs.getString(1));
      }
    }
    return result;
  }

  /* ---- compare: one object as this IBM i has it ---- */

  /* The object (LIB/NAME, or NAME on the library list) with its create, change and source timestamps; ILE per module */
  ObjectNode objectInfo(Connection connection, String object, String type) throws SQLException {
    String name = object.trim().toUpperCase(Locale.ROOT).replace('.', '/');
    String lib = name.contains("/") ? name.substring(0, name.indexOf('/')) : "*LIBL";
    name = name.substring(name.indexOf('/') + 1);
    String types = type == null || type.trim().isEmpty() ? "*PGM *SRVPGM *MODULE *FILE *CMD" : type.trim().toUpperCase(Locale.ROOT);
    ObjectNode result = mapper.createObjectNode();
    result.put("request", (lib.equals("*LIBL") ? "" : lib + "/") + name + " " + types);
    result.put("libraryList", String.join(" ", libraryList(connection)));
    ArrayNode objects = result.putArray("objects");
    List<Object[]> rows = new ArrayList<Object[]>();
    int first = Integer.MAX_VALUE;
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(O.OBJLIB), TRIM(O.OBJTYPE), COALESCE(TRIM(O.OBJATTRIBUTE), ''), O.OBJCREATED, O.CHANGE_TIMESTAMP, " +
        "COALESCE(TRIM(O.SOURCE_LIBRARY), ''), COALESCE(TRIM(O.SOURCE_FILE), ''), COALESCE(TRIM(O.SOURCE_MEMBER), ''), " +
        "O.SOURCE_TIMESTAMP, COALESCE(TRIM(O.CREATED_SYSTEM), ''), COALESCE(L.ORDINAL_POSITION, 0) " +
        "FROM TABLE(QSYS2.OBJECT_STATISTICS(?, ?, OBJECT_NAME => ?)) O " +
        "LEFT JOIN QSYS2.LIBRARY_LIST_INFO L ON L.SYSTEM_SCHEMA_NAME = O.OBJLIB")) {
      stmt.setString(1, lib);
      stmt.setString(2, types);
      stmt.setString(3, name);
      stmt.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          Object[] row = new Object[11];
          for (int i = 0; i < 11; i++) row[i] = rs.getObject(i + 1);
          rows.add(row);
          first = Math.min(first, ((Number) row[10]).intValue());
        }
      }
    } catch (SQLException e) {
      if ("42704".equals(e.getSQLState()) || e.getErrorCode() == -443) {
        result.put("note", "Library " + lib + " not found");
        return result;
      }
      throw e;
    }
    for (Object[] row : rows) {
      if (((Number) row[10]).intValue() != first) continue;  // a same-named object later in the library list
      String objLib = (String) row[0], objType = (String) row[1];
      ObjectNode entry = objects.addObject();
      entry.put("object", objLib + "/" + name + " " + objType);
      if (!((String) row[2]).isEmpty()) entry.put("attribute", (String) row[2]);
      entry.put("created", text(row[3]));
      entry.put("changed", text(row[4]));
      if (!((String) row[9]).isEmpty()) entry.put("createdOnSystem", (String) row[9]);
      if (!((String) row[7]).isEmpty()) {
        entry.set("source", member((String) row[5], (String) row[6], (String) row[7], text(row[8]), connection));
      }
      if (objType.equals("*PGM") || objType.equals("*SRVPGM")) modules(connection, objLib, name, objType, entry);
    }
    if (objects.size() == 0) result.put("note", "Not found" + (lib.equals("*LIBL") ? " on the library list" : " in " + lib));
    return result;
  }

  private void modules(Connection connection, String lib, String name, String type, ObjectNode entry) {
    ArrayNode modules = entry.putArray("modules");
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT TRIM(BOUND_MODULE), COALESCE(TRIM(SOURCE_FILE_LIBRARY), ''), COALESCE(TRIM(SOURCE_FILE), ''), " +
        "COALESCE(TRIM(SOURCE_FILE_MEMBER), ''), COALESCE(SOURCE_STREAM_FILE_PATH, ''), SOURCE_CHANGE_TIMESTAMP, " +
        "MODULE_CREATE_TIMESTAMP FROM QSYS2.BOUND_MODULE_INFO WHERE PROGRAM_LIBRARY = ? AND PROGRAM_NAME = ? AND OBJECT_TYPE = ?")) {
      stmt.setString(1, lib);
      stmt.setString(2, name);
      stmt.setString(3, type);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          ObjectNode module = modules.addObject();
          module.put("module", rs.getString(1));
          module.put("moduleCreated", text(rs.getTimestamp(7)));
          if (!rs.getString(4).isEmpty()) {
            module.set("source", member(rs.getString(2), rs.getString(3), rs.getString(4), text(rs.getTimestamp(6)), connection));
          } else if (!rs.getString(5).trim().isEmpty()) {
            ObjectNode source = module.putObject("source");
            source.put("streamFile", rs.getString(5).trim());
            source.put("changeRecordedAtCompile", text(rs.getTimestamp(6)));
          }
        }
      }
    } catch (SQLException e) {
      entry.put("modulesNote", "Not read: " + e.getMessage());
    }
  }

  /* A source member: the change timestamp the compile recorded, and the member as it is now */
  private ObjectNode member(String lib, String file, String member, String recorded, Connection connection) {
    ObjectNode source = mapper.createObjectNode();
    source.put("member", lib + "/" + file + "/" + member);
    source.put("changeRecordedAtCompile", recorded);
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT LAST_SOURCE_UPDATE_TIMESTAMP, NUMBER_ROWS FROM QSYS2.SYSPARTITIONSTAT " +
        "WHERE SYSTEM_TABLE_SCHEMA = ? AND SYSTEM_TABLE_NAME = ? AND SYSTEM_TABLE_MEMBER = ?")) {
      stmt.setString(1, lib);
      stmt.setString(2, file);
      stmt.setString(3, member);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          source.put("memberChangedNow", text(rs.getTimestamp(1)));
          source.put("lines", rs.getLong(2));
        } else {
          source.put("memberNow", "missing");
        }
      }
    } catch (SQLException e) {
      source.put("memberNow", "not read: " + e.getMessage());
    }
    return source;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /* Both systems' objectInfo side by side, with what differs */
  static ObjectNode compare(String buildName, JsonNode build, String otherName, JsonNode other) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode result = mapper.createObjectNode();
    ArrayNode differences = mapper.createArrayNode();
    java.util.Map<String, JsonNode> buildByType = byType(build), otherByType = byType(other);
    java.util.Set<String> types = new java.util.LinkedHashSet<String>(buildByType.keySet());
    types.addAll(otherByType.keySet());
    boolean sameSource = !types.isEmpty();
    for (String type : types) {
      JsonNode a = buildByType.get(type), b = otherByType.get(type);
      if (a == null || b == null) {
        differences.add(type + ": only on " + (a == null ? otherName : buildName));
        sameSource = false;
        continue;
      }
      if (!a.path("attribute").asText().equals(b.path("attribute").asText())) {
        differences.add(type + " attribute: " + a.path("attribute").asText() + " vs " + b.path("attribute").asText());
      }
      if (!a.path("created").asText().equals(b.path("created").asText())) {
        differences.add(type + " created: " + a.path("created").asText() + " on " + buildName + ", "
            + b.path("created").asText() + " on " + otherName);
      }
      sameSource &= sameSource(type, "", a.path("source"), b.path("source"), buildName, otherName, differences);
      java.util.Map<String, JsonNode> am = new java.util.LinkedHashMap<String, JsonNode>(), bm = new java.util.LinkedHashMap<String, JsonNode>();
      for (JsonNode m : a.path("modules")) am.put(m.path("module").asText(), m);
      for (JsonNode m : b.path("modules")) bm.put(m.path("module").asText(), m);
      java.util.Set<String> names = new java.util.LinkedHashSet<String>(am.keySet());
      names.addAll(bm.keySet());
      for (String module : names) {
        JsonNode x = am.get(module), y = bm.get(module);
        if (x == null || y == null) {
          differences.add(type + " module " + module + ": only on " + (x == null ? otherName : buildName));
          sameSource = false;
          continue;
        }
        sameSource &= sameSource(type, " module " + module, x.path("source"), y.path("source"), buildName, otherName, differences);
        if (!x.path("moduleCreated").asText().equals(y.path("moduleCreated").asText())) {
          differences.add(type + " module " + module + " created: " + x.path("moduleCreated").asText() + " on " + buildName
              + ", " + y.path("moduleCreated").asText() + " on " + otherName);
        }
      }
    }
    result.put("sameRecordedSource", sameSource);
    result.set("differences", differences);
    result.put("note", "sameRecordedSource: every compile recorded the same source member and change timestamp on both "
        + "systems, which usually means the same source version (a saved and restored object keeps them). Source "
        + "changed after the compile shows as memberChangedNow later than changeRecordedAtCompile.");
    result.set(buildName, build);
    result.set(otherName, other);
    return result;
  }

  private static boolean sameSource(String type, String what, JsonNode a, JsonNode b, String buildName, String otherName,
      ArrayNode differences) {
    if (a.isMissingNode() && b.isMissingNode()) return true;
    String recordedA = a.path("changeRecordedAtCompile").asText(), recordedB = b.path("changeRecordedAtCompile").asText();
    String whereA = a.path("member").asText(a.path("streamFile").asText()), whereB = b.path("member").asText(b.path("streamFile").asText());
    boolean same = recordedA.equals(recordedB) && !recordedA.isEmpty();
    if (!same) {
      differences.add(type + what + " source change recorded at compile: " + (recordedA.isEmpty() ? "none" : recordedA) + " ("
          + whereA + ") on " + buildName + ", " + (recordedB.isEmpty() ? "none" : recordedB) + " (" + whereB + ") on " + otherName);
    }
    if (a.has("lines") && b.has("lines") && a.path("lines").asLong() != b.path("lines").asLong()) {
      differences.add(type + what + " source lines now: " + a.path("lines").asLong() + " on " + buildName + ", "
          + b.path("lines").asLong() + " on " + otherName);
    }
    return same;
  }

  private static java.util.Map<String, JsonNode> byType(JsonNode info) {
    java.util.Map<String, JsonNode> map = new java.util.LinkedHashMap<String, JsonNode>();
    for (JsonNode o : info.path("objects")) {
      String object = o.path("object").asText();
      map.put(object.substring(object.lastIndexOf(' ') + 1), o);
    }
    return map;
  }

  /* ---- copy_rows: the rows, typed, as the other IBM i has them ---- */

  /*
   * SELECT the rows of LIB/TABLE (one of the allowed libraries) WHERE ..., with the 'set' columns replaced by
   * SQL expressions there, so masked values never leave this system. Columns in 'mask' must be replaced.
   * More rows than maxRows is an error, not a cut: a copy takes all it selects or nothing.
   */
  ObjectNode exportRows(Connection connection, String from, String where, java.util.Map<String, String> set, int maxRows,
      List<String> allowed) throws SQLException {
    String name = from.trim().toUpperCase(Locale.ROOT).replace('.', '/');
    String lib;
    if (name.contains("/")) {
      lib = name.substring(0, name.indexOf('/'));
      name = name.substring(name.indexOf('/') + 1);
    } else {
      String resolved = findObject(connection, name, "*FILE").path("resolvesTo").asText("");
      if (!resolved.contains("/")) throw new IllegalArgumentException(name + " is not in the libraries this connection reads " + allowed);
      lib = resolved.substring(0, resolved.indexOf('/'));
    }
    if (!allowed.contains(lib)) throw new IllegalArgumentException(lib + "/" + name + ": this connection reads only " + allowed);
    List<String> columns = RowCopier.columns(connection, lib + "/" + name);
    java.util.Map<String, String> replace = new java.util.LinkedHashMap<String, String>();
    for (java.util.Map.Entry<String, String> e : set.entrySet()) {
      String col = e.getKey().trim().toUpperCase(Locale.ROOT);
      if (!columns.contains(col)) throw new IllegalArgumentException("set: " + lib + "/" + name + " has no column " + col);
      replace.put(col, e.getValue());
    }
    List<String> unmasked = new ArrayList<String>();
    for (String col : mask) if (columns.contains(col) && !replace.containsKey(col)) unmasked.add(col);
    if (!unmasked.isEmpty()) {
      throw new IllegalArgumentException("This connection masks " + unmasked + ": give each a replacement in set "
          + "(e.g. " + unmasked.get(0) + ": \"'TEST'\"), so the real values are not copied");
    }
    List<String> select = new ArrayList<String>();
    for (String col : columns) select.add(replace.containsKey(col) ? "(" + replace.get(col) + ") AS " + col : col);
    String statement = "SELECT " + String.join(", ", select) + " FROM " + lib + "/" + name
        + (where != null && !where.trim().isEmpty() ? " WHERE " + where : "");
    checkReadOnly(statement);
    checkLibraries(statement, allowed);

    int cap = Math.max(1, Math.min(maxRows > 0 ? maxRows : MAX_ROWS, rowCap > 0 ? rowCap : MAX_ROWS));
    ObjectNode result = mapper.createObjectNode();
    result.put("from", lib + "/" + name);
    result.put("statement", statement);
    try (Statement stmt = connection.createStatement()) {
      stmt.setMaxRows(cap + 1);
      stmt.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = stmt.executeQuery(statement)) {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        ArrayNode cols = result.putArray("columns");
        for (int c = 1; c <= n; c++) {
          ObjectNode col = cols.addObject();
          col.put("name", meta.getColumnLabel(c).toUpperCase(Locale.ROOT));
          col.put("jdbcType", meta.getColumnType(c));
          col.put("typeName", meta.getColumnTypeName(c));
        }
        ArrayNode data = result.putArray("rows");
        while (rs.next()) {
          if (data.size() == cap) {
            throw new IllegalArgumentException("More than " + cap + " rows match: narrow where (a copy takes all its rows or none)");
          }
          ArrayNode row = data.addArray();
          for (int c = 1; c <= n; c++) {
            int t = meta.getColumnType(c);
            if (t == java.sql.Types.BINARY || t == java.sql.Types.VARBINARY || t == java.sql.Types.LONGVARBINARY || t == java.sql.Types.BLOB) {
              byte[] bytes = rs.getBytes(c);
              if (bytes == null) row.addNull();
              else row.add(hex(bytes));
              continue;
            }
            String value = rs.getString(c);
            if (value == null) row.addNull();
            else row.add(t == java.sql.Types.CHAR || t == java.sql.Types.NCHAR ? value.replaceAll("\\s+$", "") : value);
          }
        }
        result.put("rowCount", data.size());
      }
    }
    if (!replace.isEmpty()) result.set("replaced", mapper.valueToTree(replace.keySet()));
    return result;
  }

  static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format("%02X", b));
    return out.toString();
  }

  static byte[] unhex(String text) {
    byte[] bytes = new byte[text.length() / 2];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
    return bytes;
  }

  /* *USE-like authority (QSYS2.SQL_CHECK_AUTHORITY); null text when the system cannot tell */
  private static String authorized(Connection connection, String lib, String object, String type) {
    try (PreparedStatement stmt = connection.prepareStatement("VALUES QSYS2.SQL_CHECK_AUTHORITY(?, ?, ?)")) {
      stmt.setString(1, lib);
      stmt.setString(2, object);
      stmt.setString(3, type);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? (rs.getInt(1) == 1 ? "yes" : "no") : "unknown";
      }
    } catch (SQLException e) {
      return "unknown";
    }
  }
}
