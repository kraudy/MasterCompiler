package com.github.kraudy.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400SecurityException;

/*
 * Every sign-on with a password (SSH and the host servers, build and read-only connections) goes through here, so a
 * wrong password costs one failed sign-on and never disables the profile (QMAXSIGN):
 *  - one attempt per try (SshTarget sends the password once);
 *  - after IBM i rejects it, no more attempts in this process: every tool answers with the reason;
 *  - the rejection is kept in .mc/auth-blocked.json (host, user, time, code and a salted hash of the password, never
 *    the password), so a restart with the same cached VS Code input does not sign on either; another password gets
 *    one attempt, and a sign-on that works removes the entry;
 *  - a disabled profile or an expired password (CPF22E3 / CPF22E4) gets no attempt at all until the user removes the
 *    entry, after the administrator fixed it.
 * Host key mismatches, exit program rejections (PWS9801) and network errors are not password failures.
 */
final class SignOnGuard {
  private static final Logger logger = LoggerFactory.getLogger(SignOnGuard.class);
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  /* What the agent does next: ask the user, who types the password only in VS Code's prompt */
  private static String reset(Target target) {
    return "Do not retry and do not call other tools on " + target.name + ". Ask the user whether they want to type "
        + "the password for " + target.user + "@" + target.name + " again (never in the chat). If they do: "
        + "1. Command Palette (Ctrl+Shift+P) > \"MCP: Reset Cached Inputs\"; "
        + "2. Command Palette > \"MCP: List Servers\" > mastercompiler > \"Restart Server\": VS Code shows its "
        + "password prompts again; "
        + "3. type the current password there. If they do not know it, it is changed on the IBM i (CHGPWD, ACS, "
        + "or the security administrator).";
  }

  private static File file = new File(".mc/auth-blocked.json");
  private static final Map<String, String> failedHere = new LinkedHashMap<String, String>();  // host user: the message
  private static final Map<String, ObjectNode> states = new LinkedHashMap<String, ObjectNode>();  // connection name: status

  /* A connection that signs on with a password: who, where, and the password it would send */
  static final class Target {
    final String name;
    final String host;
    final String user;
    final String password;

    Target(String name, String host, String user, String password) {
      this.name = name != null && !name.isEmpty() ? name : host;
      this.host = host != null ? host : "";
      this.user = user != null ? user.toUpperCase(Locale.ROOT) : "";
      this.password = password != null ? password : "";
    }

    String key() {
      return host.toLowerCase(Locale.ROOT) + " " + user;
    }
  }

  /* The sign-on was refused before any attempt, or IBM i rejected it: the tool result says why */
  static final class Blocked extends IllegalStateException {
    Blocked(String message) {
      super(message);
    }
  }

  /* A sign-on IBM i refused that no exception type says, e.g. the 5250 sign-on display: its code and why */
  static final class Refused extends Exception {
    final String code;

