package com.github.kraudy.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;

/*
 * MCP server over stdio ({@code --mcp}): newline-delimited JSON-RPC on stdin/stdout,
 * logs on stderr. One IBM i connection (one job) stays open across tool calls, so
 * builds skip the connect cost and the library list and joblog carry over.
 * The spec is re-read on every call, so source and spec edits are picked up.
 *
 * Tools: build, plan, impact, joblog.
 */
public class McpServer {
  private static final Logger logger = LoggerFactory.getLogger(McpServer.class);
  private static final String DEFAULT_PROTOCOL = "2025-06-18";

  private final ArgParser parser;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ObjectMapper pretty = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private PrintStream protocolOut;

  private AS400 system;
  private Connection connection;
  private Timestamp lastJoblog;

  public McpServer(ArgParser parser) {
    this.parser = parser;
  }

  /* Reads requests until stdin closes */
  public void serve() throws Exception {
    /* stdout is the protocol channel: everything else (logback, libraries) goes to stderr */
    protocolOut = new PrintStream(System.out, true, "UTF-8");
    System.setOut(System.err);
    logger.info("MasterCompiler MCP server ready");

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    try {
      while ((line = in.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        handle(line);
      }
    } finally {
      disconnect();
    }
  }

  private void handle(String line) {
    JsonNode request;
    try {
      request = mapper.readTree(line);
    } catch (Exception e) {
      sendError(null, -32700, "Parse error");
      return;
    }

    JsonNode id = request.get("id");
    String method = request.path("method").asText("");
    JsonNode params = request.path("params");

    if (id == null) return;  // notification (notifications/initialized, cancelled, ...)

    try {
      switch (method) {
        case "initialize":   sendResult(id, initialize(params)); break;
        case "ping":         sendResult(id, mapper.createObjectNode()); break;
        case "tools/list":   sendResult(id, toolsList()); break;
        case "tools/call":   sendResult(id, callTool(params)); break;
        default:             sendError(id, -32601, "Method not found: " + method);
      }
    } catch (Exception e) {
      logger.error("Request failed: " + method, e);
      sendError(id, -32603, e.toString());
    }
  }

  private ObjectNode initialize(JsonNode params) {
    ObjectNode result = mapper.createObjectNode();
    result.put("protocolVersion", params.path("protocolVersion").asText(DEFAULT_PROTOCOL));
    result.putObject("capabilities").putObject("tools");
    ObjectNode info = result.putObject("serverInfo");
    info.put("name", "mastercompiler");
    info.put("version", "1.0-SNAPSHOT");
    result.put("instructions",
        "Builds IBM i objects from the MasterCompiler spec " + specName() + ". " +
        "Call plan to see what would compile, build to compile (errors come back with file, line, " +
        "column, message id and severity; severity 20 and up stop a compile), impact to see what " +
        "depends on an object, and joblog for the IBM i job's recent messages.");
    return result;
  }

  private ObjectNode toolsList() {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode tools = result.putArray("tools");

    tools.add(tool("build",
        "Compile targets on the IBM i. With 'files' or 'since' only the targets whose sources " +
        "(or /copy members) changed are built, plus everything that depends on them; with neither, " +
        "every target is built. Returns the build report: per target status, command, compile errors " +
        "(file, line, column, id, severity, message) and joblog. A failed target does not stop the build: " +
        "everything that does not depend on it is still built, its dependents are reported as blocked.",
        selectionSchema()));
    tools.add(tool("plan",
        "Dry run of build: which targets would compile, in order, and with which commands. " +
        "Nothing is compiled. Same 'files' / 'since' selection as build.",
        selectionSchema()));

    ObjectNode impact = mapper.createObjectNode();
    impact.put("type", "object");
    ObjectNode object = impact.putObject("properties").putObject("object");
    object.put("type", "string");
    object.put("description", "Object name (e.g. ARTICLE) or target key (e.g. curlib.ARTICLE.pf.dds)");
    impact.putArray("required").add("object");
    tools.add(tool("impact",
        "Everything that would have to be rebuilt if this object changed: the targets that depend " +
        "on it, directly or transitively, in build order. Reads sources only; compiles nothing.",
        impact));

    ObjectNode joblog = mapper.createObjectNode();
    joblog.put("type", "object");
    joblog.putObject("properties");
    tools.add(tool("joblog",
        "Joblog messages of the server's IBM i job since the previous joblog call (or since connecting).",
        joblog));
    return result;
  }

  private ObjectNode selectionSchema() {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    ObjectNode files = props.putObject("files");
    files.put("type", "array");
    files.putObject("items").put("type", "string");
    files.put("description", "Changed source files, relative to the spec's directory (or absolute)");
    ObjectNode since = props.putObject("since");
    since.put("type", "string");
    since.put("description", "Git ref: build what changed since it (committed, uncommitted and untracked)");
    ObjectNode keepGoing = props.putObject("keepGoing");
    keepGoing.put("type", "boolean");
    keepGoing.put("description", "build only (default true): false stops at the first failed target");
    return schema;
  }

  private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
    ObjectNode tool = mapper.createObjectNode();
    tool.put("name", name);
    tool.put("description", description);
    tool.set("inputSchema", inputSchema);
    return tool;
  }

