package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

/** Code for IBM i settings reuse (--code4i) and the VS Code setup (--setup-vscode). No IBM i. */
public class Code4iConfigTest {

  /* VS Code settings.json is JSONC: comments and trailing commas */
  private static final String SETTINGS = "{\n"
      + "  // Code for IBM i\n"
      + "  \"code-for-ibmi.connections\": [\n"
      + "    { \"name\": \"DEV\", \"host\": \"dev.example.com\", \"port\": 22, \"username\": \"jdoe\", },\n"
      + "    { \"name\": \"PROD\", \"host\": \"prod.example.com\", \"port\": 22, \"username\": \"jdoe\" },\n"
      + "  ],\n"
      + "  \"code-for-ibmi.connectionSettings\": [\n"
      + "    { \"name\": \"DEV\", \"currentLibrary\": \"jdoedev\", \"libraryList\": [\"jdoedev\", \"appdta\"], \"homeDirectory\": \"/home/JDOE/\" },\n"
      + "  ],\n"
      + "}\n";

  @Test
  void test_Load_Named_Connection(@TempDir Path dir) throws Exception {
    File settings = write(dir.resolve("settings.json"), SETTINGS);
    Code4iConfig dev = Code4iConfig.load(settings, "DEV");

    assertEquals("dev.example.com", dev.host);
    assertEquals("jdoe", dev.username);
    assertEquals("JDOEDEV", dev.currentLibrary);
    assertEquals(2, dev.libraryList.size());
    assertEquals("/home/JDOE/mc/myrepo", dev.defaultPushDir(new File("/work/myrepo")));

    List<CommandObject> hooks = dev.libraryHooks();
    assertEquals(SysCmd.CHGLIBL, hooks.get(0).getSystemCommand());
    assertEquals("JDOEDEV APPDTA", hooks.get(0).get(ParamCmd.LIBL));
    assertEquals(SysCmd.CHGCURLIB, hooks.get(1).getSystemCommand());
    assertEquals("JDOEDEV", hooks.get(1).get(ParamCmd.CURLIB));
  }

  @Test
  void test_Connection_Without_Settings_Uses_Defaults(@TempDir Path dir) throws Exception {
    Code4iConfig prod = Code4iConfig.load(write(dir.resolve("settings.json"), SETTINGS), "PROD");
    assertNull(prod.currentLibrary);
    assertTrue(prod.libraryHooks().isEmpty(), "no library list configured: leave the job's own");
    assertEquals("/home/JDOE/mc/myrepo", prod.defaultPushDir(new File("/work/myrepo")));
  }

  @Test
  void test_Several_Connections_Need_A_Name(@TempDir Path dir) throws Exception {
    File settings = write(dir.resolve("settings.json"), SETTINGS);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Code4iConfig.load(settings, null));
    assertTrue(e.getMessage().contains("DEV") && e.getMessage().contains("PROD"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Code4iConfig.load(settings, "TEST"));
  }

  @Test
  void test_Setup_Merges_Into_Existing_McpJson(@TempDir Path dir) throws Exception {
    File mcp = write(dir.resolve(".vscode/mcp.json"),
        "{ \"servers\": { \"other\": { \"type\": \"stdio\", \"command\": \"x\" } } }\n");
    Code4iConfig dev = Code4iConfig.load(write(dir.resolve("settings.json"), SETTINGS), "DEV");
    assertTrue(VscodeSetup.isPlainJson(mcp));

    ObjectNode root = (ObjectNode) new ObjectMapper().readTree(mcp);
    VscodeSetup.addServer(root, Collections.singletonList(dev));
    assertTrue(root.path("servers").has("other"), "existing servers are kept");
    JsonNode mc = root.path("servers").path(VscodeSetup.SERVER);
    assertEquals("stdio", mc.path("type").asText());
    assertTrue(mc.path("args").toString().contains("--code4i"));
    assertTrue(mc.path("args").toString().contains("DEV"));
    assertEquals("${input:ibmiPassword}", mc.path("env").path("IBMI_PASSWORD").asText());
    assertTrue(root.path("inputs").get(0).path("password").asBoolean());
  }

  @Test
  void test_Setup_Several_Connections_Picked_At_Start(@TempDir Path dir) throws Exception {
    List<Code4iConfig> all = Code4iConfig.loadAll(write(dir.resolve("settings.json"), SETTINGS));
    assertEquals(2, all.size());

    ObjectNode fragment = VscodeSetup.fragment(all);
    JsonNode pick = fragment.path("inputs").get(0);
    assertEquals("pickString", pick.path("type").asText());
    assertEquals("[\"DEV\",\"PROD\"]", pick.path("options").toString());
    assertTrue(fragment.path("servers").path(VscodeSetup.SERVER).path("args").toString().contains("${input:ibmiConnection}"));
  }

  @Test
  void test_Setup_Leaves_Commented_McpJson_Alone(@TempDir Path dir) throws Exception {
    File mcp = write(dir.resolve(".vscode/mcp.json"), "{ // mine\n \"servers\": {}, }\n");
    assertFalse(VscodeSetup.isPlainJson(mcp), "comments would be lost on rewrite");
  }

  @Test
  void test_Setup_Without_Code4i_Prompts_For_Host_And_User() {
    JsonNode env = VscodeSetup.fragment(Collections.<Code4iConfig>emptyList())
        .path("servers").path(VscodeSetup.SERVER).path("env");
    assertEquals("${input:ibmiHost}", env.path("IBMI_HOSTNAME").asText());
    assertEquals("${input:ibmiUser}", env.path("IBMI_USERNAME").asText());
  }

  private static File write(Path path, String content) throws Exception {
    Files.createDirectories(path.getParent());
    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    return path.toFile();
  }
}
