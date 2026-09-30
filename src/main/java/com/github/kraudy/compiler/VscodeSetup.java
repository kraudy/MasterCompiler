package com.github.kraudy.compiler;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
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
 * --setup-vscode [--project dir] [--connection name]: make a repository ready for Copilot's agent mode.
 *
 *   .vscode/mcp.json         "mastercompiler" MCP server started with this java and this jar, using the
 *                            Code for IBM i connection when one is configured (password prompted by VS Code)
 *   .github/skills/...       MC's agent skills, loaded by Copilot when relevant
 *
 * An existing mcp.json keeps its other servers and inputs.
 */
public final class VscodeSetup {
  private static final Logger logger = LoggerFactory.getLogger(VscodeSetup.class);

  static final String SERVER = "mastercompiler";
  static final List<String> SKILLS = Arrays.asList(
      "mastercompiler/SKILL.md", "mastercompiler-vscode/SKILL.md", "ibm-i-pase/SKILL.md");

  private VscodeSetup() {}

  public static int run(ArgParser parser) throws Exception {
    File project = new File(parser.getProjectRoot() != null ? parser.getProjectRoot() : ".").getCanonicalFile();

    Code4iConfig code4i = null;
    String code4iProblem = null;
    try {
      code4i = Code4iConfig.load(parser.getConnection());
    } catch (Exception e) {
      code4iProblem = e.getMessage();
    }

    File mcp = new File(project, ".vscode/mcp.json");
    boolean mcpWritten = writeMcpJson(mcp, code4i);
    int skills = installSkills(new File(project, ".github/skills"));

    StringBuilder out = new StringBuilder("\nMasterCompiler is set up for Copilot in " + project + "\n");
    out.append(mcpWritten ? "  wrote   " : "  kept    ").append(mcp).append(mcpWritten ? "\n" : " (already has a \"" + SERVER + "\" server)\n");
    out.append("  skills  ").append(skills).append(" in ").append(new File(project, ".github/skills")).append('\n');
    if (code4i != null) {
      out.append("  IBM i   Code for IBM i connection \"").append(code4i.name).append("\": ")
          .append(code4i.username).append('@').append(code4i.host)
          .append(code4i.currentLibrary != null ? ", current library " + code4i.currentLibrary : "").append('\n');
    } else {
      out.append("  IBM i   no Code for IBM i connection used (").append(code4iProblem)
          .append("); VS Code will ask for host and user as well\n");
    }
    out.append("\nNext:\n")
        .append("  1. Open this folder in VS Code and switch Copilot Chat to Agent mode.\n")
        .append("  2. Start the \"").append(SERVER).append("\" server (Command Palette: \"MCP: List Servers\") and enter your IBM i password when asked.\n")
        .append("  3. Ask Copilot to build, e.g. \"plan what would rebuild\" or \"build and fix the errors\".\n")
        .append("  If no MCP servers show up, your organization may have disabled MCP for Copilot: ask your admin.\n");
    logger.info(out.toString());
    return 0;
  }

  /* Adds the MC server (and the inputs it needs) unless the file already has one */
  static boolean writeMcpJson(File mcp, Code4iConfig code4i) throws Exception {
    ObjectMapper reader = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build();
    ObjectMapper writer = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    ObjectNode root = mcp.isFile() ? (ObjectNode) reader.readTree(mcp) : writer.createObjectNode();
    ObjectNode servers = root.has("servers") ? (ObjectNode) root.get("servers") : root.putObject("servers");
    if (servers.has(SERVER)) return false;

    ArrayNode inputs = root.has("inputs") ? (ArrayNode) root.get("inputs") : root.putArray("inputs");
    addInput(inputs, "ibmiPassword", "IBM i password (the one you use in Code for IBM i / ACS)", true);

    ObjectNode server = servers.putObject(SERVER);
    server.put("type", "stdio");
    server.put("command", javaExecutable());
    ArrayNode args = server.putArray("args");
    args.add("-jar").add(jarPath()).add("--mcp");
    if (code4i != null) args.add("--code4i").add("--connection").add(code4i.name);
    args.add("--project").add("${workspaceFolder}");

    ObjectNode env = server.putObject("env");
    env.put("IBMI_PASSWORD", "${input:ibmiPassword}");
    if (code4i == null) {
      addInput(inputs, "ibmiHost", "IBM i host name", false);
      addInput(inputs, "ibmiUser", "IBM i user profile", false);
      env.put("IBMI_HOSTNAME", "${input:ibmiHost}");
      env.put("IBMI_USERNAME", "${input:ibmiUser}");
    }

    mcp.getParentFile().mkdirs();
    writer.writeValue(mcp, root);
    return true;
  }

  private static void addInput(ArrayNode inputs, String id, String description, boolean password) {
    for (JsonNode input : inputs) {
      if (id.equals(input.path("id").asText())) return;
    }
    ObjectNode input = inputs.addObject();
    input.put("id", id);
    input.put("type", "promptString");
    input.put("description", description);
    if (password) input.put("password", true);
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

  /* The java running MC, so VS Code starts the same one even when it is not on PATH */
  private static String javaExecutable() {
    File bin = new File(System.getProperty("java.home"), "bin");
    File exe = new File(bin, "java.exe");
    return (exe.isFile() ? exe : new File(bin, "java")).getAbsolutePath();
  }

  private static String jarPath() {
    try {
      File location = new File(MasterCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (location.isFile()) return location.getAbsolutePath();
    } catch (Exception ignored) {
      /* fall through */
    }
    return "MasterCompiler.jar";
  }
}
