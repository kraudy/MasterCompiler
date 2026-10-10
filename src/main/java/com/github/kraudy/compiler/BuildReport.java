package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/*
 * Machine-readable build result ({@code --json <file>}): one entry per target with
 * its status, the paste-ready command, joblog messages and EVFEVENT compile errors.
 * Meant for CI and agents; the console log stays for humans.
 */
public class BuildReport {

  public static final String BUILT = "built";
  public static final String FAILED = "failed";
  public static final String SKIPPED = "skipped";     // --diff: unchanged
  public static final String PLANNED = "planned";     // --dry-run
  public static final String NOT_BUILT = "not_built"; // never reached (an earlier target failed)
  public static final String BLOCKED = "blocked";     // --keep-going: depends on a failed target

  public boolean success = true;
  public boolean dryRun;
  public int built;
  public int failed;
  public int skipped;
  public int blocked;
  public String error;  // failure outside a target (global hooks, connection, ...)
  public List<String> warnings;  // from reading the sources, e.g. two files building the same object
  public List<String> summary;   // compact(): one line per target, before the details
  public List<TargetResult> targets = new ArrayList<TargetResult>();
  /* Objects the sources use that the project does not build ("CUSTSRV *SRVPGM" -> targets using it):
     find_source / import_source fetch their sources when an agent needs to read them */
  public java.util.Map<String, List<String>> external = new java.util.TreeMap<String, List<String>>();

  public static class TargetResult {
    public String target;
    public String status;
    public String command;
    public String error;
    public String warning;  // destructive step, e.g. an existing PF deleted and created again
    public List<CompileError> errors;
    public List<JoblogMessage> joblog;

    public TargetResult(String target, String status) {
      this.target = target;
      this.status = status;
    }
  }

  /* One EVFEVENT ERROR record, file path relative to the spec base directory when possible */
  public static class CompileError {
    public String file;
    public int line;
    public int column;
    public int endLine;
    public int endColumn;
    public String id;
    public int severity;
    public String message;
  }

  public static class JoblogMessage {
    public String time;
    public String id;
    public int severity;
    public String text;
  }

  /*
   * For agents (MCP): a summary line per target first; compile errors below minSeverity dropped and
   * duplicates removed; joblog deduplicated, its severity 30+ messages moved into errors (a binder or
   * authority failure has no EVFEVENT record), the rest kept only at minSeverity+ and only for targets
   * that did not build. Reports went from megabytes to what the agent needs.
   */
  public void compact(int minSeverity) {
    summary = new ArrayList<String>();
    for (TargetResult t : targets) {
      List<CompileError> errors = new ArrayList<CompileError>();
      java.util.Set<String> seen = new java.util.HashSet<String>();
      if (t.errors != null) {
        for (CompileError e : t.errors) {
          if (e.severity >= minSeverity && seen.add(e.file + ":" + e.line + ":" + e.id + ":" + e.message)) errors.add(e);
        }
      }
      List<JoblogMessage> joblog = new ArrayList<JoblogMessage>();
      java.util.Set<String> seenJoblog = new java.util.HashSet<String>();
      if (t.joblog != null) {
        for (JoblogMessage m : t.joblog) {
          if (!seenJoblog.add(m.id + ":" + m.text)) continue;
          if (m.severity >= 30) {
            if (seen.add("null:0:" + m.id + ":" + m.text)) {
              CompileError e = new CompileError();
              e.id = m.id;
              e.severity = m.severity;
              e.message = m.text;
              errors.add(e);
            }
          } else if (m.severity >= minSeverity && !BUILT.equals(t.status)) {
            joblog.add(m);
          }
        }
      }
      t.errors = errors.isEmpty() ? null : errors;
      t.joblog = joblog.isEmpty() ? null : joblog;
      summary.add(t.target + " " + t.status + (t.errors != null ? " (" + t.errors.size() + " errors)" : "")
          + (t.warning != null ? " WARNING" : ""));
    }
  }

  public TargetResult add(String target, String status) {
    TargetResult result = new TargetResult(target, status);
    targets.add(result);
    if (BUILT.equals(status) || PLANNED.equals(status)) built++;
    if (FAILED.equals(status)) { failed++; success = false; }
    if (SKIPPED.equals(status)) skipped++;
    if (BLOCKED.equals(status)) blocked++;
    return result;
  }

  public void writeToFile(String path) throws Exception {
    ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mapper.writeValue(new File(path), this);
  }
}
