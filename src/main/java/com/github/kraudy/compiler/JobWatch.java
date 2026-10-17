package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;

/*
 * Looks at the build job from a connection of its own, so it answers while the build job is busy: its status
 * (RUN, MSGW, LCKW, DEQW, ...), call stack, the locks it waits for and who holds them, the message it waits on
 * and the joblog's last lines. Also stops it: a cancel of the running statement first, ENDJOB *IMMED if that
 * does not end it.
 */
final class JobWatch implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(JobWatch.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  static final int STACK_ENTRIES = 25;
  static final int JOBLOG_LINES = 15;
  static final int CANCEL_GRACE_SECONDS = 10;

  private final AS400 system;
  private Connection monitor;

  JobWatch(AS400 system) {
    this.system = system;
  }

  /* Qualified name of the job behind a connection: 123456/QUSER/QZDASOINIT */
  static String jobName(Connection connection) {
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT QSYS2.JOB_NAME FROM SYSIBM.SYSDUMMY1")) {
      return rs.next() ? rs.getString(1).trim() : null;
    } catch (SQLException e) {
      logger.warn("Could not read the job name: {}", e.getMessage());
      return null;
    }
  }

  private synchronized Connection connection() throws SQLException {
    if (monitor == null || monitor.isClosed()) monitor = new AS400JDBCDataSource(system).getConnection();
    return monitor;
  }

  /* Everything at once: what the job does now and what it waits for */
  synchronized ObjectNode inspect(String job) {
    ObjectNode result = mapper.createObjectNode();
    result.put("job", job);
    if (job == null) return result.put("note", "The build job is not known yet");
    try {
      Connection c = connection();
      status(c, job, result);
      if (result.has("ended")) return result;
      stack(c, job, result);
      locks(c, job, result);
      waiting(c, job, result);
      result.set("joblogTail", joblogTail(c, job, JOBLOG_LINES));
    } catch (SQLException e) {
      result.put("error", "Could not look at the job: " + e.getMessage());
    }
    return result;
  }

  /* The job's last joblog lines: time, id, text */
  synchronized ArrayNode joblogTail(String job, int lines) {
    try {
      return joblogTail(connection(), job, lines);
    } catch (SQLException e) {
      ArrayNode none = mapper.createArrayNode();
      none.add("Could not read the joblog: " + e.getMessage());
      return none;
    }
  }

  private ArrayNode joblogTail(Connection c, String job, int lines) throws SQLException {
    List<String> newestFirst = new ArrayList<String>();
    try (PreparedStatement stmt = c.prepareStatement("SELECT MESSAGE_TIMESTAMP, MESSAGE_ID, MESSAGE_TYPE, MESSAGE_TEXT "
        + "FROM TABLE(QSYS2.JOBLOG_INFO(?)) WHERE MESSAGE_ID NOT IN ('SQL0443', 'CPC0904', 'CPF2407') "
        + "ORDER BY ORDINAL_POSITION DESC FETCH FIRST " + lines + " ROWS ONLY")) {
      stmt.setString(1, job);
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          String id = trim(rs.getString(2));
          newestFirst.add(String.valueOf(rs.getTimestamp(1)).replaceAll("\\.\\d+$", "") + " " + (id == null ? trim(rs.getString(3)) : id)
              + " " + trim(rs.getString(4)));
        }
      }
    }
    ArrayNode tail = mapper.createArrayNode();
    for (int i = newestFirst.size() - 1; i >= 0; i--) tail.add(newestFirst.get(i));
    return tail;
  }

  private void status(Connection c, String job, ObjectNode into) {
    String shortName = job.substring(job.lastIndexOf('/') + 1);
    try (PreparedStatement stmt = c.prepareStatement("SELECT JOB_STATUS, FUNCTION_TYPE, FUNCTION, CPU_TIME, SQL_STATEMENT_TEXT "
        + "FROM TABLE(QSYS2.ACTIVE_JOB_INFO(JOB_NAME_FILTER => ?, DETAILED_INFO => 'ALL')) WHERE JOB_NAME = ?")) {
      stmt.setString(1, shortName);
      stmt.setString(2, job);
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          into.put("ended", true);
          into.put("status", "not active: the job ended");
          return;
        }
        into.put("status", trim(rs.getString(1)));
        into.put("meaning", meaning(trim(rs.getString(1))));
        String type = trim(rs.getString(2));
        String function = trim(rs.getString(3));
        if (function != null) into.put("function", (type != null ? type + " " : "") + function);
        into.put("cpuMilliseconds", rs.getLong(4));
        String sql = trim(rs.getString(5));
        if (sql != null) into.put("sqlStatement", sql.length() > 300 ? sql.substring(0, 300) + "..." : sql);
      }
    } catch (SQLException e) {
      into.put("status", "unknown: " + e.getMessage());
    }
  }

  /* The common wait states, in words */
  static String meaning(String status) {
    if (status == null) return null;
    switch (status) {
      case "RUN":  return "running";
      case "MSGW": return "waiting for a reply to a message (see waitingFor)";
      case "LCKW": return "waiting for a lock (see locksWaitedFor)";
      case "DEQW": return "waiting on a data queue (a server job between requests waits here too)";
      case "TIMW": return "waiting for a time interval (a timer)";
      case "DLYW": return "delayed (DLYJOB)";
      case "TIMA": return "waiting for a time interval (asynchronous wait)";
      case "SELW": return "waiting for a socket or another select";
      case "SIGW": return "waiting for a signal";
      case "CNDW": return "waiting on a condition";
      case "DSPW": return "waiting for a display";
      case "EVTW": return "waiting for an event";
      default:     return null;
    }
  }

  private void stack(Connection c, String job, ObjectNode into) {
    ArrayNode stack = mapper.createArrayNode();
    try (PreparedStatement stmt = c.prepareStatement("SELECT ENTRY_TYPE, PROGRAM_LIBRARY_NAME, PROGRAM_NAME, MODULE_NAME, "
        + "PROCEDURE_NAME, STATEMENT_IDENTIFIERS FROM TABLE(QSYS2.STACK_INFO(?, 'ALL')) "
        + "WHERE PROGRAM_LIBRARY_NAME IS NULL OR PROGRAM_LIBRARY_NAME NOT IN ('QSYS', 'QSQL') "
        + "ORDER BY ORDINAL_POSITION DESC FETCH FIRST " + STACK_ENTRIES + " ROWS ONLY")) {
      stmt.setString(1, job);
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {  // newest call first
          String program = trim(rs.getString(3));
          if (program == null) continue;
          StringBuilder entry = new StringBuilder();
          if (trim(rs.getString(2)) != null) entry.append(trim(rs.getString(2))).append('/');
          entry.append(program);
          String module = trim(rs.getString(4));
          if (module != null && !module.equals(program)) entry.append(' ').append(module);
          String procedure = trim(rs.getString(5));
          if (procedure != null) entry.append(' ').append(procedure);
          String statement = trim(rs.getString(6));
          if (statement != null) entry.append(" stmt ").append(statement);
          stack.add(entry.toString());
        }
      }
      into.set("callStack", stack);
      into.put("callStackNote", "Newest call first; QSYS and QSQL programs left out");
    } catch (SQLException e) {
      into.put("callStack", "unavailable: " + e.getMessage());
    }
  }

  /* Locks the job waits for, and the jobs holding them */
  private void locks(Connection c, String job, ObjectNode into) {
    ArrayNode waits = mapper.createArrayNode();
    try (PreparedStatement stmt = c.prepareStatement("SELECT OBJECT_LIBRARY, OBJECT_NAME, OBJECT_TYPE, LOCK_STATE, LOCK_STATUS "
        + "FROM TABLE(QSYS2.JOB_LOCK_INFO(JOB_NAME => ?)) WHERE LOCK_STATUS <> 'HELD'")) {
      stmt.setString(1, job);
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          ObjectNode wait = waits.addObject();
          String lib = trim(rs.getString(1));
          String name = trim(rs.getString(2));
          String type = trim(rs.getString(3));
          wait.put("object", (lib != null ? lib + "/" : "") + name + " " + type);
          wait.put("wants", trim(rs.getString(4)));
          wait.put("lockStatus", trim(rs.getString(5)));
          if (lib != null && name != null) wait.set("heldBy", holders(c, lib, name, type, job));
        }
      }
      into.set("locksWaitedFor", waits);
    } catch (SQLException e) {
      into.put("locksWaitedFor", "unavailable: " + e.getMessage());
    }
  }

  private ArrayNode holders(Connection c, String lib, String name, String type, String job) {
    ArrayNode held = mapper.createArrayNode();
    try (PreparedStatement stmt = c.prepareStatement("SELECT JOB_NAME, LOCK_STATE FROM QSYS2.OBJECT_LOCK_INFO "
        + "WHERE SYSTEM_OBJECT_SCHEMA = ? AND SYSTEM_OBJECT_NAME = ? AND OBJECT_TYPE = ? AND LOCK_STATUS = 'HELD' "
        + "AND JOB_NAME <> ? FETCH FIRST 10 ROWS ONLY")) {
      stmt.setString(1, lib);
      stmt.setString(2, name);
      stmt.setString(3, type);
      stmt.setString(4, job);
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) held.add(trim(rs.getString(1)) + " (" + trim(rs.getString(2)) + ")");
      }
    } catch (SQLException e) {
      held.add("unavailable: " + e.getMessage());
    }
    return held;
  }

  /* The newest inquiry message in the joblog: in MSGW, the one the job waits on */
  private void waiting(Connection c, String job, ObjectNode into) {
    if (!"MSGW".equals(into.path("status").asText())) return;
    try (PreparedStatement stmt = c.prepareStatement("SELECT MESSAGE_ID, MESSAGE_TEXT, MESSAGE_SECOND_LEVEL_TEXT, FROM_PROGRAM "
        + "FROM TABLE(QSYS2.JOBLOG_INFO(?)) WHERE MESSAGE_TYPE = 'INQUIRY' ORDER BY ORDINAL_POSITION DESC FETCH FIRST 1 ROW ONLY")) {
      stmt.setString(1, job);
      stmt.setQueryTimeout(30);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) return;
        ObjectNode message = into.putObject("waitingFor");
        message.put("id", trim(rs.getString(1)));
        message.put("text", trim(rs.getString(2)));
        String help = trim(rs.getString(3));
        if (help != null) message.put("help", help.length() > 500 ? help.substring(0, 500) + "..." : help);
        message.put("from", trim(rs.getString(4)));
      }
    } catch (SQLException e) {
      into.put("waitingFor", "unavailable: " + e.getMessage());
    }
  }

  /*
   * Stops what the job runs, each step only while it still runs: the running statement's cancel (on a thread of
   * its own: JT400's cancel can block until the command ends), QSYS2.CANCEL_SQL, then ENDJOB *IMMED (the
   * connection is gone after that). Returns the steps.
   */
  String stop(String job, Statement running, java.util.function.BooleanSupplier stillRunning) {
    List<String> steps = new ArrayList<String>();
    if (running != null) {
      Thread cancel = new Thread(() -> {
        try {
          running.cancel();
        } catch (Exception e) {
          logger.info("Statement cancel: {}", e.getMessage());
        }
      }, "mc-cancel");
      cancel.setDaemon(true);
      cancel.start();
      steps.add("cancelled the running statement");
      if (waitWhile(stillRunning, CANCEL_GRACE_SECONDS / 2)) return done(steps);
    }
    try (PreparedStatement stmt = connection().prepareStatement("CALL QSYS2.CANCEL_SQL(?)")) {
      stmt.setString(1, job);
      stmt.execute();
      steps.add("QSYS2.CANCEL_SQL");
    } catch (Exception e) {
      steps.add("QSYS2.CANCEL_SQL failed: " + e.getMessage());
    }
    if (waitWhile(stillRunning, CANCEL_GRACE_SECONDS / 2)) return done(steps);
    try (Statement stmt = connection().createStatement()) {
      stmt.execute("CALL QSYS2.QCMDEXC('ENDJOB JOB(" + job + ") OPTION(*IMMED)')");
      steps.add("still running after " + CANCEL_GRACE_SECONDS + " s: ENDJOB " + job + " OPTION(*IMMED)");
    } catch (SQLException e) {
      steps.add("ENDJOB " + job + " failed: " + e.getMessage());
      return String.join("; ", steps);
    }
    waitWhile(stillRunning, CANCEL_GRACE_SECONDS);
    return String.join("; ", steps);
  }

  /* The steps say ENDJOB ended the job (its connection is gone) */
  static boolean jobEnded(String steps) {
    return steps != null && steps.contains(" s: ENDJOB ");
  }

  /* true when it stopped within the seconds */
  private static boolean waitWhile(java.util.function.BooleanSupplier stillRunning, int seconds) {
    for (int i = 0; i < seconds * 4 && stillRunning.getAsBoolean(); i++) {
      try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    }
    return !stillRunning.getAsBoolean();
  }

  private static String done(List<String> steps) {
    steps.add("it stopped");
    return String.join("; ", steps);
  }

  @Override
  public synchronized void close() {
    try {
      if (monitor != null) monitor.close();
    } catch (Exception ignored) { /* closing anyway */ }
    monitor = null;
  }

  private static String trim(String value) {
    if (value == null) return null;
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
