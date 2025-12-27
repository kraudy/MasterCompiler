package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandExecutor {
  private static final Logger logger = LoggerFactory.getLogger(CommandExecutor.class);

  private final Connection connection;
  private final boolean debug;
  private final boolean verbose;
  private final boolean dryRun;
  private final StringBuilder CmdExecutionChain = new StringBuilder();

  public CommandExecutor(Connection connection, boolean debug, boolean verbose, boolean dryRun){
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
    this.dryRun = dryRun;

  }
 
  public void executeCommand(List<String> commandList) throws SQLException{
    for(String command: commandList){
      executeCommand(command);
    }
  }

  /* Executes targets compilation commands */
  public void executeCommand(TargetKey key) throws Exception{
    Timestamp commandTime = getCurrentTime();
    String command = key.getCommandString();

    try {
      executeCommand(command);
    } catch (CompilerException e) {
      //if(verbose) showCompilationSpool(commandTime);
      if(verbose) logger.info(showCompilationSpool(commandTime));
      throw new CompilerException("Target compilation failed", e, key);
    } catch (Exception e) {
      throw new CompilerException("Unexpected exception in target compilation command", e, key);
    }

    /* Set build time */
    key.setLastBuild(commandTime);
  }

  /* Executes system commands */
  public void executeCommand(String command) throws CompilerException {
    Timestamp commandTime = getCurrentTime();

    if (this.CmdExecutionChain.length() > 0) {
      this.CmdExecutionChain.append(" => ");
    }
    this.CmdExecutionChain.append(command);

    /* Dry run just returns before executing the command */
    if(dryRun){
      return;
    }

    try (Statement cmdStmt = connection.createStatement()) {
      cmdStmt.execute("CALL QSYS2.QCMDEXC('" + command + "')");
    } catch (SQLException e) {
      logger.error("\nCommand failed: " + command);

      String joblog = buildJoblogMessagesString(commandTime);
      throw new CompilerException("Command execution failed", e, command, commandTime, joblog);  // No target here
    }

    logger.info("\nCommand successful: " + command);
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

  private String buildJoblogMessagesString(Timestamp commandTime) {
    StringBuilder messages = new StringBuilder();
    messages.append("\nJoblog info\n");

    try (Statement stmt = connection.createStatement();
         ResultSet rsMessages = stmt.executeQuery(
             "SELECT MESSAGE_TIMESTAMP, MESSAGE_ID, SEVERITY, MESSAGE_TEXT " +
             "FROM TABLE(QSYS2.JOBLOG_INFO('*')) " +
             "WHERE FROM_USER = USER " +
             "AND MESSAGE_TIMESTAMP > '" + commandTime + "' " +
             "AND MESSAGE_ID NOT IN ('SQL0443', 'CPC0904', 'CPF2407') " +
             "ORDER BY MESSAGE_TIMESTAMP ASC"
         )) {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        boolean hasMessages = false;

        while (rsMessages.next()) {
            hasMessages = true;
            Timestamp messageTime = rsMessages.getTimestamp("MESSAGE_TIMESTAMP");
            String messageId = rsMessages.getString("MESSAGE_ID").trim();
            String severity = rsMessages.getString("SEVERITY").trim();
            String message = rsMessages.getString("MESSAGE_TEXT").trim();

            String formattedTime = sdf.format(messageTime);
            messages.append(String.format("%-20s | %-10s | %-4s | %s%n",
                    formattedTime, messageId, severity, message));
        }

        if (!hasMessages) {
            messages.append("No relevant joblog messages found.\n");
        }

    } catch (SQLException e) {
      throw new CompilerException("Error retrieving joblog", e);
    }

    return messages.toString();
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
