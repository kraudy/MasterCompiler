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
    int rows = Math.max(1, Math.min(maxRows <= 0 ? DEFAULT_ROWS : maxRows, MAX_ROWS));
    int columns = Math.max(1, maxColumns <= 0 ? DEFAULT_COLUMNS : maxColumns);
    ObjectNode result = mapper.createObjectNode();
    try (Statement stmt = readOnly.createStatement()) {
      stmt.setMaxRows(rows + 1);  // one more tells whether there were more
      stmt.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = stmt.executeQuery(statement)) {
        ResultSetMetaData meta = rs.getMetaData();
        int shown = Math.min(columns, meta.getColumnCount());
        ArrayNode names = result.putArray("columns");
        for (int c = 1; c <= shown; c++) names.add(meta.getColumnLabel(c));
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
            else {
              value = value.replaceAll("\\s+$", "");
              row.add(value.length() > MAX_VALUE_CHARS ? value.substring(0, MAX_VALUE_CHARS) + "..." : value);
            }
          }
          count++;
        }
        result.put("rowCount", count);
        if (more) result.put("moreRows", "Only the first " + rows + " rows are shown: add a WHERE or raise maxRows (up to " + MAX_ROWS + ")");
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
