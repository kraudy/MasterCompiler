package com.github.kraudy.compiler;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;

/*
 * seeds.yaml: test data the project needs, replayable (the seed MCP tool).
 *
 *   seedLibs: [LIB2]                 # libraries seeds may write to besides the current library
 *   seeds:
 *     - name: seed1
 *       to: TABLE1                   # unqualified: the current library (never the library list)
 *       from: LIB1/TABLE1            # copy rows from here (read, any library) ...
 *       where: "COL1 = 'A01'"
 *       set: { COL2: "'X'" }         # ... with these columns replaced (SQL expressions)
 *       deleteWhere: "COL1 = 'A01'"  # optional: cleared in the target first, so a replay gives the same rows
 *     - name: seed2
 *       to: TABLE2
 *       values: { COL1: 1, COL2: "'test'" }  # or one literal row
 *     - name: seed3
 *       fromConnection: SYS2         # a read-only connection of the spec: rows read there ...
 *       from: LIB1/TABLE1            # ... (set is applied there too, so replaced values never leave it)
 *       where: "COL1 = 1"
 *       to: TABLE1                   # ... and inserted here with their exact values
 *
 * Writes go only to the current library or seedLibs, never to a protectedLibs library of the spec.
 * fromConnection rows are read by MC on the PC (ReadOnlyConnections) and come in the call as 'fetched'.
 */
final class SeedRunner {

  @JsonIgnoreProperties(ignoreUnknown = false)
  static final class SeedFile {
    public List<String> seedLibs = new ArrayList<String>();
    public List<Seed> seeds = new ArrayList<Seed>();
  }

  @JsonIgnoreProperties(ignoreUnknown = false)
  static final class Seed {
    public String name;
    public String to;
    public String from;
    public String where;
    public Map<String, Object> set = new LinkedHashMap<String, Object>();
    public Map<String, Object> values;
    public String deleteWhere;
    public String fromConnection;
  }

  static SeedFile load(File file) throws Exception {
    SeedFile seeds = new ObjectMapper(new YAMLFactory()).readValue(file, SeedFile.class);
    for (Seed seed : seeds.seeds) {
      if (seed.to == null || seed.to.trim().isEmpty()) throw new IllegalArgumentException("Seed " + seed.name + ": 'to' is required");
      if (seed.fromConnection != null && seed.from == null) {
        throw new IllegalArgumentException("Seed " + seed.name + ": fromConnection needs 'from' (the table to read there)");
      }
      if ((seed.from == null) == (seed.values == null)) {
        throw new IllegalArgumentException("Seed " + seed.name + ": give either 'from' (copy rows) or 'values' (one row)");
      }
    }
    return seeds;
  }

  /* LIB/TABLE for a target: unqualified is the current library; refused outside it and seedLibs, or in protectedLibs */
  static String target(String to, String currentLibrary, List<String> seedLibs, List<String> protectedLibs) {
    String name = to.trim().toUpperCase(Locale.ROOT).replace('.', '/');
    String lib = name.contains("/") ? name.substring(0, name.indexOf('/')) : currentLibrary;
    String table = name.substring(name.indexOf('/') + 1);
    for (String p : protectedLibs) {
      if (p.trim().equalsIgnoreCase(lib)) throw new IllegalArgumentException(to + " is in a protected library (" + lib + ")");
    }
    boolean allowed = lib.equalsIgnoreCase(currentLibrary);
    for (String s : seedLibs) allowed |= s.trim().equalsIgnoreCase(lib);
    if (!allowed) {
      throw new IllegalArgumentException(to + ": seeds write only to the current library (" + currentLibrary
          + ") or seedLibs " + seedLibs + ", not " + lib);
    }
    return lib + "/" + table;
  }

  /* The statements a seed runs, in order */
  static List<String> statements(Seed seed, String target, List<String> targetColumns) {
    List<String> sql = new ArrayList<String>();
    if (seed.deleteWhere != null && !seed.deleteWhere.trim().isEmpty()) {
      sql.add("DELETE FROM " + target + " WHERE " + seed.deleteWhere);
    }
    if (seed.values != null) {
      List<String> cols = new ArrayList<String>();
      List<String> vals = new ArrayList<String>();
      for (Map.Entry<String, Object> v : seed.values.entrySet()) {
        cols.add(v.getKey().toUpperCase(Locale.ROOT));
        vals.add(String.valueOf(v.getValue()));
      }
      sql.add("INSERT INTO " + target + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", vals) + ")");
      return sql;
    }
    Map<String, String> set = new LinkedHashMap<String, String>();
    for (Map.Entry<String, Object> s : seed.set.entrySet()) set.put(s.getKey().toUpperCase(Locale.ROOT), String.valueOf(s.getValue()));
    List<String> select = new ArrayList<String>();
    for (String col : targetColumns) select.add(set.containsKey(col) ? set.get(col) + " AS " + col : col);
    sql.add("INSERT INTO " + target + " (" + String.join(", ", targetColumns) + ") SELECT " + String.join(", ", select)
        + " FROM " + seed.from.trim().replace('.', '/') + (seed.where != null && !seed.where.trim().isEmpty() ? " WHERE " + seed.where : ""));
    return sql;
  }

