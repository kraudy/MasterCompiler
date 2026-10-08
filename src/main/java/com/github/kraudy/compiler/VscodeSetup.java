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
    out.append(ssh
        ? "  Over SSH, like Code for IBM i: MC runs on the IBM i (SSH key or password). --host-servers to use the ACS ports instead.\n"
        : "  Through the host servers (ports 449, 8470-8476, like ACS).\n");

    /* .vscode/mcp.json */
    File mcp = new File(project, ".vscode/mcp.json");
    out.append("\n.vscode/mcp.json\n");
    if (mcp.isFile() && JSONC.readTree(mcp).path("servers").has(SERVER)) {
      out.append("  kept: already has a \"").append(SERVER).append("\" server\n");
    } else if (mcp.isFile() && !isPlainJson(mcp)) {
      out.append("  not changed: it has comments, which rewriting would lose. Add this to it by hand:\n")
          .append(indent(WRITER.writeValueAsString(fragment(connections, ssh)))).append('\n');
    } else {
      ObjectNode content = mcp.isFile() ? (ObjectNode) JSONC.readTree(mcp) : WRITER.createObjectNode();
      addServer(content, connections, ssh);
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

    /* .github/skills */
    File skillsDir = new File(project, ".github/skills");
    out.append("\n.github/skills\n");
    for (String skill : SKILLS) {
      out.append("  ").append(print ? "would install " : "installed ").append(skill).append('\n');
    }
    if (!print) installSkills(skillsDir);

    out.append(print
        ? "\nRun again without --print to write these files.\n"
        : "\nNext:\n"
            + "  1. Open this folder in VS Code and switch Copilot Chat to Agent mode.\n"
            + "  2. Command Palette: \"MCP: List Servers\" > " + SERVER + " > Start"
            + (connections.size() > 1 ? "; pick the connection," : ",") + " then enter your IBM i password.\n"
            + "  3. Ask Copilot to \"plan\" first: it lists what would compile and where.\n"
            + "  If no MCP servers show up, your organization may have disabled MCP for Copilot: ask your admin.\n");
    System.out.println(out.toString());  // plain text, no logger prefix
    return 0;
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
    JavaChoice best = new JavaChoice(executable(new File(System.getProperty("java.home"))),
        major(System.getProperty("java.specification.version")),
        bits.equals("64") || System.getProperty("os.arch", "").contains("64"));

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
      if (candidate != null && (candidate.is64 && !best.is64 || candidate.is64 == best.is64 && candidate.major > best.major)) {
        best = candidate;
      }
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
      return new JavaChoice(exe.getAbsolutePath(), major(version), arch.contains("64"));
    } catch (Exception unreadable) {
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
