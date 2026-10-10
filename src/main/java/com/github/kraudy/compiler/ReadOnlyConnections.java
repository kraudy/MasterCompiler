package com.github.kraudy.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jcraft.jsch.ChannelExec;

/*
 * Other IBM i systems the tools may read, from the spec (build.yaml or mc-base.yaml):
 *
 *   connections:
 *     build: SYS1                  # the Code for IBM i connection builds go to; tools refuse to run on another
 *     readOnly:
 *       - name: SYS2               # a Code for IBM i connection
 *         libraries: [LIB1, LIB2]  # the only libraries read there (its library list)
 *         maxRows: 500             # most rows one query or copy reads (default 1000)
 *         maskColumns: [COL3]      # shown masked by sql; copy_rows must replace them (set)
 *
 * MC on the PC (the MCP server VS Code starts) routes here: sql, find_object and find_source with
 * connection: SYS2 run there; compare reads the object there; copy_rows reads rows there and hands them to the
 * build system's MC, which inserts them into the current library. Each read-only connection is a second
 * MC started with --read-only (over SSH on that system, or in this process over the host servers), opened on
 * first use, with a read-only JDBC connection; it has no tool that writes. Its password, when SSH has no key,
 * comes from IBMI_PASSWORD_<NAME> (a VS Code password prompt). Confirmed copies are logged in .mc/copies.log.
 */
final class ReadOnlyConnections {
  private static final Logger logger = LoggerFactory.getLogger(ReadOnlyConnections.class);
  static final int DEFAULT_MAX_ROWS = 1000;
  static final List<String> ROUTED = java.util.Arrays.asList("sql", "find_object", "find_source");

  static final class ReadOnly {
    String name;
    List<String> libraries = new ArrayList<String>();
    int maxRows = DEFAULT_MAX_ROWS;
    List<String> maskColumns = new ArrayList<String>();
  }

  final String build;
  final Map<String, ReadOnly> readOnly = new LinkedHashMap<String, ReadOnly>();  // by upper-case name

  private ReadOnlyConnections(String build) {
    this.build = build;
  }

  /* The project's connections: build.yaml first, else mc-base.yaml; none when neither has them */
  static ReadOnlyConnections load(File project) throws Exception {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    for (String name : new String[] { "build.yaml", "mc-base.yaml" }) {
      File spec = new File(project, name);
      if (!spec.isFile()) continue;
      JsonNode connections = yaml.readTree(spec).path("connections");
      if (connections.isMissingNode() || connections.isNull()) continue;
      return parse(connections, name);
    }
    return new ReadOnlyConnections(null);
  }

