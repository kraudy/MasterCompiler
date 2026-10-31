package com.github.kraudy.compiler;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;

/*
 * --call LIB/PGM [--call-params '<json>'] [--libl "<libs>"] [--curlib <lib>]: run a program from the command line, in one
 * job with the library list you gave (CHGLIBL, then the call, in the same job), and print what it gives back: its
 * parameters, its messages and the spooled output of its job (QPRINT: what a C program prints, a CL program's DSPLIBL, ...).
 * It is call_program's engine (ProgramCaller) without the preview: you typed the command, so there is no one to ask.
 * The parameters use call_program's form: [{"type": "char", "length": 10, "value": "A"}, {"type": "int", "bytes": 4}].
 */
final class CallRunner {
  private static final Logger logger = LoggerFactory.getLogger(CallRunner.class);
  private static final Pattern NAME = Pattern.compile("[A-Z0-9_#@$]{1,10}");
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final int JOB_END_SECONDS = 20;

  private CallRunner() {}

  static final class Program {
    final String library;  // null: found through the library list
    final String name;

    Program(String library, String name) {
      this.library = library;
      this.name = name;
    }

    String asString() {
      return (library == null ? "*LIBL" : library) + "/" + name;
    }
  }

  /* LIB/PGM or PGM (through the library list); names checked, they end up in a path; throws IllegalArgumentException */
  static Program parse(String selection) {
    String[] names = selection.trim().toUpperCase(Locale.ROOT).split("/", -1);
    boolean qualified = names.length == 2;
    if (names.length > 2 || !NAME.matcher(names[names.length - 1]).matches() || (qualified && !NAME.matcher(names[0]).matches())) {
      throw new IllegalArgumentException("--call needs LIB/PGM or PGM (names of up to 10 letters, digits, _ # @ $): " + selection.trim());
    }
    return new Program(qualified ? names[0] : null, names[names.length - 1]);
  }

  /* The parameters as call_program takes them: a JSON array of {type, length|digits|decimals|bytes, value} */
  static JsonNode parameters(String json) {
    if (json == null || json.trim().isEmpty()) return mapper.createArrayNode();
    JsonNode parsed;
    try {
      parsed = mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("--call-params is a JSON array, e.g. '[{\"type\":\"char\",\"length\":10,\"value\":\"A\"}]': "
          + e.getMessage().split("\n")[0]);
    }
    if (parsed == null || !parsed.isArray()) throw new IllegalArgumentException("--call-params is a JSON array of parameters");
    for (JsonNode p : parsed) {
      String type = p.path("type").asText("char").toLowerCase(Locale.ROOT);
      if (!p.isObject() || !(type.equals("char") || type.equals("packed") || type.equals("zoned") || type.equals("int"))) {
        throw new IllegalArgumentException("--call-params: each parameter is {\"type\": char|packed|zoned|int, ...}: " + p);
      }
    }
    return parsed;
  }

  /* --libl as a list of library names (spaces or commas); throws IllegalArgumentException for anything else */
  static List<String> libraries(String libl) {
    List<String> libs = new ArrayList<String>();
    for (String lib : libl.trim().toUpperCase(Locale.ROOT).split("[\\s,]+")) {
      if (lib.isEmpty()) continue;
      if (!NAME.matcher(lib).matches()) throw new IllegalArgumentException("--libl is a list of library names: " + lib);
      libs.add(lib);
    }
    return libs;
  }

  /* What --dry-run says: nothing is called */
  static String dryRun(ArgParser parser) {
    Program program = parse(parser.getCall());
    return "Dry run: would call " + program.asString() + " with parameters " + parameters(parser.getCallParams())
        + (parser.getLibl() != null ? " and library list " + libraries(parser.getLibl()) : "")
        + (parser.getCurlib() != null ? " and current library " + parser.getCurlib().trim().toUpperCase(Locale.ROOT) : "")
        + "; nothing was called.";
  }

  /* Exit code: 0 when the program ran and ended without an error message, 1 otherwise */
  static int run(AS400 system, Connection connection, ArgParser parser, Code4iConfig code4i) throws Exception {
    Program program = parse(parser.getCall());
    JsonNode parameters = parameters(parser.getCallParams());

    /* The job's library list: --libl, else Code for IBM i's, else the one MC's own job has. The current library:
       --curlib, else (without --libl) Code for IBM i's, else (without --libl) MC's own job's; with --libl alone the job keeps its own */
    List<String> libl;
    String curlib = parser.getCurlib() != null ? parser.getCurlib().trim().toUpperCase(Locale.ROOT) : null;
    boolean typedLibl = parser.getLibl() != null;
    if (typedLibl) {
      libl = libraries(parser.getLibl());
    } else {
      libl = code4i != null && !code4i.libraryList.isEmpty() ? new ArrayList<String>(code4i.libraryList) : SqlTools.libraryList(connection);
      if (curlib == null) {
        curlib = code4i != null && code4i.currentLibrary != null && !code4i.currentLibrary.isEmpty() ? code4i.currentLibrary
            : currentLibrary(connection);
      }
    }
    if (curlib != null && !NAME.matcher(curlib.toUpperCase(Locale.ROOT)).matches()) {
      throw new IllegalArgumentException("--curlib is not a library name: " + curlib);
    }

    Timestamp started = serverTime(connection);
    ObjectNode result;
    try {
      result = ProgramCaller.call(system, program.library == null ? "*LIBL" : program.library, program.name, parameters, libl, curlib);
    } catch (IllegalStateException e) {
      System.err.println("Call failed: " + e.getMessage());
      return 1;
    }
    collectSpool(system, connection, result, started);

    if (parser.getJsonReport() != null) {
      mapper.writerWithDefaultPrettyPrinter().writeValue(new File(parser.getJsonReport()), result);
    }
    System.out.println(format(result));
    return result.path("success").asBoolean(false) ? 0 : 1;
  }

