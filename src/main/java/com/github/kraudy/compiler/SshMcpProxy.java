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
  private final Set<String> pendingImports = java.util.concurrent.ConcurrentHashMap.newKeySet();  // import_source call ids  // VS Code closed the session: the remote end stopping is expected

  public SshMcpProxy(ArgParser parser) {
    this.parser = parser;
  }

  public void serve() throws Exception {
    protocolOut = new PrintStream(System.out, true, "UTF-8");
    System.setOut(System.err);  // stdout is the protocol channel
    MasterCompiler.quietLogs(parser.isVerbose());

    Code4iConfig code4i = MasterCompiler.code4i(parser);
    host = code4i != null ? code4i.host : required("IBMI_HOSTNAME");
    String user = code4i != null ? code4i.username : required("IBMI_USERNAME");
    int port = code4i != null ? code4i.port : Integer.parseInt(env("IBMI_SSH_PORT", "22"));
    project = new File(parser.getProjectRoot()).getCanonicalFile();

    /*
     * initialize and tools/list are answered here right away; connecting, the first jar upload and the
     * first sync run meanwhile, so a slow link does not hit VS Code's start timeout. Tool calls wait for them.
     */
    Thread setup = new Thread(() -> {
      try {
        remoteReady.complete(startRemote(code4i, host, port, user));
      } catch (Throwable e) {
        logger.error("Could not start MasterCompiler on " + host, e);
        remoteReady.completeExceptionally(e);
      }
    }, "mc-ssh-setup");
    setup.setDaemon(true);
    setup.start();

    McpServer local = new McpServer(parser);
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
        JsonNode id = request.get("id");
        if (id == null) continue;  // notifications: the remote server ignores them as well
        JsonNode result = local.localResult(request.path("method").asText(""), request.path("params"));
        /* status before MC runs on the IBM i: answered here, with the stage it is at */
        if (result == null && "tools/call".equals(request.path("method").asText(""))
            && "status".equals(request.path("params").path("name").asText("")) && !remoteReady.isDone()) {
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
        String call = line;
        calls.execute(() -> forward(call));  // one at a time, in order
      }
    } finally {
      closing = true;
      if (remote != null) remote.disconnect();
      if (ssh != null) ssh.close();
    }
  }

  /* SSH in, upload MC when this version is new, sync the sources and start MC there; returns its stdin */
  private OutputStream startRemote(Code4iConfig code4i, String host, int port, String user) throws Exception {
    long start = System.currentTimeMillis();
    stage("1/5 connecting to " + user + "@" + host + ":" + port + " over SSH");
    ssh = SshTarget.connect(host, port, user, System.getenv("IBMI_PASSWORD"),
        code4i != null ? code4i.privateKeyPath : null);
    /* Home: Code for IBM i's when it has one, else the SSH user's own */
    String home = code4i != null && code4i.homeKnown ? code4i.homeDirectory : ssh.exec("echo $HOME").trim();
    home = home.startsWith("/") ? trimSlash(home) : "/home/" + user.toUpperCase();
    remoteDir = parser.getPush() != null ? trimSlash(parser.getPush()) : home + "/mc/" + project.getName();

    stage("2/5 checking MasterCompiler on the IBM i");
    String jar = ensureRemoteJar(home);
    long jarDone = System.currentTimeMillis();
    stage("3/5 syncing the sources to " + remoteDir);
    int uploaded = sync(projectFiles(), true);
    logger.info("Sources synced: {} files uploaded ({} s for MC, {} s for sources)", uploaded,
        (jarDone - start) / 1000, (System.currentTimeMillis() - jarDone) / 1000);

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

  /* Start-up stage, logged and kept for tool calls that wait or fail */
  private volatile String stage = "not started";

  private void stage(String now) {
    stage = now;
    logger.info("MC start-up {}", now);
  }

  /* A call the remote MC answers: wait until it runs, upload what changed, pass it on */
  private void forward(String line) {
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
    if (forward == null) return;  // answered here (sync failed)
    try {
      toRemote.write((forward + "\n").getBytes(StandardCharsets.UTF_8));
      toRemote.flush();
    } catch (Exception e) {
      sendToolError(line, "Lost the connection to MasterCompiler on the IBM i: " + e.getMessage());
    }
  }

  /* Remote MC answers go straight back to VS Code; when it stops, so does this server */
  private void relayResponses(InputStream fromRemote) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fromRemote, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!pendingStatus.isEmpty()) line = afterStatus(line);
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
      if (tool.equals("seed") || tool.equals("call_program") || tool.equals("find_export")) {
        sync(projectFiles(), true);  // they read seeds.yaml or the sources in the IBM i copy
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

  /* The PC side of status: version, host, folders, start-up stage */
  private ObjectNode statusFields(ObjectNode into) {
    into.put("pcVersion", MasterCompiler.version());
    into.put("connection", "SSH " + host);
    into.put("startup", stage);
    into.put("localProjectFolder", project != null ? project.getAbsolutePath() : null);
    into.put("ibmiProjectFolder", remoteDir);
    return into;
  }

  private ObjectNode statusHere() {
    ObjectNode result = mapper.createObjectNode();
    ObjectNode text = statusFields(mapper.createObjectNode());
    text.put("note", "MasterCompiler is not running on the IBM i yet: the user, current library and library list come once it is");
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
    logger.info("Comparing {} local files with the copy on the IBM i", localFiles.size());
    Map<String, long[]> remoteFiles = ssh.listTree(remoteDir);  // a few round trips for the whole copy
    Map<String, File> changed = new LinkedHashMap<String, File>();
    for (String path : localFiles) {
      File local = new File(path);
      String rel = relative(local);
      if (rel == null) continue;  // outside the project
      long[] attrs = remoteFiles.get(rel);
      if (!local.isFile()) {
        if (attrs != null) ssh.exec("rm -f " + SshTarget.quote(remoteDir + "/" + rel));
        continue;
      }
      if (attrs != null && attrs[0] == local.length() && attrs[1] == local.lastModified() / 1000) {
        continue;
      }
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
      TarWriter tar = new TarWriter();
      for (Map.Entry<String, File> file : changed.entrySet()) tar.add(file.getKey(), file.getValue());
      String archive = remoteDir + "/.mc-sync.tar";
      ssh.upload(tar.finish(), archive);
      logger.info("Unpacking the tar on the IBM i");
      ssh.exec("cd " + SshTarget.quote(remoteDir) + " && " + env("MC_REMOTE_TAR", "/QOpenSys/usr/bin/tar")
          + " -xf .mc-sync.tar; status=$?; rm -f .mc-sync.tar; exit $status");
    } else {
      for (Map.Entry<String, File> file : changed.entrySet()) ssh.upload(file.getValue(), remoteDir + "/" + file.getKey());
    }
    for (String rel : changed.keySet()) uploaded.add(remoteDir + "/" + rel);
    if (!uploaded.isEmpty()) {
      logger.info("Tagging {} uploaded files as UTF-8 (CCSID 1208)", uploaded.size());
      ssh.setUtf8(uploaded);
    }
    return uploaded.size();
  }

  /* The jar running here, uploaded once per version (named by its hash) */
  private String ensureRemoteJar(String home) throws Exception {
    File local = new File(VscodeSetup.jarPath());
    if (!local.isFile()) throw new IllegalStateException("Run MasterCompiler from its jar to use --ssh");
    String remote = home + "/mc/.mc/MasterCompiler-" + sha256(local).substring(0, 12) + ".jar";
    com.jcraft.jsch.SftpATTRS there = ssh.stat(remote);
    if (there != null && there.getSize() == local.length()) {
      logger.info("MasterCompiler {} already on the IBM i: {}", MasterCompiler.version(), remote);
      return remote;
    }
    if (there != null) {
      logger.warn("The MasterCompiler jar on the IBM i is broken ({} of {} bytes, an earlier upload was cut): "
          + "uploading it again", there.getSize(), local.length());
    } else {
      logger.warn("First start of this MasterCompiler version: uploading it ({}) to {}", SshTarget.mb(local.length()), remote);
    }
    ssh.upload(local, remote);
    /* keep only the version in use; remove pieces of cut uploads */
    String dir = remote.substring(0, remote.lastIndexOf('/'));
    ssh.exec("for f in " + SshTarget.quote(dir) + "/MasterCompiler-*.jar " + SshTarget.quote(dir) + "/*.part; do [ \"$f\" = "
        + SshTarget.quote(remote) + " ] || rm -f \"$f\"; done");
    return remote;
  }

  private String remoteCommand(String jar, Code4iConfig code4i) {
    /* mkdir -p: an empty repository (import_source fills it) uploads nothing, so the folder may not exist yet */
    StringBuilder command = new StringBuilder("mkdir -p ").append(SshTarget.quote(remoteDir))
        .append(" && cd ").append(SshTarget.quote(remoteDir))
        .append(" && ").append(env("MC_REMOTE_JAVA", REMOTE_JAVA))
        .append(" -jar ").append(SshTarget.quote(jar))
        .append(" --mcp --project ").append(SshTarget.quote(remoteDir));
    String libl = parser.getLibl() != null ? parser.getLibl()
        : code4i != null && !code4i.libraryList.isEmpty() ? String.join(" ", code4i.libraryList) : null;
    String curlib = parser.getCurlib() != null ? parser.getCurlib() : code4i != null ? code4i.currentLibrary : null;
    if (libl != null) command.append(" --libl ").append(SshTarget.quote(libl));
    if (curlib != null) command.append(" --curlib ").append(SshTarget.quote(curlib));
    if (parser.isVerbose()) command.append(" -v");
    return command.toString();
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

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is not set: use --code4i or set IBMI_HOSTNAME and IBMI_USERNAME");
    }
    return value;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String trimSlash(String dir) {
    return dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
  }
}
