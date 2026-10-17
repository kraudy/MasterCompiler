package com.github.kraudy.compiler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/*
 * Asks the user for a secret a 5250 screen needs (a PASSCODE, a token, a password), so it never goes through the
 * chat or the agent: MC types it and never logs or returns it.
 *   1. MCP URL elicitation, when the client offers it: a one-time page on 127.0.0.1 with a password field, which
 *      the client opens for the user (the MCP spec keeps secrets out of form elicitation).
 *   2. Else a password dialog of MC's own on this desktop.
 * Neither available (no desktop, no URL elicitation): refused, with why.
 */
final class SecretPrompt {
  private static final ObjectMapper mapper = new ObjectMapper();
  static final int WAIT_SECONDS = 300;

  private static volatile JsonNode capabilities = mapper.createObjectNode();
  private static volatile Consumer<String> sender;
  private static final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
  private static final AtomicInteger ids = new AtomicInteger();
  private static final SecureRandom random = new SecureRandom();

  private SecretPrompt() {}

  /* initialize: what the client can do */
  static void client(JsonNode params) {
    JsonNode caps = params.path("capabilities");
    capabilities = caps.isObject() ? caps : mapper.createObjectNode();
  }

  /* How this server writes a JSON-RPC message to the client */
  static void sender(Consumer<String> send) {
    sender = send;
  }

  /* A response from the client to one of these requests: true when it was one (then it is not passed on) */
  static boolean answer(JsonNode message) {
    if (message.has("method") || !message.has("id")) return false;
    CompletableFuture<JsonNode> waiting = pending.remove(message.path("id").asText());
    if (waiting == null) return false;
    waiting.complete(message);
    return true;
  }

  static boolean urlElicitation() {
    return capabilities.path("elicitation").has("url") && sender != null;
  }

  static boolean desktop() {
    return !java.awt.GraphicsEnvironment.isHeadless();
  }

  /* What can ask here, for a preview or an error */
  static String how() {
    if (urlElicitation()) return "a page VS Code opens (MCP URL elicitation, a password field on 127.0.0.1)";
    if (desktop()) return "a password dialog MasterCompiler shows on this desktop";
    return null;
  }

  /* The user's value, or null when they cancelled; it is never logged */
  static char[] ask(String title, String label, int maxLength) throws Exception {
    if (urlElicitation()) return askByUrl(title, label, maxLength);
    if (desktop()) return askByDialog(title, label, maxLength);
    throw new IllegalStateException("MasterCompiler cannot ask the user for " + label + " here: the client offers no "
        + "URL elicitation and there is no desktop for a dialog. The user types it in their own emulator.");
  }

  private static char[] askByDialog(String title, String label, int maxLength) throws Exception {
    final char[][] value = new char[1][];
    javax.swing.SwingUtilities.invokeAndWait(() -> {
      javax.swing.JPasswordField field = new javax.swing.JPasswordField(Math.min(Math.max(maxLength, 8), 40));
      javax.swing.JOptionPane pane = new javax.swing.JOptionPane(new Object[] { label, field },
          javax.swing.JOptionPane.QUESTION_MESSAGE, javax.swing.JOptionPane.OK_CANCEL_OPTION);
      javax.swing.JDialog dialog = pane.createDialog(title);
      dialog.setAlwaysOnTop(true);
      dialog.addWindowFocusListener(new java.awt.event.WindowAdapter() {
        @Override public void windowGainedFocus(java.awt.event.WindowEvent e) { field.requestFocusInWindow(); }
      });
      dialog.setVisible(true);  // modal: returns once answered
      Object choice = pane.getValue();
      if (choice instanceof Integer && (Integer) choice == javax.swing.JOptionPane.OK_OPTION) value[0] = field.getPassword();
      field.setText("");
      dialog.dispose();
    });
    return value[0];
  }

