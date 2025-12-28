package com.github.kraudy.compiler;

import java.sql.Timestamp;

/*
 * Custom exception for compiler errors
 */
public class CompilerException extends RuntimeException {
  private final String failedCommand;               // Failed command
  private final TargetKey targetKey;                // Compilation target
  private final CommandObject commandObject;        // Command object
  private final Timestamp commandTime;              // Fail time
  private final String extraContext;   // System messages

  /* Constructor for simplest use */
  public CompilerException(String message) {
    super(message, null);
    this.failedCommand = null;
    this.targetKey = null;
    this.commandObject = null;
    this.commandTime = null;
    this.extraContext = null;
  }

  /* Constructor for general use (no target/command) */
  public CompilerException(String message, Throwable cause) {
    super(message, cause);
    this.failedCommand = null;
    this.targetKey = null;
    this.commandObject = null;
    this.commandTime = null;
    this.extraContext = null;
  }

  /* Constructor for system command */
  public CompilerException(String message, Throwable cause, CommandObject commandObject) {
    super(message, cause);
    this.failedCommand = null;
    this.targetKey = null;
    this.commandObject = commandObject;
    this.commandTime = null;
    this.extraContext = null;
  }

  /* Constructor for target compilation command */
  public CompilerException(String message, Throwable cause, TargetKey targetKey) {
    super(message, cause);
    this.failedCommand = null;
    this.targetKey = targetKey;
    this.commandObject = null;
    this.commandTime = null;
    this.extraContext = null;
  }

  /* Constructor for command execution */
  public CompilerException(String message, Throwable cause, String failedCommand, Timestamp commandTime, String extraContext) {
    super(message, cause);
    this.failedCommand = failedCommand;
    this.targetKey = null;
    this.commandObject = null;
    this.commandTime = commandTime;
    this.extraContext = extraContext;
  }

  /* Getters */
  public String getFailedCommand() { return failedCommand; }
  public TargetKey getTargetKey() { return targetKey; }
  public Timestamp getCommandTime() { return commandTime; }
  public String getExtraContext() { return extraContext; }

  /* Get full context */
  public String getFullContext() {
    StringBuilder sb = new StringBuilder();

    sb.append("\nCompilerException Details:\n");

    sb.append("- Message: ").append(getMessage()).append("\n");

    if (failedCommand != null) sb.append("- Command: ").append(failedCommand).append("\n");

    if (targetKey != null) sb.append("- Target: ").append(targetKey.asString()).append("\n");

    if (commandObject != null) sb.append("- System Command: ").append(commandObject.asString()).append("\n");

    if (commandTime != null) sb.append("- Time: ").append(commandTime).append("\n");

    if (extraContext != null) sb.append("- Joblog Messages: ").append("\n").append(extraContext).append("\n");

    Throwable cause = getCause();
    /* No previous cause */
    if (cause == null) return sb.append("- Cause: null\n").toString();

    sb.append("- Cause: ");

    /* Previous general exception */
    if (!(cause instanceof CompilerException)) {
      sb.append(cause).append("\n");
      java.io.StringWriter sw = new java.io.StringWriter();
      java.io.PrintWriter pw = new java.io.PrintWriter(sw);
      cause.printStackTrace(pw);
      sb.append(sw.toString());
      return sb.append("\n").toString();
    }

    /* Previous  CompilerException with recursive context */
    CompilerException ce = (CompilerException) cause;
    return sb.append("\nChained ").append(ce.getFullContext()).toString();
  }

}