    Refused(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  private SignOnGuard() {}

  /* Where the persisted rejections live: the project's .mc folder on the PC */
  static synchronized void project(File project) {
    file = new File(project, ".mc/auth-blocked.json");
  }

  /* A connection known from the spec, shown by status before its first use */
  static synchronized void known(Target target) {
    if (!states.containsKey(target.name)) state(target, "not connected", null);
  }

  private static final Map<String, Object> attempts = new java.util.concurrent.ConcurrentHashMap<String, Object>();

  /*
   * Sign on once with 'attempt' unless that is known to fail; an IBM i rejection blocks every later try. Attempts for
   * one host and user run one at a time (SSH start-up, a 5250 session, a read-only connection to the same profile), so
   * a second one checks again after the first was rejected instead of sending the same password with it.
   */
  static <T> T signOn(Target target, Callable<T> attempt) throws Exception {
    synchronized (attempts.computeIfAbsent(target.key(), k -> new Object())) {
      check(target);
      try {
        T connected = attempt.call();
        succeeded(target);
        return connected;
      } catch (Exception e) {
        String code = target.password.isEmpty() ? null : authCode(e);  // no password sent: a key failed, nothing to block
        if (code == null) {
          synchronized (SignOnGuard.class) {
            state(target, "error", e.getMessage());
          }
          throw e;
        }
        throw failed(target, code, e);
      }
    }
  }

  /* Before a try: refuse when this process saw a rejection, or the same password (or a disabled profile) is on file */
  static synchronized void check(Target target) {
    String here = failedHere.get(target.key());
    if (here != null) throw new Blocked(here);
    ObjectNode entry = (ObjectNode) read().get(target.key());
    if (entry == null) return;
    String message = null;
    if (entry.path("hard").asBoolean(false)) {
      message = hardMessage(target, entry.path("code").asText(), entry.path("time").asText());
    } else if (hash(target.password, entry.path("salt").asText()).equals(entry.path("hash").asText())) {
      message = "This is the same password " + target.name + " rejected on " + entry.path("time").asText() + " ("
          + entry.path("code").asText() + "): MasterCompiler did not sign on, so the profile was not disabled. "
          + reset(target);
    }
    if (message == null) {
      logger.info("{}: a different password than the one rejected on {}: one sign-on attempt", target.name,
          entry.path("time").asText());
      return;
    }
    failedHere.put(target.key(), message);
    state(target, "auth-failed", message);
    throw new Blocked(message);
  }

  /* After a tool failed: a rejected password blocks further tries; true when it was one */
  static boolean failed(Target target, Exception e) {
    String code = target.password.isEmpty() ? null : authCode(e);
    if (code == null) return false;
    failed(target, code, e);
    return true;
  }

  private static synchronized Blocked failed(Target target, String code, Exception e) {
    boolean hard = code.equals("CPF22E3") || code.equals("CPF22E4") || code.equals("CPF1394");
    String time = OffsetDateTime.now().withNano(0).toString();
    String message = hard ? hardMessage(target, code, time)
        : "IBM i rejected the password for " + target.user + "@" + target.name + " (" + code + "). MasterCompiler "
          + "stopped signing on to " + target.name + " so the profile is not disabled (QMAXSIGN). " + reset(target);
    logger.error("{} ({})", message, e.getMessage());
    failedHere.put(target.key(), message);
    state(target, "auth-failed", message);

    ObjectNode all = read();
    ObjectNode entry = all.putObject(target.key());
    entry.put("connection", target.name);
    entry.put("host", target.host);
    entry.put("user", target.user);
    entry.put("time", time);
    entry.put("code", code);
    entry.put("error", String.valueOf(e.getMessage()));
    if (hard) entry.put("hard", true);
    String salt = salt();
    entry.put("salt", salt);
    entry.put("hash", hash(target.password, salt));
    write(all);
    return new Blocked(message);
  }

  private static String hardMessage(Target target, String code, String time) {
    return "IBM i says the profile " + target.user + " on " + target.name + " is "
        + (code.equals("CPF22E4") ? "using an expired password" : "disabled") + " (" + code + ", " + time + "). "
        + "MasterCompiler will not sign on there, even with another password. Do not retry. Tell the user to ask "
        + "their security administrator to " + (code.equals("CPF22E4") ? "reset the password" : "enable the profile")
        + "; then delete " + file.getPath() + " in the project, Command Palette (Ctrl+Shift+P) > \"MCP: Reset "
        + "Cached Inputs\", \"MCP: List Servers\" > mastercompiler > \"Restart Server\", and type the current "
        + "password in VS Code's prompt (never in the chat).";
  }

  private static synchronized void succeeded(Target target) {
    failedHere.remove(target.key());
    state(target, "connected", null);
    ObjectNode all = read();
    if (all.remove(target.key()) != null) {
      write(all);
      logger.info("{}: signed on, the earlier password rejection is cleared", target.name);
    }
  }

  /* Status of every connection seen in this run: name, host, user, state, since, error */
  static synchronized ArrayNode status() {
    ArrayNode list = mapper.createArrayNode();
    for (ObjectNode s : states.values()) list.add(s.deepCopy());
    return list;
  }

  private static void state(Target target, String state, String error) {
    ObjectNode s = mapper.createObjectNode();
    s.put("name", target.name);
    s.put("host", target.host);
    s.put("user", target.user);
    s.put("state", state);
    s.put("since", OffsetDateTime.now().withNano(0).toString());
    if (error != null) s.put("error", error);
    states.put(target.name, s);
  }

  /* The IBM i code for a rejected sign-on in e or its causes, null when it is something else */
  static String authCode(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      String text = String.valueOf(t.getMessage());
      if (t instanceof Refused) return ((Refused) t).code;
      if (text.contains("PWS9801")) return null;  // an exit program refused the connection: not the password
      for (String code : new String[] { "CPF22E2", "CPF22E3", "CPF22E4", "CPF22E5", "CPF2204" }) {
        if (text.contains(code)) return code;
      }
      if (t instanceof AS400SecurityException) {
        switch (((AS400SecurityException) t).getReturnCode()) {
          case AS400SecurityException.PASSWORD_INCORRECT:               return "CPF22E2";
          case AS400SecurityException.PASSWORD_ERROR:                   return "CPF22E2";
          case AS400SecurityException.PASSWORD_INCORRECT_USERID_DISABLE: return "CPF22E3";
          case AS400SecurityException.USERID_DISABLE:                   return "CPF22E3";
          case AS400SecurityException.PASSWORD_EXPIRED:                 return "CPF22E4";
          case AS400SecurityException.PASSWORD_NOT_SET:                 return "CPF22E5";
          case AS400SecurityException.PASSWORD_NONE:                    return "CPF22E5";
          case AS400SecurityException.USERID_UNKNOWN:                   return "CPF2204";
          case AS400SecurityException.USERID_ERROR:                     return "CPF2204";
          default: break;
        }
      }
      if (t instanceof com.jcraft.jsch.JSchException) {
        if (text.startsWith("Auth fail")) return "SSH Auth fail";
        if (text.contains("Too many authentication failures")) return "SSH Too many authentication failures";
        /* code 2 is also a plain protocol error: only an authentication one counts */
        if (text.contains("SSH_MSG_DISCONNECT: 2 ") && text.toLowerCase(Locale.ROOT).contains("authentication")) {
          return "SSH_MSG_DISCONNECT 2";
        }
      }
      if (t instanceof SQLException && ("08004".equals(((SQLException) t).getSQLState())
          || "28000".equals(((SQLException) t).getSQLState()))) {
        return "SQLSTATE " + ((SQLException) t).getSQLState();
      }
    }
    return null;
  }

  private static ObjectNode read() {
    try {
      if (file.isFile()) {
        JsonNode all = mapper.readTree(file);
        if (all.isObject()) return (ObjectNode) all;
      }
    } catch (Exception e) {
      logger.warn("Could not read {}: {}", file, e.getMessage());
    }
    return mapper.createObjectNode();
  }

  private static void write(ObjectNode all) {
    try {
      if (all.size() == 0) {
        java.nio.file.Files.deleteIfExists(file.toPath());
        return;
      }
      file.getParentFile().mkdirs();
      java.nio.file.Files.write(file.toPath(), mapper.writeValueAsString(all).getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      logger.warn("Could not write {}: {}", file, e.getMessage());
    }
  }

  private static String salt() {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
  }

  /* PBKDF2 (HMAC-SHA256) of the password: tells the same password again without keeping it */
  static String hash(String password, String salt) {
    try {
      PBEKeySpec spec = new PBEKeySpec(("mc:" + password).toCharArray(), Base64.getDecoder().decode(salt), 100_000, 256);
      return Base64.getEncoder().encodeToString(
          SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException("Could not hash the password", e);
    }
  }
}
