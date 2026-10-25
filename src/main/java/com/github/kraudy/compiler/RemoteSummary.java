package com.github.kraudy.compiler;

import com.fasterxml.jackson.databind.JsonNode;

/*
 * The end of a --remote build, from the JSON report MC wrote on the IBM i: the result, and for each failed target its
 * command and the first compile errors. Short enough for a CI log or an agent; the whole report is --json.
 */
final class RemoteSummary {
  private static final int ERRORS_PER_TARGET = 5;
  private static final int MAX_TARGETS = 10;
  private static final int MAX_LINE = 400;

  private RemoteSummary() {}

  static String format(JsonNode report, int exitCode, String host) {
    StringBuilder out = new StringBuilder("\n---- Build summary (").append(host).append(") ----\n");
    out.append(exitCode == 0 ? "Result : OK" : "Result : FAILED").append(" (exit ").append(exitCode).append("): built ")
        .append(report.path("built").asInt()).append(", failed ").append(report.path("failed").asInt()).append(", skipped ")
        .append(report.path("skipped").asInt()).append(", blocked ").append(report.path("blocked").asInt()).append('\n');
    if (!report.path("error").isMissingNode() && !report.path("error").isNull()) {
      out.append("Error  : ").append(cut(report.path("error").asText())).append('\n');
    }
    int shown = 0;
    int failed = 0;
    for (JsonNode target : report.path("targets")) {
      if (!"failed".equals(target.path("status").asText())) continue;
      failed++;
      if (shown++ >= MAX_TARGETS) continue;
      out.append("Failed : ").append(target.path("target").asText());
      if (target.hasNonNull("failedAt")) out.append(" (").append(target.path("failedAt").asText()).append(')');
      out.append('\n');
      if (target.hasNonNull("command")) out.append("  command: ").append(cut(target.path("command").asText())).append('\n');
      JsonNode errors = target.path("errors");
      int count = 0;
      for (JsonNode error : errors) {
        if (count++ >= ERRORS_PER_TARGET) continue;
        out.append("  ").append(error.path("file").asText()).append(':').append(error.path("line").asInt()).append(' ')
            .append(error.path("id").asText()).append(' ').append(cut(error.path("message").asText())).append('\n');
      }
      if (count > ERRORS_PER_TARGET) out.append("  ... ").append(count - ERRORS_PER_TARGET).append(" more compile errors\n");
      if (count == 0 && target.hasNonNull("error")) out.append("  ").append(cut(target.path("error").asText())).append('\n');
    }
    if (failed > MAX_TARGETS) out.append("... ").append(failed - MAX_TARGETS).append(" more failed targets\n");
    return out.toString().trim();
  }

  private static String cut(String text) {
    String line = text.replace('\n', ' ').trim();
    return line.length() > MAX_LINE ? line.substring(0, MAX_LINE) + "..." : line;
  }
}
