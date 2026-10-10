package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;

/*
 * copy_rows, on the build system: rows a read-only connection read (SqlTools.exportRows, brought by the router as
 * 'fetched') inserted into a table of the current library, never into a protectedLibs library. Values go in as
 * parameters with their exact text (decimals, dates, timestamps), so nothing is rounded or retyped. Without
 * confirm only the statements, the row count and the first rows are shown.
 */
final class RowCopier {

  private RowCopier() {}

  /* LIB/TABLE for 'to': unqualified is the current library; another library or a protected one is refused */
  static String target(String to, String currentLibrary, List<String> protectedLibs) {
    String name = to.trim().toUpperCase(Locale.ROOT).replace('.', '/');
    String lib = name.contains("/") ? name.substring(0, name.indexOf('/')) : currentLibrary;
    String table = name.substring(name.indexOf('/') + 1);
    for (String p : protectedLibs) {
      if (p.trim().equalsIgnoreCase(lib)) throw new IllegalArgumentException(to + " is in a protected library (" + lib + ")");
    }
    if (!lib.equalsIgnoreCase(currentLibrary)) {
      throw new IllegalArgumentException(to + ": copy_rows writes only to the current library (" + currentLibrary + "), not " + lib);
    }
    return lib + "/" + table;
  }

  /* Columns of LIB/TABLE in their order */
  static List<String> columns(Connection connection, String target) throws SQLException {
    String lib = target.substring(0, target.indexOf('/'));
    String table = target.substring(target.indexOf('/') + 1);
    TreeMap<Integer, String> byPosition = new TreeMap<Integer, String>();
    try (PreparedStatement stmt = connection.prepareStatement(
        "SELECT ORDINAL_POSITION, TRIM(SYSTEM_COLUMN_NAME) FROM QSYS2.SYSCOLUMNS2 " +
        "WHERE SYSTEM_TABLE_SCHEMA = ? AND (SYSTEM_TABLE_NAME = ? OR TABLE_NAME = ?)")) {
      stmt.setString(1, lib);
      stmt.setString(2, table);
      stmt.setString(3, table);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) byPosition.put(rs.getInt(1), rs.getString(2));
      }
    }
    if (byPosition.isEmpty()) throw new IllegalArgumentException("Table " + target + " not found: create it first (e.g. build it)");
    return new ArrayList<String>(byPosition.values());
  }

  /* No commitment control: development tables are usually not journaled */
  static Connection writeConnection(AS400 system, List<String> libraryList) throws SQLException {
    AS400JDBCDataSource ds = new AS400JDBCDataSource(system);
    ds.setNaming("system");
    ds.setTransactionIsolation("none");
    ds.setDateFormat("iso");  // the text form the rows come in
    ds.setTimeFormat("iso");
    if (!libraryList.isEmpty()) ds.setLibraries(String.join(",", libraryList));
    return ds.getConnection();
  }

  /* Preview (writer null) or insert the fetched rows into target; deleteWhere clears matching rows there first */
  static ObjectNode copy(JsonNode rows, String fromConnection, String target, String deleteWhere, Connection reader,
      Connection writer) throws Exception {
    ObjectNode entry = new ObjectMapper().createObjectNode();
    entry.put("fromConnection", fromConnection);
    entry.put("readThere", rows.path("statement").asText());
    entry.put("target", target);
    List<String> targetColumns = columns(reader, target);
    List<Integer> used = new ArrayList<Integer>();
    List<String> names = new ArrayList<String>();
    ArrayNode dropped = entry.putArray("columnsNotInTarget");
    JsonNode columns = rows.path("columns");
    for (int i = 0; i < columns.size(); i++) {
      String name = columns.get(i).path("name").asText();
      if (targetColumns.contains(name)) {
        used.add(i);
        names.add(name);
      } else {
        dropped.add(name);
      }
    }
    if (names.isEmpty()) throw new IllegalArgumentException(target + " has none of the columns read from " + fromConnection);
    String delete = deleteWhere != null && !deleteWhere.trim().isEmpty() ? "DELETE FROM " + target + " WHERE " + deleteWhere : null;
    String insert = "INSERT INTO " + target + " (" + String.join(", ", names) + ") VALUES ("
        + String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")";
    ArrayNode list = entry.putArray("statements");
    if (delete != null) list.add(delete);
    list.add(insert);
    entry.put("rows", rows.path("rows").size());
    if (rows.has("replaced")) entry.set("replacedThere", rows.get("replaced"));
    ArrayNode sample = entry.putArray("firstRows");
    for (int r = 0; r < Math.min(3, rows.path("rows").size()); r++) sample.add(rows.path("rows").get(r));
    if (writer == null) {
      entry.put("next", "Nothing written yet. Show this to the user; if they agree, call copy_rows again with confirm: true.");
      return entry;
    }

    if (delete != null) {
      try (Statement stmt = writer.createStatement()) {
        entry.put("rowsDeleted", stmt.executeUpdate(delete));
      }
    }
    int inserted = 0;
    try (PreparedStatement stmt = writer.prepareStatement(insert)) {
      for (JsonNode row : rows.path("rows")) {
        for (int p = 0; p < used.size(); p++) {
          int i = used.get(p);
          JsonNode value = row.get(i);
          int type = columns.get(i).path("jdbcType").asInt(java.sql.Types.VARCHAR);
          boolean binary = type == java.sql.Types.BINARY || type == java.sql.Types.VARBINARY
              || type == java.sql.Types.LONGVARBINARY || type == java.sql.Types.BLOB;
          if (value == null || value.isNull()) stmt.setNull(p + 1, type);
          else if (binary) stmt.setBytes(p + 1, SqlTools.unhex(value.asText()));
          else stmt.setString(p + 1, value.asText());
        }
        inserted += stmt.executeUpdate();
      }
    }
    entry.put("rowsInserted", inserted);
    entry.put("status", "done");
    return entry;
  }
}
