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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.SftpATTRS;

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
 */
public class SshMcpProxy {
  private static final Logger logger = LoggerFactory.getLogger(SshMcpProxy.class);
  private static final String REMOTE_JAVA = "/QOpenSys/usr/bin/java";

  private final ArgParser parser;
  private final ObjectMapper mapper = new ObjectMapper();
  private PrintStream protocolOut;
  private SshTarget ssh;
  private File project;
  private String remoteDir;
  private volatile boolean closing;  // VS Code closed the session: the remote end stopping is expected

  public SshMcpProxy(ArgParser parser) {
    this.parser = parser;
  }

  public void serve() throws Exception {
    protocolOut = new PrintStream(System.out, true, "UTF-8");
    System.setOut(System.err);  // stdout is the protocol channel

    Code4iConfig code4i = MasterCompiler.code4i(parser);
    String host = code4i != null ? code4i.host : required("IBMI_HOSTNAME");
    String user = code4i != null ? code4i.username : required("IBMI_USERNAME");
    int port = code4i != null ? code4i.port : Integer.parseInt(env("IBMI_SSH_PORT", "22"));
    String home = code4i != null ? trimSlash(code4i.homeDirectory) : "/home/" + user.toUpperCase();

    project = new File(parser.getProjectRoot()).getCanonicalFile();
    remoteDir = parser.getPush() != null ? trimSlash(parser.getPush()) : home + "/mc/" + project.getName();

    ssh = SshTarget.connect(host, port, user, System.getenv("IBMI_PASSWORD"),
        code4i != null ? code4i.privateKeyPath : null);
    String jar = ensureRemoteJar(home);
    int uploaded = sync(projectFiles());
    logger.info("Project synced to {}: {} files uploaded", remoteDir, uploaded);

    ChannelExec remote = ssh.start(remoteCommand(jar, code4i));
    remote.setErrStream(System.err, true);
    InputStream fromRemote = remote.getInputStream();
    OutputStream toRemote = remote.getOutputStream();
    remote.connect(30_000);
    logger.info("MasterCompiler running on {} in {}", host, remoteDir);

    Thread pump = new Thread(() -> relayResponses(fromRemote), "mc-ssh-responses");
    pump.setDaemon(true);
    pump.start();

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    try {
      while ((line = in.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        String forward = beforeForwarding(line);
        if (forward == null) continue;  // answered here (sync failed)
        toRemote.write((forward + "\n").getBytes(StandardCharsets.UTF_8));
        toRemote.flush();
      }
    } finally {
      closing = true;
      remote.disconnect();
      ssh.close();
    }
  }

  /* Remote MC answers go straight back to VS Code; when it stops, so does this server */
  private void relayResponses(InputStream fromRemote) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fromRemote, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) send(line);
    } catch (Exception e) {
      logger.error("Lost the remote MasterCompiler", e);
    }
    if (closing) return;
    logger.error("Remote MasterCompiler stopped");
    System.exit(1);
  }

  /* Upload what a build / plan / impact call needs; null when this server answered it itself */
  String beforeForwarding(String line) {
    try {
      JsonNode request = mapper.readTree(line);
      if (!"tools/call".equals(request.path("method").asText())) return line;
      String tool = request.path("params").path("name").asText();
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
        List<String> files = new ArrayList<String>();
        for (JsonNode f : args.get("files")) files.add(f.asText());
        uploaded = sync(absolute(files));
      } else {
        uploaded = sync(projectFiles());
      }
      if (uploaded > 0) logger.info("Uploaded {} changed files before {}", uploaded, tool);
      return mapper.writeValueAsString(request);
    } catch (Exception e) {
      logger.error("Could not prepare tool call", e);
      sendToolError(line, "Could not upload the sources to the IBM i: " + e.getMessage());
      return null;
    }
  }

  /* Uploads files whose size or modification time differs on the IBM i; removes deleted ones there */
  int sync(Set<String> localFiles) throws Exception {
    List<String> uploaded = new ArrayList<String>();
    for (String path : localFiles) {
      File local = new File(path);
      String rel = relative(local);
      if (rel == null) continue;  // outside the project
      String remote = remoteDir + "/" + rel;
      SftpATTRS attrs = ssh.stat(remote);
      if (!local.isFile()) {
        if (attrs != null) ssh.exec("rm -f " + SshTarget.quote(remote));
        continue;
      }
      if (attrs != null && attrs.getSize() == local.length() && attrs.getMTime() == (int) (local.lastModified() / 1000)) {
        continue;
      }
      ssh.upload(local, remote);
      uploaded.add(remote);
    }
    if (!uploaded.isEmpty()) ssh.setUtf8(uploaded);
    return uploaded.size();
  }

  /* The jar running here, uploaded once per version (named by its hash) */
  private String ensureRemoteJar(String home) throws Exception {
    File local = new File(VscodeSetup.jarPath());
    if (!local.isFile()) throw new IllegalStateException("Run MasterCompiler from its jar to use --ssh");
    String remote = home + "/mc/.mc/MasterCompiler-" + sha256(local).substring(0, 12) + ".jar";
    if (ssh.stat(remote) == null) {
      logger.info("Uploading MasterCompiler to {}", remote);
      ssh.upload(local, remote);
      /* keep only the version in use */
      String dir = remote.substring(0, remote.lastIndexOf('/'));
      ssh.exec("for f in " + SshTarget.quote(dir) + "/MasterCompiler-*.jar; do [ \"$f\" = " + SshTarget.quote(remote)
          + " ] || rm -f \"$f\"; done");
    }
    return remote;
  }

  private String remoteCommand(String jar, Code4iConfig code4i) {
    StringBuilder command = new StringBuilder("cd ").append(SshTarget.quote(remoteDir))
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