  private static Timestamp serverTime(Connection connection) {
    try (Statement stmt = connection.createStatement()) {
      stmt.setQueryTimeout(10);
      ResultSet rs = stmt.executeQuery("SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1");
      return rs.next() ? rs.getTimestamp(1) : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String currentLibrary(Connection connection) {
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT TRIM(SYSTEM_SCHEMA_NAME) FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT'")) {
      return rs.next() ? rs.getString(1) : null;
    } catch (Exception e) {
      return null;
    }
  }

  /*
   * The call's job is ended so its open QPRINT is closed (an open spooled file shows no data) and its job log is
   * written; then the spooled files that job made are read. Nothing here can fail the call: the answer is just shorter.
   */
  private static void collectSpool(AS400 system, Connection connection, ObjectNode result, Timestamp started) {
    String job = result.path("job").asText("");
    ArrayNode spool = result.putArray("spool");
    if (job.isEmpty()) {
      result.put("spoolNote", "The call's job is not known, so its spooled output was not read");
      return;
    }
    try {
      system.disconnectService(AS400.COMMAND);
      boolean ended = waitForJobEnd(connection, job);
      if (!ended) result.put("spoolNote", "The job may still have been ending: its last output may be missing");
      /* The files the job owns, oldest first: the order it wrote them */
      List<String[]> files = new ArrayList<String[]>();
      for (JsonNode f : SpoolFiles.list(connection, "", job).path("spooledFiles")) {
        files.add(0, new String[] { f.path("file").asText(), String.valueOf(f.path("number").asInt()), job });
      }
      /* A server job's output can belong to a print job (QPRTJOB) of the user instead: then the files made since the call began */
      if (files.isEmpty() && started != null) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT SPOOLED_FILE_NAME, SPOOLED_FILE_NUMBER, QUALIFIED_JOB_NAME "
            + "FROM TABLE(QSYS2.SPOOLED_FILE_INFO(USER_NAME => USER)) WHERE CREATION_TIMESTAMP >= ? ORDER BY CREATION_TIMESTAMP")) {
          /* CREATION_TIMESTAMP has whole seconds: start one second earlier, cut to seconds, so a quick call's file is not left out */
          stmt.setTimestamp(1, new Timestamp(started.getTime() / 1000 * 1000 - 1000));
          stmt.setQueryTimeout(30);
          try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) files.add(new String[] { rs.getString(1).trim(), String.valueOf(rs.getInt(2)), rs.getString(3).trim() });
          }
        }
        if (!files.isEmpty()) result.put("spoolNote", "The job owns no spooled file; these are the files of this user made since the call began");
      }
      for (String[] f : files) spool.add(SpoolFiles.read(connection, f[0], f[2], Integer.parseInt(f[1]), 1, SpoolFiles.MAX_LINES));
    } catch (Exception e) {
      logger.debug("Spooled output not read", e);
      result.put("spoolNote", "The spooled output of " + job + " could not be read: " + e.getMessage());
    }
  }

  /* true when the job is gone; false when that could not be told (the caller says so) */
  private static boolean waitForJobEnd(Connection connection, String job) {
    String simpleName = job.substring(job.lastIndexOf('/') + 1);  // the filter takes the simple name; the full one is compared below
    long end = System.currentTimeMillis() + JOB_END_SECONDS * 1000L;
    while (System.currentTimeMillis() < end) {
      try (PreparedStatement stmt = connection.prepareStatement(
          "SELECT COUNT(*) FROM TABLE(QSYS2.ACTIVE_JOB_INFO(JOB_NAME_FILTER => ?)) WHERE JOB_NAME = ?")) {
        stmt.setQueryTimeout(10);
        stmt.setString(1, simpleName);
        stmt.setString(2, job);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next() && rs.getInt(1) == 0) return true;
        }
      } catch (Exception e) {
        sleep(3000);  // the view is not there: give the job a moment to end instead
        return false;
      }
      sleep(500);
    }
    return false;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /* The answer as text: result line, parameters, messages, then each spooled file */
  static String format(ObjectNode result) {
    StringBuilder out = new StringBuilder();
    out.append(result.path("program").asText()).append(result.path("success").asBoolean(false) ? ": ended" : ": ended with an error")
        .append(" in ").append(result.path("milliseconds").asLong()).append(" ms");
    if (result.has("job")) out.append(", job ").append(result.path("job").asText());
    out.append('\n');
    JsonNode parameters = result.path("parameters");
    for (int i = 0; i < parameters.size(); i++) out.append("  parameter ").append(i + 1).append(": ").append(parameters.get(i).asText()).append('\n');
    for (JsonNode message : result.path("messages")) out.append("  message: ").append(message.asText()).append('\n');
    for (JsonNode file : result.path("spool")) {
      out.append("--- ").append(file.path("file").asText()).append(" (").append(file.path("number").asInt()).append("), ")
          .append(file.path("totalLines").asInt()).append(" lines\n");
      for (JsonNode line : file.path("lines")) out.append(line.asText()).append('\n');
      if (file.has("more")) out.append("[").append(file.path("more").asText()).append("]\n");
    }
    if (result.has("spoolNote")) out.append("Note: ").append(result.path("spoolNote").asText()).append('\n');
    return out.toString().replaceAll("\\s+$", "");
  }
}
