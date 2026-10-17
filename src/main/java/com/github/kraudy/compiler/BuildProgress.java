package com.github.kraudy.compiler;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * Where a build is now, written by the build thread and read by build_status / whats_running / cancel from
 * other threads: the stage, the target, the command running and since when, the statement to cancel.
 */
final class BuildProgress {
  static final String RUNNING = "running";
  static final String SUCCEEDED = "succeeded";
  static final String FAILED = "failed";
  static final String CANCELLED = "cancelled";

  private static final ObjectMapper mapper = new ObjectMapper();

  final String id;
  final long started = System.currentTimeMillis();
  volatile String state = RUNNING;
  volatile long finished;
  volatile String stage = "starting";
  volatile String target;
  volatile String command;      // the command running now (pasteable)
  volatile String commandKind;  // hook, compile, sql
  volatile long commandStarted;
  volatile int timeoutSeconds;  // of the command running now; 0: none
  volatile Statement statement; // the one running it, for a cancel
  volatile String jobName;      // the build job: 123456/QUSER/QZDASOINIT
  volatile int targetsDone;
  volatile int targetsTotal;
  volatile boolean cancelRequested;
  volatile String cancelSteps;
  volatile boolean jobEnded;    // ENDJOB ran: the build connection is gone
  volatile boolean stopping;    // cancel / a timeout is stopping the job now
  volatile BuildReport report;
  volatile com.fasterxml.jackson.databind.node.ObjectNode result;  // MCP: the tool result build returns
  volatile String error;
  final List<String> stopped = Collections.synchronizedList(new ArrayList<String>());  // timeouts: what ran out of time

  BuildProgress(String id) {
    this.id = id;
  }

  void stage(String stage) {
    this.stage = stage;
  }

  void running(String command, String kind, Statement statement, int timeoutSeconds) {
    this.command = command;
    this.commandKind = kind;
    this.statement = statement;
    this.timeoutSeconds = timeoutSeconds;
    this.commandStarted = System.currentTimeMillis();
  }

  void ended(Statement statement) {
    if (this.statement == statement) {
      this.statement = null;
      this.command = null;
      this.commandKind = null;
      this.timeoutSeconds = 0;
    }
  }

  boolean isRunning() {
    return RUNNING.equals(state);
  }

  /* A command on the build connection right now */
  boolean commandRunning() {
    return statement != null;
  }

  void finish(String state, BuildReport report, String error) {
    this.report = report;
    this.error = error;
    this.finished = System.currentTimeMillis();
    this.state = state;
  }

  /* What it does now, for the agent to tell the user */
  ObjectNode toJson() {
    ObjectNode node = mapper.createObjectNode();
    node.put("buildId", id);
    node.put("state", state);
    node.put("elapsedSeconds", ((finished > 0 ? finished : System.currentTimeMillis()) - started) / 1000);
    if (!isRunning()) return node;
    node.put("stage", stage);
    if (targetsTotal > 0) node.put("targets", targetsDone + " of " + targetsTotal + " done");
    if (target != null) node.put("target", target);
    String now = command;
    if (now != null) {
      ObjectNode running = node.putObject("running");
      running.put("kind", commandKind);
      running.put("command", now.length() > 400 ? now.substring(0, 400) + "..." : now);
      running.put("forSeconds", (System.currentTimeMillis() - commandStarted) / 1000);
      if (timeoutSeconds > 0) running.put("timeoutSeconds", timeoutSeconds);
    }
    if (jobName != null) node.put("job", jobName);
    if (cancelRequested) node.put("cancel", cancelSteps != null ? cancelSteps : "requested");
    return node;
  }
}
