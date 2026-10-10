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
 * Tools: build, plan, impact, clean, joblog.
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
    MasterCompiler.quietLogs(parser.isVerbose());
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

  /* Answers that need no IBM i (the SSH proxy gives them while it is still connecting); null for the rest */
  JsonNode localResult(String method, JsonNode params) {
    switch (method) {
      case "initialize": return initialize(params);
      case "ping":       return mapper.createObjectNode();
      case "tools/list": return toolsList();
      default:           return null;
    }
  }

  private ObjectNode initialize(JsonNode params) {
    ObjectNode result = mapper.createObjectNode();
    result.put("protocolVersion", params.path("protocolVersion").asText(DEFAULT_PROTOCOL));
    result.putObject("capabilities").putObject("tools");
    ObjectNode info = result.putObject("serverInfo");
    info.put("name", "mastercompiler");
    info.put("version", MasterCompiler.version());
    result.put("instructions",
        "Builds IBM i objects from the MasterCompiler spec " + specName() + ". " +
        "Call plan to see what would compile, build to compile (errors come back with file, line, " +
        "column, message id and severity; severity 20 and up stop a compile), impact to see what " +
        "depends on an object, and joblog for the IBM i job's recent messages." + pendingRequest());
    return result;
  }

  /* A request saved by --setup-vscode --next in another window: tell the agent it is waiting */
  private String pendingRequest() {
    try {
      File prompt = new File(projectDir(), VscodeSetup.CONTINUE_PROMPT);
      if (!prompt.isFile()) return "";
      return " A request from the MasterCompiler setup is waiting in " + VscodeSetup.CONTINUE_PROMPT
          + " (the user runs it with /mastercompiler-continue): offer to carry it on, and delete that file once done.";
    } catch (Exception e) {
      return "";
    }
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

    ObjectNode clean = mapper.createObjectNode();
    clean.put("type", "object");
    ObjectNode confirm = clean.putObject("properties").putObject("confirm");
    confirm.put("type", "boolean");
    confirm.put("description", "false (default): only list what would be deleted. true: delete, after the user agreed to that list");
    tools.add(tool("clean",
        "Delete this project's objects from the current library, e.g. to remove a demo. Only when the user asks. "
        + "First call it without confirm: it lists the target objects that exist there and deletes nothing. Show "
        + "that list to the user (it includes any object with a target's name and type, even one MC did not build) "
        + "and call again with confirm: true only if they agree. Also removes those objects' EVFEVENT members.",
        clean));

    ObjectNode find = mapper.createObjectNode();
    find.put("type", "object");
    ObjectNode findProps = find.putObject("properties");
    objectsProperty(findProps);
    find.putArray("required").add("objects");
    tools.add(tool("find_source",
        "Where the IBM i says objects were compiled from: source file members (ILE programs: per bound module), "
        + "stream files, binder source, and the /COPY members they include. Use it for programs, service programs "
        + "and files the project uses but does not build (the build report's 'external' list), e.g. to read the "
        + "parameters of a called program. Flags members changed after the compile (changed_since_compile) and "
        + "missing ones. Reads only.",
        find));

    ObjectNode imp = mapper.createObjectNode();
    imp.put("type", "object");
    ObjectNode impProps = imp.putObject("properties");
    objectsProperty(impProps);
    ObjectNode members = impProps.putObject("members");
    members.put("type", "string");
    members.put("description", "Instead of (or besides) objects: source members, comma-separated: LIB/SRCPF/MBR, "
        + "LIB/SRCPF/PREFIX* or LIB/SRCPF");
    ObjectNode into = impProps.putObject("into");
    into.put("type", "string");
    into.putArray("enum").add("reference").add("repo");
    ObjectNode dry = impProps.putObject("dryRun");
    dry.put("type", "boolean");
    dry.put("description", "true: check what exists and list the files it would write (wouldWrite, notImported); write nothing");
    into.put("description", "reference (default): read-only copies under .mc/sources/<LIB>/<SRCPF>/ (git-ignored, "
        + "never built). repo: into the project as <SRCPF>/<OBJECT>.<type>.<srctype>, so the next build compiles "
        + "it into the current library; only when the user wants to change that object. Existing files are kept.");
    ObjectNode sqlSchema = mapper.createObjectNode();
    sqlSchema.put("type", "object");
    ObjectNode sqlProps = sqlSchema.putObject("properties");
    sqlProps.putObject("statement").put("type", "string")
        .put("description", "One SELECT, VALUES or WITH query. Unqualified names resolve through the spec's library list");
    sqlProps.putObject("maxRows").put("type", "integer")
        .put("description", "Rows to return (default " + SqlTools.DEFAULT_ROWS + ", at most " + SqlTools.MAX_ROWS + ")");
    sqlProps.putObject("maxColumns").put("type", "integer")
        .put("description", "Columns to return (default " + SqlTools.DEFAULT_COLUMNS + ")");
    sqlSchema.putArray("required").add("statement");
    tools.add(tool("sql",
        "Read-only Db2 for i query on the IBM i: table rows, catalog views (QSYS2.SYSCOLUMNS2, SYSTABLES, "
        + "OBJECT_STATISTICS, PROGRAM_INFO, ...). The connection is opened read only, so only SELECT / VALUES / WITH "
        + "run; it has the spec's library list (system naming: LIB/TABLE or LIB.TABLE). Long values are cut at "
        + SqlTools.MAX_VALUE_CHARS + " characters.",
        sqlSchema));

    ObjectNode findObj = mapper.createObjectNode();
    findObj.put("type", "object");
    ObjectNode findObjProps = findObj.putObject("properties");
    findObjProps.putObject("name").put("type", "string").put("description", "Object name, e.g. CUSTMAST");
    findObjProps.putObject("type").put("type", "string").put("description", "Object type, e.g. *FILE, *PGM (default: any)");
    findObj.putArray("required").add("name");
    tools.add(tool("find_object",
        "Every library holding an object, which one the spec's library list resolves to (resolvesTo), and whether "
        + "you are authorized to use each. Use it before reading or changing anything named without a library.",
        findObj));

    ObjectNode findExp = mapper.createObjectNode();
    findExp.put("type", "object");
    findExp.putObject("properties").putObject("symbol").put("type", "string")
        .put("description", "Exported procedure or data name, e.g. CALCTAX (as in CPD5D02 'Definition not found for symbol')");
    findExp.putArray("required").add("symbol");
    tools.add(tool("find_export",
        "Who exports a symbol: service programs on the spec's library list, the binding directories there that list them, "
        + "and project sources that export it. Use it on binder errors (CPD5D02, CPD5D03).",
        findExp));

    tools.add(tool("import_source",
        "Copy sources from the IBM i into the project folder, named with MC's conventions (copybooks as "
        + "*.include.*). Takes the objects (it finds their sources and /COPY members like find_source) or source "
        + "members. SQL tables, views and indexes without a member get generated DDL. Returns the files written.",
        imp));

    ObjectNode joblog = mapper.createObjectNode();
    joblog.put("type", "object");
    joblog.putObject("properties");
    tools.add(tool("joblog",
        "Joblog messages of the server's IBM i job since the previous joblog call (or since connecting).",
        joblog));
    return result;
  }

  private void objectsProperty(ObjectNode props) {
    ObjectNode objects = props.putObject("objects");
    objects.put("type", "array");
    objects.putObject("items").put("type", "string");
    objects.put("description", "Objects: NAME (looked up on the library list) or LIB/NAME, optionally followed "
        + "by the type, e.g. \"CUSTSRV *SRVPGM\" as the build report's external list names them");
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
    ObjectNode minSeverity = props.putObject("minSeverity");
    minSeverity.put("type", "integer");
    minSeverity.put("description", "Lowest message severity in the report (default 20: errors that stop a compile). "
        + "0 shows everything, including informational messages");
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
    /* MCP hints: clients can auto-approve read-only tools and confirm destructive ones */
    boolean destructive = name.equals("build") || name.equals("clean");
    boolean writes = destructive || name.equals("import_source");  // import_source only adds files to the project
    ObjectNode annotations = tool.putObject("annotations");
    annotations.put("readOnlyHint", !writes);
    annotations.put("destructiveHint", destructive);
    annotations.put("openWorldHint", false);
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
        case "clean":  return clean(args);
        case "joblog": return joblog();
        case "find_source":   return findSource(args);
        case "sql":           return sql(args);
        case "find_object":   return findObject(args);
        case "find_export":   return findExport(args);
        case "import_source": return importSource(args);
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
    Code4iConfig code4i = MasterCompiler.code4i(parser);
    compiler.setLibraryHooks(MasterCompiler.libraryHooks(parser, code4i));
    compiler.setPush(MasterCompiler.pushDir(parser, code4i, spec));
    /* One round trip should show every error: keep going unless the agent asks otherwise */
    compiler.setKeepGoing(!dryRun && args.path("keepGoing").asBoolean(true));

    Set<String> files = files(args, spec);
    if (files != null) compiler.setChangedFiles(files);
    else if (args.hasNonNull("since")) compiler.setSince(args.get("since").asText());

    compiler.build();
    BuildReport report = compiler.getReport();
    report.compact(args.path("minSeverity").asInt(20));
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

  /* Lists (confirm false) or deletes (confirm true) the targets that exist in the current library, dependents first */
  private ObjectNode clean(JsonNode args) throws Exception {
    connect();
    BuildSpec spec = loadSpec();
    CommandExecutor executor = new CommandExecutor(connection, parser.isDebug(), parser.isVerbose(), false);
    for (CommandObject hook : MasterCompiler.libraryHooks(parser, MasterCompiler.code4i(parser))) {
      if (hook.getSystemCommand() == CompilationPattern.SysCmd.CHGCURLIB) executor.executeCommand(hook);  // DLTOBJ uses *CURLIB
    }

    ObjectDescriptor objects = new ObjectDescriptor(connection, parser.isDebug(), parser.isVerbose());
    List<TargetKey> existing = new java.util.ArrayList<TargetKey>();
    List<TargetKey> targets = new java.util.ArrayList<TargetKey>(spec.targets.keySet());
    java.util.Collections.reverse(targets);  // dependents first
    for (TargetKey key : targets) {
      objects.objectExists(key);
      if (key.objectExists()) existing.add(key);
    }

    boolean confirmed = args.path("confirm").asBoolean(false);
    ObjectNode result = mapper.createObjectNode();
    result.put("library", currentLibrary());
    result.put("confirmed", confirmed);
    ArrayNode listed = result.putArray(confirmed ? "deleted" : "wouldDelete");
    ArrayNode failed = result.putArray("notDeleted");
    for (TargetKey key : existing) {
      String name = key.getObjectName() + " *" + key.getObjectTypeEnum().name();
      if (!confirmed) {
        listed.add(name);
        continue;
      }
      try {
        executor.deleteObject(key);
        listed.add(name);
        removeEventMember(key.getObjectName());
      } catch (Exception e) {
        failed.add(name + ": " + e.getMessage());
      }
    }
    if (!confirmed) {
      result.put("next", existing.isEmpty() ? "Nothing to delete."
          : "Nothing deleted yet. Show this list to the user; if they agree, call clean again with confirm: true.");
    }
    return toolResult(pretty.writeValueAsString(result), false);
  }

  /* Its compile's EVFEVENT member, when there is one (not every command writes it; same-named targets share it) */
  private void removeEventMember(String name) {
    try (java.sql.Statement stmt = connection.createStatement();
         java.sql.ResultSet rs = stmt.executeQuery(
           "SELECT 1 FROM QSYS2.SYSPARTITIONSTAT WHERE TABLE_NAME = 'EVFEVENT' AND SYSTEM_TABLE_MEMBER = '" + name + "'" +
           " AND TABLE_SCHEMA = (SELECT SCHEMA_NAME FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT')")) {
      if (!rs.next()) return;
    } catch (Exception e) {
      return;
    }
    try (java.sql.Statement stmt = connection.createStatement()) {
      stmt.execute("CALL QSYS2.QCMDEXC('RMVM FILE(*CURLIB/EVFEVENT) MBR(" + name + ")')");
    } catch (Exception e) {
      logger.info("EVFEVENT member {} not removed: {}", name, e.getMessage());
    }
  }

  /* Connected, with the developer's library list (unqualified names resolve as in their compiles) */
  /* The connection's library list, then the spec's own CHGLIBL / CHGCURLIB hooks, as a build applies them */
  private void connectWithLibraryList() throws Exception {
    connect();
    CommandExecutor executor = new CommandExecutor(connection, parser.isDebug(), parser.isVerbose(), false);
    for (CommandObject hook : MasterCompiler.libraryHooks(parser, MasterCompiler.code4i(parser))) {
      executor.executeCommand(hook);
    }
    BuildSpec spec;
    try {
      spec = loadSpec();
    } catch (Exception noSpecYet) {
      return;  // e.g. an empty repository import_source is filling
    }
    for (CommandObject hook : spec.before) {
      if (CommandExecutor.isLibraryListCommand(hook)) executor.executeCommand(hook);
    }
  }

  private List<String> objectsArg(JsonNode args) {
    List<String> objects = new java.util.ArrayList<String>();
    for (JsonNode object : args.path("objects")) {
      if (!object.asText("").trim().isEmpty()) objects.add(object.asText().trim());
    }
    return objects;
  }

  /* Read-only connection for the sql tool, kept while the library list stays the same */
  private Connection readOnly;
  private String readOnlyLibl;

  private ObjectNode sql(JsonNode args) throws Exception {
    String statement = args.path("statement").asText("").trim();
    if (statement.isEmpty()) return toolResult("Give the query in 'statement'", true);
    try {
      SqlTools.checkReadOnly(statement);
    } catch (IllegalArgumentException e) {
      return toolResult(e.getMessage() + ". The sql tool is read only.", true);
    }
    connectWithLibraryList();
    List<String> libl = SqlTools.libraryList(connection);
    if (readOnly == null || readOnly.isClosed() || !String.join(" ", libl).equals(readOnlyLibl)) {
      if (readOnly != null) try { readOnly.close(); } catch (Exception ignored) { /* reopened below */ }
      readOnly = SqlTools.readOnlyConnection(system, libl);
      readOnlyLibl = String.join(" ", libl);
    }
    try {
      ObjectNode result = new SqlTools().query(readOnly, statement, args.path("maxRows").asInt(0), args.path("maxColumns").asInt(0));
      return toolResult(pretty.writeValueAsString(result), false);
    } catch (java.sql.SQLException e) {
      return toolResult("SQL error " + e.getSQLState() + " (" + e.getErrorCode() + "): " + e.getMessage(), true);
    }
  }

  private ObjectNode findObject(JsonNode args) throws Exception {
    String name = args.path("name").asText("").trim();
    if (name.isEmpty()) return toolResult("Give the object 'name'", true);
    connectWithLibraryList();
    return toolResult(pretty.writeValueAsString(new SqlTools().findObject(connection, name, args.path("type").asText(null))), false);
  }

  private ObjectNode findExport(JsonNode args) throws Exception {
    String symbol = args.path("symbol").asText("").trim();
    if (symbol.isEmpty()) return toolResult("Give the 'symbol'", true);
    connectWithLibraryList();
    java.util.Map<String, String> sources = new java.util.LinkedHashMap<String, String>();
    try {
      BuildSpec spec = loadSpec();
      File base = spec.getBaseDirectory() != null ? new File(spec.getBaseDirectory()) : projectDir();
      for (TargetKey key : spec.targets.keySet()) {
        if (!key.containsStreamFile()) continue;
        File file = new File(key.getStreamFile());
        if (!file.isAbsolute()) file = new File(base, key.getStreamFile());
        if (file.isFile()) sources.put(key.getStreamFile(), new String(java.nio.file.Files.readAllBytes(file.toPath()),
            java.nio.charset.StandardCharsets.UTF_8));
      }
    } catch (Exception noSpec) {
      /* only the IBM i side */
    }
    return toolResult(pretty.writeValueAsString(new SqlTools().findExport(connection, symbol, sources)), false);
  }

  private ObjectNode findSource(JsonNode args) throws Exception {
    List<String> objects = objectsArg(args);
    if (objects.isEmpty()) return toolResult("Give at least one object in 'objects'", true);
    connectWithLibraryList();
    List<SourceLocator.Located> found = new SourceLocator(connection).locate(objects, true);
    return toolResult(pretty.writeValueAsString(found), false);
  }

  private ObjectNode importSource(JsonNode args) throws Exception {
    List<String> objects = objectsArg(args);
    String members = args.path("members").asText("").trim();
    if (objects.isEmpty() && members.isEmpty()) return toolResult("Give 'objects' or 'members'", true);
    boolean intoRepo = "repo".equals(args.path("into").asText("reference"));
    boolean dryRun = args.path("dryRun").asBoolean(false);
    connectWithLibraryList();

    List<SourceLocator.SourceRef> sources = new java.util.ArrayList<SourceLocator.SourceRef>();
    java.util.Set<String> seen = new java.util.HashSet<String>();
    ObjectNode result = mapper.createObjectNode();
    File project = projectDir();
    result.put("projectFolder", project.getAbsolutePath());  // the SSH proxy puts the PC's folder here
    result.put("into", intoRepo ? "repo" : "reference");
    if (dryRun) result.put("dryRun", true);
    ArrayNode notImported = result.putArray("notImported");
    int copybooksFound = 0;
    if (!objects.isEmpty()) {
      for (SourceLocator.Located located : new SourceLocator(connection).locate(objects, true)) {
        if (located.sources.isEmpty()) notImported.add(located.request + ": " + located.note);
        copybooksFound += located.copybooks.size();
        List<SourceLocator.SourceRef> refs = new java.util.ArrayList<SourceLocator.SourceRef>(located.sources);
        refs.addAll(located.copybooks);
        for (SourceLocator.SourceRef ref : refs) {
          if ("missing".equals(ref.status) || "stream_file".equals(ref.status)) {
            notImported.add((ref.streamFile != null ? ref.streamFile : ref.key()) + ": " + ref.note);
          } else if (seen.add(ref.key())) {
            sources.add(ref);
          }
        }
      }
    }

    String outDir = (intoRepo ? project.getPath() : new File(project, ".mc/sources").getPath()).replace(File.separatorChar, '/');
    LibraryImporter importer = new LibraryImporter(system, connection, parser.isVerbose());
    importer.setKeepExisting(intoRepo);
    importer.setWriteReportFile(false);
    importer.setDryRun(dryRun);
    if (!intoRepo && !dryRun) importer.writeFile(new File(project, ".mc/.gitignore").getPath(), "# MasterCompiler working files\n*");

    List<LibraryImporter.ImportedMember> files = new java.util.ArrayList<LibraryImporter.ImportedMember>(
        importer.importSources(sources, outDir, !intoRepo).files);
    /* Members: the library importer names them from the objects built from them (reference copies per library).
       One selection that matches nothing is reported, the others are still imported. */
    for (String item : members.split(",")) {
      String selection = item.trim().toUpperCase();
      if (selection.isEmpty()) continue;
      String library = selection.contains("/") ? selection.substring(0, selection.indexOf('/')) : selection;
      try {
        for (LibraryImporter.ImportedMember file : importer.run(selection, intoRepo ? outDir : outDir + "/" + library).files) {
          if (!intoRepo) file.file = library + "/" + file.file;
          files.add(file);
        }
      } catch (CompilerException e) {
        notImported.add(selection + ": " + e.getMessage());
      }
    }

    /* Project-relative paths: written now, already there (kept), and each file's own path */
    String prefix = intoRepo ? "" : ".mc/sources/";
    ArrayNode written = result.putArray(dryRun ? "wouldWrite" : "written");
    ArrayNode kept = result.putArray("kept");
    if (!intoRepo && !dryRun) written.add(".mc/.gitignore");
    ArrayNode fileList = mapper.createArrayNode();
    for (LibraryImporter.ImportedMember file : files) {
      if ("copybook".equals(file.how)) copybooksFound++;
      ObjectNode entry = pretty.valueToTree(file);
      if (!"error".equals(file.how)) {
        entry.put("path", prefix + file.file);
        (file.kept != null ? kept : written).add(prefix + file.file);
      } else {
        notImported.add(file.library + "/" + file.sourceFile + "/" + file.member + ": " + file.note);
      }
      fileList.add(entry);
    }
    result.put("copybooks", copybooksFound);
    if (copybooksFound == 0) {
      result.put("copybookNote", "No /COPY, /INCLUDE or EXEC SQL INCLUDE member was found in these sources (or none is in this selection)");
    }
    result.set("files", fileList);
    result.put("next", dryRun ? "Nothing written. Call again without dryRun to import."
        : notImported.size() > 0 ? "Some sources were not imported: see notImported. The rest was imported."
        : intoRepo ? "The imported sources are build targets now; plan shows what a build would compile."
        : "Read the copies under .mc/sources/.");
    return toolResult(pretty.writeValueAsString(result), false);
  }

  /* The project folder: --project, else the scanned folder, the spec's folder or the TOBi root */
  private File projectDir() {
    if (parser.getProjectRoot() != null) return new File(parser.getProjectRoot());
    if (parser.hasTobi()) return new File(parser.getTobiRoot());
    if (parser.hasScan()) return new File(parser.getScanRoot());
    File spec = new File(parser.getYamlFile()).getAbsoluteFile();
    return spec.getParentFile();
  }

  private String currentLibrary() throws Exception {
    try (java.sql.Statement stmt = connection.createStatement();
         java.sql.ResultSet rs = stmt.executeQuery(
           "SELECT TRIM(SCHEMA_NAME) FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT'")) {
      return rs.next() ? rs.getString(1) : "*CURLIB";
    }
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
      /* no object inspection: builds use the sources and MC's defaults, not whatever object exists */
      return new SpecGenerator(system, null, parser.isDebug(), parser.isVerbose())
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
    /* An MCP server cannot prompt: without credentials, say how to provide them */
    if (!parser.isCode4i() && !IBMiDotEnv.isIBMi() && System.getenv("IBMI_PASSWORD") == null && !new File(".env").isFile()) {
      throw new IllegalArgumentException("No IBM i credentials: start MC with --code4i and IBMI_PASSWORD from a password "
          + "input in .vscode/mcp.json (java -jar MasterCompiler.jar --setup-vscode writes it), or set "
          + "IBMI_HOSTNAME / IBMI_USERNAME / IBMI_PASSWORD, or put them in a .env file.");
    }
    system = MasterCompiler.connect(parser);
    connection = new AS400JDBCDataSource(system).getConnection();
    lastJoblog = new CommandExecutor(connection, false, false, false).getCurrentTime();
    logger.info("Connected to IBM i as {}", system.getUserId());
  }

  private void disconnect() {
    try {
      if (readOnly != null && !readOnly.isClosed()) readOnly.close();
    } catch (Exception ignored) {}
    readOnly = null;
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
