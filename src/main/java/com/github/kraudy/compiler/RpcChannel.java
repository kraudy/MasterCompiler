package com.github.kraudy.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.SequenceInputStream;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * The JSON-RPC channel between MC's MCP server and one client: stdin/stdout, a --socket connection, or the requests of
 * --tool / --script.
 *
 *   In:  NDJSON, Content-Length framed or pretty-printed (multi-line) JSON. Every request gets exactly one answer: what
 *        is not JSON gets -32700, what is not a JSON-RPC message -32600. A request's id is swapped for one of MC's own
 *        (c<n>) and swapped back in its answer, so ids of two clients (or of MC's own calls) never meet, and an answer
 *        that comes after its client left goes nowhere.
 *   Out: one compact line per message. Tool results get structuredContent (the JSON of their text, parsed once) and,
 *        when they failed, _meta.errorKind; with --rpc the result is the tool's JSON itself and a failure a JSON-RPC error.
 *   EOF: drain (the default) answers what was asked before the channel ends, up to --drain-timeout; abort ends at once.
 *
 * The server reads with next() and answers with write(); the channel holds what is still owed.
 */
final class RpcChannel {
  private static final Logger logger = LoggerFactory.getLogger(RpcChannel.class);
  private static final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final AtomicLong ids = new AtomicLong();
  static final int MAX_MESSAGE = 64 << 20;
  static final String STRUCTURED_SINCE = "2025-06-18";  // the MCP version that has structuredContent
  static final int DEFAULT_DRAIN_SECONDS = 900;
  static final int FOLLOW_WAIT = 120;

  /* MCP's own methods; with --rpc or --script any other method without a / names a tool (MCP's others all have one) */
  static final Set<String> PROTOCOL = new HashSet<String>(Arrays.asList("initialize", "ping", "tools/list", "tools/call", "shutdown"));

  static boolean namesTool(String method) {
    return !PROTOCOL.contains(method) && method.indexOf('/') < 0;
  }

  /* A failed tool call's errorKind, from its message: the first that matches */
  static final Map<String, Pattern> ERROR_KINDS = new LinkedHashMap<String, Pattern>();
  static {
    kind("NO_PROJECT", "^No project is open");
    kind("SIGN_ON_LIMIT", "--max-sign-ons");
    kind("NO_CREDENTIALS", "No IBM i credentials|IBMI_HOSTNAME is not set|IBMI_PASSWORD");
    kind("AUTH_BLOCKED", "rejected the (password|user profile)|same password .{0,80}rejected|CPF22E\\d|CPF1394");
    kind("SSH_LOST", "^Lost MasterCompiler on the IBM i|is not running on the IBM i|^Could not start MasterCompiler on the IBM i");
    kind("BUILD_BUSY", "^Build \\S+ is running|still running on the IBM i|(is|are) still running");
    kind("CONNECTION_FAILED", "cannot establish the connection|Connection (timed out|refused)|UnknownHost|No route to host");
    kind("PROTECTED_LIB", "protectedLibs|protected librar");
    kind("INQMSGRPY_WAIT", "\\bMSGW\\b|inquiry message|waiting for a reply");
    kind("SQL_ERROR", "^SQL error");
    kind("UNKNOWN_TOOL", "^Unknown tool|does not run on a read-only connection");
    kind("REFUSED", "^Refused");
    kind("NOT_FOUND", "does not exist|not found|^No build ");
    kind("INVALID_ARGS", "^Give |is required|^'\\w+' (is|needs)");
  }

  private static void kind(String name, String regex) {
    ERROR_KINDS.put(name, Pattern.compile(regex));
  }

  /* A failed build's errorKind, from the failedAt of its targets */
  static final Map<String, String> FAILED_AT = new HashMap<String, String>();
  static {
    FAILED_AT.put("compile", "COMPILE_FAILED");
    FAILED_AT.put("hook", "HOOK_FAILED");
    FAILED_AT.put("tests", "TESTS_FAILED");
    FAILED_AT.put("timeout", "TIMEOUT");
    FAILED_AT.put("cancelled", "CANCELLED");
    FAILED_AT.put("protectedLibs", "PROTECTED_LIB");
  }

  /* A failed tool's JSON: the field that says why, in one line, for the --rpc error's message (the first that has one) */
  static final List<String> REASONS = Arrays.asList("error", "message");

  /* --batch: an answer still running (this field says which) is followed with this tool until it ends */
  static final Map<String, String> FOLLOW = Collections.singletonMap("buildId", "build_status");

  enum Eof { DRAIN, ABORT }

  /* Exit codes of a script run (--tool, --script, a --socket client); 2 is invalid arguments, as everywhere */
  static final int OK = 0;
  static final int CALL_FAILED = 1;
  static final int UNREACHABLE = 3;  // the --socket server could not be reached, or left before it answered
  static final int CONNECT_SECONDS = 30;  // a client waits this long for the server's socket (it may still be starting)

  /* One client of the server: its messages until next() says it ended */
  interface Session {
    void serve(RpcChannel channel) throws Exception;
  }

  /*
   * The server's clients: stdin (or the --tool / --script requests), or one --socket client after another until one
   * sends shutdown. Returns the exit code: CALL_FAILED when a --batch call failed.
   */
  static int serve(ArgParser parser, PrintStream stdout, Session session) throws Exception {
    Options options = Options.of(parser);
    if (parser.getSocket() == null) {
      RpcChannel channel = new RpcChannel(requests(parser), stdout, options, true);
      session.serve(channel);
      return options.batch && channel.failed() ? CALL_FAILED : OK;
    }
    Path path = Paths.get(parser.getSocket()).toAbsolutePath();
    UnixSockets.Listener listener = UnixSockets.listen(path);  // refused: the socket there is someone else's, left alone
    try {
      Runtime.getRuntime().addShutdownHook(new Thread(listener::close));  // ended by a signal too
      ready(parser, stdout, path);
      while (true) {
        try (SocketChannel client = listener.channel.accept()) {
          RpcChannel channel = new RpcChannel(UnixSockets.in(client), UnixSockets.out(client), options, false);
          session.serve(channel);
          if (channel.shutdownAsked()) return OK;
        } catch (IOException e) {
          logger.warn("A --socket client failed: {}", e.getMessage());
        }
      }
    } finally {
      listener.close();
    }
  }

  /* The socket listens: a notification on stdout, and on --ready-fd */
  private static void ready(ArgParser parser, PrintStream stdout, Path path) {
    ObjectNode note = mapper.createObjectNode();
    note.put("jsonrpc", "2.0");
    note.put("method", "notifications/ready");
    note.putObject("params").put("socket", path.toString()).put("version", MasterCompiler.version());
    byte[] line = (note.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    stdout.write(line, 0, line.length);
    stdout.flush();
    if (parser.getReadyFd() < 0) return;
    try (OutputStream fd = new FileOutputStream("/dev/fd/" + parser.getReadyFd())) {
      fd.write(line);
    } catch (IOException e) {
      logger.warn("--ready-fd {}: {}", parser.getReadyFd(), e.getMessage());
    }
  }

  /*
   * What a --socket client sends around its own requests: before them, --wait-ready's status that waits for the
   * server's start-up; after them, --shutdown. Their ids are strings, apart from the numbers of the --tool calls.
   */
  static final String WAIT_READY = "{\"jsonrpc\":\"2.0\",\"id\":\"wait-ready\",\"method\":\"tools/call\","
      + "\"params\":{\"name\":\"status\",\"arguments\":{\"wait\":true}}}\n";
  static final String SHUTDOWN = "{\"jsonrpc\":\"2.0\",\"id\":\"shutdown\",\"method\":\"shutdown\"}\n";
  static final String WAIT_READY_ID = "\"wait-ready\"";

  /* The client's requests: --tool calls, the --script file, or stdin; with --wait-ready / --shutdown around them */
  static InputStream requests(ArgParser parser) throws IOException {
    InputStream own = ownRequests(parser);
    if (!parser.isWaitReady() && !parser.isShutdown()) return own;
    return new SequenceInputStream(Collections.enumeration(Arrays.asList(
        text(parser.isWaitReady() ? WAIT_READY : ""), own, text(parser.isShutdown() ? "\n" + SHUTDOWN : ""))));
  }

  private static InputStream text(String lines) {
    return new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8));
  }

  private static InputStream ownRequests(ArgParser parser) throws IOException {
    if (parser.getScript() != null) return Stdio.dash(parser.getScript()) ? System.in : new FileInputStream(parser.getScript());
    if (parser.getTools().isEmpty()) return parser.clientReadsStdin() || parser.getSocket() == null ? System.in : text("");
    StringBuilder lines = new StringBuilder();
    int id = 0;
    for (String[] tool : parser.getTools()) {
      ObjectNode request = response(mapper.getNodeFactory().numberNode(++id));
      request.put("method", "tools/call");
      ObjectNode params = request.putObject("params");
      params.put("name", tool[0]);
      params.set("arguments", tool[1] != null ? mapper.readTree(tool[1]) : mapper.createObjectNode());
      lines.append(request).append('\n');
    }
    return new ByteArrayInputStream(lines.toString().getBytes(StandardCharsets.UTF_8));
  }

  /*
   * --socket without --mcp: sends the requests to the server one at a time and prints each answer (with --rpc as the
   * tool's JSON). The server's own questions (a secret to type) cannot be answered here: they get an error.
   */
  static int client(ArgParser parser) throws Exception {
    PrintStream stdout = Stdio.takeStdout();
    Options options = Options.of(parser);
    options.batch = true;  // one at a time: the next request once the last was answered
    options.relay = true;
    RpcChannel local = new RpcChannel(requests(parser), stdout, options, true);
    Path path = Paths.get(parser.getSocket());
    SocketChannel socket = null;
    for (long until = System.currentTimeMillis() + CONNECT_SECONDS * 1000L; socket == null;) {
      try {
        socket = UnixSockets.connect(path);
      } catch (IOException e) {
        if (System.currentTimeMillis() > until) {
          System.err.println("Error: no MasterCompiler server answers at " + path + " (" + e.getMessage() + "): start one with "
              + "--mcp --socket " + path);
          return UNREACHABLE;
        }
        Thread.sleep(100);
      }
    }
    Message message = null;
    try (SocketChannel server = socket) {
      Frames replies = new Frames(UnixSockets.in(server));
      OutputStream toServer = UnixSockets.out(server);
      while ((message = local.next()) != null) {
        toServer.write((message.line() + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
        if (!message.node.has("method") || !message.node.has("id")) continue;  // nothing comes back
        if (!answered(local, message.node.get("id"), replies, toServer)) return UNREACHABLE;
      }
      server.shutdownOutput();  // the server sees this client end
    } catch (IOException e) {  // the server went away mid-call
      if (message != null && message.node.has("id") && message.node.has("method")) lost(local, message.node.get("id"));
      System.err.println("Error: the MasterCompiler server at " + path + " went away: " + e.getMessage());
      return UNREACHABLE;
    }
    return local.failed() ? CALL_FAILED : OK;
  }

  /* The server left before it answered id: that request's one answer is this error */
  private static void lost(RpcChannel local, JsonNode id) {
    ObjectNode lost = response(id);
    lost.putObject("error").put("code", -32002).put("message", "The --socket server closed the connection before it "
        + "answered: the result is unknown, the call may have run").putObject("data").put("errorKind", "SERVER_LOST");
    local.write(lost);
  }

  /* Reads the server's messages until the answer to id; false when the server left first (the request gets an error) */
  private static boolean answered(RpcChannel local, JsonNode id, Frames replies, OutputStream toServer) throws IOException {
    while (true) {
      byte[] frame = replies.next();
      if (frame == null) {
        lost(local, id);
        return false;
      }
      JsonNode reply;
      try {
        reply = mapper.readTree(frame);
      } catch (Exception e) {
        continue;
      }
      if (reply.has("method")) {
        if (reply.has("id")) {  // the server asks (a secret for a 5250 screen): nobody here to answer
          ObjectNode refused = response(reply.get("id"));
          refused.putObject("error").put("code", -32601).put("message", "A --socket client cannot answer " + reply.path("method").asText());
          toServer.write((refused.toString() + "\n").getBytes(StandardCharsets.UTF_8));
          toServer.flush();
        }
        continue;
      }
      if (id.equals(reply.get("id"))) {
        local.write(reply);
        return true;
      }
    }
  }

  /* What the channel does besides plain MCP */
  static final class Options {
    boolean rpc;        // --rpc: results are the tool's JSON, failures JSON-RPC errors; a method may name a tool
    boolean script;     // --script: a method may name a tool too ({"id":1,"method":"status"}), results stay MCP's
    boolean decorate;   // structuredContent and errorKind (not for MC on the IBM i behind the SSH server: the PC adds them)
    boolean batch;      // one request at a time, in order; running builds followed to their end
    boolean failFast;   // --fail-fast: no request after the first that failed
    boolean confirm;    // --confirm: confirm: true where a call does not say
    boolean relay;      // a --socket client: shutdown is for the server, passed on
    boolean finite;     // the requests are --tool calls or a --script file: reading them never waits
    boolean waitReady;  // --wait-ready: its status call (the first request) must answer ready, or nothing more is sent
    Eof eof = Eof.DRAIN;
    int drainSeconds = DEFAULT_DRAIN_SECONDS;

    static Options of(ArgParser parser) {
      Options o = new Options();
      boolean inner = System.getProperty("mc.session") != null;  // started by the SSH MCP server on the PC
      o.rpc = parser.isRpc();
      o.script = parser.getScript() != null;
      o.decorate = !inner;
      o.batch = parser.isBatch();
      o.failFast = parser.isFailFast();
      o.confirm = parser.isConfirm();
      o.waitReady = parser.socketClient() && parser.isWaitReady();
      o.eof = parser.getExitOnEof() != null ? Eof.valueOf(parser.getExitOnEof().toUpperCase(Locale.ROOT))
          : inner ? Eof.ABORT : Eof.DRAIN;  // inner: the SSH server already waited for its answers
      o.drainSeconds = parser.getDrainTimeout() >= 0 ? parser.getDrainTimeout() : DEFAULT_DRAIN_SECONDS;
      o.finite = (parser.getSocket() == null || parser.socketClient())
          && (!parser.getTools().isEmpty() || parser.getScript() != null && !Stdio.dash(parser.getScript())
              || parser.socketClient() && parser.getScript() == null && !parser.clientReadsStdin());
      return o;
    }
  }

  /* One message the client sent: a request (its id already MC's own), a notification, or an answer to MC's question */
  static final class Message {
    final JsonNode node;
    private String line;

    Message(JsonNode node) {
      this.node = node;
    }

    String method() {
      return node.path("method").asText("");
    }

    /* The message on one line, as forwarded to MC on the IBM i */
    String line() {
      if (line == null) line = node.toString();
      return line;
    }
  }

  private final Frames frames;
  private final OutputStream out;
  private final Object writing = new Object();
  private final Options options;
  private final boolean stdio;
  private final Map<String, JsonNode> owed = new LinkedHashMap<String, JsonNode>();  // MC's id -> the client's: not answered yet
  private final Set<String> owedClientIds = new HashSet<String>();
  private final Deque<Message> followUps = new ArrayDeque<Message>();
  private boolean inputEnded;
  private byte[] ahead;  // options.finite: the frame read before the last answer came
  private final Set<String> sending = new HashSet<String>();  // answers being written: not expired meanwhile
  private long drainUntil;
  private volatile boolean stop;      // --fail-fast after a failure, or shutdown
  private volatile boolean shutdown;  // the client asked the server to end (a --socket server stops listening)
  private volatile boolean failed;
  private volatile boolean closed;    // the client's end is gone: writes go nowhere
  private volatile boolean structured = true;

  RpcChannel(InputStream in, OutputStream out, Options options, boolean stdio) {
    this.frames = new Frames(in);
    this.out = out;
    this.options = options;
    this.stdio = stdio;
  }

  boolean failed() {
    return failed;
  }

  boolean shutdownAsked() {
    return shutdown;
  }

  /* stdin/stdout: when it ends, the server ends with it (unlike a --socket client) */
  boolean stdio() {
    return stdio;
  }

  Options options() {
    return options;
  }

  /* Milliseconds left to drain once the client's input ended; 0 when aborting or none left */
  synchronized long drainLeft() {
    return options.eof == Eof.ABORT ? 0 : Math.max(0, drainUntil - System.currentTimeMillis());
  }

  /*
   * The next message, null once the client's input ended and what it asked was answered (or the drain timed out).
   * --batch: only after the last request was answered.
   */
  Message next() throws IOException {
    while (true) {
      /* --tool / a --script file never block a read: the next frame is read first, so the end of the input (and the
         drain timeout) is known while the last call is still running */
      if (options.finite && ahead == null && !inputEnded()) {
        ahead = read();
        if (ahead == null) endInput();
      }
      List<JsonNode> expired;
      synchronized (this) {
        while (followUps.isEmpty() && !owed.isEmpty() && (options.batch || inputEnded)) {
          long left = inputEnded ? drainUntil - System.currentTimeMillis() : 0;
          if (inputEnded && (left <= 0 || options.eof == Eof.ABORT)) break;
          try {
            wait(left);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
        if (!followUps.isEmpty()) return followUps.poll();
        expired = inputEnded && options.eof == Eof.DRAIN ? expireOwed() : null;
      }
      if (expired != null) {
        for (JsonNode answer : expired) send(answer);
        return null;
      }
      if (inputEnded()) return null;
      byte[] frame = ahead != null && !stop ? ahead : read();
      ahead = null;
      if (frame == null) {
        endInput();
        continue;
      }
      Message message = accept(frame);
      if (message != null) return message;
    }
  }

  private byte[] read() throws IOException {
    return stop || closed ? null : frames.next();
  }

  private synchronized boolean inputEnded() {
    return inputEnded;
  }

  /* The client's input ended: the drain starts, and nobody is left to answer MC's questions (a secret to type) */
  private void endInput() {
    synchronized (this) {
      if (inputEnded) return;
      inputEnded = true;
      drainUntil = System.currentTimeMillis() + options.drainSeconds * 1000L;
    }
    SecretPrompt.clientGone();
  }

  /* A frame read: the message for the server, or null when the channel answered it itself */
  private Message accept(byte[] frame) {
    JsonNode node;
    try {
      node = frame == Frames.TOO_LARGE ? null : mapper.readTree(frame);
    } catch (Exception e) {
      node = null;
    }
    if (node == null || node.isMissingNode()) {
      reply(null, -32700, frame == Frames.TOO_LARGE ? "Parse error: message larger than " + (MAX_MESSAGE >> 20) + " MB" : "Parse error");
      return null;
    }
    JsonNode id = node.get("id");
    if (!node.isObject()) return invalid(null, "a JSON-RPC message is an object");
    boolean request = node.has("method");
    if (!request) {
      if (node.has("result") || node.has("error")) return new Message(node);  // the client's answer to MC's question
      return invalid(id, "no method (and no result or error)");
    }
    if (!node.get("method").isTextual()) return invalid(id, "method is not a string");
    if (id == null) return new Message(node);  // a notification: no answer
    if (!(id.isTextual() || id.isNumber() || id.isNull())) return invalid(null, "id is not a string or a number");
    String method = node.get("method").asText();
    ObjectNode call = (ObjectNode) node;
    if (method.equals("shutdown") && !options.relay) {
      shutdown = true;
      stop = true;
      send(response(id).set("result", mapper.createObjectNode()));
      return null;
    }
    if (method.equals("initialize")) {
      String version = node.path("params").path("protocolVersion").asText(STRUCTURED_SINCE);
      structured = version.compareTo(STRUCTURED_SINCE) >= 0;  // an older client does not know structuredContent
    }
    if ((options.rpc || options.script) && namesTool(method)) {
      JsonNode arguments = node.path("params");
      ObjectNode params = mapper.createObjectNode();
      params.put("name", method);
      params.set("arguments", arguments.isObject() ? arguments : mapper.createObjectNode());
      call.put("method", "tools/call");
      call.set("params", params);
      method = "tools/call";
    }
    if (options.confirm && method.equals("tools/call") && call.path("params").isObject()) {
      ObjectNode params = (ObjectNode) call.get("params");
      if (!params.path("arguments").isObject()) params.putObject("arguments");
      ObjectNode arguments = (ObjectNode) params.get("arguments");
      if (!arguments.has("confirm")) arguments.put("confirm", true);  // confirm: false stays
    }
    synchronized (this) {
      if (!owedClientIds.add(id.toString())) return invalid(id, "id " + id + " is still waiting for its answer");
      String own = "c" + ids.incrementAndGet();
      owed.put(own, id);
      call.put("id", own);
    }
    return new Message(node);
  }

  private Message invalid(JsonNode id, String why) {
    reply(id, -32600, "Invalid Request: " + why);
    return null;
  }

  /* An answer to a request the server never saw (parse errors, invalid requests) */
  private void reply(JsonNode id, int code, String message) {
    ObjectNode response = response(id);
    response.putObject("error").put("code", code).put("message", message);
    failed = true;
    if (options.failFast) stop = true;
    send(response);
  }

  private static ObjectNode response(JsonNode id) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    return response;
  }

  /* A message from the server, as one line (MC on the IBM i over SSH): parsed for its id */
  void write(String line) {
    JsonNode message;
    try {
      message = mapper.readTree(line);
    } catch (Exception e) {
      logger.warn("Not a JSON-RPC message, not passed on: {}", line.length() > 200 ? line.substring(0, 200) + "..." : line);
      return;
    }
    write(message);
  }

  /* A message from the server: its own requests and notifications go out as they are; an answer gets its client's id back */
  void write(JsonNode message) {
    if (!message.isObject()) return;
    ObjectNode node = (ObjectNode) message;
    if (node.has("method") || !node.has("id") || node.get("id").isNull()) {
      send(node);
      return;
    }
    String own = node.get("id").asText();
    JsonNode outgoing = route(own, node);
    if (outgoing == null) return;
    send(outgoing);
    synchronized (this) {  // only now: in --batch the next request is read once this answer is out
      sending.remove(own);
      owedClientIds.remove(owed.remove(own).toString());
      notifyAll();
    }
  }

  /* An answer: the line for its client (null when nobody waits for it, or a running build is followed instead) */
  private synchronized JsonNode route(String own, ObjectNode node) {
    JsonNode id = owed.get(own);
    if (id == null || sending.contains(own)) {
      logger.debug("Answer {} has no client waiting (it left, or the drain timed out): dropped", own);
      return null;
    }
    JsonNode result = node.get("result");
    /* a tool result, or (from a --rpc server) the tool's JSON itself */
    JsonNode data = isToolResult(result) ? decorate((ObjectNode) result) : result != null && result.isObject() ? result : null;
    Message next = options.batch && data != null ? followUp(own, data, result) : null;
    if (next != null) {
      followUps.add(next);
      notifyAll();
      return null;
    }
    node.set("id", id);
    /* --wait-ready's status: a server whose start-up or spec failed (state other than ready) is not ready */
    boolean waitReady = options.waitReady && WAIT_READY_ID.equals(id.toString());
    boolean error = node.has("error") || result != null && result.path("isError").asBoolean(false)
        || waitReady && data != null && !"ready".equals(data.path("state").asText("ready"));
    if (error) {
      failed = true;
      if (options.failFast || waitReady) stop = true;  // what comes after --wait-ready depends on it
    }
    sending.add(own);
    return options.rpc ? rpc(node, data) : node;
  }

  private static boolean isToolResult(JsonNode result) {
    return result != null && result.isObject() && result.path("content").isArray() && result.has("isError");
  }

  /*
   * structuredContent: the JSON object in the text, parsed once; errorKind in _meta when it failed. Returns the
   * structured value (null when the text is not a JSON object).
   */
  private JsonNode decorate(ObjectNode result) {
    JsonNode data = result.get("structuredContent");
    String text = result.path("content").path(0).path("text").asText("");
    if (data == null && text.startsWith("{")) {
      try {
        data = mapper.readTree(text);
      } catch (Exception notJson) {
        data = null;
      }
      if (data != null && !data.isObject()) data = null;
    }
    if (!options.decorate) return data;
    if (data != null && structured) result.set("structuredContent", data);
    if (result.path("isError").asBoolean(false) && !result.path("_meta").has("errorKind")) {
      (result.path("_meta").isObject() ? (ObjectNode) result.get("_meta") : result.putObject("_meta"))
          .put("errorKind", errorKind(data, text));
    }
    return data;
  }

  static String errorKind(JsonNode data, String text) {
    if (data != null) {
      for (JsonNode target : data.path("targets")) {
        String kind = FAILED_AT.get(target.path("failedAt").asText());
        if (kind != null) return kind;
      }
      return data.has("buildId") || data.has("targets") ? "BUILD_FAILED" : "TOOL_FAILED";
    }
    for (Map.Entry<String, Pattern> kind : ERROR_KINDS.entrySet()) {
      if (kind.getValue().matcher(text).find()) return kind.getKey();
    }
    return "TOOL_ERROR";
  }

  /* --batch: a build that answered "running" is asked again (build_status, waiting) under the same id until it ends */
  private static Message followUp(String own, JsonNode data, JsonNode result) {
    if (data == null || result.path("isError").asBoolean(false) || !BuildProgress.RUNNING.equals(data.path("state").asText())) return null;
    for (Map.Entry<String, String> follow : FOLLOW.entrySet()) {
      if (!data.has(follow.getKey())) continue;
      ObjectNode request = response(mapper.getNodeFactory().textNode(own));
      request.put("method", "tools/call");
      ObjectNode params = request.putObject("params");
      params.put("name", follow.getValue());
      params.putObject("arguments").put(follow.getKey(), data.get(follow.getKey()).asText()).put("wait", FOLLOW_WAIT);
      return new Message(request);
    }
    return null;
  }

  /* --rpc: the tool's JSON as the result; a failed call as a JSON-RPC error with its errorKind */
  private static JsonNode rpc(ObjectNode node, JsonNode data) {
    JsonNode result = node.get("result");
    if (!isToolResult(result)) return node;
    String text = result.path("content").path(0).path("text").asText("");
    ObjectNode value;
    if (data != null) {
      value = (ObjectNode) data;
    } else {
      value = mapper.createObjectNode().put("text", text);
      if (result.path("content").size() > 1) value.set("content", result.get("content"));
    }
    ObjectNode out = response(node.get("id"));
    if (!result.path("isError").asBoolean(false)) {
      out.set("result", value);
      return out;
    }
    String kind = result.path("_meta").path("errorKind").asText(errorKind(data, text));
    ObjectNode error = out.putObject("error");
    error.put("code", -32000);
    error.put("message", data == null ? text : kind + reason(data));
    ObjectNode details = error.putObject("data");
    details.put("errorKind", kind);
    if (data != null) details.set("result", data);
    return out;
  }

  /* ": <why>" from a failed tool's JSON, empty when it has no such field */
  static String reason(JsonNode data) {
    for (String field : REASONS) {
      if (data.path(field).isTextual() && !data.get(field).asText().trim().isEmpty()) return ": " + data.get(field).asText().trim();
    }
    return "";
  }

  /* Drain timed out: what is still owed gets an error (sent by the caller, outside the lock), one answer per request */
  private List<JsonNode> expireOwed() {
    List<JsonNode> expired = new ArrayList<JsonNode>();
    for (Map.Entry<String, JsonNode> left : owed.entrySet()) {
      if (sending.contains(left.getKey())) continue;  // its answer is being written
      ObjectNode response = response(left.getValue());
      ObjectNode error = response.putObject("error");
      error.put("code", -32001);
      error.put("message", "Not answered within --drain-timeout (" + options.drainSeconds + " s) after the input ended: the "
          + "result is unknown, the call may have run");
      error.putObject("data").put("errorKind", "DRAIN_TIMEOUT");
      expired.add(response);
    }
    if (!expired.isEmpty()) failed = true;
    owed.keySet().retainAll(sending);
    owedClientIds.clear();
    followUps.clear();
    return expired;
  }

  /*
   * One line out; message, newline and flush together, under a lock of its own (a client slow to read never holds up
   * the channel's state). A client that is gone: written nowhere from then on.
   */
  private void send(JsonNode message) {
    byte[] line = (message.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    synchronized (writing) {
      if (closed) return;
      try {
        out.write(line);
        out.flush();
        if (out instanceof PrintStream && ((PrintStream) out).checkError()) throw new IOException("stdout is closed");
      } catch (IOException e) {
        closed = true;
        logger.warn("The client is gone ({}): answers from here on are not sent", e.getMessage());
      }
    }
  }

  /*
   * The client's messages, one at a time: a line of NDJSON; a Content-Length header block and its body; or JSON over
   * several lines when its first line opens it and ends open ({ [ , :), as jq and pretty printers write it. A line
   * that does not end open is one message even when unbalanced: a cut-off request gets its parse error and does not
   * swallow the next one.
   */
  static final class Frames {
    static final byte[] TOO_LARGE = new byte[0];
    private static final Pattern HEADER = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*):\\s*(.*)$");
    private static final int MAX_HEADERS = 32;
    private static final byte[] NOT_JSON = { '?' };  // a header block without a usable Content-Length
    private final InputStream in;
    private final byte[] buffer = new byte[8192];
    private int position;
    private int limit;
    private boolean eof;

    Frames(InputStream in) {
      this.in = in;
    }

    byte[] next() throws IOException {
      byte[] first;
      do {
        first = line();
        if (first == null) return null;
      } while (first != TOO_LARGE && blank(first));
      if (first == TOO_LARGE) return TOO_LARGE;
      int start = firstByte(first);
      if (first[start] != '{' && first[start] != '[') {
        Matcher header = HEADER.matcher(new String(first, StandardCharsets.US_ASCII));
        return header.matches() ? body(header) : first;  // not JSON: the parser answers it
      }
      int[] state = new int[3];  // depth, in a string, after a backslash
      scan(first, state);
      if (state[0] <= 0 || !endsOpen(first)) return first;
      ByteArrayOutputStream message = new ByteArrayOutputStream(first.length * 4);
      message.write(first, 0, first.length);
      while (state[0] > 0) {
        byte[] more = line();
        if (more == null) break;
        if (more == TOO_LARGE || message.size() + more.length > MAX_MESSAGE) return TOO_LARGE;
        message.write('\n');
        message.write(more, 0, more.length);
        scan(more, state);
      }
      return message.toByteArray();
    }

    /* Content-Length: headers up to an empty line, then exactly that many bytes */
    private byte[] body(Matcher header) throws IOException {
      int length = -1;
      for (int count = 0; ; count++) {
        if (header != null && header.group(1).equalsIgnoreCase("Content-Length")) {
          try {
            length = Integer.parseInt(header.group(2).trim());
          } catch (NumberFormatException e) {
            length = -1;
          }
        }
        byte[] next = line();
        if (next == null || count > MAX_HEADERS) return NOT_JSON;
        if (next == TOO_LARGE) return TOO_LARGE;
        if (blank(next)) break;
        Matcher more = HEADER.matcher(new String(next, StandardCharsets.US_ASCII));
        header = more.matches() ? more : null;
      }
      if (length < 0) return NOT_JSON;
      if (length > MAX_MESSAGE) return TOO_LARGE;
      byte[] body = new byte[length];
      for (int n = 0; n < length;) {
        if (position == limit && !fill()) return Arrays.copyOf(body, n);  // cut off: the parser says so
        int take = Math.min(length - n, limit - position);
        System.arraycopy(buffer, position, body, n, take);
        position += take;
        n += take;
      }
      return body;
    }

    /* One line without its \n (and \r); null at the end; TOO_LARGE for a line past MAX_MESSAGE (read up to its end) */
    private byte[] line() throws IOException {
      ByteArrayOutputStream line = null;
      boolean tooLarge = false;
      while (true) {
        if (position == limit && !fill()) {
          if (line == null) return null;
          break;
        }
        int end = position;
        while (end < limit && buffer[end] != '\n') end++;
        if (!tooLarge) {
          if (line == null) line = new ByteArrayOutputStream(Math.max(64, end - position));
          line.write(buffer, position, end - position);
          tooLarge = line.size() > MAX_MESSAGE;
          if (tooLarge) line.reset();
        }
        boolean found = end < limit;
        position = found ? end + 1 : end;
        if (found) break;
      }
      if (tooLarge) return TOO_LARGE;
      byte[] bytes = line.toByteArray();
      return bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? Arrays.copyOf(bytes, bytes.length - 1) : bytes;
    }

    private boolean fill() throws IOException {
      if (eof) return false;
      int n = in.read(buffer);
      if (n <= 0) {
        eof = true;
        return false;
      }
      position = 0;
      limit = n;
      return true;
    }

    private static boolean blank(byte[] line) {
      return firstByte(line) == line.length;
    }

    private static int firstByte(byte[] line) {
      int i = 0;
      while (i < line.length && (line[i] == ' ' || line[i] == '\t' || line[i] == '\r')) i++;
      return i;
    }

    private static boolean endsOpen(byte[] line) {
      int i = line.length - 1;
      while (i >= 0 && (line[i] == ' ' || line[i] == '\t')) i--;
      return i >= 0 && (line[i] == '{' || line[i] == '[' || line[i] == ',' || line[i] == ':');
    }

    /* Brackets outside strings: state = depth, in a string (1/0), after a backslash (1/0) */
    private static void scan(byte[] line, int[] state) {
      for (byte b : line) {
        if (state[1] == 1) {
          if (state[2] == 1) state[2] = 0;
          else if (b == '\\') state[2] = 1;
          else if (b == '"') state[1] = 0;
        } else if (b == '"') {
          state[1] = 1;
        } else if (b == '{' || b == '[') {
          state[0]++;
        } else if (b == '}' || b == ']') {
          state[0]--;
        }
      }
    }
  }
}
