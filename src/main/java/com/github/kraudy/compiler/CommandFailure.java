package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
 * Why a run_command failed, in one line: the escape message, the diagnostics before it (one per message id, with how
 * many more of that id) and, for the commands whose errors are only in the listing they spool (the SQL precompilers,
 * RUNSQLSTM: SQL9010 alone says nothing), that listing's messages. The listing is spooled since the command started
 * into the user's QPRTJOB, not MC's job (Listing: how it is named).
 */
final class CommandFailure {
  /* How a command's listing is found: the parameter whose object names it (the SQL precompilers: OBJ), or named after
     the source (RUNSQLSTM: SRCSTMF / SRCMBR as the system shortens it; QSYSPRT on some systems, so another name is
     taken when it is the only listing of that user data then); and its user data */
  static final class Listing {
    final String param;
    final boolean bySource;
    final String userData;

    Listing(String param, boolean bySource, String userData) {
      this.param = param;
      this.bySource = bySource;
      this.userData = userData;
    }
  }

  static final Map<String, Listing> LISTED_BY = new LinkedHashMap<String, Listing>();
  static {
    for (String precompiler : new String[] { "CRTSQLCI", "CRTSQLCPPI", "CRTSQLRPGI", "CRTSQLCBLI" }) {
      LISTED_BY.put(precompiler, new Listing("OBJ", false, precompiler));
    }
    LISTED_BY.put("RUNSQLSTM", new Listing(null, true, "SQL"));
  }
  static final int DIAGNOSTIC = 30;   // severity from which a message says why
  static final int MAX_LISTING = 50;  // listing messages kept

  private CommandFailure() {}

  /* The spooled listing's name for this command (its object, or its source), or null when its errors are in the joblog */
  static String listingName(String command) {
    Listing listing = LISTED_BY.get(ReadOnlyCommands.name(command));
    if (listing == null) return null;
    String trimmed = command.trim();
    Map<String, String> params = ReadOnlyCommands.keywords(trimmed.substring(trimmed.split("[\\s(]", 2)[0].length()));
    if (params == null) return null;
    if (listing.bySource) {
      String source = params.containsKey("SRCSTMF") ? params.get("SRCSTMF") : params.get("SRCMBR");
      return source == null ? null : spoolName(source.replace("'", ""));
    }
    String value = params.get(listing.param);
    if (value == null) return null;
    String[] parts = value.trim().split("/");
    String name = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
    return name.matches("[A-Z$#@][A-Z0-9$#@_]{0,9}") ? name : null;
  }

  /* A source's listing name as the system makes it: the file name up to its first '.', upper case, what a name does
     not take as '_', 10 characters (mc-deploy_tables-48.sql: MC_DEPLOY_) */
  static String spoolName(String source) {
    String file = source.trim().replaceAll("^.*/", "");
    String name = file.split("\\.", 2)[0].toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9$#@_]", "_");
    return name.isEmpty() ? null : name.substring(0, Math.min(10, name.length()));
  }

  /* The listing's messages of a command that failed between since and until (null: now), at most MAX_LISTING */
  static List<BuildReport.CompileError> listing(Connection connection, String command, Timestamp since, Timestamp until) {
    Listing how = LISTED_BY.get(ReadOnlyCommands.name(command));
    String name = how == null ? null : listingName(command);
    if (name == null) return new ArrayList<BuildReport.CompileError>();
    List<BuildReport.CompileError> listed = EventFile.readListing(connection, name, how.userData, since, until, how.bySource, null);
    return listed.subList(0, Math.min(listed.size(), MAX_LISTING));
  }

  /* A failed hook's description with its listing's messages, when its command spools them (RUNSQLSTM) */
  static String withListing(Connection connection, String described, Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (!(t instanceof CompilerException) || ((CompilerException) t).getFailedCommand() == null) continue;
      CompilerException failed = (CompilerException) t;
      List<String> listed = new ArrayList<String>();
      for (BuildReport.CompileError e : listing(connection, CommandStringParser.toPasteable(failed.getFailedCommand()),
          failed.getCommandTime(), null)) {
        listed.add(e.id + " record " + e.line + ": " + e.message);
      }
      return listed.isEmpty() ? described : described + " | Listing: " + String.join("; ", listed);
    }
    return described;
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
