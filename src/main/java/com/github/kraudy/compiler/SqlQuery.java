package com.github.kraudy.compiler;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;

/*
 * --sql "SELECT ...": the MCP sql tool from the command line. One read-only statement on a read-only connection,
 * the rows printed tab-separated (--json writes them as JSON). For a probe that writes its answer to a physical file.
 */
final class SqlQuery {
  private SqlQuery() {}

  /* The library list of the query's connection: --curlib first, then --libl, else Code for IBM i's; empty: the job's own */
  static List<String> libraries(ArgParser parser, Code4iConfig code4i) {
    List<String> libl = new ArrayList<String>();
    String curlib = parser.getCurlib() != null ? parser.getCurlib().trim().toUpperCase(Locale.ROOT) : null;
    List<String> base;
    if (parser.getLibl() != null) {
      base = CallRunner.libraries(parser.getLibl());
    } else {
      base = code4i != null ? code4i.libraryList : new ArrayList<String>();
      if (curlib == null && code4i != null && code4i.currentLibrary != null && !code4i.currentLibrary.isEmpty()) curlib = code4i.currentLibrary;
    }
    if (curlib != null) libl.add(curlib);
    for (String lib : base) if (!lib.equalsIgnoreCase(curlib)) libl.add(lib);
    return libl;
  }

  static int run(AS400 system, ArgParser parser, Code4iConfig code4i) throws Exception {
    String statement = SqlTools.checkReadOnly(parser.getSql());
    ObjectNode result;
    try (Connection connection = SqlTools.readOnlyConnection(system, libraries(parser, code4i))) {
      result = new SqlTools(0, new ArrayList<String>(), null).query(connection, statement, parser.getMaxRows(), 0);
    } catch (SQLException e) {
      System.err.println("SQL error " + e.getSQLState() + " (" + e.getErrorCode() + "): " + e.getMessage());
      return 1;
    }
    if (parser.getJsonReport() != null) {
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(parser.getJsonReport()), result);
    }
    System.out.println(format(result));
    return 0;
  }

  /* Header, one tab-separated line per row, then the row count (and what was left out) */
  static String format(ObjectNode result) {
    StringBuilder out = new StringBuilder();
    boolean first = true;
    for (JsonNode column : result.path("columns")) {
      out.append(first ? "" : "\t").append(column.asText());
      first = false;
    }
    out.append('\n');
    for (JsonNode row : result.path("rows")) {
      first = true;
      for (JsonNode value : row) {
        out.append(first ? "" : "\t").append(value.isNull() ? "-" : value.asText().replaceAll("[\\t\\r\\n]+", " "));
        first = false;
      }
      out.append('\n');
    }
    out.append(result.path("rowCount").asInt()).append(result.path("rowCount").asInt() == 1 ? " row" : " rows");
    if (result.has("moreRows")) out.append(". ").append(result.path("moreRows").asText());
    if (result.has("columnsOmitted")) out.append(". ").append(result.path("columnsOmitted").asInt()).append(" more columns not shown");
    return out.toString();
  }
}