  private ObjectNode callTool(JsonNode params) throws Exception {
    String name = params.path("name").asText("");
    JsonNode args = params.path("arguments");
    try {
      switch (name) {
        case "build":  return build(args, false);
        case "plan":   return build(args, true);
        case "impact": return impact(args);
        case "joblog": return joblog();
        default:       return toolResult("Unknown tool: " + name, true);
      }
    } catch (Exception e) {
      logger.error("Tool failed: " + name, e);
      String message = e instanceof CompilerException ? ((CompilerException) e).getFullContext() : e.toString();
      return toolResult(message, true);
    }
  }

  private ObjectNode build(JsonNode args, boolean dryRun) throws Exception {
    connect();
    BuildSpec spec = loadSpec();

    MasterCompiler compiler = new MasterCompiler(system, connection, spec, dryRun,
        parser.isDebug(), parser.isVerbose(), false, false, parser.isNoMigrate());
    compiler.setCollectReport(true);
    compiler.setPush(parser.getPush());
    /* One round trip should show every error: keep going unless the agent asks otherwise */
    compiler.setKeepGoing(!dryRun && args.path("keepGoing").asBoolean(true));

    Set<String> files = files(args, spec);
    if (files != null) compiler.setChangedFiles(files);
    else if (args.hasNonNull("since")) compiler.setSince(args.get("since").asText());

    compiler.build();
    BuildReport report = compiler.getReport();
    return toolResult(pretty.writeValueAsString(report), !report.success);
  }

  private ObjectNode impact(JsonNode args) throws Exception {
    String object = args.path("object").asText("").trim();
    if (object.isEmpty()) return toolResult("'object' is required", true);

    BuildSpec spec = loadSpec();
    new DependencyAwareness(system, parser.isDebug(), parser.isVerbose()).detectDependencies(spec);

    String name = object.contains(".") ? object.split("\\.")[1] : object;
    Set<TargetKey> seeds = new HashSet<TargetKey>();
    for (TargetKey key : spec.targets.keySet()) {
      if (key.getObjectName().equalsIgnoreCase(name)) seeds.add(key);
    }
    if (seeds.isEmpty()) return toolResult("No target named " + name + " in " + specName(), true);

    Set<TargetKey> rebuild = DiffPlanner.expand(spec, seeds);
    ObjectNode result = mapper.createObjectNode();
    result.put("object", name.toUpperCase());
    ArrayNode targets = result.putArray("targets");
    ArrayNode dependents = result.putArray("dependents");
    for (TargetKey key : spec.targets.keySet()) {  // spec order = build order
      if (!rebuild.contains(key)) continue;
      (seeds.contains(key) ? targets : dependents).add(key.asString());
    }
    return toolResult(pretty.writeValueAsString(result), false);
  }

  private ObjectNode joblog() throws Exception {
    connect();
    CommandExecutor executor = new CommandExecutor(connection, parser.isDebug(), parser.isVerbose(), false);
    Timestamp now = executor.getCurrentTime();
    List<BuildReport.JoblogMessage> messages = executor.getJoblogMessages(lastJoblog);
    lastJoblog = now;
    return toolResult(pretty.writeValueAsString(messages), false);
  }

  /* 'files' argument → canonical absolute paths, or null when not given */
  private Set<String> files(JsonNode args, BuildSpec spec) {
    if (!args.has("files") || !args.get("files").isArray()) return null;
    Set<String> files = new LinkedHashSet<String>();
    for (JsonNode file : args.get("files")) {
      String path = file.asText().trim();
      if (path.isEmpty()) continue;
      File f = new File(path);
      if (!f.isAbsolute()) f = new File(spec.getBaseDirectory(), path);
      files.add(GitChanges.canonical(f.getPath()));
    }
    return files;
  }

  private BuildSpec loadSpec() throws Exception {
    if (parser.hasTobi()) {
      return new TobiConverter(parser.isVerbose()).convert(parser.getTobiRoot(), parser.getLibrary());
    }
    if (parser.hasScan()) {
      return new SpecGenerator(system, connection, parser.isDebug(), parser.isVerbose())
          .generate(parser.getScanRoot(), parser.getLibrary(), parser.getBaseFile());
    }
    return parser.getSpecFromYamlFile();
  }

  private String specName() {
    if (parser.hasTobi()) return parser.getTobiRoot();
    return parser.hasScan() ? parser.getScanRoot() : parser.getYamlFile();
  }

  /* One connection for the server's lifetime; reconnect if it dropped */
  private void connect() throws Exception {
    if (connection != null && !connection.isClosed()) return;
    disconnect();
    system = IBMiDotEnv.getNewSystemConnection(true);
    connection = new AS400JDBCDataSource(system).getConnection();
    lastJoblog = new CommandExecutor(connection, false, false, false).getCurrentTime();
    logger.info("Connected to IBM i as {}", system.getUserId());
  }

  private void disconnect() {
    try {
      if (connection != null && !connection.isClosed()) connection.close();
    } catch (Exception ignored) {}
    if (system != null) system.disconnectAllServices();
    connection = null;
    system = null;
  }

  private ObjectNode toolResult(String text, boolean isError) {
    ObjectNode result = mapper.createObjectNode();
    ObjectNode content = result.putArray("content").addObject();
    content.put("type", "text");
    content.put("text", text);
    result.put("isError", isError);
    return result;
  }

  private void sendResult(JsonNode id, JsonNode result) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    send(response);
  }

  private void sendError(JsonNode id, int code, String message) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message);
    send(response);
  }

  private synchronized void send(JsonNode message) {
    try {
      protocolOut.println(mapper.writeValueAsString(message));
      protocolOut.flush();
    } catch (Exception e) {
      logger.error("Could not send MCP message", e);
    }
  }
}
