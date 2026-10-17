package com.github.kraudy.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

  private ScreenTools() {}

  /* A tool result for one of TOOLS; target is the build connection's sign-on (null: not available here) */
  static synchronized ObjectNode handle(String tool, JsonNode args, SignOnGuard.Target target, File project) {
    watchFile = new File(project, ".mc/screen.txt");
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
  }

  private static ObjectNode open(JsonNode args, SignOnGuard.Target buildTarget) throws Exception {
    SignOnGuard.Target build = buildTarget;
    if (build == null || build.host.isEmpty()) {
      return result("screen_open runs on the PC (the mastercompiler server VS Code starts); it is not available here.", true);
    }
    if (build.password.isEmpty()) {
      return result("screen_open signs on with the IBM i password, and this server has none (IBMI_PASSWORD, VS Code's "
          + "password prompt; an SSH key alone is not enough for a 5250 sign-on).", true);
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
      preview.put("watch", watchFile.getPath() + ": every screen is written there; the user can open it in VS Code to follow along");
      preview.put("next", "Nothing opened yet. Show this to the user; if they agree, call screen_open again with confirm: true.");
      return result(mapper.writeValueAsString(preview), false);
    }
    if (!tls) logger.warn("5250 without TLS to {}: the password goes in clear text", build.host);

    SignOnGuard.Target target = new SignOnGuard.Target(build.name + " 5250", build.host, build.user, build.password);  // own status entry, same profile
    int wait = waitSeconds(args);
    Screen5250 screen = Screen5250.open(target.host, port, tls, ccsid, ScreenTools::writeWatch, wait);
    try {
      List<Screen5250.Field> input = screen.inputFields();
      if (input.size() < 2 || input.get(0).hidden() || !input.get(1).hidden()) {
        screen.close();
        return result("The first screen from " + target.host + " is not a sign-on display (user field, then a hidden "
            + "password field), so MasterCompiler did not type the password. It showed:\n" + text(screen), true);
      }
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
    if (codeRefused(target) != null) codeRefused(target, null);  // password and code accepted
    writeWatch();
    ObjectNode answer = view(screen);
    answer.put("signedOn", target.user + "@" + target.name);
    answer.put("note", "Signed on with the user profile's own library list, not the spec's: run CHGLIBL / CHGCURLIB on "
        + "a command line first when the program needs them. Sign off (SIGNOFF) before screen_close so the job ends.");
    return result(mapper.writeValueAsString(answer), false);
  }

  /* The screens' CCSID when screen_open names none: MC_5250_CCSID, else the system's (QCHRID's code page, what a
     device that the telnet server creates uses), else 37 */
  private static volatile java.util.function.Supplier<Integer> systemCcsid;

  static void systemCcsid(java.util.function.Supplier<Integer> supplier) {
    systemCcsid = supplier;
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
    return new File(watchFile.getParentFile(), "screen-code-refused.json");
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
    if (!screen.waitingForInput()) screen.waitForInput(waitSeconds(args));
    return result(mapper.writeValueAsString(view(screen)), false);
  }

  private static ObjectNode sendKeys(JsonNode args) throws Exception {
    Screen5250 screen = current();
    String key = args.path("key").asText("Enter");
    if (key.equalsIgnoreCase("Reset")) {
      screen.reset();
      return result(mapper.writeValueAsString(view(screen)), false);
    }
    if (!screen.waitingForInput() && !screen.waitForInput(5)) {
      return result("The IBM i is still working on the previous key (keyboard locked). Call screen to wait for it.", true);
    }
    screen.reset();
    List<Screen5250.Field> input = screen.inputFields();
    JsonNode values = args.path("fields");
    for (Iterator<Map.Entry<String, JsonNode>> it = values.fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> entry = it.next();
      Screen5250.Field f = field(screen, input, entry.getKey());
      if (f.hidden()) {
        throw new IllegalArgumentException("Field " + entry.getKey() + " is a hidden (password) field: never pass its "
            + "value. Put its number in 'ask' instead: MasterCompiler asks the user for it and types it.");
      }
      screen.fill(f, entry.getValue().asText());
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
    if (key.equalsIgnoreCase("Attn") || key.equalsIgnoreCase("Attention")) {
      screen.attention();
    } else {
      int aid = Screen5250.aid(key);
      if (aid < 0) {
        throw new IllegalArgumentException("Unknown key '" + key + "': Enter, F1-F24, PageUp, PageDown, Help, Print, "
            + "Clear, Attn or Reset");
      }
      screen.press(aid);
    }
    boolean ready = screen.waitForInput(waitSeconds(args));
    ObjectNode answer = view(screen);
    if (!ready && !screen.closed()) {
      answer.put("note", "The IBM i is still working (no input asked yet): call screen to wait for the next display.");
    }
    return result(mapper.writeValueAsString(answer), false);
  }

  private static ObjectNode closeSession() {
    if (session == null) return result("No 5250 session is open.", false);
    session.close();
    session = null;
    writeWatchText("(session closed " + LocalTime.now().withNano(0) + ")\n");
    return result("5250 session closed. If it was not signed off first, the job may stay as a disconnected job "
        + "(QDEVRCYACN) until the system ends it.", false);
  }

  private static Screen5250 current() {
    if (session == null) throw new IllegalStateException("No 5250 session: call screen_open first.");
    if (session.closed()) {
      String why = session.closedBy();
      session = null;
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

  /* The screen for the agent: numbered lines, cursor, input fields with a label, and whether input is expected */
  private static ObjectNode view(Screen5250 screen) {
    if (screen == session) writeWatchText(text(screen));
    ObjectNode v = mapper.createObjectNode();
    List<String> lines = screen.lines();
    int cols = screen.cols();
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
