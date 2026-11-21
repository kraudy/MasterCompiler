package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
 * Why a run_command failed, in one line: the escape message, the diagnostics before it (one per message id, with how
 * many more of that id) and, for the commands whose errors are only in the listing they spool (the SQL precompilers),
 * that listing's messages. The listing is the spooled file named after the object the command creates, with the
 * command as its user data, spooled since the command started (into the user's QPRTJOB, not MC's job).
 */
final class CommandFailure {
  /* Commands whose errors are in their listing -> the parameter that names the object (and so the spooled file) */
  static final Map<String, String> LISTED_BY = new LinkedHashMap<String, String>();
  static {
    LISTED_BY.put("CRTSQLCI", "OBJ");
    LISTED_BY.put("CRTSQLCPPI", "OBJ");
    LISTED_BY.put("CRTSQLRPGI", "OBJ");
    LISTED_BY.put("CRTSQLCBLI", "OBJ");
  }
  static final int DIAGNOSTIC = 30;   // severity from which a message says why
  static final int MAX_LISTING = 50;  // listing messages kept

  private CommandFailure() {}

  /* The spooled listing's name for this command (its object), or null when its errors are in the joblog */
  static String listingName(String command) {
    String param = LISTED_BY.get(ReadOnlyCommands.name(command));
    if (param == null) return null;
    String trimmed = command.trim();
    Map<String, String> params = ReadOnlyCommands.keywords(trimmed.substring(trimmed.split("[\\s(]", 2)[0].length()));
    String value = params == null ? null : params.get(param);
    if (value == null) return null;
    String[] parts = value.trim().split("/");
    String name = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
    return name.matches("[A-Z$#@][A-Z0-9$#@_]{0,9}") ? name : null;
  }

  /* The line: escape, diagnostics, listing; fallback (the failed call's own text) when the joblog has none of them */
  static String summary(List<BuildReport.JoblogMessage> joblog, List<BuildReport.CompileError> listing, String fallback) {
    BuildReport.JoblogMessage escape = null;
    for (BuildReport.JoblogMessage m : joblog) if (m.severity >= DIAGNOSTIC && (escape == null || m.severity >= escape.severity)) escape = m;
    Map<String, List<BuildReport.JoblogMessage>> byId = new LinkedHashMap<String, List<BuildReport.JoblogMessage>>();
    for (BuildReport.JoblogMessage m : joblog) {
      if (m.severity < DIAGNOSTIC || m == escape) continue;
      byId.computeIfAbsent(m.id, id -> new ArrayList<BuildReport.JoblogMessage>()).add(m);
    }
    List<String> diagnostics = new ArrayList<String>();
    for (List<BuildReport.JoblogMessage> same : byId.values()) {
      BuildReport.JoblogMessage first = same.get(0);
      diagnostics.add(first.id + " " + first.text + (same.size() > 1 ? " (and " + (same.size() - 1) + " more " + first.id + ")" : ""));
    }
    List<String> listed = new ArrayList<String>();
    for (BuildReport.CompileError e : listing) listed.add(e.id + " record " + e.line + ": " + e.message);
    StringBuilder line = new StringBuilder(escape != null ? escape.id + " " + escape.text : fallback != null ? fallback : "Failed");
    if (!diagnostics.isEmpty()) line.append(" Diagnostics: ").append(String.join("; ", diagnostics));
    if (!listed.isEmpty()) line.append(" Listing: ").append(String.join("; ", listed));
    return line.toString();
  }
}
