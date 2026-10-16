package com.github.kraudy.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * sql with 'format': the whole result (SqlTools.queryForFile, read wherever the query ran) written on the PC to
 * .mc/results/<file>, so big results stay out of the chat and the user can open or share them:
 *   csv      every value quoted, " doubled, NULL empty, UTF-8 with a BOM (Excel reads it right)
 *   json     an array of objects by column name (a repeated name gets _2, _3)
 *   md       a Markdown table
 *   inserts  INSERT statements with typed literals: numbers as they are (exact), DATE / TIME / TIMESTAMP '...',
 *            X'...' for binary, ' doubled in strings; 100 rows per statement
 * The tool answer keeps only the file, the counts and the first rows.
 */
final class ResultFiles {
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final int ROWS_PER_INSERT = 100;
  private static final int ROWS_IN_ANSWER = 5;
  private static final Pattern ONLY_TABLE = Pattern.compile(
      "\\bFROM\\s+((?:[A-Z$#@][A-Z0-9_$#@]*\\s*[./]\\s*)?([A-Z$#@][A-Z0-9_$#@]*))", Pattern.CASE_INSENSITIVE);

  private ResultFiles() {}

  static boolean wanted(JsonNode args) {
    return !args.path("format").asText("").trim().isEmpty();
  }

  /* The tool result with the file written, or an error result; 'result' is the full query (SqlTools) */
  static ObjectNode finish(JsonNode args, ObjectNode result, File project) {
    if (!wanted(args) || result.path("isError").asBoolean(false)) return result;
    String format = args.path("format").asText().trim().toLowerCase(Locale.ROOT);
    try {
      JsonNode full = mapper.readTree(result.path("content").path(0).path("text").asText("{}"));
      if (!full.has("rows")) return result;  // not a file query (an older MC on the other side): as it came
      String content;
      String ext;
      String table = null;
      ObjectNode answer = mapper.createObjectNode();
      switch (format) {
        case "csv":  content = "\uFEFF" + csv(full); ext = "csv"; break;
        case "json": content = mapper.writeValueAsString(objects(full)); ext = "json"; break;
        case "md":   content = markdown(full); ext = "md"; break;
        case "inserts":
          table = table(args);
          content = inserts(full, table, "keep".equalsIgnoreCase(args.path("generated").asText("omit")), answer);
          ext = "sql";
          break;
        default:
          return error("format is csv, json, md or inserts, not '" + format + "'");
      }
      File dir = new File(project, ".mc/results");
      dir.mkdirs();
      File file = new File(dir, fileName(args, table, ext));
      java.nio.file.Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));

