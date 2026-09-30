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

  public boolean success = true;
  public boolean dryRun;
  public int built;
  public int failed;
  public int skipped;
  public String error;  // failure outside a target (global hooks, connection, ...)
  public List<TargetResult> targets = new ArrayList<TargetResult>();

  public static class TargetResult {
    public String target;
    public String status;
    public String command;
    public String error;
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

  public TargetResult add(String target, String status) {
    TargetResult result = new TargetResult(target, status);
    targets.add(result);
    if (BUILT.equals(status) || PLANNED.equals(status)) built++;
    if (FAILED.equals(status)) { failed++; success = false; }
    if (SKIPPED.equals(status)) skipped++;
    return result;
  }

  public void writeToFile(String path) throws Exception {
    ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mapper.writeValue(new File(path), this);
  }
}
