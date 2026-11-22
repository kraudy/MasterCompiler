package com.github.kraudy.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * screen_open / screen / screen_send / screen_close: one interactive 5250 session on the build system, run from
 * the PC (Screen5250, TLS port 992), so an agent can run a display file program, read the screens and press keys.
 * The sign-on goes through SignOnGuard like every other one: a rejected password blocks it and the build
 * connection alike (same profile). Each screen is also written to .mc/screen.txt, which the user can keep open in
 * VS Code to watch. Non-display fields (passwords) are never shown and never typed into.
 */
final class ScreenTools {
  private static final Logger logger = LoggerFactory.getLogger(ScreenTools.class);
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  static final List<String> TOOLS = java.util.Arrays.asList("screen_open", "screen", "screen_send", "screen_close");

  private static volatile Screen5250 session;
  private static volatile File watchFile;
  private static volatile File projectFolder;
  private static volatile ScreenRecording recording;  // record: true
  private static volatile String[] sessionMeta;       // host, port, tls, ccsid: a recording's meta line
  private static volatile String[] sessionJob;        // device, user: the interactive job's name and user
  private static volatile String sessionJobName;      // number/user/name, once looked up

  /* The display's name on the sign-on screen: "Display . . . :  QPADEV0012" (IBM's), "Display name. . . : ..." (PUB400) */
  static final Pattern DEVICE = Pattern.compile("Display(?: name)?\\s*(?:\\.\\s*)+:\\s*([A-Z][A-Z0-9#$@_]{0,9})");
  private static final int CLOSE_WAIT_SECONDS = 15;

  /* Rows of one SQL query (first column), on a connection that is open already: no sign-on; null when there is none */
  private static volatile Function<String, List<String>> query;

  static void jobQuery(Function<String, List<String>> runner) {
    query = runner;
  }

  static String device(List<String> lines) {
    for (int r = 0; r < Math.min(8, lines.size()); r++) {
      Matcher m = DEVICE.matcher(lines.get(r));
      if (m.find()) return m.group(1);
    }
    return null;
  }

  /* The job's number/user/name and status, as "MCJOB <job> <status>" rows (none when it ended), and an MCOK row that
     says the query ran (an error is not an ended job) */
  static String jobSql(String device, String user) {
    return "SELECT 'MCJOB ' CONCAT JOB_NAME CONCAT ' ' CONCAT COALESCE(JOB_STATUS, '') FROM TABLE(QSYS2.ACTIVE_JOB_INFO("
        + "JOB_NAME_FILTER => '" + device + "', DETAILED_INFO => 'NONE')) X WHERE JOB_NAME LIKE '%/" + user + "/" + device + "'"
        + " UNION ALL SELECT 'MCOK' FROM SYSIBM.SYSDUMMY1";
  }

  /* The session's job now: {job, status}, {ended: true}, or null when it cannot be asked */
  private static ObjectNode job(String[] deviceUser) {
    Function<String, List<String>> q = query;
    if (deviceUser == null || q == null) return null;
    List<String> rows;
    try {
      rows = q.apply(jobSql(deviceUser[0], deviceUser[1]));
    } catch (Exception e) {
      logger.info("Could not look up the 5250 job: {}", e.getMessage());
      return null;
    }
    if (rows == null || rows.stream().noneMatch(r -> r != null && r.trim().equals("MCOK"))) return null;
    ObjectNode job = mapper.createObjectNode();
    for (String row : rows) {
      int at = row == null ? -1 : row.indexOf("MCJOB ");
      if (at < 0) continue;
      String[] parts = row.substring(at + 6).trim().split("\\s+");
      job.put("job", parts[0]);
      job.put("status", parts.length > 1 ? parts[1] : "");
      return job;
    }
    return job.put("ended", true);
  }

  /* For the answers: the job (number/user/name once it was found, else user/device), and what is known of it */
  private static ObjectNode jobAnswer(String[] deviceUser, ObjectNode found) {
    ObjectNode job = found != null ? found : mapper.createObjectNode();
    if (deviceUser == null) return job.put("note", "the sign-on display named no display device: the job is not known");
    if (job.has("job")) sessionJobName = job.get("job").asText();
    job.put("job", sessionJobName != null ? sessionJobName : deviceUser[1] + "/" + deviceUser[0]);
    if (found == null) job.put("note", "state not looked up: no open connection to ask on (that would be a sign-on)");
    return job;
  }


  /* What a display attribute byte (0x20-0x3F) shows, as tn5250-rs draws it (IBM 5250 color display) */
  static final Map<Integer, String> DISPLAY = new HashMap<Integer, String>();
  static {
    String[] shown = { "green", "green reverse", "white", "white reverse", "green underline", "green underline reverse",
        "white underline", "nondisplay", "red", "red reverse", "red blink", "red blink reverse", "red underline",
        "red underline reverse", "red underline blink", "nondisplay", "turquoise column-separators",
        "turquoise column-separators reverse", "yellow column-separators", "yellow column-separators reverse",
        "turquoise underline", "turquoise underline reverse", "yellow underline", "nondisplay", "pink", "pink reverse",
        "blue", "blue reverse", "pink underline", "pink underline reverse", "blue underline", "nondisplay" };
    for (int i = 0; i < shown.length; i++) DISPLAY.put(0x20 + i, shown[i]);
  }

  /* The field format word's shift (FFW1 bits 5-7): what the field accepts */
  static final String[] SHIFT = { "alpha shift", "alpha only", "numeric shift", "numeric only", "katakana",
      "digits only", "I/O", "signed numeric" };

  private ScreenTools() {}

  /*
   * Programs and service programs a build rebuilt while the 5250 session was open (not under the tools' lock: a tool may be waiting
   * for a screen while the build ends). A job keeps the copy it activated, so the screens say so until a new session.
   */
  private static final Set<String> rebuiltSinceOpen = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /* Object types a job keeps activated: a session that ran one before its rebuild still runs the old copy */
  private static final Set<String> ACTIVATED = new java.util.HashSet<String>(java.util.Arrays.asList("PGM", "SRVPGM"));

