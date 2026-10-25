package com.github.kraudy.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcraft.jsch.ChannelExec;

/*
 * --mcp --ssh --project <local folder>: MCP server on the PC that runs MC on the IBM i over SSH,
 * the same way Code for IBM i reaches the system (port 22, not the host servers).
 *
 *   1. SSH in (Code for IBM i host / port / user; SSH key or IBMI_PASSWORD)
 *   2. upload this jar once per version to <home>/mc/.mc/
 *   3. sync the project's sources to <home>/mc/<folder> (only files whose size or time changed),
 *      tagged UTF-8 (CCSID 1208)
 *   4. start "java -jar MC.jar --mcp --project <remote folder>" there: it builds as the SSH user's own job
 *   5. relay MCP messages; before build / plan / impact, upload what changed. A git "since" becomes an
 *      explicit file list, since the remote copy has no git history.
 * initialize and tools/list are answered here at once while steps 1-4 run in the background.
 */
public class SshMcpProxy {
  private static final Logger logger = LoggerFactory.getLogger(SshMcpProxy.class);
  private static final String REMOTE_JAVA = "/QOpenSys/usr/bin/java";
  static final int TAR_FROM = 10;  // changed files from which one tar beats file by file

  private final ArgParser parser;
  private final ObjectMapper mapper = new ObjectMapper();
  private PrintStream protocolOut;
  private volatile SshTarget ssh;
  private File project;
  private volatile String remoteDir;
  private volatile String host;  // set once connected (the home directory may come from the IBM i)
  private volatile ChannelExec remote;
  private final CompletableFuture<OutputStream> remoteReady = new CompletableFuture<OutputStream>();
  private final ExecutorService calls = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "mc-ssh-calls");
    t.setDaemon(true);
    return t;
  });
  private volatile boolean closing;
  private final ExecutorService screenCalls = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "mc-ssh-screen");
    t.setDaemon(true);
    return t;
  });
  private ReadOnlyConnections.Router router;  // calls for the spec's read-only connections
  private SignOnGuard.Target buildTarget;      // the build connection's sign-on
  private final Set<String> pendingImports = java.util.concurrent.ConcurrentHashMap.newKeySet();  // import_source call ids  // VS Code closed the session: the remote end stopping is expected

  public SshMcpProxy(ArgParser parser) {
    this.parser = parser;
  }

  public void serve() throws Exception {
    protocolOut = new PrintStream(System.out, true, "UTF-8");
    System.setOut(System.err);  // stdout is the protocol channel
    MasterCompiler.quietLogs(parser.isVerbose());

    Code4iConfig code4i = MasterCompiler.code4i(parser);
    configure(code4i);
    router = new ReadOnlyConnections.Router(project, true, code4i != null ? code4i.name : null);
    SignOnGuard.known(buildTarget);

    /*
     * initialize and tools/list are answered here right away; connecting, the first jar upload and the
     * first sync run meanwhile, so a slow link does not hit VS Code's start timeout. Tool calls wait for them.
     */
    Thread setup = new Thread(() -> {
      try {
        remoteReady.complete(startRemote(code4i));
      } catch (Throwable e) {
        logger.error("Could not start MasterCompiler on " + host, e);
        remoteReady.completeExceptionally(e);
      }
    }, "mc-ssh-setup");
    setup.setDaemon(true);
    setup.start();

    McpServer local = new McpServer(parser);
    SecretPrompt.sender(this::send);
    ScreenTools.systemCcsid(this::remoteScreenCcsid);
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    try {
      while ((line = in.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        JsonNode request;
        try {
          request = mapper.readTree(line);
        } catch (Exception e) {
          logger.error("Not a JSON-RPC message, ignored: {}", line);
          continue;
        }
        if (SecretPrompt.answer(request)) continue;  // the client's answer to a 5250 secret request from here
        JsonNode id = request.get("id");
        if (id == null) continue;  // notifications: the remote server ignores them as well
        JsonNode result = local.localResult(request.path("method").asText(""), request.path("params"));
        /* status before MC runs on the IBM i: answered here, with the stage it is at */
        if (result == null && "tools/call".equals(request.path("method").asText(""))
            && "status".equals(request.path("params").path("name").asText(""))
            && (!remoteReady.isDone() || remoteReady.isCompletedExceptionally())) {
          sendResult(id, statusHere());
          continue;
        }
        if ("tools/call".equals(request.path("method").asText(""))
            && "status".equals(request.path("params").path("name").asText(""))) {
          pendingStatus.add(id.toString());
        }
        if (result != null) {
          sendResult(id, result);
          continue;
        }
        String tool = "tools/call".equals(request.path("method").asText("")) ? request.path("params").path("name").asText("") : "";
        /* build_status, whats_running, cancel: straight to the IBM i, never behind a sync or another call */
        if (McpServer.NEVER_QUEUED.contains(tool)) {
          if (!remoteReady.isDone() || remoteReady.isCompletedExceptionally()) {
            sendToolError(line, "MasterCompiler is not running on the IBM i yet (stage " + stage + "): no build runs");
            continue;
          }
          if (tool.equals("cancel") && queuedBuilds.get() > 0 && remoteBuild == null) {
            boolean confirm = request.path("params").path("arguments").path("confirm").asBoolean(false);
            if (confirm) dropQueuedBuilds = true;
            ObjectNode answer = mapper.createObjectNode();
            answer.putArray("content").addObject().put("type", "text").put("text", confirm
                ? "The build had not started on the IBM i yet (its sources were being uploaded): it will not start."
                : "A build is waiting for its sources to be uploaded and has not started on the IBM i. Show this to the "
                  + "user; with confirm: true it will not start.");
            answer.put("isError", false);
            sendResult(id, answer);
            continue;
          }
          if (!tool.equals("whats_running")) buildCalls.add(id.toString());
          try {
            writeRemote(remoteReady.get(), line);
          } catch (Exception e) {
            sendToolError(line, "Lost the connection to MasterCompiler on the IBM i: " + e.getMessage());
          }
          continue;
        }
        /* 5250 tools on a thread of their own: one waiting for the user (a PASSCODE) does not hold up the rest */
        if (ScreenTools.TOOLS.contains(tool)) {
          String screenCall = line;
          screenCalls.execute(() -> forward(screenCall));
          continue;
        }
        if (tool.equals("build")) {
          buildCalls.add(id.toString());
          queuedBuilds.incrementAndGet();
        }
        String call = line;
        calls.execute(() -> forward(call));  // one at a time, in order
      }
    } finally {
      closing = true;
      if (remote != null) remote.disconnect();
      if (ssh != null) ssh.close();
      router.close();
      ScreenTools.closeAll();
    }
  }

  private volatile String user;
  private volatile int port;
  private volatile String remoteHome;
  private volatile String ownerId;  // the hash in .mc/local-project of this project's copy

  /* Who and where: the Code for IBM i connection, else IBMI_HOSTNAME / IBMI_USERNAME (environment or .env); the SSH port rule is in SshTarget.Port */
  private void configure(Code4iConfig code4i) throws Exception {
    host = code4i != null ? code4i.host : required("IBMI_HOSTNAME");
    user = code4i != null ? code4i.username : required("IBMI_USERNAME");
    SshTarget.Port sshPort = SshTarget.Port.of(parser, code4i);
    port = sshPort.number;
    logger.info("SSH port {} ({})", port, sshPort.origin);
    project = new File(parser.getProjectRoot()).getCanonicalFile();
    SignOnGuard.project(project);
    buildTarget = new SignOnGuard.Target(code4i != null ? code4i.name : host, host, user, Passwords.get(user + "@" + host));
  }

  /* Stages 1 to 3: SSH in, MC on the IBM i (this version), the project's sources; returns the remote jar */
  private String prepareRemote(Code4iConfig code4i) throws Exception {
    long start = System.currentTimeMillis();
    stage("1/5 connecting to " + user + "@" + host + ":" + port + " over SSH");
    /* A rejected password: no further try in this run (or at a restart with the same one); the server stays up */
    ssh = SignOnGuard.signOn(buildTarget, () -> SshTarget.connect(host, port, user, buildTarget.password,
        code4i != null ? code4i.privateKeyPath : null));
    /* Home: Code for IBM i's when it has one, else the SSH user's own */
    String home = code4i != null && code4i.homeKnown ? code4i.homeDirectory : ssh.exec("echo $HOME").trim();
    home = home.startsWith("/") ? trimSlash(home) : "/home/" + user.toUpperCase();
    remoteHome = home;
    if (parser.getPush() != null) {
      remoteDir = trimSlash(parser.getPush());
    } else {
      /* MC's own folders are private to the SSH user */
      ssh.privateDirs(true);
      ssh.exec("umask 077 && mkdir -p " + SshTarget.quote(home + "/mc/.mc") + " && chmod 700 "
          + SshTarget.quote(home + "/mc") + " " + SshTarget.quote(home + "/mc/.mc"));
      remoteDir = ownDirectory(home);
    }

    stage("2/5 checking MasterCompiler on the IBM i");
    String jar = ensureRemoteJar(ssh, home);
    long jarDone = System.currentTimeMillis();
    stage("3/5 syncing the sources to " + remoteDir);
    int uploaded = sync(projectFiles(), true);
    logger.info("Sources synced: {} files uploaded ({} s for MC, {} s for sources)", uploaded,
        (jarDone - start) / 1000, (System.currentTimeMillis() - jarDone) / 1000);
    return jar;
  }

  /*
   * <home>/mc/<project folder>, unless another project with that folder name already owns it there (the marker
   * .mc/local-project holds a hash of the owner's path): two projects must not mirror over each other
   */
  private String ownDirectory(String home) throws Exception {
    String base = home + "/mc/" + project.getName();
    String id = sha256(project.getCanonicalPath().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    ownerId = id;
    String marker = "/.mc/local-project";
    String owner = ssh.execQuiet("cat " + SshTarget.quote(base + marker));  // null: no marker yet
    String dir = base;
    if (owner != null && !owner.trim().isEmpty() && !owner.trim().equals(id)) {
      dir = base + "-" + id;
      logger.warn("{} on the IBM i belongs to another project with the same folder name: this one uses {}", base, dir);
      owner = ssh.execQuiet("cat " + SshTarget.quote(dir + marker));
    }
    if (owner == null || !owner.trim().equals(id)) ssh.upload(id.getBytes(StandardCharsets.UTF_8), dir + marker);
    return dir;
  }

  /* SSH in, upload MC when this version is new, sync the sources and start MC there; returns its stdin */
  private OutputStream startRemote(Code4iConfig code4i) throws Exception {
    long start = System.currentTimeMillis();
    String jar = prepareRemote(code4i);

    stage("4/5 starting MasterCompiler on the IBM i");
    remote = ssh.start(remoteCommand(jar, code4i));
    remote.setErrStream(System.err, true);
    InputStream fromRemote = remote.getInputStream();
    OutputStream toRemote = remote.getOutputStream();
    remote.connect(30_000);
    stage("5/5 ready: MasterCompiler running on " + host + " in " + remoteDir + " ("
        + (System.currentTimeMillis() - start) / 1000 + " s)");

    Thread pump = new Thread(() -> relayResponses(fromRemote), "mc-ssh-responses");
    pump.setDaemon(true);
    pump.start();
    return toRemote;
  }

  /* --remote: the same start-up, then MC builds on the IBM i and its output comes back here; returns its exit code */
  public int build() throws Exception {
    Code4iConfig code4i = MasterCompiler.code4i(parser);
    configure(code4i);
    try {
      String jar = prepareRemote(code4i);
      stage("4/5 building on the IBM i");
      List<String> args = parser.remoteArgs(remoteDir);
      args.addAll(libraryArgs(code4i, false));
      /* The spec may name this PC's project folder (a scanned CHGCURDIR): MC there points it at the uploaded copy */
      args.add("--local-project");
      args.add(project.getPath());
      /* The build report comes back for the summary (and for --json); a file in MC's private folder, deleted after */
      byte[] random = new byte[8];
      new java.security.SecureRandom().nextBytes(random);
      StringBuilder id = new StringBuilder();
      for (byte b : random) id.append(String.format("%02x", b));
      String reportDir = remoteHome + "/mc/.mc";
      ssh.exec("umask 077 && mkdir -p " + SshTarget.quote(reportDir));
      String remoteReport = reportDir + "/report-" + id + ".json";
      args.add("--json");
      args.add(remoteReport);
      StringBuilder command = new StringBuilder(startCommand(jar));
      for (String arg : args) command.append(' ').append(SshTarget.quote(arg));
      ChannelExec channel = ssh.start(command.toString());
      channel.setOutputStream(System.out, true);
      channel.setErrStream(System.err, true);
      channel.connect(30_000);
      Thread stop = new Thread(channel::disconnect, "mc-ssh-stop");
      Runtime.getRuntime().addShutdownHook(stop);  // Ctrl+C here ends the build there too
      while (!channel.isClosed()) Thread.sleep(100);
      try { Runtime.getRuntime().removeShutdownHook(stop); } catch (IllegalStateException shuttingDown) { /* too late: fine */ }
      int status = channel.getExitStatus();
      channel.disconnect();
      status = status < 0 ? 1 : status;
      if (!summarize(remoteReport, status) && parser.getJsonReport() != null) {
        logger.error("--json: the build report could not be copied from the IBM i, so {} is not written", parser.getJsonReport());
        if (status == 0) status = 1;
      }
      if (parser.isCleanRemote()) {
        if (status == 0) cleanRemote(jar);
        else logger.warn("--clean-remote: the build failed, so {} and the MC jar stay on the IBM i for inspection", remoteDir);
      }
      return status;
    } finally {
      if (ssh != null) ssh.close();
    }
  }

  /* The report MC wrote on the IBM i: to --json when given, and a short summary of the result printed last */
  private boolean summarize(String remoteReport, int status) {
    File local = null;
    try {
      local = parser.getJsonReport() != null ? new File(parser.getJsonReport()) : File.createTempFile("mc-report", ".json");
      ssh.download(remoteReport, local);
      System.out.println(RemoteSummary.format(mapper.readTree(local), status, host));
      return true;
    } catch (Exception e) {
      System.out.println("Build summary: no report came back from the IBM i (" + e.getClass().getSimpleName()
          + "); the exit code is " + status);
      logger.debug("Remote report not read", e);
      return false;
    } finally {
      if (local != null && parser.getJsonReport() == null) local.delete();
      ssh.execQuiet("rm -f " + SshTarget.quote(remoteReport));
    }
  }

  /*
   * --clean-remote after a successful build: the uploaded project copy and this MC version's jar. Only MC's own copy
   * is deleted: never a --push folder, and only when its real path (symbolic links resolved) is below <home>/mc and its
   * marker .mc/local-project holds this project's id. The next --remote uploads again.
   */
  private void cleanRemote(String jar) {
    try {
      String mc = ssh.realPath(remoteHome + "/mc");
      String real = ssh.realPath(remoteDir);
      String marker = ssh.execQuiet("cat " + SshTarget.quote(remoteDir + "/.mc/local-project"));
      if (parser.getPush() != null || mc == null || real == null || !real.startsWith(mc + "/") || real.length() <= mc.length() + 1
          || marker == null || !marker.trim().equals(ownerId)) {
        logger.warn("--clean-remote: {} is not an MC copy of this project (inside {}/mc, with its marker), so it stays", remoteDir, remoteHome);
      } else {
        ssh.exec("rm -rf " + SshTarget.quote(real));
        SyncManifest manifest = SyncManifest.load(project, host + ":" + remoteDir);
        manifest.clear();
        manifest.save();
        logger.info("--clean-remote: {} deleted from the IBM i", remoteDir);
      }
      ssh.exec("rm -f " + SshTarget.quote(jar));
      ssh.execQuiet("rmdir " + SshTarget.quote(remoteHome + "/mc/.mc") + " " + SshTarget.quote(remoteHome + "/mc"));  // only when empty
      logger.info("--clean-remote: MC's jar deleted from the IBM i");
    } catch (Exception e) {
      logger.warn("--clean-remote did not finish: {}", e.getMessage());
    }
  }

  /* Start-up stage, logged and kept for tool calls that wait or fail */
  private volatile String stage = "not started";

  private void stage(String now) {
    stage = now;
    logger.info("MC start-up {}", now);
  }

  /* A call the remote MC answers: wait until it runs, upload what changed, pass it on */
  private void forward(String line) {
    boolean build = isBuild(line);
    try {
      forwardCall(line);
    } finally {
      if (build && queuedBuilds.decrementAndGet() == 0) dropQueuedBuilds = false;
    }
  }

  private boolean isBuild(String line) {
    try {
      JsonNode request = mapper.readTree(line);
      return "tools/call".equals(request.path("method").asText()) && "build".equals(request.path("params").path("name").asText());
    } catch (Exception e) {
      return false;
    }
  }

  /* Builds waiting here for the upload; a cancel meanwhile drops them */
  private final java.util.concurrent.atomic.AtomicInteger queuedBuilds = new java.util.concurrent.atomic.AtomicInteger();
  private volatile boolean dropQueuedBuilds;

  private void forwardCall(String line) {
    line = routed(line);
    if (line == null) return;  // answered by a read-only connection
    OutputStream toRemote;
    try {
      if (!remoteReady.isDone()) logger.info("Tool call waiting for the start-up, now at stage {}", stage);
      toRemote = remoteReady.get();
    } catch (Exception e) {
      Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
      sendToolError(line, "Could not start MasterCompiler on the IBM i (stage " + stage + "): " + cause.getMessage());
      return;
    }
    String forward = beforeForwarding(line);
    if (forward != null && dropQueuedBuilds && isBuild(forward)) {
      sendToolError(line, "Cancelled before it started on the IBM i: nothing was built");
      forward = null;
    }
    if (forward == null) {  // answered here (sync failed, a build running, cancelled)
      try {
        buildCalls.remove(mapper.readTree(line).path("id").toString());
      } catch (Exception ignored) { /* not JSON: no id kept */ }
      return;
    }
    try {
      writeRemote(toRemote, forward);
    } catch (Exception e) {
      sendToolError(line, "Lost the connection to MasterCompiler on the IBM i: " + e.getMessage());
    }
  }

  private final Object writing = new Object();

  private void writeRemote(OutputStream toRemote, String line) throws java.io.IOException {
    synchronized (writing) {
      toRemote.write((line + "\n").getBytes(StandardCharsets.UTF_8));
      toRemote.flush();
    }
  }

  /*
   * The build running on the IBM i, from the build / build_status / cancel answers. While it runs, calls that would
   * sync the sources first (build, plan, impact, call_program, ...) are answered here: a sync would change the
   * sources under the build.
   */
  private final Set<String> buildCalls = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile String remoteBuild;
  private final Map<String, CompletableFuture<JsonNode>> ownCalls = new java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
  private final java.util.concurrent.atomic.AtomicInteger ownIds = new java.util.concurrent.atomic.AtomicInteger();

  void noteBuild(JsonNode response) {
    try {
      JsonNode text = mapper.readTree(response.path("result").path("content").path(0).path("text").asText("{}"));
      if (!text.isObject()) return;
      JsonNode build = text.has("buildId") ? text : text.path("build");
      if (!build.isObject() || !build.has("buildId")) return;
      if ("running".equals(build.path("state").asText())) remoteBuild = build.get("buildId").asText();
      else if (build.get("buildId").asText().equals(remoteBuild)) remoteBuild = null;
    } catch (Exception notJson) { /* an error text: no build state in it */ }
  }

  /* Asks the IBM i (build_status, not seen by VS Code) whether the build it ran last is still running */
  private boolean remoteBuildRunning() throws Exception {
    String id = remoteBuild;
    if (id == null) return false;
    String own = "mc-proxy-" + ownIds.incrementAndGet();
    CompletableFuture<JsonNode> answer = new CompletableFuture<JsonNode>();
    ownCalls.put(own, answer);
    ObjectNode request = mapper.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", own);
    request.put("method", "tools/call");
    ObjectNode params = request.putObject("params");
    params.put("name", "build_status");
    params.putObject("arguments").put("buildId", id);
    try {
      writeRemote(remoteReady.get(), mapper.writeValueAsString(request));
      noteBuild(answer.get(60, java.util.concurrent.TimeUnit.SECONDS));
    } catch (java.util.concurrent.TimeoutException e) {
      return true;  // no answer: taken as still running (the caller says so)
    } finally {
      ownCalls.remove(own);
    }
    return remoteBuild != null;
  }

  /* The screens' CCSID from MC on the IBM i (its status says screenCcsid), asked once */
  private volatile Integer screenCcsid;

  private Integer remoteScreenCcsid() {
    if (screenCcsid != null || !remoteReady.isDone() || remoteReady.isCompletedExceptionally()) return screenCcsid;
    String own = "mc-proxy-" + ownIds.incrementAndGet();
    CompletableFuture<JsonNode> answer = new CompletableFuture<JsonNode>();
    ownCalls.put(own, answer);
    try {
      ObjectNode request = mapper.createObjectNode();
      request.put("jsonrpc", "2.0");
      request.put("id", own);
      request.put("method", "tools/call");
      request.putObject("params").put("name", "status").putObject("arguments");
      writeRemote(remoteReady.get(), mapper.writeValueAsString(request));
      JsonNode text = mapper.readTree(answer.get(30, java.util.concurrent.TimeUnit.SECONDS)
          .path("result").path("content").path(0).path("text").asText("{}"));
      if (text.path("screenCcsid").isInt()) screenCcsid = text.get("screenCcsid").asInt();
    } catch (Exception e) {
      logger.info("Could not read the screens' CCSID from the IBM i: {}", e.getMessage());
    } finally {
      ownCalls.remove(own);
    }
    return screenCcsid;
  }

  /* Calls for a read-only connection are answered here; compare gets what the other system holds */
  private String routed(String line) {
    try {
      JsonNode request = mapper.readTree(line);
      if (!"tools/call".equals(request.path("method").asText())) return line;
      ObjectNode params = (ObjectNode) request.path("params");
      if (ScreenTools.TOOLS.contains(params.path("name").asText())) {  // a 5250 session from this PC
        sendResult(request.get("id"), ScreenTools.handle(params.path("name").asText(), params.path("arguments"),
            buildTarget, project));
        return null;
      }
      if ("upload_file".equals(params.path("name").asText())) {  // the file is on this PC: SFTP from here
        sendResult(request.get("id"), upload(params.path("arguments")));
        return null;
      }
      ObjectNode args = params.has("arguments") && params.get("arguments").isObject()
          ? (ObjectNode) params.get("arguments") : params.putObject("arguments");
      ObjectNode answer = router.handle(params.path("name").asText(), args);
      if (answer != null) {
        sendResult(request.get("id"), answer);
        return null;
      }
      return mapper.writeValueAsString(request);
    } catch (Exception e) {
      sendToolError(line, "Could not prepare the call: " + e.getMessage());
      return null;
    }
  }

  /* upload_file over this server's SSH session, once it is up */
  private ObjectNode upload(JsonNode args) {
    ObjectNode result = mapper.createObjectNode();
    String text;
    boolean error = false;
    try {
      remoteReady.get();  // signed on (or refused) first
      text = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(FileUpload.handle(args, project, FileUpload.over(ssh)));
    } catch (ExecutionException e) {
      text = "Could not start MasterCompiler on the IBM i (stage " + stage + "): " + e.getCause().getMessage();
      error = true;
    } catch (Exception e) {
      text = e instanceof IllegalArgumentException ? e.getMessage() : "Upload failed: " + e.getMessage();
      error = true;
    }
    result.putArray("content").addObject().put("type", "text").put("text", text);
    result.put("isError", error);
    return result;
  }

  /* Remote MC answers go straight back to VS Code; when it stops, so does this server */
  private void relayResponses(InputStream fromRemote) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fromRemote, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!ownCalls.isEmpty() || !buildCalls.isEmpty()) {
          JsonNode response = mapper.readTree(line);
          String id = response.path("id").asText();
          CompletableFuture<JsonNode> own = ownCalls.get(id);
          if (own != null || id.startsWith("mc-proxy-")) {  // this server's own build_status (a late one is dropped)
            if (own != null) own.complete(response);
            continue;
          }
          if (buildCalls.remove(response.path("id").toString())) noteBuild(response);
        }
        if (!pendingStatus.isEmpty()) line = afterStatus(line);
        if (!pendingResults.isEmpty()) line = afterResults(line);
        send(pendingImports.isEmpty() ? line : afterImport(line));
      }
    } catch (Exception e) {
      logger.error("Lost the remote MasterCompiler", e);
    }
    if (closing) return;
    if (ssh != null && !ssh.isConnected()) {
      logger.error("Lost the SSH connection to the IBM i: it stopped answering for about 60 s (network, VPN or the "
          + "IBM i itself). Start the mastercompiler server again.");
      System.exit(1);
    }
    /* Usually a start-up problem on the IBM i (Java, the folder, authority): its own messages are just above */
    for (int i = 0; i < 20 && remote != null && !remote.isClosed(); i++) {
      try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
    }
    int status = remote != null ? remote.getExitStatus() : -1;
    logger.error("MasterCompiler stopped on the IBM i (exit status {}) in {}. The lines above, from the IBM i, say why; "
        + "check that {} works there and that you can create {}.", status, remoteDir,
        env("MC_REMOTE_JAVA", REMOTE_JAVA), remoteDir);
    System.exit(1);
  }

  /* Upload what a build / plan / impact call needs; null when this server answered it itself */
  String beforeForwarding(String line) {
    try {
      JsonNode request = mapper.readTree(line);
      if (!"tools/call".equals(request.path("method").asText())) return line;
      String tool = request.path("params").path("name").asText();
      if ((tool.equals("call_program") || tool.equals("debug_program") || tool.equals("find_export") || tool.equals("import_source") || tool.equals("build")
          || tool.equals("plan") || tool.equals("impact")) && remoteBuildRunning()) {
        sendToolError(line, "Build " + remoteBuild + " is running on the IBM i (or did not say within 60 s that it ended): "
            + tool + " would upload the sources under it. Follow it with build_status, see what the job does with "
            + "whats_running, or stop it with cancel.");
        return null;
      }
      if (tool.equals("sql") && ResultFiles.wanted(request.path("params").path("arguments"))) {
        pendingResults.put(request.path("id").toString(), request.path("params").path("arguments"));  // the file is written here
        return line;
      }
      if (tool.equals("call_program") || tool.equals("debug_program") || tool.equals("find_export")) {
        sync(projectFiles(), true);  // they read the sources in the IBM i copy
        return line;
      }
      if (tool.equals("import_source")) {
        sync(projectFiles(), true);  // "repo" keeps files already there: the IBM i copy must be current
        pendingImports.add(request.path("id").toString());
        return line;
      }
      if (!tool.equals("build") && !tool.equals("plan") && !tool.equals("impact")) return line;

      ObjectNode args = request.path("params").has("arguments") && request.path("params").get("arguments").isObject()
          ? (ObjectNode) request.path("params").get("arguments")
          : ((ObjectNode) request.path("params")).putObject("arguments");

      int uploaded;
      if (args.hasNonNull("since")) {
        List<String> changed = relativeToProject(GitChanges.changedFiles(project.getPath(), args.get("since").asText()));
        uploaded = sync(absolute(changed));
        args.remove("since");
        ArrayNode files = args.putArray("files");  // no git on the remote copy: say which files changed
        for (String file : changed) {
          if (new File(project, file).isFile()) files.add(file);
        }
      } else if (args.has("files") && args.get("files").isArray()) {
        /* the whole copy, not only the files named: a changed spec (mc-base.yaml, build.yaml) must go up too */
        uploaded = sync(projectFiles(), true);
      } else {
        uploaded = sync(projectFiles(), true);
      }
      if (uploaded > 0) logger.info("Uploaded {} changed files before {}", uploaded, tool);
      return mapper.writeValueAsString(request);
    } catch (Exception e) {
      logger.error("Could not prepare tool call", e);
      sendToolError(line, ssh != null && !ssh.isConnected()
          ? "The SSH connection to the IBM i was lost (no answer for about 60 s): start the mastercompiler server again. "
            + e.getMessage()
          : "Could not upload the sources to the IBM i: " + e.getMessage());
      return null;
    }
  }

  private final Set<String> pendingStatus = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Map<String, JsonNode> pendingResults = new java.util.concurrent.ConcurrentHashMap<String, JsonNode>();  // sql with format, by call id

  /* sql with format: the IBM i sent the whole result; the file goes into the project on this PC */
  String afterResults(String line) {
    try {
      JsonNode response = mapper.readTree(line);
      JsonNode args = pendingResults.remove(response.path("id").toString());
      if (args == null || !response.path("result").isObject()) return line;
      ((ObjectNode) response).set("result", ResultFiles.finish(args, (ObjectNode) response.get("result"), project));
      return mapper.writeValueAsString(response);
    } catch (Exception e) {
      logger.error("Could not write the results file", e);
      return line;
    }
  }

  /* The PC side of status: version, host, folders, start-up stage */
  private ObjectNode statusFields(ObjectNode into) {
    into.put("pcVersion", MasterCompiler.version());
    into.put("connection", "SSH " + host);
    into.put("startup", stage);
    into.put("localProjectFolder", project != null ? project.getAbsolutePath() : null);
    into.put("ibmiProjectFolder", remoteDir);
    into.set("connections", router.connectionsStatus());
    return into;
  }

  private ObjectNode statusHere() {
    ObjectNode result = mapper.createObjectNode();
    ObjectNode text = statusFields(mapper.createObjectNode());
    String failed = null;
    if (remoteReady.isCompletedExceptionally()) {
      try {
        remoteReady.get();
      } catch (Exception e) {
        failed = (e.getCause() != null ? e.getCause() : e).getMessage();
      }
    }
    text.put("note", failed != null ? "MasterCompiler could not start on the IBM i: " + failed
        : "MasterCompiler is not running on the IBM i yet: the user, current library and library list come once it is");
    result.putArray("content").addObject().put("type", "text").put("text", text.toPrettyString());
    result.put("isError", false);
    return result;
  }

  String afterStatus(String line) {
    try {
      JsonNode response = mapper.readTree(line);
      if (!pendingStatus.remove(response.path("id").toString())) return line;
      ObjectNode content = (ObjectNode) response.path("result").path("content").path(0);
      JsonNode report = mapper.readTree(content.path("text").asText("{}"));
      if (!report.isObject()) return line;
      content.put("text", statusFields((ObjectNode) report).toPrettyString());
      return mapper.writeValueAsString(response);
    } catch (Exception e) {
      return line;
    }
  }

  /* import_source wrote the sources on the IBM i: bring them into the local project before answering */
  String afterImport(String line) {
    try {
      JsonNode response = mapper.readTree(line);
      if (!pendingImports.remove(response.path("id").toString())) return line;
      JsonNode result = response.path("result");
      if (result.path("isError").asBoolean(false)) return line;
      JsonNode report = mapper.readTree(result.path("content").path(0).path("text").asText("{}"));
      if (!report.isObject() || report.path("dryRun").asBoolean(false)) return line;  // a dry run writes and downloads nothing
      /* Written now, plus files kept on the IBM i that this PC does not have (e.g. from a call that failed before) */
      int downloaded = 0;
      for (String list : new String[] { "written", "kept" }) {
        for (JsonNode path : report.path(list)) {
          File local = new File(project, path.asText());
          if (list.equals("kept") && local.isFile()) continue;
          ssh.download(remoteDir + "/" + path.asText(), local);
          downloaded++;
        }
      }
      if (downloaded > 0) logger.info("Downloaded {} imported files into {}", downloaded, project);
      /* Paths in the report are the PC's: that is where the agent reads them */
      ((ObjectNode) report).put("projectFolder", project.getAbsolutePath());
      ((ObjectNode) report).put("downloadedToThisPC", downloaded);
      ((ObjectNode) result.path("content").path(0)).put("text",
          new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
              .writeValueAsString(report));
      return mapper.writeValueAsString(response);
    } catch (Exception e) {
      logger.error("Could not download the imported sources", e);
      try {
        JsonNode id = mapper.readTree(line).get("id");
        ObjectNode error = mapper.createObjectNode();
        error.put("jsonrpc", "2.0");
        error.set("id", id);
        ObjectNode result = error.putObject("result");
        result.putArray("content").addObject().put("type", "text")
            .put("text", "Imported on the IBM i but the download to this PC failed: " + e.getMessage());
        result.put("isError", true);
        return mapper.writeValueAsString(error);
      } catch (Exception ignored) {
        return line;
      }
    }
  }

  /*
   * Uploads files whose size or modification time differs on the IBM i; removes deleted ones there.
   * Many files go as one tar (one upload and one command instead of a few round trips per file).
   */
  int sync(Set<String> localFiles) throws Exception {
    return sync(localFiles, false);
  }

  /*
   * mirror (a full sync): files in the IBM i copy that the project no longer has (renamed, moved, deleted)
   * are removed too, so they cannot become a second target. Only in MC's own folder (<home>/mc/...),
   * never in a --push folder the user chose; MC's .mc/ working files stay.
   */
  int sync(Set<String> localFiles, boolean mirror) throws Exception {
    localFiles = withoutSecrets(localFiles);
    logger.info("Comparing {} local files with the copy on the IBM i", localFiles.size());
    Map<String, long[]> remoteFiles = ssh.listTree(remoteDir);  // a few round trips for the whole copy
    SyncManifest manifest = SyncManifest.load(project, host + ":" + remoteDir);
    Map<String, File> changed = new LinkedHashMap<String, File>();
    for (String path : localFiles) {
      File local = new File(path);
      String rel = relative(local);
      if (rel == null) continue;  // outside the project
      long[] attrs = remoteFiles.get(rel);
      if (!local.isFile()) {
        if (attrs != null) ssh.exec("rm -f " + SshTarget.quote(remoteDir + "/" + rel));
        manifest.remove(rel);
        continue;
      }
      if (attrs != null && unchanged(manifest, rel, local, attrs)) continue;
      changed.put(rel, local);
    }

    if (mirror && parser.getPush() == null) {
      Set<String> localRel = new java.util.HashSet<String>();
      for (String path : localFiles) {
        String rel = relative(new File(path));
        if (rel != null) localRel.add(rel);
      }
      List<String> gone = new ArrayList<String>();
      for (String rel : remoteFiles.keySet()) {
        if (!localRel.contains(rel) && !rel.startsWith(".mc/") && !rel.equals(".mc-sync.tar")) gone.add(rel);
      }
      for (String rel : gone) manifest.remove(rel);
      if (!gone.isEmpty()) {
        logger.info("Removing {} files from the IBM i copy that the project no longer has: {}", gone.size(),
            gone.size() <= 10 ? String.join(", ", gone) : String.join(", ", gone.subList(0, 10)) + ", ...");
        for (int i = 0; i < gone.size(); i += 50) {
          StringBuilder rm = new StringBuilder("rm -f");
          for (String rel : gone.subList(i, Math.min(gone.size(), i + 50))) rm.append(' ').append(SshTarget.quote(remoteDir + "/" + rel));
          ssh.exec(rm.toString());
        }
      }
    }

    List<String> uploaded = new ArrayList<String>();
    long bytes = 0;
    for (File file : changed.values()) bytes += file.length();
    if (!changed.isEmpty()) {
      logger.info("{} changed files ({}) to upload {}", changed.size(), SshTarget.mb(bytes),
          changed.size() >= TAR_FROM ? "as one tar" : "one by one");
    }
    if (changed.size() >= TAR_FROM) {
      boolean own = parser.getPush() == null;
      TarWriter tar = new TarWriter(own ? 0600 : 0644);  // MC's own copy is private to the SSH user
      for (Map.Entry<String, File> file : changed.entrySet()) tar.add(file.getKey(), file.getValue());
      String archive = remoteDir + "/.mc-sync.tar";
      ssh.upload(tar.finish(), archive);
      logger.info("Unpacking the tar on the IBM i");
      ssh.exec("cd " + SshTarget.quote(remoteDir) + " && " + (own ? "umask 077 && " : "") + env("MC_REMOTE_TAR", "/QOpenSys/usr/bin/tar")
          + " -xf .mc-sync.tar; status=$?; rm -f .mc-sync.tar; exit $status");
    } else {
      for (Map.Entry<String, File> file : changed.entrySet()) ssh.upload(file.getValue(), remoteDir + "/" + file.getKey());
    }
    for (String rel : changed.keySet()) uploaded.add(remoteDir + "/" + rel);
    if (!uploaded.isEmpty()) {
      /* Only text is UTF-8: a binary file tagged 1208 would be converted when read */
      List<String> text = new ArrayList<String>();
      for (Map.Entry<String, File> file : changed.entrySet()) {
        if (isText(file.getValue())) text.add(remoteDir + "/" + file.getKey());
      }
      if (!text.isEmpty()) {
        logger.info("Tagging {} uploaded text files as UTF-8 (CCSID 1208)", text.size());
        ssh.setUtf8(text);
      }
      if (parser.getPush() == null && changed.size() < TAR_FROM) ssh.restrict(uploaded);  // the tar already set 600
      for (Map.Entry<String, File> file : changed.entrySet()) {
        File local = file.getValue();
        if (local.length() <= SyncManifest.HASH_UP_TO) {
          manifest.put(file.getKey(), local.length(), local.lastModified() / 1000, sha256(local));
        } else {
          manifest.remove(file.getKey());
        }
      }
    }
    manifest.save();
    return uploaded.size();
  }

  /*
   * Same size and time on the IBM i, and the same SHA-256 when the last sync recorded one (an edit of the same size
   * within the same second looks identical otherwise). When the IBM i did not keep the time but this file is exactly
   * what the last sync uploaded, it is unchanged too. Files over 1 MB are compared by size and time only.
   */
  private static boolean unchanged(SyncManifest manifest, String rel, File local, long[] remote) throws Exception {
    long size = local.length();
    long mtime = local.lastModified() / 1000;
    if (remote[0] != size) return false;
    String sha = size <= SyncManifest.HASH_UP_TO ? sha256(local) : null;
    com.fasterxml.jackson.databind.JsonNode known = manifest.get(rel);
    boolean sameAsUploaded = known != null && sha != null && known.path("size").asLong(-1) == size
        && sha.equals(known.path("sha256").asText());
    if (remote[1] == mtime) {
      if (known != null && sha != null && !sameAsUploaded) return false;  // same size and time, other content
      if (known == null && sha != null) manifest.put(rel, size, mtime, sha);
      return true;
    }
    return sameAsUploaded && known.path("mtime").asLong(-1) == mtime;
  }

  /* Secrets never go to the IBM i copy, tracked by git or not */
  private Set<String> withoutSecrets(Set<String> files) throws Exception {
    Set<String> kept = new LinkedHashSet<String>();
    List<String> secrets = new ArrayList<String>();
    for (String path : files) {
      String rel = relative(new File(path));
      if (rel != null && isSecret(rel)) secrets.add(rel);
      else kept.add(path);
    }
    if (!secrets.isEmpty()) {
      logger.warn("Not uploading {} files that look like secrets or MC's own working files: {}", secrets.size(),
          secrets.size() <= 10 ? String.join(", ", secrets) : String.join(", ", secrets.subList(0, 10)) + ", ...");
    }
    return kept;
  }

  /* .env and .env.*, SSH keys, certificates, credentials files, and the .mc / .ssh / .gnupg folders */
  static boolean isSecret(String rel) {
    String[] parts = rel.replace('\\', '/').split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if (parts[i].equals(".mc") || parts[i].equals(".ssh") || parts[i].equals(".gnupg")) return true;
    }
    String name = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
    if (name.equals(".env") || name.startsWith(".env.") || name.equals(".netrc") || name.equals("known_hosts")
        || name.startsWith("id_rsa") || name.startsWith("id_dsa") || name.startsWith("id_ecdsa")
        || name.startsWith("id_ed25519")) return true;
    for (String extension : new String[] { ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".ppk" }) {
      if (name.endsWith(extension)) return true;
    }
    return false;
  }

  /* No NUL byte in the first 8 KB: a source or text file, not a binary */
  static boolean isText(File file) {
    try (InputStream in = new FileInputStream(file)) {
      byte[] head = new byte[8192];
      int read = in.read(head);
      for (int i = 0; i < read; i++) if (head[i] == 0) return false;
      return true;
    } catch (java.io.IOException e) {
      return true;
    }
  }

  /* The jar running here, uploaded once per version (named by its hash) and checked there by SHA-256 */
  static String ensureRemoteJar(SshTarget ssh, String home) throws Exception {
    File local = new File(VscodeSetup.jarPath());
    if (!local.isFile()) throw new IllegalStateException("Run MasterCompiler from its jar to use --ssh");
    String hash = sha256(local);
    String remote = home + "/mc/.mc/MasterCompiler-" + hash.substring(0, 12) + ".jar";
    com.jcraft.jsch.SftpATTRS there = ssh.stat(remote);
    if (there != null && there.getSize() == local.length()) {
      String actual = remoteSha256(ssh, remote);  // null: the IBM i has no checksum tool, the size has to do
      if (actual == null || actual.equals(hash)) {
        logger.info("MasterCompiler {} already on the IBM i: {}", MasterCompiler.version(), remote);
        ssh.restrict(java.util.Collections.singletonList(remote));
        checkRemoteJava(ssh);
        return remote;
      }
      logger.warn("The MasterCompiler jar on the IBM i has the right size but another content (SHA-256 differs): "
          + "uploading it again");
    } else if (there != null) {
      logger.warn("The MasterCompiler jar on the IBM i is broken ({} of {} bytes, an earlier upload was cut): "
          + "uploading it again", there.getSize(), local.length());
    } else {
      logger.warn("First start of this MasterCompiler version: uploading it ({}) to {}", SshTarget.mb(local.length()), remote);
    }
    ssh.upload(local, remote);
    String actual = remoteSha256(ssh, remote);
    if (actual != null && !actual.equals(hash)) {
      throw new IllegalStateException("The MasterCompiler jar arrived damaged on the IBM i (SHA-256 " + actual
          + " instead of " + hash + "): start again to upload it once more");
    }
    ssh.restrict(java.util.Collections.singletonList(remote));
    /* keep only the version in use; remove pieces of cut uploads */
    String dir = remote.substring(0, remote.lastIndexOf('/'));
    ssh.exec("for f in " + SshTarget.quote(dir) + "/MasterCompiler-*.jar " + SshTarget.quote(dir) + "/*.part; do [ \"$f\" = "
        + SshTarget.quote(remote) + " ] || rm -f \"$f\"; done");
    checkRemoteJava(ssh);
    return remote;
  }

  /* SHA-256 of a file on the IBM i (sha256sum, else PASE's csum); null when neither can run */
  static String remoteSha256(SshTarget ssh, String path) {
    String quoted = SshTarget.quote(path);
    java.util.regex.Pattern hex = java.util.regex.Pattern.compile("\\b([0-9a-fA-F]{64})\\b");
    for (String command : new String[] { "sha256sum " + quoted, "/QOpenSys/pkgs/bin/sha256sum " + quoted,
        "/QOpenSys/usr/bin/csum -h SHA256 " + quoted }) {
      String out = ssh.execQuiet(command);
      java.util.regex.Matcher found = out == null ? null : hex.matcher(out);
      if (found != null && found.find()) return found.group(1).toLowerCase(java.util.Locale.ROOT);
    }
    return null;
  }

  /* MC is compiled for Java 8: say clearly when java is missing or older on the IBM i; returns its version line */
  static String checkRemoteJava(SshTarget ssh) {
    String javaCommand = env("MC_REMOTE_JAVA", REMOTE_JAVA);
    String out = ssh.execQuiet(javaCommand + " -version");
    java.util.regex.Matcher found = out == null ? null
        : java.util.regex.Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(out);
    if (found == null || !found.find()) {
      throw new IllegalStateException("Java does not run on the IBM i at " + javaCommand + " (MC_REMOTE_JAVA changes the path). "
          + "Install an OpenJDK 8 or newer for PASE (or IBM Java, 5733-SC1) and start again.");
    }
    int first = Integer.parseInt(found.group(1));
    int major = first == 1 && found.group(2) != null ? Integer.parseInt(found.group(2)) : first;
    if (major < 8) {
      throw new IllegalStateException("Java " + major + " on the IBM i (" + javaCommand + ") is too old: MasterCompiler needs Java 8 "
          + "or newer. Install a newer OpenJDK for PASE or point MC_REMOTE_JAVA at it.");
    }
    return out.trim().split("\n")[0];
  }

  /* umask 077 (the owner only) in MC's own folder, the folder, and the java call without arguments */
  private String startCommand(String jar) {
    /* mkdir -p: an empty repository (import_source fills it) uploads nothing, so the folder may not exist yet */
    return (parser.getPush() == null ? "umask 077 && " : "") + "mkdir -p " + SshTarget.quote(remoteDir)
        + " && cd " + SshTarget.quote(remoteDir)
        + " && " + env("MC_REMOTE_JAVA", REMOTE_JAVA) + " -jar " + SshTarget.quote(jar);
  }

  private String remoteCommand(String jar, Code4iConfig code4i) {
    StringBuilder command = new StringBuilder(startCommand(jar))
        .append(" --mcp --project ").append(SshTarget.quote(remoteDir));
    List<String> libraries = libraryArgs(code4i, true);
    for (int i = 0; i < libraries.size(); i++) command.append(' ').append(i % 2 == 0 ? libraries.get(i) : SshTarget.quote(libraries.get(i)));
    if (parser.isVerbose()) command.append(" -v");
    return command.toString();
  }

  /* --libl / --curlib pairs: as typed (when typed is true), else the Code for IBM i connection's */
  private List<String> libraryArgs(Code4iConfig code4i, boolean typed) {
    List<String> out = new ArrayList<String>();
    String libl = typed && parser.getLibl() != null ? parser.getLibl()
        : parser.getLibl() == null && code4i != null && !code4i.libraryList.isEmpty() ? String.join(" ", code4i.libraryList) : null;
    String curlib = typed && parser.getCurlib() != null ? parser.getCurlib()
        : parser.getCurlib() == null && code4i != null ? code4i.currentLibrary : null;
    if (libl != null) { out.add("--libl"); out.add(libl); }
    if (curlib != null) { out.add("--curlib"); out.add(curlib); }
    return out;
  }

  /* Every file git knows in the project (tracked and not ignored), else every file under it */
  private Set<String> projectFiles() {
    try {
      return GitChanges.allFiles(project.getPath());
    } catch (Exception notGit) {
      Set<String> files = new LinkedHashSet<String>();
      collect(project, files);
      return files;
    }
  }

  private static void collect(File dir, Set<String> out) {
    File[] kids = dir.listFiles();
    if (kids == null) return;
    for (File kid : kids) {
      if (kid.getName().startsWith(".") || kid.getName().equals("target") || kid.getName().equals("node_modules")) continue;
      if (kid.isDirectory()) collect(kid, out);
      else out.add(kid.getAbsolutePath());
    }
  }

  /* Path relative to the project with "/" separators, or null when outside it */
  String relative(File file) throws Exception {
    String root = project.getCanonicalPath() + File.separator;
    String path = file.getCanonicalPath();
    return path.startsWith(root) ? path.substring(root.length()).replace(File.separatorChar, '/') : null;
  }

  private List<String> relativeToProject(Set<String> absolute) throws Exception {
    List<String> rel = new ArrayList<String>();
    for (String path : absolute) {
      String r = relative(new File(path));
      if (r != null) rel.add(r);
    }
    return rel;
  }

  private Set<String> absolute(List<String> files) {
    Set<String> abs = new LinkedHashSet<String>();
    for (String file : files) {
      File f = new File(file);
      abs.add((f.isAbsolute() ? f : new File(project, file)).getAbsolutePath());
    }
    return abs;
  }

  private synchronized void send(String line) {
    protocolOut.println(line);
    protocolOut.flush();
  }

  private void sendResult(JsonNode id, JsonNode result) {
    try {
      ObjectNode response = mapper.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", id);
      response.set("result", result);
      send(mapper.writeValueAsString(response));
    } catch (Exception e) {
      logger.error("Could not answer the request", e);
    }
  }

  private void sendToolError(String requestLine, String message) {
    try {
      JsonNode id = mapper.readTree(requestLine).get("id");
      ObjectNode response = mapper.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", id);
      ObjectNode result = response.putObject("result");
      result.putArray("content").addObject().put("type", "text").put("text", message);
      result.put("isError", true);
      send(mapper.writeValueAsString(response));
    } catch (Exception e) {
      logger.error("Could not answer the tool call", e);
    }
  }

  private static String sha256(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private static String sha256(byte[] data) throws Exception {
    StringBuilder hex = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      try {
        value = io.github.theprez.dotenv_ibmi.IBMiDotEnv.getDotEnv().get(name);  // the .env of the folder MC runs in
      } catch (Exception noDotEnv) { /* the message below */ }
    }
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is not set: use --code4i or set IBMI_HOSTNAME and IBMI_USERNAME");
    }
    return value;
  }

  static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String trimSlash(String dir) {
    return dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
  }
}