  static ReadOnlyConnections parse(JsonNode connections, String where) {
    ReadOnlyConnections config = new ReadOnlyConnections(connections.path("build").asText(null));
    for (JsonNode entry : connections.path("readOnly")) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException(where + ": connections.readOnly entries are { name, libraries: [...] } "
            + "(the libraries are the only ones read there), not '" + entry.asText() + "'");
      }
      ReadOnly ro = new ReadOnly();
      ro.name = entry.path("name").asText("").trim();
      for (JsonNode lib : entry.path("libraries")) ro.libraries.add(lib.asText().trim().toUpperCase(Locale.ROOT));
      for (JsonNode col : entry.path("maskColumns")) ro.maskColumns.add(col.asText().trim().toUpperCase(Locale.ROOT));
      ro.maxRows = Math.max(1, Math.min(entry.path("maxRows").asInt(DEFAULT_MAX_ROWS), SqlTools.MAX_ROWS));
      if (ro.name.isEmpty()) throw new IllegalArgumentException(where + ": a connections.readOnly entry has no name");
      if (ro.libraries.isEmpty()) {
        throw new IllegalArgumentException(where + ": read-only connection " + ro.name + " needs libraries: [...], "
            + "the only libraries read there");
      }
      if (config.build != null && config.build.equalsIgnoreCase(ro.name)) {
        throw new IllegalArgumentException(where + ": " + ro.name + " is the build connection, it cannot be read-only too");
      }
      config.readOnly.put(ro.name.toUpperCase(Locale.ROOT), ro);
    }
    return config;
  }

  /* IBMI_PASSWORD_<NAME>: letters and digits kept, the rest as _ */
  static String passwordVariable(String connection) {
    return "IBMI_PASSWORD_" + connection.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
  }

  /* ---- routing, in the MC that VS Code started (the PC) ---- */

  /* One read-only MC: answers tools/call with a tool result */
  interface Backend {
    JsonNode call(String tool, JsonNode arguments) throws Exception;
    void close();
  }

  static final class Router {
    private final ObjectMapper mapper = new ObjectMapper();
    private final File project;
    private final boolean ssh;
    private final String connected;  // the Code for IBM i connection this server builds on (null: not known)
    private final Map<String, Backend> open = new java.util.concurrent.ConcurrentHashMap<String, Backend>();

    Router(File project, boolean ssh, String connected) {
      this.project = project;
      this.ssh = ssh;
      this.connected = connected;
    }

    /*
     * A tool result when the call is for a read-only connection (or refused), null when the build system's MC
     * answers it. For that MC the call is made ready: 'connection' removed, compare and copy_rows given what the
     * other system holds.
     */
    ObjectNode handle(String tool, ObjectNode args) {
      ReadOnlyConnections config;
      try {
        config = load(project);
      } catch (Exception e) {
        return error("connections in the spec: " + e.getMessage());
      }
      args.remove("otherInfo");      // only this router fills them
      args.remove("fetched");
      args.remove("otherConnection");
      if (config.build != null && connected != null && !config.build.equalsIgnoreCase(connected) && !tool.equals("status")) {
        return error("The spec builds on the connection " + config.build + " (connections.build), but this server is "
            + "connected to " + connected + ". Restart the mastercompiler server and pick " + config.build + ".");
      }
      String target = args.path("connection").asText("").trim();
      args.remove("connection");
      boolean other = !target.isEmpty() && !target.equalsIgnoreCase(config.build) && !target.equalsIgnoreCase(connected);
      try {
        if (tool.equals("compare")) return compare(config, target, args);
        if (tool.equals("copy_rows")) return copyRows(config, target, args);
        if (!other) return null;
        ReadOnly ro = config.readOnly.get(target.toUpperCase(Locale.ROOT));
        if (ro == null) return error(unknown(config, target));
        if (!ROUTED.contains(tool)) {
          return error(target + " is a read-only connection: only sql, find_object, find_source and compare run there");
        }
        return (ObjectNode) backend(ro).call(tool, args);
      } catch (Exception e) {
        logger.error("Read-only connection call failed", e);
        return error((target.isEmpty() ? "" : target + ": ") + e.getMessage());
      }
    }

    /* compare: the other system's view of the object goes along to the build system's MC */
    private ObjectNode compare(ReadOnlyConnections config, String target, ObjectNode args) throws Exception {
      if (target.isEmpty() && config.readOnly.size() == 1) target = config.readOnly.values().iterator().next().name;
      ReadOnly ro = config.readOnly.get(target.toUpperCase(Locale.ROOT));
      if (ro == null) return error(target.isEmpty() ? "compare needs the read-only 'connection' to compare with: "
          + config.readOnly.values().stream().map(r -> r.name).collect(java.util.stream.Collectors.toList()) : unknown(config, target));
      ObjectNode request = mapper.createObjectNode();
      request.set("object", args.path("object"));
      if (args.has("type")) request.set("type", args.get("type"));
      JsonNode result = backend(ro).call("object_info", request);
      if (result.path("isError").asBoolean(false)) return (ObjectNode) result;
      args.set("otherInfo", mapper.readTree(result.path("content").path(0).path("text").asText("{}")));
      args.put("otherConnection", ro.name);
      if (config.build != null) args.put("buildConnection", config.build);
      else if (connected != null) args.put("buildConnection", connected);
      return null;
    }

    /* copy_rows: the rows read now on the read-only connection, for the build system's MC to insert */
    private ObjectNode copyRows(ReadOnlyConnections config, String target, ObjectNode args) throws Exception {
      if (target.isEmpty() && config.readOnly.size() == 1) target = config.readOnly.values().iterator().next().name;
      ReadOnly ro = config.readOnly.get(target.toUpperCase(Locale.ROOT));
      if (ro == null) return error(target.isEmpty() ? "copy_rows needs the read-only 'connection' to read from" : unknown(config, target));
      if (args.path("from").asText("").trim().isEmpty()) return error("Give 'from': the table to read on " + ro.name);
      ObjectNode request = mapper.createObjectNode();
      request.put("from", args.path("from").asText());
      if (args.hasNonNull("where")) request.put("where", args.path("where").asText());
      request.set("set", args.path("set").isObject() ? args.get("set") : mapper.createObjectNode());
      JsonNode result = backend(ro).call("export_rows", request);
      if (result.path("isError").asBoolean(false)) return (ObjectNode) result;
      JsonNode rows = mapper.readTree(result.path("content").path(0).path("text").asText("{}"));
      args.set("fetched", rows);
      args.put("fromConnection", ro.name);
      if (args.path("confirm").asBoolean(false)) logCopy(ro, rows, args);
      return null;
    }

    /* .mc/copies.log: one line per confirmed copy_rows */
    private void logCopy(ReadOnly ro, JsonNode rows, JsonNode args) {
      try {
        File log = new File(project, ".mc/copies.log");
        log.getParentFile().mkdirs();
        ObjectNode line = mapper.createObjectNode();
        line.put("time", java.time.OffsetDateTime.now().toString());
        line.put("fromConnection", ro.name);
        line.put("read", rows.path("statement").asText());
        line.put("rows", rows.path("rows").size());
        line.put("to", args.path("to").asText(args.path("from").asText()));
        if (rows.has("replaced")) line.set("replaced", rows.get("replaced"));
        java.nio.file.Files.write(log.toPath(), (mapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Exception e) {
        logger.warn("Could not write .mc/copies.log: {}", e.getMessage());
      }
    }

    private Backend backend(ReadOnly ro) throws Exception {
      String key = ro.name.toUpperCase(Locale.ROOT) + " " + ro.libraries + " " + ro.maxRows + " " + ro.maskColumns;
      Backend backend = open.get(key);
      if (backend != null) return backend;
      synchronized (open) {
        backend = open.get(key);
        if (backend != null) return backend;
        logger.info("Opening the read-only connection {} ({})", ro.name, ssh ? "SSH" : "host servers");
        backend = ssh ? new SshBackend(ro) : new LocalBackend(ro);
        open.put(key, backend);
        return backend;
      }
    }

    void close() {
      for (Backend b : open.values()) b.close();
      open.clear();
    }

    private String unknown(ReadOnlyConnections config, String name) {
      List<String> names = new ArrayList<String>();
      for (ReadOnly r : config.readOnly.values()) names.add(r.name);
      return "No read-only connection " + name + " in the spec: connections.readOnly has " + names
          + " (add it there with its libraries)";
    }

    private ObjectNode error(String text) {
      ObjectNode result = mapper.createObjectNode();
      result.putArray("content").addObject().put("type", "text").put("text", text);
      result.put("isError", true);
      return result;
    }
  }

  /* A read-only MC in this process, over the host servers */
  static final class LocalBackend implements Backend {
    private final McpServer server;

    LocalBackend(ReadOnly ro) throws Exception {
      Code4iConfig code4i = Code4iConfig.load(ro.name);
      String variable = passwordVariable(ro.name);
      server = McpServer.readOnly(ro.libraries, ro.maxRows, ro.maskColumns, () -> code4i.connect(variable));
    }

    @Override public JsonNode call(String tool, JsonNode arguments) throws Exception {
      return server.call(tool, arguments);
    }

    @Override public void close() {
      server.close();
    }
  }

  /* A read-only MC on that IBM i, over SSH like the build system's */
  static final class SshBackend implements Backend {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SshTarget ssh;
    private final ChannelExec remote;
    private final OutputStream toRemote;
    private final BufferedReader fromRemote;
    private int nextId = 1;

    SshBackend(ReadOnly ro) throws Exception {
      Code4iConfig code4i = Code4iConfig.load(ro.name);
      try {
        ssh = SshTarget.connect(code4i.host, code4i.port, code4i.username, System.getenv(passwordVariable(ro.name)),
            code4i.privateKeyPath);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(e.getMessage().replace("IBMI_PASSWORD", passwordVariable(ro.name))
            + " (run java -jar MasterCompiler.jar --setup-vscode again: it adds that connection's password prompt)");
      }
      String home = code4i.homeKnown ? code4i.homeDirectory : ssh.exec("echo $HOME").trim();
      if (!home.startsWith("/")) home = "/home/" + code4i.username.toUpperCase(Locale.ROOT);
      if (home.length() > 1 && home.endsWith("/")) home = home.substring(0, home.length() - 1);
      String jar = SshMcpProxy.ensureRemoteJar(ssh, home);
      String dir = home + "/mc/.mc/readonly";
      StringBuilder command = new StringBuilder("mkdir -p ").append(SshTarget.quote(dir))
          .append(" && cd ").append(SshTarget.quote(dir))
          .append(" && ").append(SshMcpProxy.env("MC_REMOTE_JAVA", "/QOpenSys/usr/bin/java"))
          .append(" -jar ").append(SshTarget.quote(jar))
          .append(" --mcp --read-only --project ").append(SshTarget.quote(dir))
          .append(" --libl ").append(SshTarget.quote(String.join(" ", ro.libraries)))
          .append(" --max-rows ").append(ro.maxRows);
      if (!ro.maskColumns.isEmpty()) command.append(" --mask-columns ").append(SshTarget.quote(String.join(",", ro.maskColumns)));
      remote = ssh.start(command.toString());
      remote.setErrStream(System.err, true);
      fromRemote = new BufferedReader(new InputStreamReader(remote.getInputStream(), StandardCharsets.UTF_8));
      toRemote = remote.getOutputStream();
      remote.connect(30_000);
      logger.info("Read-only MasterCompiler running on {} ({})", code4i.host, ro.name);
    }

    @Override public synchronized JsonNode call(String tool, JsonNode arguments) throws Exception {
      int id = nextId++;
      ObjectNode request = mapper.createObjectNode();
      request.put("jsonrpc", "2.0");
      request.put("id", id);
      request.put("method", "tools/call");
      request.putObject("params").put("name", tool).set("arguments", arguments);
      toRemote.write((mapper.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
      toRemote.flush();
      String line;
      while ((line = fromRemote.readLine()) != null) {
        JsonNode response = mapper.readTree(line);
        if (response.path("id").asInt(-1) != id) continue;
        if (response.has("error")) throw new IllegalStateException(response.path("error").path("message").asText());
        return response.path("result");
      }
      throw new IllegalStateException("The read-only MasterCompiler stopped (its messages are in the MCP log)");
    }

    @Override public void close() {
      remote.disconnect();
      ssh.close();
    }
  }
}
