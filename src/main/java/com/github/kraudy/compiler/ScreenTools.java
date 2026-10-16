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
    int ccsid = args.path("ccsid").asInt(Integer.parseInt(env("MC_5250_CCSID", "37")));
    String where = build.host + ":" + port + (tls ? " (TLS)" : " (no TLS: the password crosses the network in clear text)");
    if (!args.path("confirm").asBoolean(false)) {
      ObjectNode preview = mapper.createObjectNode();
      preview.put("wouldOpen", "an interactive 5250 session on " + where + ", signed on as " + build.user
          + ": a job where every key you send runs as that user");
      preview.put("signOn", "one attempt with the build connection's password; if IBM i rejects it, MasterCompiler "
          + "stops signing on for this user (also for builds) until the user types the password again");
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
      SignOnGuard.signOn(target, () -> {
        screen.fill(userField, target.user);
        fillHidden(screen, passwordField, target.password);
        screen.press(Screen5250.AID_ENTER);
        screen.waitForInput(wait);
        refusedSignOn(screen);
        return screen;
      });
    } catch (Exception e) {
      screen.close();
      throw e;
    }
    session = screen;
    writeWatch();
    ObjectNode answer = view(screen);
    answer.put("signedOn", target.user + "@" + target.name);
    answer.put("note", "Signed on with the user profile's own library list, not the spec's: run CHGLIBL / CHGCURLIB on "
        + "a command line first when the program needs them. Sign off (SIGNOFF) before screen_close so the job ends.");
    return result(mapper.writeValueAsString(answer), false);
  }

  /* The password goes only into the sign-on display's hidden field, typed by MasterCompiler itself */
  private static void fillHidden(Screen5250 screen, Screen5250.Field field, String password) {
    if (password.length() > field.length) {
      throw new IllegalArgumentException("The password is longer than the sign-on display's password field");
    }
    screen.fill(field, password);
  }

  /* Still on the sign-on display (the password field again): IBM i refused it */
  private static void refusedSignOn(Screen5250 screen) throws Exception {
    if (screen.closed()) {
      /* the IBM i itself closed it (it does after too many tries, or varies the device off): count it as refused */
      if (Screen5250.CLOSED_BY_IBMI.equals(screen.closedBy())) {
        throw new SignOnGuard.Refused("5250 sign-on refused", "5250 sign-on: the IBM i ended the session right after the password");
      }
      throw new java.io.IOException("The connection ended at sign-on: " + screen.closedBy());
    }
    /* a hidden field still there, wherever it is: the sign-on display again (or a changed one), not signed on */
    boolean signOnAgain = false;
    for (Screen5250.Field f : screen.inputFields()) {
      if (f.hidden()) signOnAgain = true;
    }
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
        throw new IllegalArgumentException("Field " + entry.getKey() + " is a hidden (password) field: MasterCompiler "
            + "does not type into those. The user types it in their own emulator.");
      }
      screen.fill(f, entry.getValue().asText());
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
