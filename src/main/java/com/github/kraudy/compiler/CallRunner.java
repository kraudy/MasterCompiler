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
import com.ibm.as400.access.CommandCall;

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
  private static final int SPOOL_SETTLE_SECONDS = 15;  // for the job's files to be complete and readable
  private static final int NO_SPOOL_SECONDS = 5;       // how long to look for a file when the job has none
  private static final int QPRINT_WIDTH = 132;

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

  /* The parameters as call_program takes them: a JSON array of {type, length|prefix|digits|decimals|bytes, value} */
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
      if (!p.isObject() || !(type.equals("char") || type.equals("varchar") || type.equals("packed") || type.equals("zoned") || type.equals("int"))) {
        throw new IllegalArgumentException("--call-params: each parameter is {\"type\": char|varchar|packed|zoned|int, ...}: " + p);
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
      result = ProgramCaller.call(system, program.library == null ? "*LIBL" : program.library, program.name, parameters, libl, curlib,
          new CallJob(system, connection, parser.isInqmsgry()));
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

  /* Around the call: the job answers inquiry messages by default when asked to, and says what it waits on if it hangs */
  private static final class CallJob implements ProgramCaller.Around {
    private final AS400 system;
    private final Connection connection;
    private final boolean inquiryDefault;

    CallJob(AS400 system, Connection connection, boolean inquiryDefault) {
      this.system = system;
      this.connection = connection;
      this.inquiryDefault = inquiryDefault;
    }

    @Override
    public void before() throws Exception {
      if (!inquiryDefault) return;
      CommandCall chgjob = new CommandCall(system);  // the same host-server job as the call
      if (!chgjob.run("CHGJOB INQMSGRPY(*DFT)")) throw new IllegalStateException("CHGJOB INQMSGRPY(*DFT) failed for the call's job");
    }

    @Override
    public void after(ObjectNode result) {}

    @Override
    public String hung(String job) {
      StringBuilder out = new StringBuilder();
      boolean waiting = false;
      String simpleName = job.substring(job.lastIndexOf('/') + 1);
      try (PreparedStatement stmt = connection.prepareStatement("SELECT JOB_STATUS, COALESCE(FUNCTION, '') "
          + "FROM TABLE(QSYS2.ACTIVE_JOB_INFO(JOB_NAME_FILTER => ?)) WHERE JOB_NAME = ?")) {
        stmt.setQueryTimeout(10);
        stmt.setString(1, simpleName);
        stmt.setString(2, job);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            String status = rs.getString(1).trim();
            waiting = status.equals("MSGW");
            out.append("Job ").append(job).append(" status: ").append(status).append(waiting ? " (a message waits for a reply)" : "");
            if (!rs.getString(2).trim().isEmpty()) out.append(", function ").append(rs.getString(2).trim());
          }
        }
      } catch (Exception e) {
        logger.debug("Job status not read", e);
      }
      boolean inquiry = false;
      try (PreparedStatement stmt = connection.prepareStatement("SELECT COALESCE(MESSAGE_ID, ''), MESSAGE_TYPE, SEVERITY, "
          + "COALESCE(MESSAGE_TEXT, '') FROM TABLE(QSYS2.JOBLOG_INFO(?)) ORDER BY ORDINAL_POSITION DESC FETCH FIRST 6 ROWS ONLY")) {
        stmt.setQueryTimeout(15);
        stmt.setString(1, job);
        List<String> lines = new ArrayList<String>();
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            String type = rs.getString(2).trim();
            inquiry |= type.equals("INQUIRY");
            lines.add(0, "  " + rs.getString(1).trim() + " " + type + " (sev " + rs.getInt(3) + ") " + rs.getString(4).trim());
          }
        }
        if (!lines.isEmpty()) {
          out.append(out.length() == 0 ? "" : "\n").append("Last messages in its job log:");
          for (String line : lines) out.append('\n').append(line);
        }
      } catch (Exception e) {
        logger.debug("Job log not read", e);
      }
      if (waiting || inquiry) {
        out.append("\nA batch / server job waits forever for an inquiry message's reply (an RPG error such as invalid numeric data): "
            + "run again with --inqmsgry to answer them with the default reply, and the error shows in the job log.");
      }
      return out.length() == 0 ? null : out.toString();
    }
  }

  /*
   * The call's job is ended so its open QPRINT is closed (an open spooled file shows no data) and its job log is
   * written; then the spooled files that job owns are read, once they are complete (not OPEN / CLOSED) and have lines,
   * for a bounded time. Only the job's own files are ever read: another job's file would look like this run's output.
   * Nothing here can fail the call: the answer is just shorter, and says why.
   */
  private static void collectSpool(AS400 system, Connection connection, ObjectNode result, Timestamp started) {
    String job = result.path("job").asText("");
    ArrayNode spool = result.putArray("spool");
    if (job.isEmpty()) {
      result.put("spoolNote", "The call's job is not known, so its spooled output was not read");
      return;
    }
    StringBuilder notes = new StringBuilder();
    try {
      system.disconnectService(AS400.COMMAND);
      if (!waitForJobEnd(connection, job)) notes.append("The job may still have been ending: its last output may be missing. ");
      long now = System.currentTimeMillis();
      long settleEnd = now + SPOOL_SETTLE_SECONDS * 1000L;
      long noFileEnd = now + NO_SPOOL_SECONDS * 1000L;
      List<ObjectNode> files = new ArrayList<ObjectNode>();
      java.util.Map<String, ObjectNode> read = new java.util.HashMap<String, ObjectNode>();  // complete files are read once
      while (true) {
        files = readJobFiles(connection, job, read);
        now = System.currentTimeMillis();
        boolean complete = !files.isEmpty();
        for (ObjectNode f : files) complete &= !incomplete(f.path("status").asText()) && f.path("totalLines").asInt() > 0;
        if (complete || now >= settleEnd || (files.isEmpty() && now >= noFileEnd)) break;
        sleep(500);
      }
      for (ObjectNode f : files) {
        spool.add(f);
        if (incomplete(f.path("status").asText()) || f.path("totalLines").asInt() == 0) {
          notes.append("Spooled file ").append(f.path("file").asText()).append(" (").append(f.path("number").asInt()).append(") is ")
              .append(f.path("status").asText()).append(" with ").append(f.path("totalLines").asInt())
              .append(" lines after ").append(SPOOL_SETTLE_SECONDS).append(" s: its output may be incomplete")
              .append(f.has("error") ? " (" + f.path("error").asText() + ")" : "").append(". ");
        }
        int widest = 0;
        for (JsonNode line : f.path("lines")) widest = Math.max(widest, line.asText().length());
        if (widest >= QPRINT_WIDTH) {
          notes.append("A line of ").append(f.path("file").asText()).append(" is ").append(widest)
              .append(" characters wide: QPRINT is ").append(QPRINT_WIDTH).append(" wide, so longer lines may be cut and the output after them lost; "
              + "keep lines shorter or write to a stream file (--fetch) or a physical file (--sql). ");
        }
      }
      if (files.isEmpty()) {
        notes.append("The job ").append(job).append(" owns no spooled file (a program that prints nothing leaves none). "
            + "Other spooled files are never read for a call, because they may belong to other jobs");
        String others = recentFiles(connection, started);
        if (!others.isEmpty()) notes.append(". Files of this user made since the call began, not read: ").append(others);
        notes.append(". ");
      }
    } catch (Exception e) {
      logger.debug("Spooled output not read", e);
      notes.append("The spooled output of ").append(job).append(" could not be read: ").append(e.getMessage()).append(". ");
    }
    if (notes.length() > 0) result.put("spoolNote", notes.toString().trim());
  }

  private static boolean incomplete(String status) {
    return status.equals("OPEN") || status.equals("CLOSED");
  }

  /*
   * The job's own spooled files, oldest first (the order it wrote them), each with its status. A file still OPEN / CLOSED is
   * not read yet (it shows no data); one that cannot be read is listed with the error, to be tried again; a complete one
   * is read once ('read' keeps it).
   */
  private static List<ObjectNode> readJobFiles(Connection connection, String job, java.util.Map<String, ObjectNode> read)
      throws java.sql.SQLException {
    List<ObjectNode> out = new ArrayList<ObjectNode>();
    JsonNode listed = SpoolFiles.list(connection, "", job).path("spooledFiles");
    for (int i = listed.size() - 1; i >= 0; i--) {
      JsonNode f = listed.get(i);
      String name = f.path("file").asText();
      int number = f.path("number").asInt();
      String status = f.path("status").asText();
      String key = name + "/" + number;
      if (read.containsKey(key)) {
        out.add(read.get(key));
        continue;
      }
      ObjectNode file;
      if (incomplete(status)) {
        file = emptyFile(name, number, job, status);
      } else {
        try {
          file = SpoolFiles.read(connection, name, job, number, 1, SpoolFiles.MAX_LINES);
          file.put("status", status);
          if (file.path("totalLines").asInt() > 0) read.put(key, file);
        } catch (IllegalArgumentException gone) {
          continue;  // deleted between the list and the read
        } catch (java.sql.SQLException notYet) {
          file = emptyFile(name, number, job, status);  // e.g. still being closed: tried again
          file.put("error", String.valueOf(notYet.getMessage()).split("\n")[0]);
        }
      }
      out.add(file);
    }
    return out;
  }

  private static ObjectNode emptyFile(String name, int number, String job, String status) {
    ObjectNode file = mapper.createObjectNode();
    file.put("file", name);
    file.put("job", job);
    file.put("number", number);
    file.put("status", status);
    file.put("totalLines", 0);
    file.putArray("lines");
    return file;
  }

  /* Names only: what the user could look at with the spool tool; never served as the call's output */
  private static String recentFiles(Connection connection, Timestamp started) {
    if (started == null) return "";
    StringBuilder out = new StringBuilder();
    try (PreparedStatement stmt = connection.prepareStatement("SELECT SPOOLED_FILE_NAME, SPOOLED_FILE_NUMBER, QUALIFIED_JOB_NAME, STATUS "
        + "FROM TABLE(QSYS2.SPOOLED_FILE_INFO(USER_NAME => USER)) WHERE CREATION_TIMESTAMP >= ? ORDER BY CREATION_TIMESTAMP DESC "
        + "FETCH FIRST 5 ROWS ONLY")) {
      /* CREATION_TIMESTAMP has whole seconds: start one second earlier, cut to seconds */
      stmt.setTimestamp(1, new Timestamp(started.getTime() / 1000 * 1000 - 1000));
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          out.append(out.length() == 0 ? "" : "; ").append(rs.getString(1).trim()).append(" (").append(rs.getInt(2)).append(") ")
              .append(rs.getString(3).trim()).append(' ').append(rs.getString(4).trim());
        }
      }
    } catch (Exception e) {
      logger.debug("Recent spooled files not listed", e);
    }
    return out.toString();
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
    for (int i = 0; i < parameters.size(); i++) out.append("  parameter ").append(i + 1).append(": ")
        .append(parameters.get(i).isNull() ? "(none: the call failed)" : parameters.get(i).asText()).append('\n');
    for (JsonNode message : result.path("messages")) out.append("  message: ").append(message.asText()).append('\n');
    for (JsonNode file : result.path("spool")) {
      out.append("--- ").append(file.path("file").asText()).append(" (").append(file.path("number").asInt()).append(") of job ")
          .append(file.path("job").asText()).append(", ").append(file.path("status").asText()).append(", ")
          .append(file.path("totalLines").asInt()).append(" lines\n");
      for (JsonNode line : file.path("lines")) out.append(line.asText()).append('\n');
      if (file.has("more")) out.append("[").append(file.path("more").asText()).append("]\n");
    }
    if (result.has("spoolNote")) out.append("Note: ").append(result.path("spoolNote").asText()).append('\n');
    return out.toString().replaceAll("\\s+$", "");
  }
}