  /*
   * A build's report targets ([{target, status}]): the programs and service programs it replaced while a 5250 session
   * is open (NAME *TYPE), also kept for that session's screens; empty with no session open
   */
  static List<String> built(JsonNode targets) {
    Screen5250 open = session;
    if (open == null || open.closed()) return new java.util.ArrayList<String>();
    List<String> names = replacedObjects(targets);
    rebuiltSinceOpen.addAll(names);
    return names;
  }

  /* The programs and service programs a build's report targets replaced, as NAME *TYPE */
  static List<String> replacedObjects(JsonNode targets) {
    List<String> names = new java.util.ArrayList<String>();
    for (JsonNode t : targets) {
      String[] parts = t.path("target").asText("").split("\\.");
      for (int i = 1; i < parts.length; i++) {
        String type = parts[i].toUpperCase(java.util.Locale.ROOT);
        if (ACTIVATED.contains(type) && replaced(t)) names.add(parts[i - 1].toUpperCase(java.util.Locale.ROOT) + " *" + type);
      }
    }
    return names;
  }

  /* The build report's line for names built() returned, null for none */
  static String sessionHint(List<String> names) {
    return names.isEmpty() ? null : String.join(", ", names) + REBUILT_NOTE;
  }

  /* Failures after the compile (a hook, its tests, a timeout) leave the new object in place */
  private static final Set<String> FAILED_AFTER_COMPILE = new java.util.HashSet<String>(java.util.Arrays.asList("hook", "tests", "timeout"));

  private static boolean replaced(JsonNode target) {
    String status = target.path("status").asText();
    return BuildReport.BUILT.equals(status)
        || (BuildReport.FAILED.equals(status) && FAILED_AFTER_COMPILE.contains(target.path("failedAt").asText()));
  }

  private static final String REBUILT_NOTE = " rebuilt while this 5250 session was open. A program that already ran in "
      + "this job keeps the copy it activated: in the default activation group (e.g. ACTGRP(*CALLER) called from the "
      + "command line) until sign-off, in a named one until that group ends (RCLACTGRP, once nothing in it runs). To "
      + "run the new code: screen_send SIGNOFF, screen_close, then screen_open.";

  /* A tool result for one of TOOLS; target is the build connection's sign-on (null: not available here); project takes
     the recordings, watchFolder the screen.txt a person follows (and its state files) */
  static synchronized ObjectNode handle(String tool, JsonNode args, SignOnGuard.Target target, File project, File watchFolder) {
    watchFile = new File(watchFolder, ".mc/screen.txt");
    projectFolder = project;
    try {
      switch (tool) {
        case "screen_open":  return open(args, target);
        case "screen":       return screen(args);
        case "screen_send":  return sendKeys(args);
        case "screen_close": return closeSession();
        default:             return result("Unknown tool: " + tool, true);
      }
    } catch (SignOnGuard.Blocked e) {
      return result(e.getMessage(), true);
    } catch (Exception e) {
      logger.error("5250 tool failed: " + tool, e);
      return result(e.getMessage() != null ? e.getMessage() : e.toString(), true);
    }
  }

  static synchronized void closeAll() {
    if (session != null) session.close();
    session = null;
    rebuiltSinceOpen.clear();
    stopRecording();
  }