  private static char[] askByUrl(String title, String label, int maxLength) throws Exception {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    StringBuilder token = new StringBuilder();
    for (byte b : bytes) token.append(String.format("%02x", b));
    String path = "/mc-secret/" + token;
    CompletableFuture<char[]> typed = new CompletableFuture<char[]>();
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(path, exchange -> page(exchange, title, label, maxLength, typed));
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
    String elicitation = token.substring(0, 16);
    long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000L;  // the user's whole answer, page included
    try {
      ObjectNode request = mapper.createObjectNode();
      String id = "mc-elicit-" + ids.incrementAndGet();
      request.put("jsonrpc", "2.0");
      request.put("id", id);
      request.put("method", "elicitation/create");
      ObjectNode params = request.putObject("params");
      params.put("mode", "url");
      params.put("elicitationId", elicitation);
      params.put("url", url);
      params.put("message", title + ": MasterCompiler needs " + label + ". Open the page and type it there; it goes "
          + "only to MasterCompiler on this PC, which types it into the screen.");
      CompletableFuture<JsonNode> response = new CompletableFuture<JsonNode>();
      pending.put(id, response);
      sender.accept(mapper.writeValueAsString(request));
      JsonNode answer;
      try {
        answer = response.get(WAIT_SECONDS, TimeUnit.SECONDS);
      } finally {
        pending.remove(id);
      }
      if (answer.has("error")) throw new IllegalStateException("The client did not open the page: " + answer.path("error").path("message").asText());
      if (!"accept".equals(answer.path("result").path("action").asText())) return null;  // declined or cancelled
      char[] value = typed.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
      ObjectNode done = mapper.createObjectNode();
      done.put("jsonrpc", "2.0");
      done.put("method", "notifications/elicitation/complete");
      done.putObject("params").put("elicitationId", elicitation);
      sender.accept(mapper.writeValueAsString(done));
      return value.length == 0 ? null : value;
    } catch (java.util.concurrent.TimeoutException e) {
      return null;
    } finally {
      server.stop(0);
    }
  }

  /* The one-time page: GET shows the password field, POST takes the value (once) */
  private static void page(HttpExchange exchange, String title, String label, int maxLength, CompletableFuture<char[]> typed)
      throws java.io.IOException {
    String body;
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    String self = "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
    if ("POST".equals(exchange.getRequestMethod()) && (origin == null || !origin.equals(self))) {
      exchange.sendResponseHeaders(403, -1);  // only this page posts here
      exchange.close();
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && !typed.isDone()) {
      String form;
      try (InputStream in = exchange.getRequestBody()) {
        byte[] data = new byte[8192];
        int n = 0;
        for (int r; n < data.length && (r = in.read(data, n, data.length - n)) > 0;) n += r;
        form = new String(data, 0, n, StandardCharsets.UTF_8);
      }
      String value = "";
      boolean cancelled = false;
      for (String pair : form.split("&")) {
        if (pair.startsWith("value=")) value = URLDecoder.decode(pair.substring(6), "UTF-8");
        if (pair.startsWith("cancel=")) cancelled = true;
      }
      typed.complete(cancelled ? new char[0] : value.toCharArray());
      body = "<p>" + (cancelled ? "Cancelled." : "Done: MasterCompiler types it into the screen.") + " You can close this tab.</p>";
    } else if (typed.isDone()) {
      body = "<p>Already answered. You can close this tab.</p>";
    } else {
      body = "<form method='post' autocomplete='off'><label>" + escape(label) + "<br><input type='password' name='value' autocomplete='new-password' "
          + "maxlength='" + maxLength + "' autofocus required></label><br><button type='submit'>OK</button> "
          + "<button type='submit' name='cancel' value='1' formnovalidate>Cancel</button></form>";
    }
    byte[] html = ("<!doctype html><html><head><meta charset='utf-8'><title>" + escape(title) + "</title>"
        + "<style>body{font-family:sans-serif;margin:3em}input{font-size:1.2em;margin:.5em 0}</style></head><body><h3>"
        + escape(title) + "</h3>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.sendResponseHeaders(200, html.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(html);
    }
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
  }

  static void clear(char[] value) {
    if (value != null) java.util.Arrays.fill(value, '\0');
  }
}
