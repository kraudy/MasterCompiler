package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * call_program's checkRows: the same read-only queries before and after the call, compared by their key columns,
 * so one call shows what the program inserted, deleted and changed. Values are compared exactly (CHAR without
 * trailing blanks); masked columns still count as changed but show no value.
 */
final class RowChanges {

  static final int MAX_QUERIES = 5;
  static final int MAX_ROWS = 1000;  // per query and snapshot
  static final int SHOWN = 50;       // inserted / deleted / changed rows listed per query

  private static final ObjectMapper mapper = new ObjectMapper();

  final String sql;
  final List<String> key = new ArrayList<String>();

  private RowChanges(String sql) {
    this.sql = sql;
  }

  /* [{sql, key: [COL, ...]}]: validated before anything runs */
  static List<RowChanges> parse(JsonNode checkRows) {
    List<RowChanges> checks = new ArrayList<RowChanges>();
    if (checkRows == null || checkRows.isMissingNode() || checkRows.isNull()) return checks;
    if (!checkRows.isArray()) throw new IllegalArgumentException("checkRows is a list of {sql, key: [columns]}");
    if (checkRows.size() > MAX_QUERIES) throw new IllegalArgumentException("checkRows takes up to " + MAX_QUERIES + " queries");
    for (JsonNode entry : checkRows) {
      String sql = entry.path("sql").asText("").trim();
      if (sql.isEmpty()) throw new IllegalArgumentException("Each checkRows entry needs its 'sql' (a SELECT)");
      RowChanges check = new RowChanges(SqlTools.checkReadOnly(sql));
      JsonNode key = entry.path("key");
      if (key.isTextual()) check.key.add(key.asText().trim().toUpperCase(Locale.ROOT));
      else for (JsonNode k : key) check.key.add(k.asText().trim().toUpperCase(Locale.ROOT));
      if (check.key.isEmpty()) {
        throw new IllegalArgumentException("Each checkRows entry needs 'key': the columns that identify a row (e.g. [\"COL1\"])");
      }
      checks.add(check);
    }
    return checks;
  }

  /* The rows now: key -> {column: value}, in the query's order */
  Map<String, Map<String, String>> snapshot(SqlTools tools, Connection readOnly) throws SQLException {
    tools.checkWithDb2(readOnly, sql);
    Map<String, Map<String, String>> rows = new LinkedHashMap<String, Map<String, String>>();
    try (Statement stmt = readOnly.createStatement()) {
      stmt.setMaxRows(MAX_ROWS + 1);
      stmt.setQueryTimeout(60);
      try (ResultSet rs = stmt.executeQuery(sql)) {
        ResultSetMetaData meta = rs.getMetaData();
        List<String> columns = new ArrayList<String>();
        for (int c = 1; c <= meta.getColumnCount(); c++) columns.add(meta.getColumnLabel(c).toUpperCase(Locale.ROOT));
        for (String k : key) {
          if (!columns.contains(k)) throw new IllegalArgumentException("Key column " + k + " is not in the result of: " + sql);
        }
        while (rs.next()) {
          if (rows.size() == MAX_ROWS) {
            throw new IllegalArgumentException("More than " + MAX_ROWS + " rows for: " + sql + ". Narrow it with a WHERE "
                + "(the rows the call works on)");
          }
          Map<String, String> row = new LinkedHashMap<String, String>();
          for (int c = 1; c <= columns.size(); c++) {
            String value = rs.getString(c);
            row.put(columns.get(c - 1), value == null ? null : value.replaceAll("\\s+$", ""));
          }
          StringBuilder id = new StringBuilder();  // each part with its length: no two keys read the same
          for (String k : key) {
            String part = row.get(k);
            if (part == null) {
              throw new IllegalArgumentException("Key column " + k + " is NULL in a row of: " + sql + ". Give key columns "
                  + "that are never NULL (rows with a NULL key cannot be matched before and after)");
            }
            id.append(part.length()).append(':').append(part);
          }
          if (rows.put(id.toString(), row) != null) {
            throw new IllegalArgumentException("The key " + key + " repeats in: " + sql + ". Give the columns that make a row unique");
          }
        }
      }
    }
    return rows;
  }

  /* What changed from before to after: inserted / deleted rows and, for kept keys, the columns that differ */
  ObjectNode compare(Map<String, Map<String, String>> before, Map<String, Map<String, String>> after, List<String> mask) {
    ObjectNode result = mapper.createObjectNode();
    result.put("sql", sql);
    result.put("rowsBefore", before.size());
    result.put("rowsAfter", after.size());
    ArrayNode inserted = mapper.createArrayNode();
    ArrayNode deleted = mapper.createArrayNode();
    ArrayNode changed = mapper.createArrayNode();
    int insertedCount = 0;
    int deletedCount = 0;
    int changedCount = 0;
    for (Map.Entry<String, Map<String, String>> row : after.entrySet()) {
      Map<String, String> old = before.get(row.getKey());
      if (old == null) {
        if (insertedCount++ < SHOWN) inserted.add(row(row.getValue(), mask));
        continue;
      }
      ObjectNode columns = mapper.createObjectNode();
      for (Map.Entry<String, String> col : row.getValue().entrySet()) {
        String was = old.get(col.getKey());
        String now = col.getValue();
        if (was == null ? now == null : was.equals(now)) continue;
        ArrayNode pair = columns.putArray(col.getKey());
        if (mask.contains(col.getKey())) {
          pair.add(SqlTools.MASKED).add(SqlTools.MASKED);
        } else {
          pair.add(was).add(now);
        }
      }
      if (columns.size() == 0) continue;
      if (changedCount++ < SHOWN) {
        ObjectNode change = changed.addObject();
        ObjectNode id = change.putObject("key");
        for (String k : key) id.put(k, mask.contains(k) ? SqlTools.MASKED : row.getValue().get(k));
        change.set("columns", columns);
      }
    }
    for (Map.Entry<String, Map<String, String>> row : before.entrySet()) {
      if (!after.containsKey(row.getKey()) && deletedCount++ < SHOWN) deleted.add(row(row.getValue(), mask));
    }
    result.put("inserted", insertedCount);
    result.put("deleted", deletedCount);
    result.put("changed", changedCount);
    if (insertedCount > 0) result.set("insertedRows", inserted);
    if (deletedCount > 0) result.set("deletedRows", deleted);
    if (changedCount > 0) result.set("changedRows", changed);
    if (insertedCount > SHOWN || deletedCount > SHOWN || changedCount > SHOWN) {
      result.put("note", "Only the first " + SHOWN + " rows of each kind are listed");
    }
    if (insertedCount + deletedCount + changedCount == 0) result.put("note", "No row changed");
    return result;
  }

  private static ObjectNode row(Map<String, String> values, List<String> mask) {
    ObjectNode row = mapper.createObjectNode();
    for (Map.Entry<String, String> col : values.entrySet()) {
      if (mask.contains(col.getKey())) row.put(col.getKey(), SqlTools.MASKED);
      else row.put(col.getKey(), col.getValue());
    }
    return row;
  }
}