  private static ObjectNode open(JsonNode args, SignOnGuard.Target buildTarget) throws Exception {
    SignOnGuard.Target build = buildTarget;
    if (build == null || build.host.isEmpty()) {
      return result("screen_open runs on the PC (the mastercompiler server your client starts); it is not available here.", true);
    }
    if (build.password.isEmpty()) {
      return result("screen_open signs on with the IBM i password, and this server has none (VS Code's password prompt, "
          + "or IBMI_PASSWORD_COMMAND / IBMI_PASSWORD_FILE in a terminal client's server entry; an SSH key alone is not "
          + "enough for a 5250 sign-on).", true);
    }
    if (session != null && !session.closed()) {
      return result("A 5250 session is already open: use screen / screen_send, or screen_close first.", true);
    }
    int port = args.path("port").asInt(Integer.parseInt(env("MC_5250_PORT", "992")));
    boolean tls = args.has("tls") ? args.get("tls").asBoolean() : port != 23;
    int ccsid = args.path("ccsid").asInt(defaultCcsid());
    String where = build.host + ":" + port + (tls ? " (TLS)" : " (no TLS: the password crosses the network in clear text)");
    if (!args.path("confirm").asBoolean(false)) {
      ObjectNode preview = mapper.createObjectNode();
      preview.put("wouldOpen", "an interactive 5250 session on " + where + ", signed on as " + build.user
          + ": a job where every key you send runs as that user");
      preview.put("signOn", "one attempt with the build connection's password; if IBM i rejects it, MasterCompiler "
          + "stops signing on for this user (also for builds) until the user types the password again. With a code field "
          + "(a PASSCODE) a refusal may be the code's: it does not stop the builds, and before another code sign-on "
          + "MasterCompiler checks the password alone over the host servers");
      String how = SecretPrompt.how();
      preview.put("otherHiddenFields", "a hidden field besides the password on the sign-on display (a PASSCODE, a token) "
          + (how != null ? "is asked of the user through " + how + ", before Enter; its value never reaches the chat"
          : "cannot be asked here (no desktop, no URL elicitation): the user signs on in their own emulator"));
      preview.put("ccsid", ccsid + (args.has("ccsid") ? "" : " (MC_5250_CCSID if set, else the system's QCHRID code page, "
          + "else 37 when it could not be read; give 'ccsid' if accents show wrong)"));
      preview.put("libraryList", args.path("libraryList").asBoolean(true)
          ? "after the sign-on, CHGLIBL with the build job's library list (the spec's) on the first command line; "
            + "libraryList: false keeps the user profile's own"
          : "the user profile's own (libraryList: false)");
      preview.put("watch", watchFile.getPath() + ": every screen is written there; the user can open it in VS Code to follow along");
      preview.put("next", "Nothing opened yet. Show this to the user; if they agree, call screen_open again with confirm: true.");
      return result(mapper.writeValueAsString(preview), false);
    }
    if (!tls) logger.warn("5250 without TLS to {}: the password goes in clear text", build.host);

    SignOnGuard.Target target = new SignOnGuard.Target(build.name + " 5250", build.host, build.user, build.password);  // own status entry, same profile
    int wait = waitSeconds(args);
    stopRecording();  // one of a session that ended without screen_close
    Screen5250 screen = Screen5250.open(target.host, port, tls, ccsid, ScreenTools::writeWatch, wait);
    sessionMeta = new String[] { target.host, String.valueOf(port), String.valueOf(tls), String.valueOf(ccsid) };
    try {
      List<Screen5250.Field> input = screen.inputFields();
      if (input.size() < 2 || input.get(0).hidden() || !input.get(1).hidden()) {
        screen.close();
        return result("The first screen from " + target.host + " is not a sign-on display (user field, then a hidden "
            + "password field), so MasterCompiler did not type the password. It showed:\n" + text(screen), true);
      }
      String device = device(screen.lines());  // its name is the job's name
      sessionJob = device != null ? new String[] { device, target.user.toUpperCase(Locale.ROOT) } : null;
      sessionJobName = null;
      Screen5250.Field userField = input.get(0);
      Screen5250.Field passwordField = input.get(1);
      /* Other hidden fields (a PASSCODE): asked of the user before Enter, so an empty one is not a refused sign-on.
         A refusal then may be the code's: it does not block the password for builds, but before another code
         sign-on MasterCompiler checks the password alone over the host servers (a wrong one counts there) */
      boolean codeField = false;
      for (int n = 2; n < input.size(); n++) codeField |= input.get(n).hidden();
      if (codeField) {
        SignOnGuard.check(target);  // a password already refused: no code asked, no try
        Long refused = codeRefused(target);
        if (refused != null) {
          SignOnBudget.take("a password check before a 5250 sign-on with a code");
          try {
            /* The password alone: right, and IBM i's count of failed sign-ons starts again; wrong, a refusal like any */
            SignOnGuard.signOn(target, () -> {
              com.ibm.as400.access.AS400 check = new com.ibm.as400.access.AS400(target.host, target.user, target.password.toCharArray());
              try {
                check.setGuiAvailable(false);
                check.validateSignon();
              } finally {
                check.disconnectAllServices();
              }
              return Boolean.TRUE;
            });
            /* the mark stays until a sign-on with a code works: every try until then checks the password first */
          } catch (SignOnGuard.Blocked | SignOnGuard.Refused e) {
            screen.close();
            throw e;
          } catch (Exception e) {
            if (SignOnGuard.authCode(e) != null) {
              screen.close();
              throw e;
            }
            screen.close();
            return result("A sign-on with a code was refused before (" + java.time.Instant.ofEpochMilli(refused) + "), and "
                + "MasterCompiler could not check the password alone over the host servers (" + e.getMessage() + "), so it "
                + "tries no other code sign-on (QMAXSIGN). The user signs on once in their own emulator, then deletes "
                + CODE_REFUSED + " and calls screen_open again.", true);
          }
        }
      }
      boolean askedOthers = false;
      for (int n = 2; n < input.size(); n++) {
        if (input.get(n).hidden()) {
          askUser(screen, input.get(n), n + 1, "Sign-on to " + target.host);
          askedOthers = true;
        }
      }
      final boolean withCode = askedOthers;
      if (withCode) codeRefused(target, System.currentTimeMillis());  // until it works: a dropped try leaves the mark
      SignOnBudget.take("the 5250 sign-on");
      SignOnGuard.signOn(target, () -> {
        screen.fill(userField, target.user);
        fillHidden(screen, passwordField, target.password);
        screen.press(Screen5250.AID_ENTER);
        screen.waitForInput(wait);
        refusedSignOn(screen, new SignOnDisplay(userField.start, passwordField.start), withCode, target);
        return screen;
      });
    } catch (Exception e) {
      screen.close();
      throw e;
    }
    session = screen;
    rebuiltSinceOpen.clear();  // a new job: it activates what is there now
    String recordingNote = record(screen, args);  // only now: the sign-on is never in a recording
    if (codeRefused(target) != null) codeRefused(target, null);  // password and code accepted
    /* The messages waiting at sign-on (Display Messages) are read once and left with F3, as a user does */
    List<String> messages = null;
    if (displayMessages(screen.lines())) {
      messages = new java.util.ArrayList<String>();
      for (String line : screen.lines()) if (!line.trim().isEmpty()) messages.add(line.trim());
      screen.press(Screen5250.aid("F3"));
      boolean left = screen.waitForInput(wait) && !displayMessages(screen.lines());
      if (!left) messages.add(0, "(F3 did not leave it: the screen below is where the session is)");
    }
    writeWatch();
    ObjectNode answer = view(screen);
    if (messages != null) {
      ObjectNode skipped = answer.putObject("skipped");
      skipped.put("screen", "Display Messages, shown after the sign-on: MasterCompiler pressed F3");
      ArrayNode shown = skipped.putArray("text");
      for (String line : messages) shown.add(line);
    }
    answer.put("signedOn", target.user + "@" + target.name);
    ObjectNode found = job(sessionJob);
    if (found != null && found.has("ended")) {  // not among the active jobs (yet): what is known
      found = (ObjectNode) jobAnswer(sessionJob, mapper.createObjectNode()).put("note", "not found among the active jobs");
    }
    answer.set("job", jobAnswer(sessionJob, found));
    if (args.path("libraryList").asBoolean(true)) {
      java.util.function.Supplier<String> spec = specLibraryList;
      answer.put("libraryList", applyLibraryList(screen, spec != null ? spec.get() : null, wait));
      answer.setAll(view(screen));  // the screen after it
    } else {
      answer.put("libraryList", "the user profile's own (libraryList: false)");
    }
    answer.put("note", "Sign off (SIGNOFF) before screen_close so the job ends.");
    if (recordingNote != null) answer.put("recording", recordingNote);
    return result(mapper.writeValueAsString(raw(screen, args, answer)), false);
  }

