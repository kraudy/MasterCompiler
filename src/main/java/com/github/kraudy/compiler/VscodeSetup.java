package com.github.kraudy.compiler;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * --setup-vscode [--project dir] [--connection name] [--print]: make a repository ready for Copilot's agent mode.
 *
 *   .vscode/mcp.json         "mastercompiler" MCP server started with this java and this jar, using the
 *                            Code for IBM i connection(s) (several: picked when the server starts) and a
 *                            password prompt; other servers in the file are kept
 *   .github/skills/...       MC's agent skills, loaded by Copilot when relevant
 *
 * --print writes nothing and shows what would be written. An existing mcp.json with comments is never
 * rewritten (that would lose them): the server entry to paste is printed instead.
 */
public final class VscodeSetup {
  private static final Logger logger = LoggerFactory.getLogger(VscodeSetup.class);

  static final String SERVER = "mastercompiler";
  static final List<String> SKILLS = Arrays.asList(
      "mastercompiler/SKILL.md", "mastercompiler-vscode/SKILL.md", "ibm-i-pase/SKILL.md");

  private static final ObjectMapper JSONC = JsonMapper.builder()
      .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
      .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
      .build();
  private static final ObjectMapper WRITER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private VscodeSetup() {}

  public static int run(ArgParser parser) throws Exception {
    File project = new File(parser.getProjectRoot() != null ? parser.getProjectRoot() : ".").getCanonicalFile();
    boolean print = parser.isPrint();
    if (!print) project.mkdirs();  // a new repository (e.g. one import_source will fill)
    boolean empty = !hasSources(project);
    /* SSH by default: the port Code for IBM i already uses; --host-servers for the ACS ports */
    boolean ssh = !parser.isHostServers();

    /* Connections: the one asked for, else all of them (several are picked when the server starts) */
    List<Code4iConfig> connections = new ArrayList<Code4iConfig>();
    String code4iProblem = null;
    try {
      if (parser.getConnection() != null) connections.add(Code4iConfig.load(parser.getConnection()));
      else connections.addAll(Code4iConfig.loadAll());
    } catch (Exception e) {
      code4iProblem = e.getMessage();
    }

    StringBuilder out = new StringBuilder();
    out.append(print ? "\nMasterCompiler setup preview (nothing written) for " : "\nMasterCompiler setup for ")
        .append(project).append("\n\nIBM i\n");
    if (connections.isEmpty()) {
      out.append("  No Code for IBM i connection").append(code4iProblem != null ? " (" + code4iProblem + ")" : "")
          .append(": VS Code will ask for host, user and password.\n");
    }
    for (Code4iConfig c : connections) {
      out.append("  ").append(c.name).append(": ").append(c.username).append('@').append(c.host).append('\n')
          .append("    objects into   ").append(c.currentLibrary != null ? c.currentLibrary : "(the job's current library)")
          .append("  <- builds replace objects here: use a development library\n");
      if (Code4iConfig.isSystemLibrary(c.currentLibrary)) {
        out.append("    WARNING: ").append(c.currentLibrary).append(" is an IBM-supplied or shared library. Set your own "
            + "development library as the current library of this connection in Code for IBM i before building.\n");
      } else if (c.currentLibrary == null) {
        out.append("    WARNING: no current library set in Code for IBM i: objects would go to the job's default "
            + "(often QGPL). Set your development library there before building.\n");
      }
      out.append("    library list   ").append(c.libraryList.isEmpty() ? "(the job's own)" : String.join(" ", c.libraryList)).append('\n')
          .append("    sources to     ").append(c.defaultPushDir(project));
      if (!c.homeKnown) {
        out.append(ssh ? "  (home directory not set in Code for IBM i: MC uses the SSH user's $HOME, shown here as assumed)"
            : "  (home directory not set in Code for IBM i: assumed; set it there if this is wrong)");
      }
      out.append('\n');
    }
    if (connections.size() > 1) out.append("  Several connections: you pick one each time the server starts.\n");

    out.append("  Java for the server: ").append(java().describe()).append('\n');
    if (java().inExtension()) {
      out.append("    WARNING: this Java is inside a VS Code extension folder whose name changes when the extension "
          + "updates; mcp.json then points at a missing Java. Run setup again after such an update, or install a "
          + "64-bit JDK (no admin rights needed: unzip one, e.g. Eclipse Temurin) and run setup again.\n");
    }
    out.append(ssh
        ? "  Over SSH, like Code for IBM i: MC runs on the IBM i (SSH key or password). --host-servers to use the ACS ports instead.\n"
        : "  Through the host servers (ports 449, 8470-8476, like ACS).\n");

    /* Read-only connections of the spec: a password prompt each, like the build connection's */
    List<String> readOnly = new ArrayList<String>();
    try {
      for (ReadOnlyConnections.ReadOnly ro : ReadOnlyConnections.load(project).readOnly.values()) readOnly.add(ro.name);
    } catch (Exception e) {
      out.append("  connections in the spec not read: ").append(e.getMessage()).append('\n');
    }
    for (String name : readOnly) {
      out.append("  read-only connection ").append(name).append(": its password is asked in its own VS Code prompt\n");
    }

    /* .vscode/mcp.json */
    File mcp = new File(project, ".vscode/mcp.json");
    out.append("\n.vscode/mcp.json\n");
    if (mcp.isFile() && JSONC.readTree(mcp).path("servers").has(SERVER)) {
      ObjectNode content = (ObjectNode) JSONC.readTree(mcp);
      int added = addReadOnlyPrompts(content, readOnly);
      if (added == 0) {
        out.append("  kept: already has a \"").append(SERVER).append("\" server\n");
      } else if (!isPlainJson(mcp)) {
        out.append("  not changed: it has comments, which rewriting would lose. Add these password prompts by hand:\n")
            .append(indent(WRITER.writeValueAsString(fragment(connections, ssh, readOnly)))).append('\n');
      } else if (print) {
        out.append("  would add ").append(added).append(" read-only connection password prompt(s), keeping the rest\n");
      } else {
        WRITER.writeValue(mcp, content);
        out.append("  added ").append(added).append(" read-only connection password prompt(s), the rest kept\n");
      }
    } else if (mcp.isFile() && !isPlainJson(mcp)) {
      out.append("  not changed: it has comments, which rewriting would lose. Add this to it by hand:\n")
          .append(indent(WRITER.writeValueAsString(fragment(connections, ssh, readOnly)))).append('\n');
    } else {
      ObjectNode content = mcp.isFile() ? (ObjectNode) JSONC.readTree(mcp) : WRITER.createObjectNode();
      addServer(content, connections, ssh);
      addReadOnlyPrompts(content, readOnly);
      if (print) {
        out.append(mcp.isFile() ? "  would add the server, keeping the rest:\n" : "  would write:\n")
            .append(indent(WRITER.writeValueAsString(content))).append('\n');
      } else {
        boolean existed = mcp.isFile();
        mcp.getParentFile().mkdirs();
        WRITER.writeValue(mcp, content);
        out.append(existed ? "  server added, other servers kept\n" : "  written\n");
      }
    }

    /* .gitignore: MC's working folder (.mc/: reference copies, sync files) never goes into git */
    File gitignore = new File(project, ".gitignore");
    String ignored = gitignore.isFile() ? new String(Files.readAllBytes(gitignore.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
    if (!ignored.contains(".mc/")) {
      out.append("\n.gitignore\n  ").append(print ? "would add" : "added").append(" .mc/ (MasterCompiler's working files)\n");
      if (!print) {
        Files.write(gitignore.toPath(), ((ignored.isEmpty() || ignored.endsWith("\n") ? ignored : ignored + "\n")
            + "# MasterCompiler working files\n.mc/\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    if (!new File(project, ".git").exists()) {
      out.append("  Not a git repository yet: \"git init\" there lets MC build only what changed (since) and keeps history.\n");
    }

    /* .github/skills */
    File skillsDir = new File(project, ".github/skills");
    out.append("\n.github/skills\n");
    for (String skill : SKILLS) {
      out.append("  ").append(print ? "would install " : "installed ").append(skill).append('\n');
    }
    if (!print) installSkills(skillsDir);

    /* .github/prompts/mastercompiler-continue.prompt.md: the request the new window carries on with */
    File prompt = new File(project, CONTINUE_PROMPT);
    if (parser.getNext() != null) {
      out.append("\n").append(CONTINUE_PROMPT).append('\n')
          .append(print ? "  would save: " : "  saved: ").append(parser.getNext()).append('\n');
      if (!print) {
        prompt.getParentFile().mkdirs();
        Files.write(prompt.toPath(), continuePrompt(parser.getNext(), parser.getConnection())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (parser.getNextFile() != null) new File(parser.getNextFile()).delete();  // a handover note, now saved
      }
    }

    String third = parser.getNext() != null || prompt.isFile()
        ? "  3. In Copilot Chat (Agent mode) type /mastercompiler-continue and press Enter: it carries on with\n"
          + "     the request saved by setup.\n"
        : empty
        ? "  3. The folder has no sources yet: ask Copilot to import them, e.g. \"import_source into the repo with\n"
          + "     members MYLIB/QRPGLESRC/ORD*\" (or objects by name). Then \"plan\" shows what would compile.\n"
        : "  3. Ask Copilot to \"plan\" first: it lists what would compile and where.\n";
    out.append(print
        ? "\nRun again without --print to write these files.\n"
        : "\nNext:\n"
            + "  1. Open this folder in VS Code (code \"" + project + "\") and, in that window, open Copilot Chat in Agent mode.\n"
            + "  2. Command Palette: \"MCP: List Servers\" > " + SERVER + " > Start"
            + (connections.size() > 1 ? "; pick the connection," : ",") + " then type your IBM i password in VS Code's prompt.\n"
            + third
            + "  If no MCP servers show up, your organization may have disabled MCP for Copilot: ask your admin.\n");
    System.out.println(out.toString());  // plain text, no logger prefix
    return 0;
  }

  static final String CONTINUE_PROMPT = ".github/prompts/mastercompiler-continue.prompt.md";

  /* A VS Code prompt file: "/mastercompiler-continue" in Copilot Chat runs it in the repository's window */
  static String continuePrompt(String request, String connection) {
    return "---\n"
        + "description: Continue the MasterCompiler setup started in another window\n"
        + "---\n"
        + "Continue the MasterCompiler setup in this repository (use Agent mode).\n\n"
        + "1. Check that the `mastercompiler` MCP server is running. If its tools are missing, tell me to start it:\n"
        + "   Command Palette > \"MCP: List Servers\" > mastercompiler > Start, and type the IBM i password only in\n"
        + "   VS Code's prompt (never in this chat or a terminal).\n"
        + (connection != null
            ? "   The IBM i for this request is the Code for IBM i connection \"" + connection + "\".\n"
            : "   If the server asks which Code for IBM i connection to use, pick the system that holds what the\n"
              + "   request below needs; ask me if you do not know which one.\n")
        + "2. Then do this request from the setup:\n\n"
        + "   " + request.replace("\n", "\n   ") + "\n\n"
        + "3. When it is done, delete this file (" + CONTINUE_PROMPT + ") and tell me what to do next\n"
        + "   (usually: call plan to see what would compile).\n";
  }

  /* Any file outside dot folders (.git, .vscode, .github, .mc) */
  private static boolean hasSources(File dir) {
    File[] kids = dir.listFiles();
    if (kids == null) return false;
    for (File kid : kids) {
      if (kid.getName().startsWith(".")) continue;
      if (kid.isFile() || hasSources(kid)) return true;
    }
    return false;
  }

  /* The server (and its inputs) added to an mcp.json content; inputs already there are kept */
  static void addServer(ObjectNode root, List<Code4iConfig> connections, boolean ssh) {
    ObjectNode fragment = fragment(connections, ssh);
    ArrayNode inputs = root.has("inputs") ? (ArrayNode) root.get("inputs") : root.putArray("inputs");
    for (JsonNode input : fragment.path("inputs")) {
      boolean present = false;
      for (JsonNode existing : inputs) present |= input.path("id").asText().equals(existing.path("id").asText());
      if (!present) inputs.add(input);
    }
    ObjectNode servers = root.has("servers") ? (ObjectNode) root.get("servers") : root.putObject("servers");
    servers.set(SERVER, fragment.path("servers").path(SERVER));
  }

  /* A password prompt and IBMI_PASSWORD_<NAME> for each read-only connection the server lacks; how many were added */
  static int addReadOnlyPrompts(ObjectNode root, List<String> readOnly) {
    ObjectNode server = (ObjectNode) root.path("servers").path(SERVER);
    if (server.isMissingNode() || readOnly.isEmpty()) return 0;
    ObjectNode env = server.has("env") && server.get("env").isObject() ? (ObjectNode) server.get("env") : server.putObject("env");
    ArrayNode inputs = root.has("inputs") ? (ArrayNode) root.get("inputs") : root.putArray("inputs");
    int added = 0;
    for (String name : readOnly) {
      String variable = ReadOnlyConnections.passwordVariable(name);
      if (env.has(variable)) continue;
      String id = "ibmiPassword_" + variable.substring("IBMI_PASSWORD_".length());
      boolean present = false;
      for (JsonNode input : inputs) present |= id.equals(input.path("id").asText());
      if (!present) input(inputs, id, "IBM i password for the read-only connection " + name
          + " (leave empty if you log in there with an SSH key)", true);
      env.put(variable, "${input:" + id + "}");
      added++;
    }
    return added;
  }

  static ObjectNode fragment(List<Code4iConfig> connections, boolean ssh, List<String> readOnly) {
    ObjectNode root = fragment(connections, ssh);
    addReadOnlyPrompts(root, readOnly);
    return root;
  }

  /* {"inputs": [...], "servers": {"mastercompiler": {...}}} for these connections */
  static ObjectNode fragment(List<Code4iConfig> connections, boolean ssh) {
    ObjectNode root = WRITER.createObjectNode();
    ArrayNode inputs = root.putArray("inputs");

    ObjectNode server = root.putObject("servers").putObject(SERVER);
    server.put("type", "stdio");
    server.put("command", javaExecutable());
    ArrayNode args = server.putArray("args");
    args.add("-jar").add(jarPath()).add("--mcp");
    if (ssh) args.add("--ssh");
    ObjectNode env = WRITER.createObjectNode();

    if (connections.size() == 1) {
      args.add("--code4i").add("--connection").add(connections.get(0).name);
    } else if (connections.size() > 1) {
      ObjectNode pick = inputs.addObject();
      pick.put("id", "ibmiConnection");
      pick.put("type", "pickString");
      pick.put("description", "Code for IBM i connection to build on");
      ArrayNode options = pick.putArray("options");
      for (Code4iConfig c : connections) options.add(c.name);
      pick.put("default", connections.get(0).name);
      args.add("--code4i").add("--connection").add("${input:ibmiConnection}");
    } else {
      input(inputs, "ibmiHost", "IBM i host name", false);
      input(inputs, "ibmiUser", "IBM i user profile", false);
      env.put("IBMI_HOSTNAME", "${input:ibmiHost}");
      env.put("IBMI_USERNAME", "${input:ibmiUser}");
    }
    args.add("--project").add("${workspaceFolder}");

    input(inputs, "ibmiPassword", ssh
        ? "IBM i password (leave empty if you log in with an SSH key)"
        : "IBM i password (the one you use in Code for IBM i / ACS)", true);
    env.put("IBMI_PASSWORD", "${input:ibmiPassword}");
    server.set("env", env);
    return root;
  }

  private static void input(ArrayNode inputs, String id, String description, boolean password) {
    ObjectNode input = inputs.addObject();
    input.put("id", id);
    input.put("type", "promptString");
    input.put("description", description);
    if (password) input.put("password", true);
  }

  /* Strict JSON (no comments, no trailing commas): safe to rewrite without losing anything */
  static boolean isPlainJson(File file) {
    try {
      new ObjectMapper().readTree(file);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /* MC's skills from the jar into .github/skills (MC owns these files: they are refreshed) */
  static int installSkills(File skillsDir) throws Exception {
    int installed = 0;
    for (String skill : SKILLS) {
      try (InputStream in = VscodeSetup.class.getResourceAsStream("/mc-skills/" + skill)) {
        if (in == null) {
          logger.info("Skill not packaged in this jar: {}", skill);
          continue;
        }
        File target = new File(skillsDir, skill);
        target.getParentFile().mkdirs();
        Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        installed++;
      }
    }
    return installed;
  }

  private static String indent(String text) {
    return "    " + text.replace("\n", "\n    ");
  }

  /* A Java installation: its executable, major version and 32 / 64 bit */
  static final class JavaChoice {
    final String executable;
    final int major;
    final boolean is64;

    JavaChoice(String executable, int major, boolean is64) {
      this.executable = executable;
      this.major = major;
      this.is64 = is64;
    }

    String describe() {
      return executable + " (Java " + major + ", " + (is64 ? "64" : "32") + "-bit)";
    }

    /* Inside a VS Code extension's versioned folder: gone after the extension updates */
    boolean inExtension() {
      return executable.replace('\\', '/').contains("/.vscode/extensions/");
    }

    /* 64-bit first, then a stable location (not an extension folder), then the newest */
    boolean betterThan(JavaChoice other) {
      if (is64 != other.is64) return is64;
      if (inExtension() != other.inExtension()) return !inExtension();
      return major > other.major;
    }
  }

  private static JavaChoice chosenJava;

  /*
   * The Java VS Code starts the server with: the best one installed, not just the one running setup
   * (often an old 32-bit Java 8 first on PATH). 64-bit first, then the newest. Looks at this Java,
   * JAVA_HOME, the Red Hat Java extension's bundled JRE and the usual install folders.
   */
  static JavaChoice java() {
    if (chosenJava != null) return chosenJava;
    String bits = System.getProperty("sun.arch.data.model", "");
    String running = executable(new File(System.getProperty("java.home")));
    Boolean runningIs64 = is64(new File(running));
    JavaChoice best = new JavaChoice(running, major(System.getProperty("java.specification.version")),
        runningIs64 != null ? runningIs64 : bits.equals("64") || System.getProperty("os.arch", "").contains("64"));

    List<File> homes = new ArrayList<File>();
    if (System.getenv("JAVA_HOME") != null) homes.add(new File(System.getenv("JAVA_HOME")));
    for (File ext : children(new File(System.getProperty("user.home"), ".vscode/extensions"), "redhat.java-")) {
      homes.addAll(children(new File(ext, "jre"), ""));
    }
    for (String env : new String[] { "ProgramFiles", "ProgramFiles(x86)" }) {
      if (System.getenv(env) == null) continue;
      for (String vendor : new String[] { "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto" }) {
        homes.addAll(children(new File(System.getenv(env), vendor), ""));
      }
    }
    for (File home : homes) {
      JavaChoice candidate = fromRelease(home);
      if (candidate != null && candidate.betterThan(best)) best = candidate;
    }
    chosenJava = best;
    return best;
  }

  /* JAVA_VERSION and OS_ARCH from the installation's release file (Java 8 and newer have one) */
  private static JavaChoice fromRelease(File home) {
    File release = new File(home, "release");
    File exe = new File(executable(home));
    if (!release.isFile() || !exe.isFile()) return null;
    try {
      String version = null;
      String arch = "";
      for (String line : Files.readAllLines(release.toPath())) {
        String value = line.contains("=") ? line.substring(line.indexOf('=') + 1).replace("\"", "").trim() : "";
        if (line.startsWith("JAVA_VERSION=")) version = value;
        if (line.startsWith("OS_ARCH=")) arch = value;
      }
      if (version == null || major(version) < 8) return null;
      Boolean is64 = is64(exe);  // the executable itself: some release files have no OS_ARCH
      return new JavaChoice(exe.getAbsolutePath(), major(version), is64 != null ? is64 : arch.contains("64"));
    } catch (Exception unreadable) {
      return null;
    }
  }

  /* 64-bit executable? From its header: PE (Windows), ELF (Linux, AIX/PASE), Mach-O (macOS); null when unknown */
  static Boolean is64(File exe) {
    try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(exe, "r")) {
      byte[] head = new byte[4];
      file.readFully(head);
      if (head[0] == 'M' && head[1] == 'Z') {
        file.seek(0x3c);
        int pe = Integer.reverseBytes(file.readInt());
        file.seek(pe + 4);
        int machine = Short.reverseBytes(file.readShort()) & 0xffff;
        return machine == 0x8664 || machine == 0xaa64 || machine == 0x0200;  // x64, ARM64, IA-64
      }
      if (head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
        file.seek(4);
        return file.readByte() == 2;  // ELFCLASS64
      }
      int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
      if (magic == 0xcffaedfe || magic == 0xfeedfacf) return true;
      if (magic == 0xcefaedfe || magic == 0xfeedface) return false;
      if (magic == 0xcafebabe) return true;  // universal binary: has a 64-bit slice on current macOS
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /* "1.8.0_291" -> 8, "21.0.2" -> 21 */
  static int major(String version) {
    try {
      String[] parts = version.split("[._+-]");
      int first = Integer.parseInt(parts[0]);
      return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
    } catch (Exception e) {
      return 0;
    }
  }

  private static String executable(File home) {
    File bin = new File(home, "bin");
    File exe = new File(bin, "java.exe");
    return (exe.isFile() ? exe : new File(bin, "java")).getAbsolutePath();
  }

  private static List<File> children(File dir, String prefix) {
    List<File> kids = new ArrayList<File>();
    File[] all = dir.listFiles();
    if (all == null) return kids;
    for (File kid : all) if (kid.isDirectory() && kid.getName().startsWith(prefix)) kids.add(kid);
    return kids;
  }

  private static String javaExecutable() {
    return java().executable;
  }

  static String jarPath() {
    try {
      File location = new File(MasterCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (location.isFile()) return location.getAbsolutePath();
    } catch (Exception ignored) {
      /* fall through */
    }
    return "MasterCompiler.jar";
  }
}
