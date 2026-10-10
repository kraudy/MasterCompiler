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
 *   seedLibs: [TESTLIB]              # libraries seeds may write to besides the current library
 *   seeds:
 *     - name: seed1
 *       to: TABLE1                  # unqualified: the current library (never the library list)
 *       from: APPLIB/TABLE1         # copy rows from here (read, any library) ...
 *       where: "COL1 = 'A01'"
 *       set: { COL2: "'X'" }     # ... with these columns replaced (SQL expressions)
 *       deleteWhere: "COL1 = 'A01'"  # optional: cleared in the target first, so a replay gives the same rows
 *     - name: seed2
 *       to: TABLE2
 *       values: { COL1: 1, COL2: "'test'" }   # or one literal row
 *
 * Writes go only to the current library or seedLibs, never to a protectedLibs library of the spec.
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
  }

  static SeedFile load(File file) throws Exception {
    SeedFile seeds = new ObjectMapper(new YAMLFactory()).readValue(file, SeedFile.class);
    for (Seed seed : seeds.seeds) {
      if (seed.to == null || seed.to.trim().isEmpty()) throw new IllegalArgumentException("Seed " + seed.name + ": 'to' is required");
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
    if (!libraryList.isEmpty()) ds.setLibraries(String.join(",", libraryList));
    return ds.getConnection();
  }

  /* Plan or run the selected seeds (all when names is empty) */
  static ObjectNode run(Connection reader, Connection writer, SeedFile file, List<String> names, String currentLibrary,
      List<String> protectedLibs, boolean confirm) throws Exception {
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