  /* The Display Messages screen (DSPMSG) a sign-on shows when messages wait: its title is in the first rows */
  static boolean displayMessages(List<String> lines) {
    for (int r = 0; r < Math.min(3, lines.size()); r++) {
      if (lines.get(r).trim().startsWith("Display Messages")) return true;  // "System: X" may follow on its row
    }
    return false;
  }

  /* The screens' CCSID when screen_open names none: MC_5250_CCSID, else the system's (QCHRID's code page, what a
     device that the telnet server creates uses), else 37 */
  private static volatile java.util.function.Supplier<Integer> systemCcsid;

  static void systemCcsid(java.util.function.Supplier<Integer> supplier) {
    systemCcsid = supplier;
  }

  /* The CHGLIBL that gives a session the build job's library list (the spec's), null when not known */
  private static volatile java.util.function.Supplier<String> specLibraryList;

  static void specLibraryList(java.util.function.Supplier<String> supplier) {
    specLibraryList = supplier;
  }

  /* The command line of a screen: the input field on a row that shows ===> before it (a menu, a command entry) */
  static Screen5250.Field commandLine(List<String> lines, List<Screen5250.Field> fields, int cols) {
    for (Screen5250.Field f : fields) {
      int row = f.start / cols;
      int col = f.start % cols;
      if (!f.bypass() && !f.hidden() && row < lines.size() && lines.get(row).substring(0, Math.min(col, lines.get(row).length())).contains("===>")) return f;
    }
    return null;
  }

  /* The Command Entry display (CALL QCMD): its command line takes 313 positions on a 24 x 80 display, a menu's 153 */
  static final String COMMAND_ENTRY = "CALL QCMD";
  static final int COMMAND_ENTRY_ROOM = 313;

  /* Markers on IBM i CL command-line screens vs QSH/STRSQL screens that also draw ===> */
  private static final List<String> CL_SCREEN_MARKERS = java.util.Arrays.asList(
      "Selection or command", "Parameters or command", "Type command, press Enter", "Command Entry");
  private static final List<String> NON_CL_SCREEN_MARKERS = java.util.Arrays.asList(
      "Enter SQL statements", "F4=End of File", "QSH Command Entry");

  static boolean isClCommandScreen(List<String> lines) {
    boolean hasMarker = false, hasPrompt = false;
    for (String line : lines) {
      for (String nonCl : NON_CL_SCREEN_MARKERS) if (line.contains(nonCl)) return false;
      for (String cl : CL_SCREEN_MARKERS) if (line.contains(cl)) hasMarker = true;
      if (line.contains("F4=Prompt")) hasPrompt = true;
    }
    return hasMarker || hasPrompt;
  }

  /*
   * One CL command on the screen's command line (===>), and the key pressed. Longer than that line, it goes on the
   * Command Entry display: CALL QCMD first (only from a CL command screen), where the session then stays. Returns
   * how it went there (null: typed where it was); nothing of the command is typed when it fits nowhere.
   */
  static String typeCommand(Screen5250 screen, String command, int aid, String key, int wait) throws IOException {
    List<String> lines = screen.lines();
    Screen5250.Field line = commandLine(lines, screen.inputFields(), screen.cols());
    if (line == null) throw new IllegalArgumentException("This screen has no command line (===>): nothing was typed");
    String how = null;
    if (command.length() > line.length) {
      if (command.length() > COMMAND_ENTRY_ROOM) {
        throw new IllegalArgumentException("The command is " + command.length() + " characters, longer than the Command "
            + "Entry display's command line (" + COMMAND_ENTRY_ROOM + "), the longest a 5250 session takes: nothing was "
            + "typed. run_command runs one CL command of up to 5000 characters in MasterCompiler's job (over SSH also "
            + "before the sources go up, open_project sync: false)");
      }
      if (!isClCommandScreen(lines)) {
        throw new IllegalArgumentException("This screen's '===>' field is " + line.length + " positions and does not "
            + "look like a CL command line (QSH/SQL/program screen): nothing was typed. Exit to a CL command line (or "
            + COMMAND_ENTRY + ") or use run_command");
      }
      screen.fill(line, COMMAND_ENTRY);
      screen.press(Screen5250.AID_ENTER, key == null ? null : "Enter");
      Screen5250.Field entry = screen.waitForInput(wait) ? commandLine(screen.lines(), screen.inputFields(), screen.cols()) : null;
      if (entry == null || entry.length < command.length()) {
        throw new IllegalStateException(COMMAND_ENTRY + " showed no command line of " + command.length() + " positions, so "
            + "the command was not typed. The screen now:\n" + text(screen));
      }
      how = "longer than this screen's command line (" + line.length + " positions): typed on the Command Entry display ("
          + COMMAND_ENTRY + ", " + entry.length + " positions), where the session stays; F3 goes back";
      line = entry;
    }
    screen.fill(line, command);
    screen.press(aid, key);
    return how;
  }

  /* After the sign-on: CHGLIBL on the first screen's command line (returning from QCMD if used). What happened, for the answer */
  static String applyLibraryList(Screen5250 screen, String chglibl, int wait) throws java.io.IOException {
    if (chglibl == null) return "not applied: the build job's library list is not known now (a build runs, or MC is not "
        + "connected); type CHGLIBL on a command line";
    String how;
    try {
      how = typeCommand(screen, chglibl, Screen5250.AID_ENTER, null, wait);
    } catch (IllegalArgumentException | IllegalStateException e) {
      if (screen.waitingForInput() && screen.lines().stream().anyMatch(l -> l.contains("Command Entry"))) {
        screen.press(Screen5250.aid("F3"));
        screen.waitForInput(wait);
      }
      return "not applied: " + e.getMessage() + "\n" + chglibl;
    }
    if (!screen.waitForInput(wait)) {
      return "outcome unknown: timed out waiting after " + chglibl + (how != null ? " (still on Command Entry)" : "");
    }
    String message;
    if (how != null) {
      message = commandEntryMessage(screen.lines());
      screen.press(Screen5250.aid("F3"));
      screen.waitForInput(wait);
    } else {
      message = lastLine(screen.lines());
    }
    return chglibl + (message.isEmpty() ? "" : " -> " + message);
  }

