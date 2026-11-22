package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/* The JSON-RPC channel for scripts: framing, one answer per request, ids, structuredContent, --rpc, --batch, drain */
class RpcChannelTest {
  private static final ObjectMapper mapper = new ObjectMapper();

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  private RpcChannel channel(String input, RpcChannel.Options options) {
    return new RpcChannel(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out, options, true);
  }

  /* The server never answers in these: the input ending does not wait */
  private static RpcChannel.Options aborting() {
    RpcChannel.Options options = options();
    options.eof = RpcChannel.Eof.ABORT;
    return options;
  }

  private static RpcChannel.Options options() {
    RpcChannel.Options options = new RpcChannel.Options();
    options.decorate = true;
    options.drainSeconds = 0;
    return options;
  }

  private List<JsonNode> written() throws Exception {
    List<JsonNode> lines = new ArrayList<JsonNode>();
    for (String line : out.toString("UTF-8").split("\n")) if (!line.isEmpty()) lines.add(mapper.readTree(line));
    return lines;
  }

  private static ObjectNode toolResult(JsonNode id, String text, boolean isError) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    ObjectNode result = response.putObject("result");
    result.putArray("content").addObject().put("type", "text").put("text", text);
    result.put("isError", isError);
    return response;
  }

  @Test
  void readsNdjsonContentLengthAndPrettyPrintedJson() throws Exception {
    String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
    String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body
        + "{\n  \"jsonrpc\": \"2.0\",\n  \"id\": 3,\n  \"method\": \"ping\"\n}\n";
    RpcChannel channel = channel(input, aborting());
    for (int i = 0; i < 3; i++) assertEquals("ping", channel.next().method());
    assertNull(channel.next());
    assertTrue(written().isEmpty(), "nothing answered by the channel itself");
  }

  @Test
  void cutOffLineGetsItsOwnParseErrorAndDoesNotSwallowTheNext() throws Exception {
    RpcChannel channel = channel("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"\n"
        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n" + "garbage\n" + "{\"id\":3,\"method\":\"ping\"} tail\n", aborting());
    RpcChannel.Message message = channel.next();
    assertEquals("ping", message.method());
    assertNull(channel.next());
    List<JsonNode> errors = written();
    assertEquals(3, errors.size());
    for (JsonNode error : errors) assertEquals(-32700, error.path("error").path("code").asInt());
  }

  @Test
  void invalidRequestsAreAnsweredAndClientAnswersPassThrough() throws Exception {
    RpcChannel channel = channel("[1,2]\n{\"jsonrpc\":\"2.0\",\"id\":5}\n{\"jsonrpc\":\"2.0\",\"id\":\"mc-elicit-1\",\"result\":{}}\n"
        + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n", options());
    assertEquals("mc-elicit-1", channel.next().node.path("id").asText(), "an answer to MC's own question keeps its id");
    assertEquals("notifications/initialized", channel.next().method());
    assertNull(channel.next());
    List<JsonNode> errors = written();
    assertEquals(-32600, errors.get(0).path("error").path("code").asInt());
    assertEquals(5, errors.get(1).path("id").asInt());
  }

  @Test
  void idsAreMcsOwnAndComeBackInTheAnswer() throws Exception {
    RpcChannel channel = channel("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n", options());
    RpcChannel.Message first = channel.next();
    String own = first.node.path("id").asText();
    assertTrue(own.startsWith("c"), own);
    assertTrue(first.line().contains("\"" + own + "\""));
    assertNull(channel.next(), "the same id while the first waits: refused, and the input ended");
    ObjectNode answer = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", own);
    answer.putObject("result");
    channel.write(answer);
    channel.write(answer);  // a late or repeated answer: no client waits for it
    List<JsonNode> lines = written();
    assertEquals(-32600, lines.get(0).path("error").path("code").asInt());
    assertTrue(lines.get(0).path("error").path("message").asText().contains("still waiting"), lines.get(0).toString());
    assertEquals("DRAIN_TIMEOUT", lines.get(1).path("error").path("data").path("errorKind").asText());
    assertEquals(7, lines.get(1).path("id").asInt());
    assertEquals(2, lines.size(), "7 is answered once (by the drain timeout); the late answer has nobody waiting");
  }

  @Test
  void toolResultsGetStructuredContentAndErrorKind() throws Exception {
    RpcChannel.Options options = options();
    options.drainSeconds = 5;
    RpcChannel channel = channel("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"status\"}}\n"
        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"plan\"}}\n", options);
    JsonNode one = channel.next().node.get("id");
    JsonNode two = channel.next().node.get("id");
    channel.write(toolResult(one, "{\n  \"curlib\" : \"DEV\"\n}", false));
    channel.write(toolResult(two, McpServer.NO_PROJECT, true));
    List<JsonNode> lines = written();
    assertEquals(1, lines.get(0).path("id").asInt());
    assertEquals("DEV", lines.get(0).path("result").path("structuredContent").path("curlib").asText());
    assertTrue(lines.get(0).path("result").path("content").path(0).path("text").asText().contains("curlib"), "the text stays");
    assertEquals("NO_PROJECT", lines.get(1).path("result").path("_meta").path("errorKind").asText());
    assertFalse(lines.get(1).path("result").has("structuredContent"));
  }

  @Test
  void anOlderClientGetsNoStructuredContent() throws Exception {
    RpcChannel.Options options = options();
    options.drainSeconds = 5;
    RpcChannel channel = channel("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}\n"
        + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"status\"}}\n", options);
    channel.next();
    channel.write(toolResult(channel.next().node.get("id"), "{\"curlib\":\"DEV\"}", false));
    assertFalse(written().get(0).path("result").has("structuredContent"));
  }

  @Test
  void rpcModeNamesToolsAsMethodsAndFailuresAsErrors() throws Exception {
    RpcChannel.Options options = options();
    options.rpc = true;
    options.confirm = true;
    options.drainSeconds = 5;
    RpcChannel channel = channel("{\"id\":1,\"method\":\"sql\",\"params\":{\"statement\":\"VALUES 1\"}}\n"
        + "{\"id\":2,\"method\":\"clean\",\"params\":{\"confirm\":false}}\n", options);
    RpcChannel.Message sql = channel.next();
    assertEquals("tools/call", sql.method());
    assertEquals("sql", sql.node.path("params").path("name").asText());
    assertEquals("VALUES 1", sql.node.path("params").path("arguments").path("statement").asText());
    assertTrue(sql.node.path("params").path("arguments").path("confirm").asBoolean(), "--confirm fills a missing confirm");
    RpcChannel.Message clean = channel.next();
    assertFalse(clean.node.path("params").path("arguments").path("confirm").asBoolean(true), "confirm: false stays");
    channel.write(toolResult(sql.node.get("id"), "{\"rows\":[[1]]}", false));
    channel.write(toolResult(clean.node.get("id"), "Build b1 is running (compile, X): this tool uses the build job", true));
    List<JsonNode> lines = written();
    assertEquals(1, lines.get(0).path("result").path("rows").path(0).path(0).asInt(), "the result is the tool's JSON");
    JsonNode error = lines.get(1).path("error");
    assertEquals(-32000, error.path("code").asInt());
    assertEquals("BUILD_BUSY", error.path("data").path("errorKind").asText());
  }

  @Test
  void batchFollowsARunningBuildToItsEnd() throws Exception {
    RpcChannel.Options options = options();
    options.batch = true;
    options.rpc = true;
    RpcChannel channel = channel("{\"id\":1,\"method\":\"build\"}\n", options);
    JsonNode own = channel.next().node.get("id");
    channel.write(toolResult(own, "{\"buildId\":\"b9\",\"state\":\"running\"}", false));
    assertTrue(written().isEmpty(), "a running build is not the answer yet");
    RpcChannel.Message follow = channel.next();
    assertEquals(own, follow.node.get("id"));
    assertEquals("build_status", follow.node.path("params").path("name").asText());
    assertEquals("b9", follow.node.path("params").path("arguments").path("buildId").asText());
    channel.write(toolResult(own, "{\"buildId\":\"b9\",\"state\":\"failed\",\"targets\":[{\"failedAt\":\"compile\"}]}", true));
    assertNull(channel.next());
    List<JsonNode> lines = written();
    assertEquals(1, lines.size());
    assertEquals("COMPILE_FAILED", lines.get(0).path("error").path("data").path("errorKind").asText());
    assertTrue(channel.failed());
  }

  @Test
  void failFastReadsNothingAfterAFailure() throws Exception {
    RpcChannel.Options options = options();
    options.batch = true;
    options.failFast = true;
    RpcChannel channel = channel("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"plan\"}}\n"
        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n", options);
    channel.write(toolResult(channel.next().node.get("id"), "Give the 'object'", true));
    assertNull(channel.next());
    assertEquals(1, written().size());
  }

  @Test
  void abortEndsWithoutAnswering() throws Exception {
    RpcChannel.Options options = options();
    options.eof = RpcChannel.Eof.ABORT;
    RpcChannel channel = channel("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n", options);
    channel.next();
    assertNull(channel.next());
    assertTrue(written().isEmpty());
  }

  @Test
  void errorKindsComeFromTheTable() {
    assertEquals("SIGN_ON_LIMIT", RpcChannel.errorKind(null, "Refused: --max-sign-ons 3 allows no more sign-ons to the IBM i"));
    assertEquals("PROTECTED_LIB", RpcChannel.errorKind(null, "Refused: the command names PRODLIB, a protectedLibs library"));
    assertEquals("SSH_LOST", RpcChannel.errorKind(null, "Lost MasterCompiler on the IBM i before it answered (x)."));
    assertEquals("SQL_ERROR", RpcChannel.errorKind(null, "SQL error 42704 (-204): X in Y type *FILE not found."));
    assertEquals("TOOL_ERROR", RpcChannel.errorKind(null, "something else"));
    assertEquals("TOOL_FAILED", RpcChannel.errorKind(mapper.createObjectNode().put("success", false), ""));
  }

  @Test
  void dashOnlyWhereItMeansStdinOrStdout() {
    ArgParser json = new ArgParser(new String[] { "--sql", "VALUES 1", "--json", "-" });
    assertEquals("-", json.getJsonReport());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] { "-f", "-" }));
    ArgParser equals = new ArgParser(new String[] { "--mcp", "--exit-on-eof=abort", "--drain-timeout=5" });
    assertEquals("abort", equals.getExitOnEof());
    assertEquals(5, equals.getDrainTimeout());
    ArgParser twoStdin = new ArgParser(new String[] { "--sql", "-", "--password-stdin" });
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, twoStdin::validate);
    assertTrue(e.getMessage().startsWith("Only one input can come from stdin"), e.getMessage());
  }

  @Test
  void aScriptNamesToolsAsMethodsAndKeepsMcpResults() throws Exception {
    ArgParser parser = new ArgParser(new String[] { "--script", "requests.ndjson" });
    parser.validate();
    assertFalse(parser.isRpc());
    RpcChannel.Options options = RpcChannel.Options.of(parser);
    options.drainSeconds = 0;
    RpcChannel channel = channel("{\"id\":1,\"method\":\"run_command\",\"params\":{\"command\":\"DSPLIBL\"}}\n"
        + "{\"id\":2,\"method\":\"resources/list\"}\n", options);
    RpcChannel.Message call = channel.next();
    assertEquals("tools/call", call.method());
    assertEquals("run_command", call.node.path("params").path("name").asText());
    assertEquals("DSPLIBL", call.node.path("params").path("arguments").path("command").asText());
    channel.write(toolResult(call.node.get("id"), "{\"success\":true}", false));  // --batch: the next once this one is answered
    assertEquals("resources/list", channel.next().method(), "a method with a / is MCP's: never a tool");
    JsonNode result = written().get(0).path("result");
    assertTrue(result.has("content") && result.path("structuredContent").path("success").asBoolean(), "an MCP result, not --rpc's");
    assertFalse(RpcChannel.namesTool("tools/call") || RpcChannel.namesTool("notifications/initialized") || RpcChannel.namesTool("ping"));
    assertFalse(RpcChannel.Options.of(new ArgParser(new String[] { "--mcp" })).script);
  }

  @Test
  void toolsBecomeRequestsOfABatchRpcServer() throws Exception {
    ArgParser parser = new ArgParser(new String[] { "--tool", "status", "--tool", "sql", "--args-json", "{\"statement\":\"VALUES 1\"}" });
    parser.validate();
    assertTrue(parser.isMcp() && parser.isBatch() && parser.isRpc());
    RpcChannel channel = new RpcChannel(RpcChannel.requests(parser), out, RpcChannel.Options.of(parser), true);
    RpcChannel.Message status = channel.next();
    assertEquals("status", status.node.path("params").path("name").asText());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] { "--args-json", "{}" }));
  }

  @Test
  void socketFlagsGoWhereTheyBelong() {
    /* --confirm on the server: every client's default; --fail-fast there only with --batch (it reads one at a time) */
    ArgParser server = new ArgParser(new String[] { "--mcp", "--socket", "/tmp/mc-t.sock", "--confirm" });
    try {
      server.validate();
    } catch (IllegalArgumentException e) {
      assertFalse(e.getMessage().contains("--confirm"), e.getMessage());
    }
    assertTrue(server.isConfirm());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] { "--mcp", "--confirm" }).validate());
    IllegalArgumentException fast = assertThrows(IllegalArgumentException.class,
        () -> new ArgParser(new String[] { "--mcp", "--socket", "/tmp/mc-t.sock", "--fail-fast" }).validate());
    assertTrue(fast.getMessage().contains("--socket <path> --fail-fast"), fast.getMessage());
    /* the client refuses the server's options, naming them, and the server the client's */
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new ArgParser(new String[] { "--socket", "/tmp/mc-t.sock", "--ssh", "--tool", "status" }).validate());
    assertTrue(e.getMessage().contains("--ssh goes on the server's start"), e.getMessage());
    e = assertThrows(IllegalArgumentException.class,
        () -> new ArgParser(new String[] { "--mcp", "--socket", "/tmp/mc-t.sock", "--wait-ready" }).validate());
    assertTrue(e.getMessage().startsWith("--wait-ready goes on a client call"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] { "--tool", "status", "--shutdown" }).validate());
    assertTrue(ArgParser.getUsage().contains("--socket client (--socket <path>) takes --tool"));
  }

  @Test
  void waitReadyAndShutdownWrapTheClientsRequests() throws Exception {
    ArgParser parser = new ArgParser(new String[] { "--socket", "/tmp/mc-t.sock", "--wait-ready", "--tool", "sql",
        "--args-json", "{\"statement\":\"VALUES 1\"}", "--shutdown" });
    parser.validate();
    assertFalse(parser.clientReadsStdin());
    RpcChannel.Options options = RpcChannel.Options.of(parser);
    assertTrue(options.finite);
    options.batch = false;  // all three read at once here: a --batch client sends each once the last was answered
    options.relay = true;   // as the client: shutdown is passed on to the server
    options.drainSeconds = 0;
    RpcChannel channel = new RpcChannel(RpcChannel.requests(parser), out, options, true);
    RpcChannel.Message wait = channel.next();
    assertEquals("status", wait.node.path("params").path("name").asText());
    assertTrue(wait.node.path("params").path("arguments").path("wait").asBoolean());
    assertEquals("sql", channel.next().node.path("params").path("name").asText());
    assertEquals("shutdown", channel.next().method());
    /* alone, they never wait for stdin */
    ArgParser alone = new ArgParser(new String[] { "--socket", "/tmp/mc-t.sock", "--shutdown" });
    alone.validate();
    assertFalse(alone.clientReadsStdin());
    assertTrue(RpcChannel.Options.of(alone).finite);
    assertTrue(new ArgParser(new String[] { "--socket", "/tmp/mc-t.sock" }).clientReadsStdin());
  }

  /* The status answer to --wait-ready, and whether the plan after it is still read */
  private RpcChannel.Message afterWaitReady(boolean waitReady, boolean isError, String text) throws Exception {
    RpcChannel.Options options = options();
    options.batch = true;
    options.waitReady = waitReady;
    RpcChannel channel = channel(RpcChannel.WAIT_READY + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"plan\"}}\n", options);
    RpcChannel.Message wait = channel.next();
    ObjectNode answer = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", wait.node.get("id").asText());
    answer.putObject("result").put("isError", isError).putArray("content").addObject().put("type", "text").put("text", text);
    channel.write(answer);
    RpcChannel.Message next = channel.next();
    assertEquals(isError || waitReady && text.contains("failed"), channel.failed());
    return next;
  }

  @Test
  void failedWaitReadyStopsTheRest() throws Exception {
    assertNull(afterWaitReady(true, true, "Could not start MasterCompiler on the IBM i (stage 1/5 connecting): refused"));
    assertNull(afterWaitReady(true, false, "{\"state\":\"failed\",\"error\":\"YAML must define at least one target\"}"));
    assertNotNull(afterWaitReady(true, false, "{\"state\":\"ready\"}"));
    /* a script's own request with that id is an ordinary one */
    assertNotNull(afterWaitReady(false, true, "refused"));
  }

  @Test
  void rpcErrorSaysWhy() {
    assertEquals(": CPF3C50 Program X not created.", RpcChannel.reason(mapper.createObjectNode().put("success", false)
        .put("error", "CPF3C50 Program X not created.")));
    assertEquals("", RpcChannel.reason(mapper.createObjectNode().put("success", false)));
  }
}