  /*
   * A fromConnection seed: the rows the other IBM i gave (exportRows), inserted with parameters, so values keep
   * their exact text (decimals, dates, timestamps) and nothing is rounded or retyped. Columns the target lacks are
   * left out and named.
   */
  private static void copyRows(Seed seed, String target, com.fasterxml.jackson.databind.JsonNode fetched, Connection reader,
      Connection writer, boolean confirm, ObjectNode entry) throws Exception {
    com.fasterxml.jackson.databind.JsonNode rows = fetched == null ? null : fetched.get(seed.name);
    if (rows == null) {
      throw new IllegalArgumentException("fromConnection " + seed.fromConnection + ": the rows are read by MasterCompiler "
          + "on the PC (the mastercompiler MCP server in VS Code), which has that connection; run seed from there");
    }
    if (rows.has("error")) throw new IllegalArgumentException(seed.fromConnection + ": " + rows.path("error").asText());
    List<String> targetColumns = columns(reader, target);
    List<Integer> used = new ArrayList<Integer>();
    List<String> names = new ArrayList<String>();
    ArrayNode dropped = entry.putArray("columnsNotInTarget");
    com.fasterxml.jackson.databind.JsonNode columns = rows.path("columns");
    for (int i = 0; i < columns.size(); i++) {
      String name = columns.get(i).path("name").asText();
      if (targetColumns.contains(name)) {
        used.add(i);
        names.add(name);
      } else {
        dropped.add(name);
      }
    }
    if (names.isEmpty()) throw new IllegalArgumentException(target + " has none of the columns read from " + seed.fromConnection);
    List<String> sql = new ArrayList<String>();
    if (seed.deleteWhere != null && !seed.deleteWhere.trim().isEmpty()) sql.add("DELETE FROM " + target + " WHERE " + seed.deleteWhere);
    String insert = "INSERT INTO " + target + " (" + String.join(", ", names) + ") VALUES ("
        + String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")";
    sql.add(insert);
    ArrayNode list = entry.putArray("statements");
    for (String s : sql) list.add(s);
    entry.put("fromConnection", seed.fromConnection);
    entry.put("readThere", rows.path("statement").asText());
    entry.put("rows", rows.path("rows").size());
    if (rows.has("replaced")) entry.set("replacedThere", rows.get("replaced"));
    ArrayNode sample = entry.putArray("firstRows");
    for (int r = 0; r < Math.min(3, rows.path("rows").size()); r++) sample.add(rows.path("rows").get(r));
    if (!confirm) return;

    ArrayNode changed = entry.putArray("rowsChanged");
    if (sql.size() > 1) {
      try (Statement stmt = writer.createStatement()) {
        changed.add(stmt.executeUpdate(sql.get(0)));
      }
    }
    int inserted = 0;
    try (PreparedStatement stmt = writer.prepareStatement(insert)) {
      for (com.fasterxml.jackson.databind.JsonNode row : rows.path("rows")) {
        for (int p = 0; p < used.size(); p++) {
          int i = used.get(p);
          com.fasterxml.jackson.databind.JsonNode value = row.get(i);
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
    changed.add(inserted);
    entry.put("status", "done");
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
    ds.setDateFormat("iso");  // the text form rows from another IBM i come in
    ds.setTimeFormat("iso");
    if (!libraryList.isEmpty()) ds.setLibraries(String.join(",", libraryList));
    return ds.getConnection();
  }

  /* Plan or run the selected seeds (all when names is empty) */
  static ObjectNode run(Connection reader, Connection writer, SeedFile file, List<String> names, String currentLibrary,
      List<String> protectedLibs, boolean confirm) throws Exception {
    return run(reader, writer, file, names, currentLibrary, protectedLibs, confirm, null);
  }

  static ObjectNode run(Connection reader, Connection writer, SeedFile file, List<String> names, String currentLibrary,
      List<String> protectedLibs, boolean confirm, com.fasterxml.jackson.databind.JsonNode fetched) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode result = mapper.createObjectNode();
    result.put("currentLibrary", currentLibrary);
    result.put("confirmed", confirm);
    ArrayNode out = result.putArray("seeds");
    for (Seed seed : file.seeds) {
      if (!names.isEmpty() && !names.contains(seed.name)) continue;
      ObjectNode entry = out.addObject();
      entry.put("name", seed.name);
      try {
        String target = target(seed.to, currentLibrary, file.seedLibs, protectedLibs);
        entry.put("target", target);
        if (seed.fromConnection != null) {
          copyRows(seed, target, fetched, reader, writer, confirm, entry);
          continue;
        }
        List<String> sql = statements(seed, target, seed.values != null ? new ArrayList<String>() : columns(reader, target));
        ArrayNode list = entry.putArray("statements");
        for (String s : sql) list.add(s);
        if (!confirm) continue;
        ArrayNode rows = entry.putArray("rowsChanged");
        try (Statement stmt = writer.createStatement()) {
          for (String s : sql) rows.add(stmt.executeUpdate(s));
        }
        entry.put("status", "done");
      } catch (Exception e) {
        entry.put("status", "failed");
        entry.put("error", e.getMessage());
      }
    }
    if (!confirm) {
      result.put("next", "Nothing written yet. Show these statements to the user; if they agree, call seed again with confirm: true.");
    }
    return result;
  }
}