      ObjectNode head = mapper.createObjectNode();
      head.put("file", file.getAbsolutePath());
      head.setAll(answer);
      answer = head;
      answer.put("projectPath", ".mc/results/" + file.getName());
      answer.put("format", format);
      answer.put("rowCount", full.path("rowCount").asInt());
      if (full.has("moreRows")) answer.set("moreRows", full.get("moreRows"));
      ArrayNode columns = answer.putArray("columns");
      for (JsonNode c : full.path("columns")) columns.add(c.path("name").asText());
      if (full.has("maskedColumns")) answer.set("maskedColumns", full.get("maskedColumns"));
      if (full.has("replaced")) answer.set("replaced", full.get("replaced"));
      ArrayNode first = answer.putArray("firstRows");
      for (int r = 0; r < Math.min(ROWS_IN_ANSWER, full.path("rows").size()); r++) first.add(full.path("rows").get(r));
      if (table != null) answer.put("insertInto", table);
      if (full.has("generatedColumns")) answer.set("generatedColumns", full.get("generatedColumns"));
      answer.put("next", "The whole result is in the file (git-ignored, under .mc). Tell the user where it is; read it from "
          + "there instead of running the query again.");
      return text(mapper.writeValueAsString(answer), false);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (Exception e) {
      return error("Could not write the results file: " + e.getMessage());
    }
  }

  private static String csv(JsonNode full) {
    StringBuilder out = new StringBuilder();
    List<String> header = new ArrayList<String>();
    for (JsonNode c : full.path("columns")) header.add(quoteCsv(c.path("name").asText()));
    out.append(String.join(",", header)).append("\r\n");
    for (JsonNode row : full.path("rows")) {
      List<String> values = new ArrayList<String>();
      for (JsonNode v : row) values.add(v.isNull() ? "" : quoteCsv(v.asText()));
      out.append(String.join(",", values)).append("\r\n");
    }
    return out.toString();
  }

  private static String quoteCsv(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  /* Rows as objects; a repeated column name gets _2, _3 so no value is lost */
  private static ArrayNode objects(JsonNode full) {
    List<String> names = new ArrayList<String>();
    for (JsonNode c : full.path("columns")) {
      String name = c.path("name").asText();
      String unique = name;
      for (int n = 2; names.contains(unique); n++) unique = name + "_" + n;
      names.add(unique);
    }
    ArrayNode list = mapper.createArrayNode();
    for (JsonNode row : full.path("rows")) {
      ObjectNode o = list.addObject();
      for (int c = 0; c < names.size(); c++) o.set(names.get(c), row.get(c));
    }
    return list;
  }

  private static String markdown(JsonNode full) {
    StringBuilder out = new StringBuilder();
    List<String> header = new ArrayList<String>();
    List<String> rule = new ArrayList<String>();
    for (JsonNode c : full.path("columns")) {
      header.add(cell(c.path("name").asText()));
      rule.add("---");
    }
    out.append("| ").append(String.join(" | ", header)).append(" |\n");
    out.append("| ").append(String.join(" | ", rule)).append(" |\n");
    for (JsonNode row : full.path("rows")) {
      List<String> values = new ArrayList<String>();
      for (JsonNode v : row) values.add(v.isNull() ? "NULL" : cell(v.asText()));
      out.append("| ").append(String.join(" | ", values)).append(" |\n");
    }
    return out.toString();
  }

  private static String cell(String value) {
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }

  /* INSERT statements with a literal per column type; masked values are refused (SqlTools asks for set first) */
  private static String inserts(JsonNode full, String table, boolean keepIdentity, ObjectNode answer) {
    if (full.has("maskedColumns")) {
      throw new IllegalArgumentException("Masked columns " + full.get("maskedColumns") + " need a replacement in set");
    }
    JsonNode columns = full.path("columns");
    List<String> names = new ArrayList<String>();
    List<Integer> used = new ArrayList<Integer>();
    boolean overriding = false;
    for (int c = 0; c < columns.size(); c++) {
      String generated = columns.get(c).path("generated").asText("");
      if (generated.equals("identity") && keepIdentity) {
        overriding = true;
      } else if (!generated.isEmpty()) {
        answer.withArray("omittedGenerated").add(columns.get(c).path("name").asText() + " (" + generated + ")");
        continue;
      }
      used.add(c);
      names.add(sqlName(columns.get(c).path("name").asText()));
    }
    if (names.isEmpty()) throw new IllegalArgumentException("Every column read is generated by Db2: nothing to insert");
    StringBuilder out = new StringBuilder();
    out.append("-- ").append(full.path("rowCount").asInt()).append(" rows read with: ")
        .append(full.path("statement").asText().replace("\n", " ")).append('\n');
    if (full.has("replaced")) out.append("-- replaced on the system read: ").append(full.get("replaced")).append('\n');
    if (answer.has("omittedGenerated")) {
      out.append("-- left out, Db2 generates them: ").append(answer.get("omittedGenerated")).append('\n');
    }
    if (full.has("generatedColumns")) out.append("-- generated columns ").append(full.path("generatedColumns").asText()).append('\n');
    JsonNode rows = full.path("rows");
    for (int start = 0; start < rows.size(); start += ROWS_PER_INSERT) {
      out.append("\nINSERT INTO ").append(table).append(" (").append(String.join(", ", names)).append(")\n")
          .append(overriding ? "  OVERRIDING SYSTEM VALUE\n" : "").append("  VALUES\n");
      List<String> tuples = new ArrayList<String>();
      for (int r = start; r < Math.min(start + ROWS_PER_INSERT, rows.size()); r++) {
        JsonNode row = rows.get(r);
        List<String> values = new ArrayList<String>();
        for (int c : used) values.add(literal(columns.get(c), row.get(c)));
        tuples.add("  (" + String.join(", ", values) + ")");
      }
      out.append(String.join(",\n", tuples)).append(";\n");
    }
    return out.toString();
  }

  static String literal(JsonNode column, JsonNode value) {
    if (value == null || value.isNull()) return "NULL";
    String text = value.asText();
    String type = column.path("typeName").asText("").toUpperCase(Locale.ROOT);
    switch (column.path("jdbcType").asInt(Types.VARCHAR)) {
      case Types.BINARY: case Types.VARBINARY: case Types.LONGVARBINARY: case Types.BLOB:
        return "X'" + text + "'";
      case Types.DATE:
        return "DATE '" + text + "'";
      case Types.TIME:
        return "TIME '" + text + "'";
      case Types.TIMESTAMP:
        return "TIMESTAMP '" + text + "'";
      case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
      case Types.DECIMAL: case Types.NUMERIC: case Types.REAL: case Types.FLOAT: case Types.DOUBLE:
        return text;
      case Types.BOOLEAN:
        return text.equalsIgnoreCase("true") || text.equals("1") ? "TRUE" : "FALSE";
      default:
        if (type.startsWith("DECFLOAT")) return text.matches("[-+0-9.Ee]+") ? text : "DECFLOAT('" + text + "')";
        return "'" + text.replace("'", "''") + "'";
    }
  }

  private static String sqlName(String name) {
    return name.matches("[A-Z$#@][A-Z0-9_$#@]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /* 'table', else the query's only FROM table (unqualified: the test's library list finds it) */
  private static String table(JsonNode args) {
    String given = args.path("table").asText("").trim();
    if (!given.isEmpty()) {
      /* a name, LIB/NAME or LIB.NAME (quoted parts allowed): nothing else ends up in the statements */
      String part = "(?:[A-Za-z$#@_][A-Za-z0-9$#@_]*|\"[^\"]+\")";
      if (!given.matches(part + "(?:\\s*[./]\\s*" + part + ")?")) {
        throw new IllegalArgumentException("table must be a table name (TABLE1, LIB1/TABLE1 or LIB1.TABLE1), not: " + given);
      }
      return given;
    }
    String only = onlyTable(args.path("statement").asText(""));
    if (only == null) {
      throw new IllegalArgumentException("format inserts needs 'table': the table the statements insert into "
          + "(the query does not read just one table)");
    }
    String name = only.replace(" ", "");
    return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('.')) + 1).toUpperCase(Locale.ROOT);
  }

  /* The table after the query's only FROM, as written (LIB/TABLE, LIB.TABLE or TABLE); null with joins or several */
  static String onlyTable(String statement) {
    Matcher m = ONLY_TABLE.matcher(statement);
    String found = null;
    int count = 0;
    while (m.find()) {
      count++;
      found = m.group(1);
    }
    if (count != 1 || statement.toUpperCase(Locale.ROOT).matches("(?s).*\\bJOIN\\b.*")) return null;
    return found;
  }

  private static String fileName(JsonNode args, String table, String ext) {
    String given = args.path("file").asText("").trim();
    String base;
    if (!given.isEmpty()) {
      base = new File(given.replace('\\', '/')).getName();  // a name, never a path out of .mc/results
      if (base.contains(".")) base = base.substring(0, base.lastIndexOf('.'));
    } else {
      String from = table;
      if (from == null) {
        Matcher m = ONLY_TABLE.matcher(args.path("statement").asText(""));
        from = m.find() ? m.group(2) : "result";
      }
      base = from.toLowerCase(Locale.ROOT) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }
    base = base.replaceAll("[^A-Za-z0-9._-]", "_");
    if (base.isEmpty() || base.startsWith(".")) base = "result" + base;
    return base + "." + ext;
  }

  private static ObjectNode error(String message) {
    return text(message, true);
  }

  private static ObjectNode text(String text, boolean isError) {
    ObjectNode result = mapper.createObjectNode();
    result.putArray("content").addObject().put("type", "text").put("text", text);
    result.put("isError", isError);
    return result;
  }
}
