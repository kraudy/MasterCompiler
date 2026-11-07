package com.github.kraudy.compiler;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/*
 * The end of a --remote build, from the JSON report MC wrote on the IBM i: the result, and for each failed target what
 * failed, with its command and its first messages (the largest severity first; informational messages are not errors).
 * Short enough for a CI log or an agent; the whole report is --json.
 */
final class RemoteSummary {
  private static final int ERRORS_PER_TARGET = 5;
  private static final int MAX_TARGETS = 10;
  private static final int MAX_LINE = 400;
  private static final int ERROR_SEVERITY = 20;

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
      String failedAt = target.path("failedAt").asText("");
      boolean hookFailure = "hook".equals(failedAt) || "tests".equals(failedAt);
      out.append("Failed : ").append(target.path("target").asText());
      if (target.hasNonNull("failedAt")) out.append(" (").append(failedAt).append(')');
      out.append('\n');
      /* A failed hook has its own command and joblog; the compile command and its EVFEVENT errors are not it */
      String command = hookFailure && target.hasNonNull("failedCommand") ? target.path("failedCommand").asText()
          : target.hasNonNull("command") ? target.path("command").asText() : null;
      if (command != null) out.append("  command: ").append(cut(command)).append('\n');
      List<JsonNode> messages = new ArrayList<JsonNode>();
      for (JsonNode message : target.path(hookFailure ? "hookJoblog" : "errors")) {
        if (message.path("severity").asInt(0) >= ERROR_SEVERITY) messages.add(message);
      }
      messages.sort(Comparator.comparingInt((JsonNode message) -> message.path("severity").asInt(0)).reversed());
      int count = messages.size();
      for (int i = 0; i < count && i < ERRORS_PER_TARGET; i++) {
        JsonNode message = messages.get(i);
        out.append("  ");
        if (!hookFailure && message.hasNonNull("file")) {
          out.append(message.path("file").asText()).append(':').append(message.path("line").asInt()).append(' ');
        }
        /* A hook's joblog entry carries text; a compile error carries message */
        String text = hookFailure ? message.path("text").asText() : message.path("message").asText();
        out.append(message.path("id").asText()).append(' ').append(cut(text)).append('\n');
      }
      if (count > ERRORS_PER_TARGET) {
        out.append("  ... ").append(count - ERRORS_PER_TARGET)
            .append(hookFailure ? " more hook messages\n" : " more compile errors\n");
      }
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
