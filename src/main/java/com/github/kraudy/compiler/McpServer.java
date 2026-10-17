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

  /* --read-only: a server for another IBM i that only reads (ReadOnlyConnections), limited to these libraries */
  private final boolean readOnlyMode;
  private final List<String> readLibs;
  private final int maxRows;
  private final List<String> mask;
  private final java.util.concurrent.Callable<AS400> connector;  // in-process read-only server: its own system
  private ReadOnlyConnections.Router router;  // MC on the PC: calls for read-only connections
  private SignOnGuard.Target signOn;  // the password sign-on this server makes (null on the IBM i: its own job)

  /*
   * Tool calls run on threads of their own, so build_status, whats_running and cancel answer at once, even while a
   * build or another call is busy. Calls that use the build connection take turns (the lane); while a build runs
   * they are refused instead of waiting behind it.
   */
  static final Set<String> NEVER_QUEUED = new HashSet<String>(java.util.Arrays.asList("build_status", "whats_running", "cancel"));
  static final Set<String> READS_ELSEWHERE = new HashSet<String>(java.util.Arrays.asList("sql", "find_object", "find_source"));
  static final int DEFAULT_BUILD_WAIT = 20;
  static final int MAX_WAIT = 120;
  private final java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "mc-call");
    t.setDaemon(true);
    return t;
  });
  private final java.util.concurrent.Semaphore lane = new java.util.concurrent.Semaphore(1, true);
  private final Object screenLock = new Object();
  private volatile String laneCall;  // the call using the build connection now, and since when
  private volatile long laneSince;
  private volatile BuildProgress lastBuild;
  private final java.util.Map<String, BuildProgress> builds = java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap<String, BuildProgress>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, BuildProgress> eldest) { return size() > 10; }
      });
  private volatile JobWatch jobWatch;     // looks at the build job from a connection of its own
  private volatile String buildJob;       // the build connection's job
  private volatile boolean connectionGone; // ENDJOB ended the build job: reconnect at the next call

  public McpServer(ArgParser parser) {
    this.parser = parser;
    this.readOnlyMode = parser != null && parser.isReadOnly();
    this.readLibs = new java.util.ArrayList<String>();
    if (readOnlyMode && parser.getLibl() != null) {
      for (String lib : parser.getLibl().trim().toUpperCase().split("\\s+")) if (!lib.isEmpty()) readLibs.add(lib);
    }
    if (readOnlyMode && readLibs.isEmpty()) throw new IllegalArgumentException("--read-only needs --libl: the libraries it may read");
    this.maxRows = parser != null ? parser.getMaxRows() : 0;
    this.mask = parser != null ? parser.getMaskColumns() : new java.util.ArrayList<String>();
    this.connector = null;
  }

  private McpServer(List<String> libraries, int maxRows, List<String> mask, java.util.concurrent.Callable<AS400> connector) {
    this.parser = null;
    this.readOnlyMode = true;
    this.readLibs = libraries;
    this.maxRows = maxRows;
    this.mask = mask;
    this.connector = connector;
  }

  /* A read-only server in this process (a read-only connection over the host servers) */
  static McpServer readOnly(List<String> libraries, int maxRows, List<String> mask, java.util.concurrent.Callable<AS400> connector,
      SignOnGuard.Target signOn) {
    McpServer server = new McpServer(libraries, maxRows, mask, connector);
    server.signOn = signOn;
    return server;
  }

  /* One tool call, for the router: the tool result */
  JsonNode call(String tool, JsonNode arguments) throws Exception {
    ObjectNode params = mapper.createObjectNode();
    params.put("name", tool);
    params.set("arguments", arguments);
    return callTool(params);
  }

  void close() {
    if (router != null) router.close();
    if (!readOnlyMode) ScreenTools.closeAll();
    disconnect();
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
      close();
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

    /* Each tool call on its own thread: the next request is read at once */
    if ("tools/call".equals(method) && !readOnlyMode) {
      workers.execute(() -> {
        try {
          sendResult(id, callTool(params));
        } catch (Exception e) {
          logger.error("Request failed: " + method, e);
          sendError(id, -32603, e.toString());
        }
      });
      return;
    }
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
        "depends on an object. A build that takes longer than its 'wait' returns a buildId: tell the user what runs " +
        "and for how long, follow it with build_status, look at a job that does not move with whats_running, and stop " +
        "it with cancel (after the user agreed). Before a call that can take long, say what it runs and about how long. " +
        "Call joblog for the IBM i job's recent messages. Before the first build of a session, " +
        "call status and plan and show the user the current library and the targets; build once they agree. Tools that " +
        "write or stop (clean, call_program, copy_rows, upload_file, screen_open, cancel) first show a preview: call them with confirm: true only " +
        "after the user agreed. The IBM i password is typed only in VS Code's prompt, never in the chat; after an " +
        "authentication error, do not retry: follow the error's steps." + pendingRequest());
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
    if (readOnlyMode) return readOnlyToolsList();
    ObjectNode result = mapper.createObjectNode();
    ArrayNode tools = result.putArray("tools");

    tools.add(tool("build",
        "Compile targets on the IBM i. With 'files' or 'since' only the targets whose sources " +
        "(or /copy members) changed are built, plus everything that depends on them; with neither, " +
        "every target is built. Returns the build report: per target status, command, compile errors " +
        "(file, line, column, id, severity, message) and joblog; hook messages apart (hookJoblog), failedAt " +
        "(compile, hook, tests, timeout, cancelled, protectedLibs), tests: each RUCALLTST hook's RPGUnit result as JSON " +
        "(counts, each failing test with its message and statement), and timeouts: hooks past their timeout with their " +
        "job's status, call stack, locks and messages. A failed target does not stop the build: everything that does " +
        "not depend on it is still built, its dependents are reported as blocked. Waits up to 'wait' seconds (20): a " +
        "build still running then returns its buildId and where it is (stage, target, command, seconds); follow it with " +
        "build_status. One build at a time; while it runs, other tools that use the build job are refused (sql, find_object and find_source " +
        "on a read-only 'connection' still answer).",
        buildSchema()));
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
    connectionProperty(findProps);
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
    impProps.putObject("refresh").put("type", "boolean")
        .put("description", "into repo: overwrite files already in the project with the IBM i's current members (default false: kept)");
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
        .put("description", "Rows to return. Without format: default " + SqlTools.DEFAULT_ROWS + ", at most " + SqlTools.MAX_ROWS
        + ". With format: at most " + SqlTools.FILE_MAX_ROWS + ". A read-only connection's maxRows caps both");
    sqlProps.putObject("maxColumns").put("type", "integer")
        .put("description", "Columns to return without format (default " + SqlTools.DEFAULT_COLUMNS + "); with format the file has them all");
    ObjectNode format = sqlProps.putObject("format");
    format.put("type", "string");
    format.putArray("enum").add("csv").add("json").add("md").add("inserts");
    format.put("description", "Write the whole result to .mc/results/<file> instead of the chat (every column, exact values, "
        + "maxRows up to " + SqlTools.FILE_MAX_ROWS + "): csv, json, md (a table), or inserts (INSERT statements with typed "
        + "literals, e.g. for a test's setUp). The answer gives the file and the first rows");
    sqlProps.putObject("file").put("type", "string")
        .put("description", "With format: the file name in .mc/results (default: the table or 'result', and the time)");
    sqlProps.putObject("table").put("type", "string")
        .put("description", "format inserts: the table the statements insert into (default: the query's only FROM table, unqualified)");
    ObjectNode generated = sqlProps.putObject("generated");
    generated.put("type", "string");
    generated.putArray("enum").add("omit").add("keep");
    generated.put("description", "format inserts: columns Db2 generates (identity, row change timestamp, ROWID, row begin / "
        + "end, generated expressions) are left out (omit, default); keep: identity values kept, with OVERRIDING SYSTEM VALUE");
    sqlProps.putObject("set").put("type", "object")
        .put("description", "With format: columns replaced on the system read, by SQL expressions over the row, e.g. "
        + "{\"COL2\": \"'TEST'\"} (quote strings); for inserts, masked columns must be");
    connectionProperty(sqlProps);
    sqlSchema.putArray("required").add("statement");
    tools.add(tool("sql",
        "Read-only Db2 for i query on the IBM i: table rows, catalog views (QSYS2.SYSCOLUMNS2, SYSTABLES, "
        + "OBJECT_STATISTICS, PROGRAM_INFO, ...). The connection is opened read only, so only SELECT / VALUES / WITH "
        + "run; it has the spec's library list (system naming: LIB/TABLE or LIB.TABLE). Without format, long values are "
        + "cut at " + SqlTools.MAX_VALUE_CHARS + " characters; with format, the file has every column and exact values.",
        sqlSchema));

    ObjectNode findObj = mapper.createObjectNode();
    findObj.put("type", "object");
    ObjectNode findObjProps = findObj.putObject("properties");
    findObjProps.putObject("name").put("type", "string").put("description", "Object name, e.g. CUSTMAST");
    findObjProps.putObject("type").put("type", "string").put("description", "Object type, e.g. *FILE, *PGM (default: any)");
    connectionProperty(findObjProps);
    findObj.putArray("required").add("name");
    tools.add(tool("find_object",
        "Every library holding an object, which one the spec's library list resolves to (resolvesTo), and whether "
        + "you are authorized to use each. Use it before reading or changing anything named without a library.",
        findObj));

    ObjectNode findExp = mapper.createObjectNode();
    findExp.put("type", "object");
    ObjectNode findExpProps = findExp.putObject("properties");
    findExpProps.putObject("symbol").put("type", "string")
        .put("description", "Exported procedure or data name, e.g. CALCTAX (as in CPD5D02 'Definition not found for symbol')");
    findExpProps.putObject("serviceProgram").put("type", "string")
        .put("description", "Instead of a symbol: list everything this service program (NAME or LIB/NAME) exports");
    tools.add(tool("find_export",
        "Who exports a symbol: service programs on the spec's library list, the binding directories there that list them, "
        + "and project sources that export it. Use it on binder errors (CPD5D02, CPD5D03).",
        findExp));

    ObjectNode compareSchema = mapper.createObjectNode();
    compareSchema.put("type", "object");
    ObjectNode compareProps = compareSchema.putObject("properties");
    compareProps.putObject("object").put("type", "string").put("description", "Object: NAME (library list) or LIB/NAME");
    compareProps.putObject("type").put("type", "string").put("description", "Object type, e.g. *PGM (default: *PGM *SRVPGM *MODULE *FILE *CMD)");
    compareProps.putObject("connection").put("type", "string")
        .put("description", "The read-only connection to compare with (connections.readOnly in the spec; default: the only one)");
    compareSchema.putArray("required").add("object");
    tools.add(tool("compare",
        "The same object on the build system and on a read-only connection side by side: library, create and change "
        + "timestamps, and per bound module the source member, the source change timestamp its compile recorded and the "
        + "module's create timestamp, with the differences listed. Answers \"does the other system run the source I "
        + "changed?\". Reads only.",
        compareSchema));

    ObjectNode copySchema = mapper.createObjectNode();
    copySchema.put("type", "object");
    ObjectNode copyProps = copySchema.putObject("properties");
    copyProps.putObject("connection").put("type", "string")
        .put("description", "The read-only connection to read from (connections.readOnly in the spec; default: the only one)");
    copyProps.putObject("from").put("type", "string").put("description", "Table there: LIB/TABLE (one of its libraries) or TABLE");
    copyProps.putObject("where").put("type", "string").put("description", "Which rows (SQL condition, run there)");
    copyProps.putObject("set").put("type", "object")
        .put("description", "Columns replaced there by SQL expressions, e.g. {\"COL2\": \"'TEST'\"} (quote strings); masked columns must be");
    copyProps.putObject("to").put("type", "string").put("description", "Table in the current library (default: the same name as from)");
    copyProps.putObject("deleteWhere").put("type", "string")
        .put("description", "Optional: delete these rows from the target first, so copying again gives the same rows");
    copyProps.putObject("confirm").put("type", "boolean")
        .put("description", "false (default): show what would be copied. true: copy, after the user agreed");
    copySchema.putArray("required").add("from");
    tools.add(tool("copy_rows",
        "Copy rows from a table on a read-only connection into a table of the current library on the build system, "
        + "with their exact values (no retyping, no rounding). 'set' replaces columns on the other system, so replaced "
        + "values (e.g. personal data) never leave it; columns the connection masks must be replaced. Never writes to a "
        + "protectedLibs library or outside the current library. First call without confirm and show the user the rows "
        + "and statements; call again with confirm: true only if they agree. Confirmed copies are logged in .mc/copies.log.",
        copySchema));

    ObjectNode statusSchema = mapper.createObjectNode();
    statusSchema.put("type", "object");
    statusSchema.putObject("properties");
    tools.add(tool("status",
        "MasterCompiler's version, the IBM i user, current library and library list the tools use (after the spec's "
        + "library settings), the project folder and spec, protectedLibs; over SSH also the host and the start-up stage. "
        + "Call it first when something looks off.",
        statusSchema));

    ObjectNode callSchema = mapper.createObjectNode();
    callSchema.put("type", "object");
    ObjectNode callProps = callSchema.putObject("properties");
    callProps.putObject("program").put("type", "string").put("description", "Program the project builds, in the current library");
    ObjectNode callParams = callProps.putObject("parameters");
    callParams.put("type", "array");
    callParams.putObject("items").put("type", "object");
    callParams.put("description", "In order: {type: char, length, value} | {type: packed|zoned, digits, decimals, value} | "
        + "{type: int, bytes: 2|4|8, value}. No value: output only. A data structure (e.g. a fixed-length message) is one char "
        + "parameter of its full length");
    ObjectNode checkRows = callProps.putObject("checkRows");
    checkRows.put("type", "array");
    ObjectNode checkItem = checkRows.putObject("items");
    checkItem.put("type", "object");
    ObjectNode checkProps = checkItem.putObject("properties");
    checkProps.putObject("sql").put("type", "string").put("description", "A SELECT over the rows the call works on (up to 1000 rows)");
    checkProps.putObject("key").put("type", "array").put("description", "Columns that identify a row, e.g. [\"COL1\"]")
        .putObject("items").put("type", "string");
    checkRows.put("description", "Up to 5 queries run before and after the call: the answer lists the rows inserted, deleted "
        + "and changed (column: [before, after]). Without confirm they run once, to check them");
    callProps.putObject("confirm").put("type", "boolean")
        .put("description", "false (default): show what would run and which files the program can change. true: run it, after the user agreed");
    callSchema.putArray("required").add("program");
    tools.add(tool("call_program",
        "Run a program this project builds (in the current library) with typed parameters and return the parameters as the "
        + "program left them, its success and messages, to check logic right after a build. Refused when the program can "
        + "write to a file in a protectedLibs library (or when it writes files and the spec sets no protectedLibs). First "
        + "call without confirm and show the user what it would do; call again with confirm: true only if they agree.",
        callSchema));

    ObjectNode spoolSchema = mapper.createObjectNode();
    spoolSchema.put("type", "object");
    ObjectNode spoolProps = spoolSchema.putObject("properties");
    spoolProps.putObject("file").put("type", "string").put("description", "Spooled file name (RPGUNIT, QPJOBLOG, a compile "
        + "listing's name). Left out: list the newest ones");
    spoolProps.putObject("job").put("type", "string").put("description", "Qualified job name 123456/USER/JOBNAME (default: the newest file of that name)");
    spoolProps.putObject("number").put("type", "integer").put("description", "Spooled file number within the job");
    spoolProps.putObject("startLine").put("type", "integer").put("description", "First line to return (1)");
    spoolProps.putObject("maxLines").put("type", "integer").put("description", "Lines to return (300, up to 2000)");
    spoolProps.putObject("list").put("type", "boolean").put("description", "true: list the files of that name / job instead of reading one");
    tools.add(tool("spool",
        "The signed-on user's own spooled files: without 'file' the newest 30 (name, number, job, pages); with 'file' its "
        + "lines, a page at a time. RPGUnit results already come as JSON in the build report's tests: read the RPGUNIT "
        + "file only when those have just the counts.",
        spoolSchema));

    ObjectNode uploadSchema = mapper.createObjectNode();
    uploadSchema.put("type", "object");
    ObjectNode uploadProps = uploadSchema.putObject("properties");
    uploadProps.putObject("file").put("type", "string").put("description", "A file in the project folder on this PC (e.g. target/app.jar)");
    uploadProps.putObject("to").put("type", "string").put("description", "IFS folder: one of the spec's uploadFolders or below it");
    uploadProps.putObject("name").put("type", "string").put("description", "File name there (default: the same name)");
    uploadProps.putObject("confirm").put("type", "boolean")
        .put("description", "false (default): show what would be uploaded and what it replaces. true: upload, after the user agreed");
    uploadSchema.putArray("required").add("file").add("to");
    tools.add(tool("upload_file",
        "Upload a file of the project folder (a built jar, a properties file, test input) to an IFS folder the spec allows "
        + "(uploadFolders). Text files are tagged UTF-8, others are copied as they are. First call without confirm and show "
        + "the user the file, its size and SHA-256 and what it replaces; call again with confirm: true only if they agree. "
        + "Confirmed uploads are logged in .mc/uploads.log.",
        uploadSchema));

    tools.add(tool("import_source",
        "Copy sources from the IBM i into the project folder, named with MC's conventions (copybooks as "
        + "*.include.*). Takes the objects (it finds their sources and /COPY members like find_source) or source "
        + "members. SQL tables, views and indexes without a member get generated DDL. Returns the files written.",
        imp));

    screenTools(tools);
    buildTools(tools);

    ObjectNode joblog = mapper.createObjectNode();
    joblog.put("type", "object");
    joblog.putObject("properties");
    tools.add(tool("joblog",
        "Joblog messages of the server's IBM i job since the previous joblog call (or since connecting).",
        joblog));
    return result;
  }

  /* A 5250 session on the build system, run from the PC (ScreenTools) */
  private void screenTools(ArrayNode tools) {
    ObjectNode open = mapper.createObjectNode();
    open.put("type", "object");
    ObjectNode openProps = open.putObject("properties");
    openProps.putObject("confirm").put("type", "boolean")
        .put("description", "false (default): say what would open. true: open and sign on, after the user agreed");
    openProps.putObject("port").put("type", "integer").put("description", "Telnet port (default 992, TLS)");
    openProps.putObject("ccsid").put("type", "integer").put("description", "EBCDIC CCSID of the screens (default 37)");
    openProps.putObject("waitSeconds").put("type", "integer").put("description", "How long to wait for a screen (default 30)");
    tools.add(tool("screen_open",
        "Open an interactive 5250 session (a green screen) on the build system and sign on as the build connection's "
        + "user, to run and check display file programs. Returns the first screen after sign-on. MasterCompiler types "
        + "the password itself (one attempt). Every screen is also written to .mc/screen.txt, so the user can watch it in "
        + "VS Code. First call without confirm and show the user what it would do; call again with confirm: true only "
        + "if they agree. Ask the user before running commands or programs that change data.",
        open));

    ObjectNode view = mapper.createObjectNode();
    view.put("type", "object");
    view.putObject("properties").putObject("waitSeconds").put("type", "integer")
        .put("description", "While the IBM i is busy, how long to wait for the next screen (default 30)");
    tools.add(tool("screen",
        "The 5250 session's current screen: numbered lines, cursor, input fields (number, row, col, length, label, "
        + "value) and state (waiting for input, busy, input error).",
        view));

    ObjectNode send = mapper.createObjectNode();
    send.put("type", "object");
    ObjectNode sendProps = send.putObject("properties");
    sendProps.putObject("fields").put("type", "object")
        .put("description", "Values to type, by field number from 'fields' (\"1\") or \"row,col\" inside the field, e.g. "
        + "{\"1\": \"CALL PGM1\"}. Each is a field exit: the rest of the field is cleared; numbers are right-aligned");
    sendProps.putObject("key").put("type", "string")
        .put("description", "Key to press after typing: Enter (default), F1-F24, PageUp, PageDown, Help, Print, Clear, Attn, Reset");
    ObjectNode cursorProp = sendProps.putObject("cursor");
    cursorProp.put("type", "object");
    cursorProp.put("description", "Optional {row, col} to put the cursor before the key (e.g. on a list line for F4 or F2)");
    sendProps.putObject("waitSeconds").put("type", "integer")
        .put("description", "How long to wait for the next screen (default 30)");
    tools.add(tool("screen_send",
        "Type into the 5250 screen's input fields and press a key, as a user at the terminal; returns the next screen. "
        + "Hidden (password) fields are never typed into. Runs as the signed-on user: ask the user before commands "
        + "or programs that change data.",
        send));

    ObjectNode close = mapper.createObjectNode();
    close.put("type", "object");
    close.putObject("properties");
    tools.add(tool("screen_close",
        "Close the 5250 session. Sign off first (SIGNOFF on a command line, or the program's exit then SIGNOFF) so the job ends.",
        close));
  }

  /* connections.readOnly in the spec: the call reads that IBM i instead */
  private void connectionProperty(ObjectNode props) {
    props.putObject("connection").put("type", "string").put("description", "Optional: a read-only connection of the "
        + "spec (connections.readOnly) to read instead of the build system; only its listed libraries are read");
  }

  /* --read-only: what another IBM i answers; nothing here writes */
  private ObjectNode readOnlyToolsList() {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode tools = result.putArray("tools");
    ObjectNode sqlSchema = mapper.createObjectNode();
    sqlSchema.put("type", "object");
    ObjectNode sqlProps = sqlSchema.putObject("properties");
    sqlProps.putObject("statement").put("type", "string");
    sqlProps.putObject("maxRows").put("type", "integer");
    sqlProps.putObject("maxColumns").put("type", "integer");
    sqlProps.putObject("format").put("type", "string");
    sqlProps.putObject("set").put("type", "object");
    tools.add(tool("sql", "Read-only query on " + readLibs, sqlSchema));
    ObjectNode findObj = mapper.createObjectNode();
    findObj.put("type", "object");
    findObj.putObject("properties").putObject("name").put("type", "string");
    tools.add(tool("find_object", "Libraries holding an object", findObj));
    ObjectNode find = mapper.createObjectNode();
    find.put("type", "object");
    objectsProperty(find.putObject("properties"));
    tools.add(tool("find_source", "Where objects were compiled from", find));
    ObjectNode info = mapper.createObjectNode();
    info.put("type", "object");
    info.putObject("properties").putObject("object").put("type", "string");
    tools.add(tool("object_info", "An object's create, change and source timestamps (for compare)", info));
    ObjectNode export = mapper.createObjectNode();
    export.put("type", "object");
    export.putObject("properties").putObject("from").put("type", "string");
    tools.add(tool("export_rows", "Rows of a table, typed, for copy_rows on the build system", export));
    ObjectNode status = mapper.createObjectNode();
    status.put("type", "object");
    status.putObject("properties");
    tools.add(tool("status", "Version, user and the libraries read", status));
    return result;
  }

  private void objectsProperty(ObjectNode props) {
    ObjectNode objects = props.putObject("objects");
    objects.put("type", "array");
    objects.putObject("items").put("type", "string");
    objects.put("description", "Objects: NAME (looked up on the library list) or LIB/NAME, optionally followed "
        + "by the type, e.g. \"CUSTSRV *SRVPGM\" as the build report's external list names them");
  }

  private ObjectNode buildSchema() {
    ObjectNode schema = selectionSchema();
    ((ObjectNode) schema.get("properties")).putObject("wait").put("type", "integer").put("description",
        "Seconds to wait for the build to end (default " + DEFAULT_BUILD_WAIT + ", up to " + MAX_WAIT + "; 0 returns the "
        + "buildId at once). A build still running after that keeps running: follow it with build_status");
    return schema;
  }

  /* build_status, whats_running, cancel: never queued behind a build */
  private void buildTools(ArrayNode tools) {
    ObjectNode status = mapper.createObjectNode();
    status.put("type", "object");
    ObjectNode statusProps = status.putObject("properties");
    statusProps.putObject("buildId").put("type", "string").put("description", "From build (default: the latest build)");
    statusProps.putObject("wait").put("type", "integer").put("description", "Seconds to wait for it to end (default 0, up to "
        + MAX_WAIT + ")");
    tools.add(tool("build_status",
        "Where a build is: running (stage, target, the command running and for how many seconds, its timeout, the job's "
        + "last joblog lines) or ended (the whole build report, as build returns it). Answers at once, even during a build. "
        + "Between calls tell the user what runs; a command whose seconds keep growing with the same joblog may be stuck: "
        + "look with whats_running.",
        status));
    ObjectNode running = mapper.createObjectNode();
    running.put("type", "object");
    running.putObject("properties");
    tools.add(tool("whats_running",
        "What the build job is doing now, from a connection of its own (never queued): the build's stage and command, the "
        + "job's status (RUN, MSGW: waits for a reply, LCKW: waits for a lock, DEQW, TIMW, ...), its call stack (newest "
        + "first), the locks it waits for and the jobs holding them, the message it waits on, and its last joblog lines. "
        + "Also says which tool call is using the build job. Reads only.",
        running));
    ObjectNode cancel = mapper.createObjectNode();
    cancel.put("type", "object");
    ObjectNode cancelProps = cancel.putObject("properties");
    cancelProps.putObject("buildId").put("type", "string").put("description", "The build to stop (default: the running one)");
    cancelProps.putObject("confirm").put("type", "boolean").put("description",
        "false (default): show what would be stopped. true: stop it, after the user agreed");
    tools.add(tool("cancel",
        "Stop the running build on the IBM i: the running command is cancelled, and if it is still running after "
        + JobWatch.CANCEL_GRACE_SECONDS + " s its job is ended (ENDJOB *IMMED; MC reconnects at the next call). Nothing "
        + "else of the build runs (no failure hooks); the report says cancelled. First call without confirm and show the user "
        + "what runs; call again with confirm: true only if they agree. Stopping the call in VS Code does not stop the "
        + "build on the IBM i: this does.",
        cancel));
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
    boolean destructive = name.equals("build") || name.equals("clean") || name.equals("call_program") || name.equals("copy_rows")
        || name.equals("upload_file") || name.equals("cancel")
        || name.equals("screen_open") || name.equals("screen_send");
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
    if (readOnlyMode) return callReadOnly(name, args);
    if (ScreenTools.TOOLS.contains(name)) {
      synchronized (screenLock) {
        return ScreenTools.handle(name, args, screenSignOn(), projectDir());
      }
    }
    try {
      switch (name) {
        case "build_status":  return buildStatus(args);
        case "whats_running": return whatsRunning();
        case "cancel":        return cancel(args);
        case "build":         return startBuild(args);
        default:              break;
      }
    } catch (Exception e) {
      logger.error("Tool failed: " + name, e);
      return toolResult(e instanceof IllegalArgumentException ? e.getMessage() : e.toString(), true);
    }
    /* A read for a read-only connection (sql, find_object, find_source with 'connection') does not touch the build
       job: answered during a build too. copy_rows and compare also use the build job: they wait for it */
    BuildProgress running = lastBuild;
    if (running != null && running.isRunning() && READS_ELSEWHERE.contains(name) && !args.path("connection").asText("").trim().isEmpty()
        && router() != null) {
      try {
        ObjectNode ready = args.isObject() ? (ObjectNode) args : mapper.createObjectNode();
        ObjectNode routed;
        synchronized (router) {
          routed = router.handle(name, ready);
        }
        if (routed != null) return routed;
      } catch (Exception e) {
        logger.error("Tool failed: " + name, e);
        return toolResult(e.getMessage(), true);
      }
    }
    /* The build connection: one call at a time, none while a build runs */
    String busy = enterLane(name);
    if (busy != null) return toolResult(busy, true);
    try {
      return callInLane(name, args);
    } finally {
      laneCall = null;
      lane.release();
    }
  }

  /* Waits its turn on the build connection; a message instead while a build runs */
  private String enterLane(String name) throws InterruptedException {
    while (true) {
      BuildProgress running = lastBuild;
      if (running != null && running.isRunning()) return busyMessage(running);
      if (lane.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        running = lastBuild;
        if (running != null && running.isRunning()) {
          lane.release();
          return busyMessage(running);
        }
        laneCall = name;
        laneSince = System.currentTimeMillis();
        return null;
      }
    }
  }

  private static String busyMessage(BuildProgress running) {
    return "Build " + running.id + " is running (" + running.stage + (running.target != null ? ", " + running.target : "")
        + "): this tool uses the build job and waits for it to end. Follow it with build_status, see what the job does "
        + "with whats_running, or stop it with cancel.";
  }

  private ObjectNode callInLane(String name, JsonNode args) throws Exception {
    if (router() != null) {
      ObjectNode ready = args.isObject() ? (ObjectNode) args : mapper.createObjectNode();
      ObjectNode routed;
      synchronized (router) {
        routed = router.handle(name, ready);
      }
      if (routed != null) return routed;
      args = ready;
    }
    try {
      if (signOn != null && !name.equals("status")) SignOnGuard.check(signOn);  // no sign-on after a rejection
      switch (name) {
        case "compare":       return compare(args);
        case "copy_rows":     return copyRows(args);
        case "plan":   return build(args, true, null);
        case "impact": return impact(args);
        case "clean":  return clean(args);
        case "joblog": return joblog();
        case "find_source":   return findSource(args);
        case "sql":           return sql(args);
        case "find_object":   return findObject(args);
        case "find_export":   return findExport(args);
        case "status":        return status();
        case "call_program":  return callProgram(args);
        case "import_source": return importSource(args);
        case "spool":         return spool(args);
        case "upload_file":   return uploadFile(args);
        default:       return toolResult("Unknown tool: " + name, true);
      }
    } catch (Exception e) {
      logger.error("Tool failed: " + name, e);
      if (signOn != null && !(e instanceof SignOnGuard.Blocked) && SignOnGuard.failed(signOn, e)) {
        disconnect();
        try {
          SignOnGuard.check(signOn);
        } catch (SignOnGuard.Blocked blocked) {
          return toolResult(blocked.getMessage(), true);
        }
      }
      if (e instanceof SignOnGuard.Blocked) return toolResult(e.getMessage(), true);
      String message = e instanceof CompilerException ? ((CompilerException) e).getFullContext() : e.toString();
      return toolResult(message, true);
    }
  }

  /* MC on the PC (not on the IBM i, where the SSH proxy routes instead): the router for read-only connections */
  private ReadOnlyConnections.Router router() {
    if (router != null || parser == null || IBMiDotEnv.isIBMi()) return router;
    String connected = null;
    try {
      Code4iConfig code4i = MasterCompiler.code4i(parser);
      if (code4i != null) connected = code4i.name;
    } catch (Exception unknown) { /* no Code for IBM i connection: not checked */ }
    SignOnGuard.project(projectDir());
    router = new ReadOnlyConnections.Router(projectDir(), false, connected);
    return router;
  }

  private ObjectNode callReadOnly(String name, JsonNode args) {
    try {
      if (signOn != null && !name.equals("status")) SignOnGuard.check(signOn);
      switch (name) {
        case "sql":          return sql(args);
        case "find_object":  return findObject(args);
        case "find_source":  return findSource(args);
        case "status":       return status();
        case "object_info":
          connect();
          return toolResult(pretty.writeValueAsString(new SqlTools().objectInfo(connection,
              args.path("object").asText(""), args.path("type").asText(null))), false);
        case "export_rows": {
          connect();
          java.util.Map<String, String> set = new java.util.LinkedHashMap<String, String>();
          java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = args.path("set").fields();
          while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> f = fields.next();
            set.put(f.getKey(), f.getValue().asText());
          }
          return toolResult(mapper.writeValueAsString(new SqlTools(maxRows, mask, readLibs).exportRows(connection,
              args.path("from").asText(""), args.path("where").asText(null), set, args.path("maxRows").asInt(0), readLibs)), false);
        }
        default: return toolResult(name + " does not run on a read-only connection (it reads only " + readLibs + ")", true);
      }
    } catch (Exception e) {
      logger.error("Tool failed: " + name, e);
      if (signOn != null && !(e instanceof SignOnGuard.Blocked) && SignOnGuard.failed(signOn, e)) disconnect();
      return toolResult(e instanceof java.sql.SQLException && SignOnGuard.authCode(e) == null
          ? "SQL error " + ((java.sql.SQLException) e).getSQLState() + ": " + e.getMessage() : e.getMessage(), true);
    }
  }

  /* compare: this system's view of the object next to the read-only connection's (otherInfo, from the router) */
  private ObjectNode compare(JsonNode args) throws Exception {
    String object = args.path("object").asText("").trim();
    if (object.isEmpty()) return toolResult("Give the 'object'", true);
    if (!args.has("otherInfo")) {
      return toolResult("compare runs from MasterCompiler on the PC (the mastercompiler MCP server in VS Code), which "
          + "reads the other system; it needs connections.readOnly in the spec", true);
    }
    connectWithLibraryList();
    JsonNode here = new SqlTools().objectInfo(connection, object, args.path("type").asText(null));
    return toolResult(pretty.writeValueAsString(SqlTools.compare(args.path("buildConnection").asText("build"), here,
        args.path("otherConnection").asText("other"), args.get("otherInfo"))), false);
  }

  /* build: started on a thread of its own (it holds the lane until it ends); waits up to 'wait' seconds for it */
  private ObjectNode startBuild(JsonNode args) throws Exception {
    String busy = enterLane("build");
    if (busy != null) return toolResult(busy, true);
    BuildProgress progress;
    try {
      if (signOn != null) SignOnGuard.check(signOn);  // no sign-on after a rejection
      connect();
      progress = new BuildProgress("b" + Long.toString(System.currentTimeMillis() % 100_000_000L, 36));
      progress.jobName = buildJob;
      builds.put(progress.id, progress);
      lastBuild = progress;
      laneCall = "build " + progress.id;
    } catch (Exception e) {
      lane.release();
      laneCall = null;
      logger.error("Tool failed: build", e);
      if (signOn != null && !(e instanceof SignOnGuard.Blocked) && SignOnGuard.failed(signOn, e)) disconnect();
      return toolResult(e instanceof CompilerException ? ((CompilerException) e).getFullContext() : e.getMessage(), true);
    }
    final BuildProgress running = progress;
    Thread thread = new Thread(() -> {
      try {
        running.result = build(args, false, running);  // before finish: a waiting call reads it once the state changes
        BuildReport report = running.report;
        running.finish(running.cancelRequested ? BuildProgress.CANCELLED
            : report != null && report.success ? BuildProgress.SUCCEEDED : BuildProgress.FAILED, report, null);
      } catch (Throwable e) {
        logger.error("Build " + running.id + " failed", e);
        running.finish(running.cancelRequested ? BuildProgress.CANCELLED : BuildProgress.FAILED, null,
            e instanceof CompilerException ? ((CompilerException) e).getFullContext() : e.toString());
      } finally {
        if (running.jobEnded) connectionGone = true;
        laneCall = null;
        lane.release();
      }
    }, "mc-build-" + running.id);
    thread.setDaemon(true);
    thread.start();
    return awaitBuild(running, seconds(args, DEFAULT_BUILD_WAIT));
  }

  private static int seconds(JsonNode args, int fallback) {
    int wait = args.path("wait").isNumber() || args.path("wait").isTextual() ? args.path("wait").asInt(fallback) : fallback;
    return Math.max(0, Math.min(wait, MAX_WAIT));
  }

  /* The report when it ended within 'wait' seconds; else where it is now */
  private ObjectNode awaitBuild(BuildProgress build, int wait) throws Exception {
    long until = System.currentTimeMillis() + wait * 1000L;
    while (build.isRunning() && System.currentTimeMillis() < until) Thread.sleep(200);
    if (!build.isRunning()) {
      if (build.result != null) return build.result;
      ObjectNode ended = build.toJson();
      ended.put("error", build.error);
      return toolResult(pretty.writeValueAsString(ended), true);
    }
    ObjectNode now = build.toJson();
    JobWatch watch = jobWatch;
    if (watch != null && build.jobName != null) now.set("joblogTail", watch.joblogTail(build.jobName, 8));
    now.put("next", "Still running on the IBM i. Tell the user what runs now (stage, target, command, forSeconds), then call "
        + "build_status with this buildId (wait up to " + MAX_WAIT + " s per call). If forSeconds keeps growing and the "
        + "joblog does not move, look with whats_running; stop it with cancel only after the user agreed.");
    return toolResult(pretty.writeValueAsString(now), false);
  }

  private BuildProgress findBuild(JsonNode args) {
    String id = args.path("buildId").asText("").trim();
    if (id.isEmpty()) return lastBuild;
    BuildProgress build = builds.get(id);
    if (build == null) throw new IllegalArgumentException("No build " + id + " in this server (it keeps the last 10; a "
        + "restarted server forgets them)");
    return build;
  }

  private ObjectNode buildStatus(JsonNode args) throws Exception {
    BuildProgress build = findBuild(args);
    if (build == null) return toolResult("No build has run in this server yet", false);
    return awaitBuild(build, seconds(args, 0));
  }

  private ObjectNode whatsRunning() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    BuildProgress build = lastBuild;
    if (build != null && build.isRunning()) result.set("build", build.toJson());
    else result.put("build", build == null ? "none run yet" : "none running (the last, " + build.id + ", " + build.state + ")");
    String call = laneCall;
    if (call != null) {
      result.put("callUsingBuildJob", call);
      result.put("forSeconds", (System.currentTimeMillis() - laneSince) / 1000);
    }
    JobWatch watch = jobWatch;
    if (watch == null || buildJob == null) {
      result.put("job", "not connected yet: nothing runs on the IBM i");
    } else {
      result.set("job", watch.inspect(buildJob));
    }
    return toolResult(pretty.writeValueAsString(result), false);
  }

  private ObjectNode cancel(JsonNode args) throws Exception {
    BuildProgress build = findBuild(args);
    if (build == null || !build.isRunning()) {
      return toolResult("No build is running" + (build != null ? " (the last, " + build.id + ", " + build.state + ")" : "")
          + ": nothing to cancel", false);
    }
    ObjectNode result = mapper.createObjectNode();
    result.set("build", build.toJson());
    JobWatch watch = jobWatch;
    if (!args.path("confirm").asBoolean(false)) {
      if (watch != null && build.jobName != null) {
        ObjectNode job = watch.inspect(build.jobName);
        result.put("jobStatus", job.path("status").asText(null));
        if (job.has("waitingFor")) result.set("waitingFor", job.get("waitingFor"));
      }
      result.put("next", "Nothing was stopped. Show this to the user; if they agree, call cancel again with confirm: true. "
          + "It cancels the running command, ends job " + build.jobName + " (ENDJOB *IMMED) if it is still running after "
          + JobWatch.CANCEL_GRACE_SECONDS + " s, and builds nothing more (no failure hooks).");
      return toolResult(pretty.writeValueAsString(result), false);
    }
    build.cancelRequested = true;
    build.cancelSteps = "requested";
    if (build.commandRunning() && watch != null && build.jobName != null) {
      java.sql.Statement running = build.statement;
      build.stopping = true;
      try {
        build.cancelSteps = watch.stop(build.jobName, running, () -> build.statement == running);
        if (JobWatch.jobEnded(build.cancelSteps)) {
          build.jobEnded = true;
          connectionGone = true;
        }
      } finally {
        build.stopping = false;
      }
    } else {
      build.cancelSteps = "stopped between commands";
    }
    long until = System.currentTimeMillis() + 15_000;
    while (build.isRunning() && System.currentTimeMillis() < until) Thread.sleep(200);
    result.set("build", build.toJson());
    result.put("stopped", build.cancelSteps);
    result.put("next", build.isRunning() ? "Still ending: call build_status with this buildId"
        : "Stopped. build_status with this buildId returns its report");
    return toolResult(pretty.writeValueAsString(result), false);
  }

  private ObjectNode build(JsonNode args, boolean dryRun, BuildProgress progress) throws Exception {
    connect();
    BuildSpec spec = loadSpec();

    MasterCompiler compiler = new MasterCompiler(system, connection, spec, dryRun,
        parser.isDebug(), parser.isVerbose(), false, false, parser.isNoMigrate());
    compiler.setCollectReport(true);
    compiler.setProgress(progress, jobWatch);
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
    if (progress == null) return toolResult(pretty.writeValueAsString(report), !report.success);
    progress.report = report;
    ObjectNode text = mapper.createObjectNode();
    text.put("buildId", progress.id);
    text.put("elapsedSeconds", (System.currentTimeMillis() - progress.started) / 1000);
    text.setAll((ObjectNode) pretty.valueToTree(report));
    return toolResult(pretty.writeValueAsString(text), !report.success);
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
    if (readOnlyMode) return;  // its library list is the libraries it reads
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

  /* The read-only connection for queries, with the build connection's library list */
  private Connection readOnlyConnection() throws Exception {
    List<String> libl = SqlTools.libraryList(connection);
    if (readOnly == null || readOnly.isClosed() || !String.join(" ", libl).equals(readOnlyLibl)) {
      if (readOnly != null) try { readOnly.close(); } catch (Exception ignored) { /* reopened below */ }
      readOnly = SqlTools.readOnlyConnection(system, libl);
      readOnlyLibl = String.join(" ", libl);
    }
    return readOnly;
  }

  private ObjectNode sql(JsonNode args) throws Exception {
    String statement = args.path("statement").asText("").trim();
    if (statement.isEmpty()) return toolResult("Give the query in 'statement'", true);
    try {
      SqlTools.checkReadOnly(statement);
    } catch (IllegalArgumentException e) {
      return toolResult(e.getMessage() + ". The sql tool is read only.", true);
    }
    if (readOnlyMode) {
      try {
        SqlTools.checkLibraries(statement, readLibs);
      } catch (IllegalArgumentException e) {
        return toolResult(e.getMessage(), true);
      }
    }
    connectWithLibraryList();
    readOnlyConnection();
    try {
      if (ResultFiles.wanted(args)) {
        java.util.Map<String, String> set = new java.util.LinkedHashMap<String, String>();
        for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = args.path("set").fields(); it.hasNext();) {
          java.util.Map.Entry<String, JsonNode> e = it.next();
          set.put(e.getKey(), e.getValue().asText());
        }
        ObjectNode full = new SqlTools(maxRows, mask, readOnlyMode ? readLibs : null).queryForFile(readOnly, statement, args.path("maxRows").asInt(0), set,
            "inserts".equalsIgnoreCase(args.path("format").asText()));
        ObjectNode result = toolResult(pretty.writeValueAsString(full), false);
        /* MC on the PC writes the file here; on the IBM i (behind SSH) or as a read-only server, the PC side does */
        return readOnlyMode || IBMiDotEnv.isIBMi() ? result : ResultFiles.finish(args, result, projectDir());
      }
      ObjectNode result = new SqlTools(maxRows, mask, readOnlyMode ? readLibs : null).query(readOnly, statement, args.path("maxRows").asInt(0), args.path("maxColumns").asInt(0));
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

  private ObjectNode status() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    result.put("version", MasterCompiler.version());
    if (readOnlyMode) {
      try {
        connect();
      } catch (Exception e) {
        result.put("readOnly", true);
        result.put("error", e.getMessage());
        return toolResult(pretty.writeValueAsString(result), false);
      }
      result.put("readOnly", true);
      result.put("user", system.getUserId());
      result.put("system", system.getSystemName());
      result.put("libraries", String.join(" ", readLibs));
      if (!mask.isEmpty()) result.put("maskColumns", String.join(" ", mask));
      return toolResult(pretty.writeValueAsString(result), false);
    }
    result.put("projectFolder", projectDir().getAbsolutePath());
    result.put("spec", specName());
    ArrayNode prot = result.putArray("protectedLibs");
    for (String lib : protectedLibs()) prot.add(lib);
    if (signOn == null) signOn = buildSignOn();
    if (signOn != null) SignOnGuard.known(signOn);
    /* Not connected (a rejected password, a network error): status still answers, with why */
    try {
      connectWithLibraryList();
      result.put("user", system.getUserId());
      result.put("system", system.getSystemName());
      result.put("currentLibrary", currentLibrary());
      result.put("libraryList", String.join(" ", SqlTools.libraryList(connection)));
    } catch (Exception e) {
      result.put("error", e.getMessage());
    }
    if (router() != null) result.set("connections", router.connectionsStatus());
    return toolResult(pretty.writeValueAsString(result), false);
  }

  /* copy_rows: the rows the router read on the other system, inserted into the current library */
  private ObjectNode copyRows(JsonNode args) throws Exception {
    if (!args.has("fetched")) {
      return toolResult("copy_rows runs from MasterCompiler on the PC (the mastercompiler MCP server in VS Code), which "
          + "reads the other system; it needs connections.readOnly in the spec", true);
    }
    connectWithLibraryList();
    String from = args.path("from").asText("").trim();
    String to = args.path("to").asText("").trim();
    if (to.isEmpty()) to = from.substring(from.replace('.', '/').lastIndexOf('/') + 1);
    String target = RowCopier.target(to, currentLibrary(), protectedLibs());
    boolean confirm = args.path("confirm").asBoolean(false);
    try (Connection writer = confirm ? RowCopier.writeConnection(system, SqlTools.libraryList(connection)) : null) {
      ObjectNode result = RowCopier.copy(args.get("fetched"), args.path("fromConnection").asText(), target,
          args.path("deleteWhere").asText(null), connection, writer);
      return toolResult(pretty.writeValueAsString(result), false);
    }
  }

  private List<String> protectedLibs() {
    try {
      return loadSpec().protectedLibs;
    } catch (Exception noSpec) {
      return new java.util.ArrayList<String>();
    }
  }

  private ObjectNode callProgram(JsonNode args) throws Exception {
    String requested = args.path("program").asText("").trim().toUpperCase();
    if (requested.isEmpty()) return toolResult("Give the 'program'", true);
    connectWithLibraryList();
    String curlib = currentLibrary();
    String library = requested.contains("/") ? requested.substring(0, requested.indexOf('/')) : curlib;
    String program = requested.substring(requested.indexOf('/') + 1);
    if (!library.equalsIgnoreCase(curlib)) {
      return toolResult("call_program runs only programs in the current library (" + curlib + "), not " + library + ".", true);
    }

    /* A program the project builds, so its source can be checked */
    BuildSpec spec = loadSpec();
    TargetKey target = null;
    for (TargetKey key : spec.targets.keySet()) {
      if (key.getObjectName().equalsIgnoreCase(program) && key.getObjectTypeEnum() == CompilationPattern.ObjectType.PGM) target = key;
    }
    if (target == null || !target.containsStreamFile()) {
      return toolResult(program + " is not a program this project builds from a source file: call_program only runs those "
          + "(their source is checked for writes to protected libraries first).", true);
    }
    File source = new File(target.getStreamFile());
    if (!source.isAbsolute()) source = new File(spec.getBaseDirectory() != null ? spec.getBaseDirectory() : projectDir().getPath(), target.getStreamFile());

    ObjectNode result = mapper.createObjectNode();
    result.put("program", curlib + "/" + program);
    ArrayNode writes = result.putArray("writes");
    List<String> forbidden = new java.util.ArrayList<String>();
    for (java.util.Map.Entry<String, java.util.TreeSet<String>> file : FileWrites.parse(
        java.nio.file.Files.readAllLines(source.toPath(), java.nio.charset.StandardCharsets.UTF_8)).entrySet()) {
      String name = file.getKey();
      String lib = name.contains("/") ? name.substring(0, name.indexOf('/'))
          : new SqlTools().findObject(connection, name, "*FILE").path("resolvesTo").asText("");
      if (lib.contains("/")) lib = lib.substring(0, lib.indexOf('/'));
      String line = name.substring(name.indexOf('/') + 1) + " " + String.join("/", file.getValue()) + " -> " + (lib.isEmpty() ? "?" : lib);
      writes.add(line);
      final String l = lib;
      if (spec.protectedLibs.stream().anyMatch(p -> p.trim().equalsIgnoreCase(l))) forbidden.add(line);
    }
    if (!forbidden.isEmpty()) {
      return toolResult("Refused: " + program + " can write to a protected library: " + String.join("; ", forbidden), true);
    }
    if (writes.size() > 0 && spec.protectedLibs.isEmpty()) {
      return toolResult("Refused: " + program + " can change files (" + writes + ") and the spec sets no protectedLibs, so "
          + "nothing keeps it from production data. Add protectedLibs: [<production libraries>] to the spec first.", true);
    }
    result.set("parameters", args.path("parameters"));
    /* checkRows: the same queries before and after the call */
    List<RowChanges> checks;
    try {
      checks = RowChanges.parse(args.path("checkRows"));
    } catch (IllegalArgumentException e) {
      return toolResult(e.getMessage(), true);
    }
    SqlTools rowTools = new SqlTools(maxRows, mask, null);
    List<java.util.Map<String, java.util.Map<String, String>>> before = new java.util.ArrayList<java.util.Map<String, java.util.Map<String, String>>>();
    try {
      Connection reader = readOnlyConnection();
      for (RowChanges check : checks) before.add(check.snapshot(rowTools, reader));
    } catch (IllegalArgumentException e) {
      return toolResult("checkRows: " + e.getMessage(), true);
    } catch (java.sql.SQLException e) {
      return toolResult("checkRows: SQL error " + e.getSQLState() + " (" + e.getErrorCode() + "): " + e.getMessage(), true);
    }
    if (!args.path("confirm").asBoolean(false)) {
      if (!checks.isEmpty()) {
        ArrayNode counts = result.putArray("checkRows");
        for (int i = 0; i < checks.size(); i++) counts.addObject().put("sql", checks.get(i).sql).put("rowsNow", before.get(i).size());
      }
      result.put("next", "Nothing ran. Show this to the user; if they agree, call again with confirm: true.");
      return toolResult(pretty.writeValueAsString(result), false);
    }
    ObjectNode ran = ProgramCaller.call(system, curlib, program, args.path("parameters").isArray()
        ? args.path("parameters") : mapper.createArrayNode(), SqlTools.libraryList(connection), curlib);
    ran.set("writes", writes);
    if (!checks.isEmpty()) {
      ArrayNode changes = ran.putArray("rowChanges");
      List<String> masked = new java.util.ArrayList<String>();
      for (String m : mask) masked.add(m.toUpperCase(java.util.Locale.ROOT));
      for (int i = 0; i < checks.size(); i++) {
        try {
          changes.add(checks.get(i).compare(before.get(i), checks.get(i).snapshot(rowTools, readOnlyConnection()), masked));
        } catch (Exception e) {
          changes.addObject().put("sql", checks.get(i).sql).put("error", "Could not read the rows after the call: " + e.getMessage());
        }
      }
    }
    return toolResult(pretty.writeValueAsString(ran), !ran.path("success").asBoolean(false));
  }

  private ObjectNode spool(JsonNode args) throws Exception {
    connect();
    try {
      return toolResult(pretty.writeValueAsString(SpoolFiles.handle(connection, args)), false);
    } catch (IllegalArgumentException e) {
      return toolResult(e.getMessage(), true);
    }
  }

  /* The file is on the PC: MC on the PC uploads (over SSH the proxy does, before this server sees the call) */
  private ObjectNode uploadFile(JsonNode args) throws Exception {
    if (IBMiDotEnv.isIBMi()) {
      return toolResult("upload_file runs where the project's files are: MasterCompiler on the PC (VS Code) uploads them", true);
    }
    connect();
    try {
      return toolResult(pretty.writeValueAsString(FileUpload.handle(args, projectDir(), FileUpload.over(system))), false);
    } catch (IllegalArgumentException e) {
      return toolResult(e.getMessage(), true);
    }
  }

  private ObjectNode findExport(JsonNode args) throws Exception {
    String symbol = args.path("symbol").asText("").trim();
    String serviceProgram = args.path("serviceProgram").asText("").trim();
    if (symbol.isEmpty() && serviceProgram.isEmpty()) return toolResult("Give the 'symbol' (or a 'serviceProgram' to list)", true);
    connectWithLibraryList();
    if (symbol.isEmpty()) {
      try {
        return toolResult(pretty.writeValueAsString(new SqlTools().exportsOf(connection, serviceProgram)), false);
      } catch (IllegalArgumentException e) {
        return toolResult(e.getMessage(), true);
      }
    }
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
    importer.setKeepExisting(intoRepo && !args.path("refresh").asBoolean(false));
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
    /* A note shared by several files is given once (notes) and referred to by number */
    java.util.Map<String, Integer> noteCount = new java.util.HashMap<String, Integer>();
    for (LibraryImporter.ImportedMember file : files) if (file.note != null) noteCount.merge(file.note, 1, Integer::sum);
    ArrayNode notes = mapper.createArrayNode();
    java.util.Map<String, Integer> noteIndex = new java.util.HashMap<String, Integer>();
    ArrayNode fileList = mapper.createArrayNode();
    for (LibraryImporter.ImportedMember file : files) {
      if ("copybook".equals(file.how)) copybooksFound++;
      ObjectNode entry = pretty.valueToTree(file);
      if (file.note != null && noteCount.get(file.note) > 1) {
        if (!noteIndex.containsKey(file.note)) {
          noteIndex.put(file.note, notes.size() + 1);
          notes.add(file.note);
        }
        entry.put("note", "see notes[" + noteIndex.get(file.note) + "]");
      }
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
    if (notes.size() > 0) result.set("notes", notes);
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
    if (connectionGone) {  // ENDJOB ended the build job: a new one
      connectionGone = false;
      disconnect();
    }
    if (connection != null && !connection.isClosed()) return;
    disconnect();
    if (readOnlyMode) {
      AS400 readSystem = connector != null ? connector.call() : MasterCompiler.connect(parser);
      connection = signedOn(() -> SqlTools.readOnlyConnection(readSystem, readLibs));
      system = readSystem;
      logger.info("Connected read only to {} as {}, reading {}", system.getSystemName(), system.getUserId(), readLibs);
      return;
    }
    /* An MCP server cannot prompt: without credentials, say how to provide them */
    if (!parser.isCode4i() && !IBMiDotEnv.isIBMi() && System.getenv("IBMI_PASSWORD") == null && !new File(".env").isFile()) {
      throw new IllegalArgumentException("No IBM i credentials. In VS Code the password comes from the password prompt "
          + "in .vscode/mcp.json (--setup-vscode writes it): ask the user to run MasterCompiler's setup or start the server "
          + "from VS Code; never ask for the password in the chat or put it in a file. A .env (IBMI_HOSTNAME, "
          + "IBMI_USERNAME, IBMI_PASSWORD) is only for a person running the jar in a terminal or CI.");
    }
    if (signOn == null) signOn = buildSignOn();
    connection = signedOn(() -> {
      system = MasterCompiler.connect(parser);
      return new AS400JDBCDataSource(system).getConnection();
    });
    lastJoblog = new CommandExecutor(connection, false, false, false).getCurrentTime();
    buildJob = JobWatch.jobName(connection);
    jobWatch = new JobWatch(system);
    logger.info("Connected to IBM i as {} (job {})", system.getUserId(), buildJob);
  }

  /* One sign-on attempt through SignOnGuard: none after IBM i rejected the password */
  private <T> T signedOn(java.util.concurrent.Callable<T> attempt) throws Exception {
    return signOn != null ? SignOnGuard.signOn(signOn, attempt) : attempt.call();
  }

  /* The 5250 session signs on as the build connection does: null on the IBM i (no password there) */
  private SignOnGuard.Target screenSignOn() {
    try {
      return signOn != null ? signOn : buildSignOn();
    } catch (Exception e) {
      logger.warn("No sign-on for a 5250 session: {}", e.getMessage());
      return null;
    }
  }

  /* The build connection's sign-on on the PC: the Code for IBM i connection, else .env / IBMI_* */
  private SignOnGuard.Target buildSignOn() throws Exception {
    if (parser == null || IBMiDotEnv.isIBMi()) return null;
    SignOnGuard.project(projectDir());
    Code4iConfig code4i = MasterCompiler.code4i(parser);
    if (code4i != null) {
      return new SignOnGuard.Target(code4i.name, code4i.host, code4i.username, System.getenv("IBMI_PASSWORD"));
    }
    io.github.cdimascio.dotenv.Dotenv env = IBMiDotEnv.getDotEnv();
    return new SignOnGuard.Target(null, env.get("IBMI_HOSTNAME", ""), env.get("IBMI_USERNAME", ""),
        env.get("IBMI_PASSWORD", ""));
  }

  private void disconnect() {
    if (jobWatch != null) jobWatch.close();
    jobWatch = null;
    buildJob = null;
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