  private static String commandEntryMessage(List<String> lines) {
    String bottom = lastLine(lines);
    if (!bottom.isEmpty() && !bottom.startsWith("F3=") && !bottom.startsWith("F11=") && !bottom.startsWith("F24=")) {
      return bottom;
    }
    for (int r = lines.size() - 1; r >= 0; r--) {
      String t = lines.get(r).trim();
      if (!t.equals("Bottom") && !t.equals("More...")) continue;
      for (int p = r - 1; p >= 0; p--) {
        String msg = lines.get(p).trim();
        if (!msg.isEmpty() && !msg.startsWith(">")) return msg;
      }
    }
    return "";
  }

  private static String lastLine(List<String> lines) {
    for (int r = lines.size() - 1; r >= Math.max(0, lines.size() - 2); r--) {
      if (!lines.get(r).trim().isEmpty()) return lines.get(r).trim();
    }
    return "";
  }

  private static int defaultCcsid() {
    String env = env("MC_5250_CCSID", "").trim();
    if (env.matches("\\d{2,5}")) return Integer.parseInt(env);
    if (!env.isEmpty()) logger.warn("MC_5250_CCSID={} is not a CCSID: ignored", env);
    try {
      Integer system = systemCcsid != null ? systemCcsid.get() : null;
      if (system != null && system > 0) return system;
    } catch (Exception e) {
      logger.info("5250: the system's CCSID is not known ({}), 37 is used", e.getMessage());
    }
    return 37;
  }

