package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ErrMsg;
import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;

public class CommandExecutor {
  private static final Logger logger = LoggerFactory.getLogger(CommandExecutor.class);

  private final Connection connection;
  private final boolean debug;
  private final boolean verbose;
  private final boolean dryRun;
  private final StringBuilder CmdExecutionChain = new StringBuilder();

  /* Live state for build_status / whats_running / cancel, and the watch that stops a hook past its timeout */
  private BuildProgress progress;
  private JobWatch watch;
  private int hookTimeout;  // seconds, for hooks without their own timeout; 0: none
  private volatile boolean timedOut;  // the last command ran out of time
  private volatile boolean stopping;  // outOfTime is looking at / stopping the job
  private static final java.util.concurrent.ScheduledExecutorService timer =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mc-hook-timeout");
        t.setDaemon(true);
        return t;
      });

  public CommandExecutor(Connection connection, boolean debug, boolean verbose, boolean dryRun){
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
    this.dryRun = dryRun;

  }
 
  public void watch(BuildProgress progress, JobWatch watch, Integer hookTimeout) {
    this.progress = progress;
    this.watch = watch;
    this.hookTimeout = hookTimeout != null ? hookTimeout : 0;
  }

  /* Hooks that ran out of time: what their job was doing, and how it was stopped */
  private final List<BuildReport.Timeout> timeouts = new ArrayList<BuildReport.Timeout>();

  public List<BuildReport.Timeout> getTimeouts() {
    return timeouts;
  }

  public void executeCommand(List<CommandObject> commandList) throws Exception, SQLException{
    for(CommandObject command: commandList){
      executeCommand(command);
    }
  }

  /* Executes system commands */
  public void executeCommand(CommandObject command) throws Exception{
    if (stopped()) {  // after a cancel or an ENDJOB nothing more runs
      String text = CommandStringParser.toPasteable(command.getCommandStringWithoutSummary());
      hooksRun.add((progress.jobEnded ? "not run (job ended): " : "not run (cancelled): ") + text);
      throw new CompilerException(progress.jobEnded ? "The build job was ended: not run: " + text
          : "Cancelled by the user (" + progress.cancelSteps + "): not run: " + text);
    }
    Timestamp commandTime = getCurrentTime();
    String commandString = "";
    if (debug) {
      /* Show each param's change history */
      commandString = command.getCommandString();
    } else {
      commandString = command.getCommandStringWithoutSummary();
    }


    /* RPGUnit: MC asks RUCALLTST for its XML, so the report gets each test's result */
    boolean testRun = !dryRun && RpgUnit.isTestRun(commandString);
    String addedXml = testRun ? RpgUnit.temporaryXml() : null;
    String withXml = testRun ? RpgUnit.withXml(commandString, addedXml) : null;
    if (withXml == null) addedXml = null;
    int timeout = command.getTimeoutSeconds() > 0 ? command.getTimeoutSeconds() : hookTimeout;

    try {
      String removeXml = addedXml;
      try {
        executeCommand(withXml != null ? withXml : commandString, commandTime, isLibraryListCommand(command), "hook", timeout);
      } catch (CompilerException e) {
        /* An RPGUnit without XMLSTMF refuses the keyword (CPD0043) before anything runs: the command as written */
        if (withXml == null || !keywordRefused(commandTime) || testsRan(commandTime)) throw e;
        addedXml = null;
        withXml = null;
        executeCommand(commandString, getCurrentTime(), false, "hook", timeout);
      } finally {
        if (testRun && !jobEnded()) testRuns.add(RpgUnit.result(connection, commandString, RpgUnit.xmlPath(commandString, addedXml),
            getJoblogMessages(commandTime)));
        if (removeXml != null && !jobEnded()) RpgUnit.remove(connection, removeXml);
      }
      hooksRun.add((dryRun && !isLibraryListCommand(command) ? "planned: " : "ok: ") + CommandStringParser.toPasteable(commandString)
          + (timeout > 0 ? " (timeout " + timeout + " s)" : ""));
    } catch (CompilerException e) {
      /* ignore: [CPF2105, ...] on the hook, like MONMSG */
      String text = e.getFullContext() + " " + (e.getCause() != null ? e.getCause().toString() : "");
      for (String id : timedOut || stopped() ? new ArrayList<String>() : command.getIgnore()) {
        if (text.contains(id)) {
          logger.info("Ignored {} from: {}", id, CommandStringParser.toPasteable(commandString));
          hooksRun.add("ignored " + id + ": " + CommandStringParser.toPasteable(commandString));
          return;
        }
      }
      hooksRun.add("failed: " + CommandStringParser.toPasteable(commandString));

      if(verbose) logger.error("No error messages found : " + commandString);
      throw new CompilerException("System command failed", e, command);

    } catch (Exception e) {
      hooksRun.add("failed: " + CommandStringParser.toPasteable(commandString));
      throw new CompilerException("Unexpected exception in target compilation command", e, command);
    }

  }

  /* Objects whose create command has no REPLACE: an existing one is deleted and created again */
  public static final List<ObjectType> RECREATED = Arrays.asList(
      ObjectType.PF, ObjectType.LF, ObjectType.DTAARA, ObjectType.DTAQ, ObjectType.MSGF);

  /* Executes targets compilation commands */
  public void executeCommand(TargetKey key) throws Exception{
    if (stopped()) throw new CompilerException(progress.jobEnded ? "The build job was ended: not compiled" : "Cancelled by the user: not compiled");
    if (key.objectExists()) {
      /* A binding directory is kept as it is (other entries stay); its ADDBNDDIRE hooks still run */
      if (key.getObjectTypeEnum() == ObjectType.BNDDIR && key.isRecreate()) {
        logger.warn("Deleting existing binding directory to create it again (recreate: true): " + key.asString());
        deleteObject(key);
      } else if (key.getObjectTypeEnum() == ObjectType.BNDDIR) {
        logger.info("Binding directory exists, kept: " + key.asString());
        key.setLastBuild(getCurrentTime());
        return;
      }
      /* SQL tables are never dropped: CREATE OR REPLACE keeps the rows, a plain CREATE fails instead */
      if (RECREATED.contains(key.getObjectTypeEnum())) {
        logger.warn("Deleting existing object to create it again (no REPLACE on its create command): " + key.asString());
        deleteObject(key);
      }
    }

    Timestamp commandTime = getCurrentTime();
    String commandString = "";
    if (debug) {
      /* Show each param's change history */
      commandString = key.getCommandString();
    } else {
      commandString = key.getCommandStringWithoutSummary();
    }


    try {
      executeCommand(commandString, commandTime, false, "compile", 0);
    } catch (CompilerException e) {
      if(verbose && !jobEnded()) logger.info(showCompilationSpool(commandTime));
      throw new CompilerException("Target compilation failed", e, key);
      
    } catch (Exception e) {
      throw new CompilerException("Unexpected exception in target compilation command", e, key);
    }

    /* Set build time */
    key.setLastBuild(commandTime);
    key.setObjectExists(true);
  }

  /* RUCALLTST hooks that ran, in order: their results go into the build report */
  private final List<BuildReport.TestRun> testRuns = new ArrayList<BuildReport.TestRun>();

  public List<BuildReport.TestRun> getTestRuns() {
    return testRuns;
  }

  /* CPD0043 (keyword not valid) naming XMLSTMF since then: the command was refused as a whole */
  private boolean keywordRefused(Timestamp since) {
    for (BuildReport.JoblogMessage m : getJoblogMessages(since)) {
      if ("CPD0043".equals(m.id) && m.text != null && m.text.toUpperCase().contains("XMLSTMF")) return true;
    }
    return false;
  }

  /* A test summary in the joblog since then: RUCALLTST did run, so it must not run again */
  private boolean testsRan(Timestamp since) {
    BuildReport.TestRun probe = new BuildReport.TestRun();
    for (BuildReport.JoblogMessage m : getJoblogMessages(since)) if (RpgUnit.summary(m.text, probe)) return true;
    return false;
  }

  /* Every hook command (and DLTOBJ) in order, with how it ended: ok, planned (dry run), ignored CPFxxxx, failed */
  private final List<String> hooksRun = new ArrayList<String>();

  public List<String> getHooksRun() {
    return hooksRun;
  }

  /* CHGLIBL / CHGCURLIB only change this job: a dry run runs them too, so it sees the spec's library list */
  static boolean isLibraryListCommand(CommandObject command) {
    return command.getSystemCommand() == SysCmd.CHGLIBL || command.getSystemCommand() == SysCmd.CHGCURLIB;
  }

  /* Executes system commands */
  public void executeCommand(String commandString, Timestamp commandTime) throws CompilerException {
    executeCommand(commandString, commandTime, false, "command", 0);
  }

  /* A cancel or ENDJOB happened: nothing more runs on this job */
  private boolean stopped() {
    return progress != null && (progress.cancelRequested || progress.jobEnded);
  }

  private boolean jobEnded() {
    return progress != null && progress.jobEnded;
  }

  private void executeCommand(String commandString, Timestamp commandTime, boolean evenInDryRun, String kind, int timeout)
      throws CompilerException {

    if (this.CmdExecutionChain.length() > 0) {
      this.CmdExecutionChain.append(" => ");
    }
    this.CmdExecutionChain.append(commandString);

    /* Dry run just returns before executing the command */
    if(dryRun && !evenInDryRun){
      return;
    }

    if (stopped()) {
      throw new CompilerException((progress.jobEnded ? "The build job was ended" : "The build was cancelled")
          + ": not run: " + CommandStringParser.toPasteable(commandString));
    }
    timedOut = false;
    java.util.concurrent.ScheduledFuture<?> limit = null;
    try (Statement cmdStmt = connection.createStatement()) {
      if (progress != null) progress.running(CommandStringParser.toPasteable(commandString), kind, cmdStmt, timeout);
      if (timeout > 0) limit = timer.schedule(() -> outOfTime(commandString, timeout, cmdStmt), timeout, java.util.concurrent.TimeUnit.SECONDS);
      try {
        cmdStmt.execute("CALL QSYS2.QCMDEXC('" + commandString + "')");
      } finally {
        synchronized (claim) {  // either it ended in time, or the timeout claimed it first
          if (limit != null) limit.cancel(false);
          if (progress != null) progress.ended(cmdStmt);
        }
      }
      if (timedOut) {  // the timeout fired just as it ended: keep it only if the job was stopped meanwhile
        waitWhileStopping();
        if (jobEnded()) throw new SQLException("The job was ended by the timeout as the command ended");
        synchronized (timeouts) {
          if (!timeouts.isEmpty()) timeouts.remove(timeouts.size() - 1);
        }
        timedOut = false;
      }
    } catch (SQLException e) {
      logger.error("Command failed: " + CommandStringParser.toPasteable(commandString));
      /* Stopped by its timeout or a cancel: wait until the record (and whether the job was ended) is complete */
      waitWhileStopping();

      String joblog;
      try {
        joblog = jobEnded() ? "" : buildJoblogMessagesString(commandTime);
      } catch (CompilerException gone) {  // the job is not answering any more
        joblog = "";
      }
      String why = timedOut ? "Timed out after " + timeout + " s (timeout: " + timeout + "); the job's call stack, locks "
          + "and messages are in the report's timeouts"
          : progress != null && progress.cancelRequested ? "Cancelled by the user (" + progress.cancelSteps + ")"
          : "Command execution failed";
      throw new CompilerException(why, e, commandString, commandTime, joblog);  // No target here
    }

    logger.info("Command successful: " + CommandStringParser.toPasteable(commandString));  // copy-pasteable form
    if(verbose) logger.info(buildJoblogMessagesString(commandTime));
  }

  private final Object claim = new Object();

  /* Up to 5 minutes: looking at the job (a few queries) and stopping it (cancel, CANCEL_SQL, ENDJOB) */
  private void waitWhileStopping() {
    for (int i = 0; i < 1200 && (stopping || (progress != null && progress.stopping)); i++) {
      try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
    }
  }

  /* The last command was stopped by its timeout */
  public boolean timedOut() {
    return timedOut;
  }

  /* The timeout ran out: look at the job, then stop the command (cancel, ENDJOB if that is not enough) */
  private void outOfTime(String commandString, int seconds, Statement running) {
    synchronized (claim) {
      if (progress == null || progress.statement != running) return;  // it ended meanwhile
      timedOut = true;
      stopping = true;
    }
    try {
      stopJob(commandString, seconds, running);
    } finally {
      stopping = false;
    }
  }

  private void stopJob(String commandString, int seconds, Statement running) {
    if (progress.jobName == null) progress.jobName = JobWatch.jobName(connection);  // not reachable now: known at the start
    logger.error("Timed out after {} s: {}", seconds, CommandStringParser.toPasteable(commandString));
    BuildReport.Timeout record = new BuildReport.Timeout();
    record.target = progress.target;
    record.command = CommandStringParser.toPasteable(commandString);
    record.seconds = seconds;
    record.job = watch != null ? watch.inspect(progress.jobName) : null;
    record.stopped = watch != null ? watch.stop(progress.jobName, running, () -> progress.statement == running)
        : "not stopped: no job watch";
    if (JobWatch.jobEnded(record.stopped)) progress.jobEnded = true;
    synchronized (timeouts) {
      timeouts.add(record);
    }
    progress.stopped.add(record.command + ": " + record.stopped);
  }

  /* Executes sql statements */
  public void executeStatement(String statement) throws CompilerException {
    Timestamp commandTime = getCurrentTime();

    if (this.CmdExecutionChain.length() > 0) {
      this.CmdExecutionChain.append(" => ");
    }
    this.CmdExecutionChain.append(statement);

    /* Dry run just returns before executing the command */
    if(dryRun){
      return;
    }

    try (Statement cmdStmt = connection.createStatement()) {
      cmdStmt.execute(statement);
    } catch (SQLException e) {
      logger.error("DB2 statement failed: " + statement);

      String joblog = buildJoblogMessagesString(commandTime);
      throw new CompilerException("DB2 statement execution failed", e, statement, commandTime, joblog);  // No target here
    }

    logger.info("DB2 statement successful: " + statement);
    if(verbose) logger.info(buildJoblogMessagesString(commandTime));
  }

  public Timestamp getCurrentTime(){
    Timestamp currentTime = null;
    try (Statement stmt = connection.createStatement();
        ResultSet rsTime = stmt.executeQuery("SELECT CURRENT_TIMESTAMP AS Command_Time FROM sysibm.sysdummy1")) {
      if (rsTime.next()) {
        currentTime = rsTime.getTimestamp("Command_Time");
      }
    } catch (SQLException e) {
      throw new CompilerException("Error retrieving command time", e);
    }
    return currentTime;
  }

  public void deleteObject(TargetKey key) throws Exception {

    if (key.getObjectTypeEnum() == ObjectType.FUNCTION) {
      executeStatement("DROP SPECIFIC FUNCTION " + key.getLibrary() + "." + key.getObjectName());
    }

    if (key.getObjectTypeEnum() == ObjectType.INDEX) {
      executeStatement("DROP INDEX   " + key.getLibrary() + "." + key.getObjectName());
    }

    if (key.getObjectTypeEnum() == ObjectType.TRIGGER) {
      executeStatement("DROP TRIGGER " + key.getLibrary() + "." + key.getObjectName());
    }

    /* This also finds procedures which are of type PGM, and tables of type FILE */
    CommandObject dlt = new CommandObject(SysCmd.DLTOBJ)
      .put(ParamCmd.OBJ, key.getQualifiedObject(ValCmd.CURLIB))
      .put(ParamCmd.OBJTYPE, ValCmd.fromString(key.getObjectType()));

    executeCommand(dlt);
  }

  private String buildJoblogMessagesString(Timestamp commandTime) {
    StringBuilder messages = new StringBuilder();
    messages.append("\nJoblog info\n");

    List<BuildReport.JoblogMessage> joblog = getJoblogMessages(commandTime);
    for (BuildReport.JoblogMessage msg : joblog) {
      messages.append(String.format("%-20s | %-10s | %-4s | %s%n",
              msg.time, msg.id, msg.severity, msg.text));
    }

    if (joblog.isEmpty()) {
      messages.append("No relevant joblog messages found.\n");
    }

    return messages.toString();
  }

  /* Joblog messages of this job since the given time */
  public List<BuildReport.JoblogMessage> getJoblogMessages(Timestamp since) {
    return getJoblogMessages(since, null);
  }

  /* Joblog messages of this job after 'since' and up to 'until' (null: up to now) */
  public List<BuildReport.JoblogMessage> getJoblogMessages(Timestamp since, Timestamp until) {
    List<BuildReport.JoblogMessage> joblog = new ArrayList<BuildReport.JoblogMessage>();

    try (Statement stmt = connection.createStatement();
         ResultSet rsMessages = stmt.executeQuery(
             "SELECT MESSAGE_TIMESTAMP, MESSAGE_ID, SEVERITY, MESSAGE_TEXT " +
             "FROM TABLE(QSYS2.JOBLOG_INFO('*')) " +
             "WHERE FROM_USER = USER " +
             "AND MESSAGE_TIMESTAMP > '" + since + "' " +
             (until != null ? "AND MESSAGE_TIMESTAMP <= '" + until + "' " : "") +
             "AND MESSAGE_ID NOT IN ('SQL0443', 'CPC0904', 'CPF2407') " +
             "ORDER BY MESSAGE_TIMESTAMP ASC"
         )) {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        while (rsMessages.next()) {
            BuildReport.JoblogMessage msg = new BuildReport.JoblogMessage();
            msg.time = sdf.format(rsMessages.getTimestamp("MESSAGE_TIMESTAMP"));
            msg.id = trim(rsMessages.getString("MESSAGE_ID"));
            msg.severity = rsMessages.getInt("SEVERITY");
            msg.text = trim(rsMessages.getString("MESSAGE_TEXT"));
            joblog.add(msg);
        }

    } catch (SQLException e) {
      if (jobEnded()) return joblog;  // the job is gone: its joblog with it
      throw new CompilerException("Error retrieving joblog", e);
    }

    return joblog;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  public String getExecutionChain() {
    return CmdExecutionChain.toString();
  }

  private String showCompilationSpool(Timestamp compilationTime) throws SQLException{
    StringBuilder spool = new StringBuilder();

    /* Is there a spool file? */
    try(Statement stmt = connection.createStatement();
      ResultSet rsCheckSpool = stmt.executeQuery(
      "Select SPOOLED_FILE_NAME, SPOOLED_FILE_NUMBER, QUALIFIED_JOB_NAME " +
      "From Table ( " +
          "QSYS2.SPOOLED_FILE_INFO( " +
              "USER_NAME => USER , " +
              "STARTING_TIMESTAMP => '" + compilationTime + "' " +
              //"JOB_NAME => (VALUES QSYS2.JOB_NAME) " +
          ") " +
        ") " +
        "LIMIT 1 "
      )){
        if (!rsCheckSpool.next()) {          
          return spool.append("No spool found for compilation command").toString();
        }
    }

    /* Get compilation spool */
    try(Statement stmt = connection.createStatement();
      ResultSet rsCompilationSpool = stmt.executeQuery(
        "Select d.SPOOLED_DATA " +
        "From Table ( " +
            "QSYS2.SPOOLED_FILE_INFO( " +
                "USER_NAME => USER, " +
                "STARTING_TIMESTAMP => '" + compilationTime + "' " +
                //"JOB_NAME => (VALUES QSYS2.JOB_NAME)" +
            ") " +
        ") As s " +
        "Inner Join Table ( " +
            "SYSTOOLS.SPOOLED_FILE_DATA( " +
                "JOB_NAME => s.QUALIFIED_JOB_NAME, " +
                "SPOOLED_FILE_NAME => s.SPOOLED_FILE_NAME, " +
                "SPOOLED_FILE_NUMBER => s.SPOOLED_FILE_NUMBER " +
            ") " +
        ") As d On 1=1" 
      )){
        while (rsCompilationSpool.next()) {
          spool.append(rsCompilationSpool.getString("SPOOLED_DATA").trim()).append("\n");
        }
      return spool.toString();
    }
  }
}