  /* QCHRID's code page, the CCSID of the screens' text: SYSTEM_VALUE_INFO gives "00000006970000000284" (two 10-digit
     numbers: character set, code page); DSPSYSVAL shows "697 284" */
  static Integer codePage(String qchrid) {
    if (qchrid == null) return null;
    String value = qchrid.trim();
    String page = value.matches("\\d{20}") ? value.substring(10) : value.replaceAll("^.*\\s", "");
    try {
      int ccsid = Integer.parseInt(page);
      return ccsid > 0 && ccsid < 65535 ? ccsid : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /* Sign-ons with a code (a PASSCODE) refused, per host and user: kept in the project, so a restart does not retry */
  static final String CODE_REFUSED = ".mc/screen-code-refused.json";

  private static File codeRefusedFile() {
    return new File(projectFolder, CODE_REFUSED);
  }

  private static final Map<String, Long> codeRefusedHere = new java.util.HashMap<String, Long>();  // also when the file fails

  /* The mark, from this run or the file; a file that cannot be read counts as marked (the safe side) */
  private static Long codeRefused(SignOnGuard.Target target) {
    Long here = codeRefusedHere.get(target.key());
    if (here != null) return here;
    File file = codeRefusedFile();
    if (!file.isFile()) return null;
    try {
      JsonNode when = mapper.readTree(file).path(target.key());
      if (when.isMissingNode()) return null;
      return when.isNumber() ? when.asLong() : 0L;
    } catch (Exception unreadable) {
      return 0L;
    }
  }

  private static void codeRefused(SignOnGuard.Target target, Long when) {
    if (when == null) codeRefusedHere.remove(target.key());
    else codeRefusedHere.put(target.key(), when);
    try {
      File file = codeRefusedFile();
      ObjectNode all = file.isFile() && mapper.readTree(file).isObject() ? (ObjectNode) mapper.readTree(file) : mapper.createObjectNode();
      if (when == null) all.remove(target.key());
      else all.put(target.key(), when);
      file.getParentFile().mkdirs();
      mapper.writeValue(file, all);
    } catch (Exception e) {
      logger.warn("Could not write {}: {}", CODE_REFUSED, e.getMessage());
    }
  }

  /* Where the sign-on display had its user and password fields */
  private static final class SignOnDisplay {
    final int userStart;
    final int passwordStart;

    SignOnDisplay(int userStart, int passwordStart) {
      this.userStart = userStart;
      this.passwordStart = passwordStart;
    }
  }

  /* A hidden field's value from the user (SecretPrompt), typed here; never logged, returned or kept */
  private static void askUser(Screen5250 screen, Screen5250.Field f, int number, String title) throws Exception {
    String label = "";
    for (JsonNode o : view(screen).path("fields")) {
      if (o.path("field").asInt() == number) label = o.path("label").asText("");
    }
    String what = label.isEmpty() ? "the hidden field " + number : label;
    char[] value = SecretPrompt.ask(title, what, f.length);
    try {
      if (value == null) throw new IllegalArgumentException("The user did not give " + what + ": nothing was typed or sent");
      fillSecret(screen, f, new String(value), what);
    } finally {
      SecretPrompt.clear(value);
    }
  }

  /* The password goes only into the sign-on display's hidden field, typed by MasterCompiler itself */
  private static void fillHidden(Screen5250 screen, Screen5250.Field field, String password) {
    if (password.length() > field.length) {
      throw new IllegalArgumentException("The password is longer than the sign-on display's password field");
    }
    fillSecret(screen, field, password, "the password");
  }

  /* fill's own errors quote the text: for a secret, only what was wrong */
  private static void fillSecret(Screen5250 screen, Screen5250.Field field, String secret, String what) {
    try {
      screen.fill(field, secret);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(what + " does not fit its field (" + field.length + " positions"
          + (field.numeric() ? ", digits only" : "") + "): nothing was sent");
    }
  }

  /* Still on the sign-on display (the password field again): IBM i refused it */
  private static void refusedSignOn(Screen5250 screen, SignOnDisplay signOn, boolean withCode, SignOnGuard.Target target)
      throws Exception {
    if (screen.closed()) {
      /* the IBM i itself closed it (it does after too many tries, or varies the device off): count it as refused */
      if (Screen5250.CLOSED_BY_IBMI.equals(screen.closedBy())) {
        throw new SignOnGuard.Refused("5250 sign-on refused", "5250 sign-on: the IBM i ended the session right after the password");
      }
      throw new java.io.IOException("The connection ended at sign-on: " + screen.closedBy());
    }
    /*
     * The sign-on display again: a hidden field where the password was, or the user field still in its place with a
     * hidden field anywhere (a sign-on display drawn a little differently). Another display with a hidden field (an
     * application's own) means signed on.
     */
    boolean hiddenAtPassword = false;
    boolean hiddenAnywhere = false;
    boolean userFieldThere = false;
    for (Screen5250.Field f : screen.inputFields()) {
      if (f.hidden()) hiddenAnywhere = true;
      if (f.hidden() && f.start == signOn.passwordStart) hiddenAtPassword = true;
      if (!f.hidden() && f.start == signOn.userStart) userFieldThere = true;
    }
    boolean signOnAgain = hiddenAtPassword || (userFieldThere && hiddenAnywhere);
    String lines = String.join("\n", screen.lines());
    String lower = lines.toLowerCase(Locale.ROOT);
    if (lower.contains("password has expired") || lower.contains("contraseña ha caducado")) {
      throw new SignOnGuard.Refused("CPF22E4", "5250 sign-on: the password has expired");
    }
    if (!signOnAgain) return;
    String message = screen.lines().get(screen.rows() - 1).trim();
    String code = lower.contains("disabled") || lower.contains("cannot sign on") ? "CPF1394"
        : lower.contains("not correct") ? "CPF1107"
        : lower.contains("does not exist") ? "CPF1120"
        : "5250 sign-on refused";
    /* With a code the user typed (a PASSCODE) the refusal may be the code's: the password is not blocked for the
       builds, and no other code sign-on is tried until the password alone signed on (see open). The bottom line
       saying the profile is disabled still counts */
    String bottom = message.toLowerCase(Locale.ROOT);
    if (withCode && !bottom.contains("disabled") && !bottom.contains("cannot sign on")) {
      codeRefused(target, System.currentTimeMillis());
      throw new java.io.IOException("The sign-on with the password and the code the user typed was refused"
          + (message.isEmpty() ? "" : ": " + message) + ". It may be the code: the password was not marked as refused, "
          + "and before another try MasterCompiler checks the password alone. Check the code with the user.");
    }
    throw new SignOnGuard.Refused(code, "5250 sign-on refused" + (message.isEmpty() ? "" : ": " + message));
  }

  private static ObjectNode screen(JsonNode args) throws Exception {
    Screen5250 screen = current();
    String recordingNote = record(screen, args);
    if (!screen.waitingForInput()) screen.waitForInput(waitSeconds(args));
    ObjectNode answer = view(screen);
    if (recordingNote != null) answer.put("recording", recordingNote);
    return result(mapper.writeValueAsString(raw(screen, args, answer)), false);
  }

  private static ObjectNode sendKeys(JsonNode args) throws Exception {
    Screen5250 screen = current();
    String recordingNote = record(screen, args);
    String key = args.path("key").asText("Enter");
    boolean reset = key.equalsIgnoreCase("Reset");
    if (reset && !args.has("command")) {
      screen.reset();
      return result(mapper.writeValueAsString(view(screen)), false);
    }
    if (!screen.waitingForInput() && !screen.waitForInput(5)) {
      return result("The IBM i is still working on the previous key (keyboard locked). Call screen to wait for it.", true);
    }
    screen.reset();
    boolean attention = key.equalsIgnoreCase("Attn") || key.equalsIgnoreCase("Attention");
    int aid = attention || reset ? 0 : Screen5250.aid(key);
    if (aid < 0) {
      throw new IllegalArgumentException("Unknown key '" + key + "': Enter, F1-F24, PageUp, PageDown, Help, Print, "
          + "Clear, Attn or Reset");
    }
    String commandEntry = null;
    if (args.has("command")) {
      if (attention || reset) throw new IllegalArgumentException("A command goes with a key that sends it (Enter, F4, ...), not " + key);
      for (Iterator<String> names = args.fieldNames(); names.hasNext();) {
        String name = names.next();
        if (!WITH_COMMAND.contains(name)) {
          throw new IllegalArgumentException("'command' goes without '" + name + "' (it may open the Command Entry "
              + "display first): send that in another screen_send");
        }
      }
      String cmd = args.get("command").isTextual() ? args.get("command").asText().trim() : "";
      if (cmd.isEmpty()) throw new IllegalArgumentException("Give the CL 'command', e.g. CALL PGM(PGM1) or QSH CMD('...')");
      if (cmd.contains("\n") || cmd.contains("\r")) {
        throw new IllegalArgumentException("'command' is one CL command on one line: nothing was typed");
      }
      commandEntry = typeCommand(screen, cmd, aid, key, waitSeconds(args));
    } else {
      typeFields(screen, args);
      if (attention) screen.attention();
      else screen.press(aid, key);
    }
    boolean ready = screen.waitForInput(waitSeconds(args));
    ObjectNode answer = view(screen);
    if (commandEntry != null) answer.put("commandEntry", commandEntry);
    if (!ready && !screen.closed()) {
      answer.put("note", "The IBM i is still working (no input asked yet): call screen to wait for the next display.");
    }
    if (recordingNote != null) answer.put("recording", recordingNote);
    return result(mapper.writeValueAsString(raw(screen, args, answer)), false);
  }

  /* screen_send's arguments that go with 'command': it may change the screen first, so none of this screen's fields */
  static final Set<String> WITH_COMMAND = new java.util.HashSet<String>(java.util.Arrays.asList(
      "command", "key", "waitSeconds", "raw", "record"));

  /* screen_send's fields, ask and cursor, on the screen as it is */
  private static void typeFields(Screen5250 screen, JsonNode args) throws Exception {
    List<Screen5250.Field> input = screen.inputFields();
    JsonNode values = args.path("fields");
    for (Iterator<Map.Entry<String, JsonNode>> it = values.fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> entry = it.next();
      Screen5250.Field f = field(screen, input, entry.getKey());
      if (f.hidden()) {
        throw new IllegalArgumentException("Field " + entry.getKey() + " is a hidden (password) field: never pass its "
            + "value. Put its number in 'ask' instead: MasterCompiler asks the user for it and types it.");
      }
      String text = entry.getValue().asText();
      if (text.length() > f.length && f == commandLine(screen.lines(), input, screen.cols())) {
        throw new IllegalArgumentException("'" + text + "' is longer than the command line (" + f.length + " positions): "
            + "nothing was sent. A CL command goes in 'command' instead: one of up to " + COMMAND_ENTRY_ROOM
            + " characters is typed on the Command Entry display (" + COMMAND_ENTRY + ")");
      }
      screen.fill(f, text);
    }
    /* ask: hidden fields whose value MasterCompiler asks of the user (never seen by the agent) */
    for (JsonNode which : args.path("ask")) {
      String k = which.asText();
      Screen5250.Field f = field(screen, input, k);
      if (!f.hidden()) {
        throw new IllegalArgumentException("Field " + k + " is not hidden: put its value in 'fields'. 'ask' is only for "
            + "hidden fields (a PASSCODE, a password)");
      }
      askUser(screen, f, input.indexOf(f) + 1, "IBM i 5250 screen");
    }
    if (args.has("cursor")) {
      screen.moveCursor(args.path("cursor").path("row").asInt(), args.path("cursor").path("col").asInt());
    }
  }

  private static ObjectNode closeSession() throws Exception {
    if (session == null) return result("No 5250 session is open.", false);
    session.close();
    session = null;
    rebuiltSinceOpen.clear();
    stopRecording();
    writeWatchText("(session closed " + LocalTime.now().withNano(0) + ")\n");
    ObjectNode answer = mapper.createObjectNode();
    answer.put("closed", true);
    /* Whether the job ended: asked until it is gone, for a while */
    ObjectNode job = job(sessionJob);
    for (int i = 0; i < CLOSE_WAIT_SECONDS && job != null && !job.has("ended"); i++) {
      Thread.sleep(1000);
      job = job(sessionJob);
    }
    answer.set("job", jobAnswer(sessionJob, job));
    if (job != null && !job.has("ended")) {
      answer.put("note", "The job is still there after " + CLOSE_WAIT_SECONDS + " s (status " + job.path("status").asText()
          + "): not signed off first, it may stay as a disconnected job (QDEVRCYACN) until the system ends it; ENDJOB "
          + "JOB(" + job.path("job").asText() + ") ends it.");
    } else if (job == null) {
      answer.put("note", "If it was not signed off first, the job may stay as a disconnected job (QDEVRCYACN) until the "
          + "system ends it.");
    }
    return result(mapper.writeValueAsString(answer), false);
  }

  private static Screen5250 current() {
    if (session == null) throw new IllegalStateException("No 5250 session: call screen_open first.");
    if (session.closed()) {
      String why = session.closedBy();
      session = null;
      stopRecording();  // a new session starts its own
      throw new IllegalStateException("The 5250 session ended (" + why + "). Call screen_open for a new one.");
    }
    return session;
  }

  /* A field by its number in the 'fields' list, or "row,col" of a position inside it */
  private static Screen5250.Field field(Screen5250 screen, List<Screen5250.Field> input, String key) {
    String k = key.trim();
    if (k.matches("\\d+")) {
      int n = Integer.parseInt(k);
      if (n < 1 || n > input.size()) throw new IllegalArgumentException("No input field " + n + " (this screen has " + input.size() + ")");
      return input.get(n - 1);
    }
    if (k.matches("\\d+\\s*,\\s*\\d+")) {
      String[] rc = k.split(",");
      int pos = (Integer.parseInt(rc[0].trim()) - 1) * screen.cols() + Integer.parseInt(rc[1].trim()) - 1;
      for (Screen5250.Field f : input) {
        if (pos >= f.start && pos < f.start + f.length) return f;
      }
      throw new IllegalArgumentException("No input field at row,col " + k);
    }
    throw new IllegalArgumentException("Field keys are the field's number from 'fields' (\"3\") or \"row,col\" inside it");
  }

  /* record: true starts a recording (one at a time), false stops it; null when the call says nothing */
  private static String record(Screen5250 screen, JsonNode args) throws IOException {
    if (!args.has("record")) return recording != null ? "recording to " + recording.file : null;
    if (!args.path("record").asBoolean()) {
      File stopped = recording != null ? recording.file : null;
      stopRecording();
      return stopped != null ? "stopped; recorded in " + stopped : "no recording was running";
    }
    if (recording == null) {
      String[] m = sessionMeta;
      recording = ScreenRecording.start(projectFolder, m[0], Integer.parseInt(m[1]), Boolean.parseBoolean(m[2]),
          Integer.parseInt(m[3]), screen.rows() == 27);
      screen.record(recording);
    }
    return "recording to " + recording.file + " (every record in exactly as the IBM i sent it, every key out with hidden "
        + "values as ***)";
  }

  private static void stopRecording() {
    ScreenRecording r = recording;
    recording = null;
    Screen5250 screen = session;
    if (screen != null) screen.record(null);
    if (r != null) r.close();
  }

  /* raw: true: the 5250 data behind the screen */
  private static ObjectNode raw(Screen5250 screen, JsonNode args, ObjectNode answer) {
    if (!args.path("raw").asBoolean(false)) return answer;
    Screen5250.Raw raw = screen.raw();
    ArrayNode writes = answer.putArray("hostWrites");
    for (byte[] r : raw.hostWrites) writes.add(SqlTools.hex(r).toLowerCase(Locale.ROOT));
    if (raw.dropped > 0) {
      answer.put("hostWritesDropped", raw.dropped + " older records (more than " + Screen5250.MAX_HOST_WRITES
          + " records or " + Screen5250.MAX_HOST_BYTES + " bytes since the key): record: true keeps all");
    }
    ArrayNode all = answer.putArray("allFields");
    List<String> lines = raw.lines;
    int cols = raw.cols;
    int previousEnd = 0;
    for (Screen5250.Field f : raw.fields) {
      ObjectNode o = all.addObject();
      o.put("id", all.size() - 1);
      o.put("row", f.start / cols + 1);
      o.put("col", f.start % cols + 1);
      o.put("len", f.length);
      o.put("input", f.input);
      o.put("hidden", f.hidden());
      o.put("attr", String.format("0x%02x", f.attr));
      o.put("display", DISPLAY.getOrDefault(f.attr, "not a display attribute"));
      if (f.input) {
        o.put("ffw", String.format("0x%02x%02x", f.ffw1, f.ffw2));
        o.put("shift", SHIFT[f.shift()]);
        o.put("numeric", f.numeric());
        o.put("upper", (f.ffw2 & 0x20) != 0);
        o.put("modified", f.mdt);
        o.put("bypass", f.bypass());
      }
      o.put("protected", !f.input || f.bypass());
      String label = label(lines, f, cols, previousEnd);
      if (!label.isEmpty()) o.put("label", label);
      /* from the screen text, which blanks hidden cells (also behind an attribute inside the field) */
      if (f.hidden()) o.putNull("value");
      else o.put("value", rtrim(textOf(lines, f, cols)));
      previousEnd = f.start + f.length;
    }
    return answer;
  }

  private static String textOf(List<String> lines, Screen5250.Field f, int cols) {
    StringBuilder s = new StringBuilder();
    for (int p = f.start; p < f.start + f.length && p / cols < lines.size(); p++) s.append(lines.get(p / cols).charAt(p % cols));
    return s.toString();
  }

  /* The screen for the agent: numbered lines, cursor, input fields with a label, and whether input is expected */
  private static ObjectNode view(Screen5250 screen) {
    if (screen == session) writeWatchText(text(screen));
    ObjectNode v = mapper.createObjectNode();
    List<String> lines = screen.lines();
    int cols = screen.cols();
    if (!rebuiltSinceOpen.isEmpty()) v.put("rebuiltObjects", String.join(", ", rebuiltSinceOpen) + REBUILT_NOTE);
    v.put("state", screen.closed() ? "closed: " + screen.closedBy()
        : screen.inputError() ? "input error (the bottom line says why; the next screen_send resets it)"
        : screen.waitingForInput() ? "waiting for input" : "busy");
    ArrayNode text = v.putArray("screen");
    for (int r = 0; r < lines.size(); r++) text.add(String.format("%02d %s", r + 1, rtrim(lines.get(r))));
    ObjectNode cursor = v.putObject("cursor");
    cursor.put("row", screen.cursor() / cols + 1);
    cursor.put("col", screen.cursor() % cols + 1);
    ArrayNode fields = v.putArray("fields");
    List<Screen5250.Field> input = screen.inputFields();
    int previousEnd = 0;
    for (int n = 0; n < input.size(); n++) {
      Screen5250.Field f = input.get(n);
      ObjectNode o = fields.addObject();
      o.put("field", n + 1);
      o.put("row", f.start / cols + 1);
      o.put("col", f.start % cols + 1);
      o.put("length", f.length);
      String label = label(lines, f, cols, previousEnd);
      if (!label.isEmpty()) o.put("label", label);
      if (f.hidden()) o.put("hidden", true);
      else o.put("value", rtrim(screen.value(f)));
      if (f.numeric()) o.put("numeric", true);
      previousEnd = f.start + f.length;
    }
    return v;
  }

  /* Text before the field on its row, after the previous field: its prompt, without the dot leaders */
  private static String label(List<String> lines, Screen5250.Field f, int cols, int previousEnd) {
    int row = f.attrPos / cols;
    int from = Math.max(row * cols, previousEnd);
    if (from >= f.attrPos) return "";
    String line = lines.get(row);
    String s = line.substring(from - row * cols, f.attrPos - row * cols);
    return s.replaceAll("[ .:]+$", "").replaceAll("^.*\\s{3,}", "").trim();
  }

  /* .mc/screen.txt: the latest screen, for the user to watch in VS Code */
  private static void writeWatch() {
    Screen5250 screen = session;  // not under the tools' lock: a tool may be waiting for this very screen
    if (screen == null) return;
    writeWatchText(text(screen));
  }

  private static String text(Screen5250 screen) {
    StringBuilder s = new StringBuilder();
    List<String> lines = screen.lines();
    int cols = screen.cols();
    s.append("MasterCompiler 5250 · ").append(LocalTime.now().withNano(0))
        .append(screen.waitingForInput() ? " · waiting for input" : " · busy")
        .append(" · cursor ").append(screen.cursor() / cols + 1).append(',').append(screen.cursor() % cols + 1).append('\n');
    s.append("   +").append(repeat('-', cols)).append("+\n");
    for (int r = 0; r < lines.size(); r++) s.append(String.format("%02d |%s|%n", r + 1, lines.get(r)));
    s.append("   +").append(repeat('-', cols)).append("+\n");
    return s.toString();
  }

  private static final Object watchLock = new Object();  // not the tools' lock: a tool may be waiting for a screen

  private static void writeWatchText(String text) {
    synchronized (watchLock) {
      writeWatchFile(text);
    }
  }

  private static void writeWatchFile(String text) {
    try {
      if (watchFile == null) return;
      watchFile.getParentFile().mkdirs();
      /* whole or not at all: an editor showing the file never reads it half written */
      File next = new File(watchFile.getParentFile(), "screen.txt.tmp");
      java.nio.file.Files.write(next.toPath(), text.getBytes(StandardCharsets.UTF_8));
      java.nio.file.Files.move(next.toPath(), watchFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      logger.warn("Could not write {}: {}", watchFile, e.getMessage());
    }
  }

  private static int waitSeconds(JsonNode args) {
    return Math.max(1, Math.min(args.path("waitSeconds").asInt(30), 300));
  }

  private static String repeat(char c, int n) {
    StringBuilder s = new StringBuilder(n);
    for (int i = 0; i < n; i++) s.append(c);
    return s.toString();
  }

  private static String rtrim(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == ' ') end--;
    return s.substring(0, end);
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v != null && !v.isEmpty() ? v : fallback;
  }

  private static ObjectNode result(String text, boolean isError) {
    ObjectNode result = mapper.createObjectNode();
    result.putArray("content").addObject().put("type", "text").put("text", text);
    result.put("isError", isError);
    return result;
  }
}
