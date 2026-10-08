package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.ibm.as400.access.AS400;

/*
 * --code4i [connection]: reuse the connection a developer already configured in Code for IBM i
 * (VS Code user settings, "code-for-ibmi.connections" and "code-for-ibmi.connectionSettings"):
 * host, user, current library, library list and home directory.
 * The password is not readable (Code for IBM i keeps it in VS Code's secret storage), so it comes
 * from IBMI_PASSWORD, which VS Code fills from a password prompt declared in .vscode/mcp.json.
 */
public final class Code4iConfig {

  public final String name;
  public final String host;
  public final String username;
  public final String currentLibrary;
  public final List<String> libraryList;
  public final String homeDirectory;
  public final boolean homeKnown;       // false: not set in Code for IBM i ("." is its default), /home/USER assumed
  public final int port;                // SSH port Code for IBM i uses (--ssh)
  public final String privateKeyPath;   // SSH key, when the connection has one

  private Code4iConfig(String name, String host, String username, String currentLibrary,
      List<String> libraryList, String homeDirectory, int port, String privateKeyPath) {
    this.name = name;
    this.host = host;
    this.username = username;
    this.port = port;
    this.privateKeyPath = privateKeyPath;
    this.currentLibrary = currentLibrary;
    this.libraryList = libraryList;
    this.homeKnown = homeDirectory != null && homeDirectory.startsWith("/");
    this.homeDirectory = homeKnown ? homeDirectory : "/home/" + username.toUpperCase();
  }

  /* The named connection, or the only one when no name is given */
  public static Code4iConfig load(String connectionName) throws Exception {
    File settings = settingsFile();
    if (settings == null) {
      throw new IllegalArgumentException("VS Code user settings not found. Set MC_VSCODE_SETTINGS to the settings.json "
          + "that has your Code for IBM i connections, or use IBMI_HOSTNAME / IBMI_USERNAME instead of --code4i.");
    }
    return load(settings, connectionName);
  }

  static Code4iConfig load(File settingsFile, String connectionName) throws Exception {
    List<Code4iConfig> all = loadAll(settingsFile);
    List<String> names = new ArrayList<String>();
    for (Code4iConfig c : all) names.add(c.name);
    for (Code4iConfig c : all) {
      if (connectionName == null ? all.size() == 1 : connectionName.equals(c.name)) return c;
    }
    throw new IllegalArgumentException(names.isEmpty()
        ? "No Code for IBM i connections in " + settingsFile
        : (connectionName == null ? "Several Code for IBM i connections, pick one with --connection <name>: "
            : "No Code for IBM i connection named '" + connectionName + "'. Connections: ") + names);
  }

  /* Every Code for IBM i connection in the VS Code user settings (empty when there are none) */
  public static List<Code4iConfig> loadAll() throws Exception {
    File settings = settingsFile();
    return settings == null ? new ArrayList<Code4iConfig>() : loadAll(settings);
  }

  static List<Code4iConfig> loadAll(File settingsFile) throws Exception {
    ObjectMapper mapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build();
    JsonNode root = mapper.readTree(settingsFile);
    List<Code4iConfig> all = new ArrayList<Code4iConfig>();
    for (JsonNode c : root.path("code-for-ibmi.connections")) all.add(fromSettings(root, c));
    return all;
  }

  private static Code4iConfig fromSettings(JsonNode root, JsonNode connection) {
    String name = connection.path("name").asText();

    String currentLibrary = null;
    String homeDirectory = null;
    List<String> libraryList = new ArrayList<String>();
    for (JsonNode s : root.path("code-for-ibmi.connectionSettings")) {
      if (!name.equals(s.path("name").asText())) continue;
      currentLibrary = text(s, "currentLibrary");
      homeDirectory = text(s, "homeDirectory");
      for (JsonNode lib : s.path("libraryList")) {
        if (!lib.asText().trim().isEmpty()) libraryList.add(lib.asText().trim().toUpperCase());
      }
    }
    String username = connection.path("username").asText();

    String key = text(connection, "privateKeyPath");
    if (key == null) key = text(connection, "privateKey");
    return new Code4iConfig(name, connection.path("host").asText(), username,
        currentLibrary != null ? currentLibrary.toUpperCase() : null, libraryList, homeDirectory,
        connection.path("port").asInt(22), key);
  }

  /* VS Code user settings: MC_VSCODE_SETTINGS, else the usual per-OS locations (Code, Insiders, VSCodium) */
  static File settingsFile() {
    String explicit = System.getenv("MC_VSCODE_SETTINGS");
    if (explicit != null && new File(explicit).isFile()) return new File(explicit);

    List<File> bases = new ArrayList<File>();
    String appData = System.getenv("APPDATA");                             // Windows
    if (appData != null) bases.add(new File(appData));
    String home = System.getProperty("user.home");
    bases.add(new File(home, "Library/Application Support"));              // macOS
    bases.add(new File(home, ".config"));                                  // Linux
    for (File base : bases) {
      for (String product : new String[] { "Code", "Code - Insiders", "VSCodium" }) {
        File candidate = new File(base, product + "/User/settings.json");
        if (candidate.isFile()) return candidate;
      }
    }
    return null;
  }

  /* Host servers connection as the Code for IBM i user; password from IBMI_PASSWORD */
  public AS400 connect() throws Exception {
    String password = System.getenv("IBMI_PASSWORD");
    if (password == null || password.isEmpty()) {
      throw new IllegalArgumentException("IBMI_PASSWORD is not set. In .vscode/mcp.json give the server "
          + "\"env\": { \"IBMI_PASSWORD\": \"${input:ibmiPassword}\" } with a password input "
          + "(java -jar MasterCompiler.jar --setup-vscode writes it).");
    }
    AS400 system = new AS400(host, username, password.toCharArray());
    system.setGuiAvailable(false);
    return system;
  }

  /* CHGLIBL / CHGCURLIB so the build job has the developer's Code for IBM i library list */
  public List<CommandObject> libraryHooks() {
    List<CommandObject> hooks = new ArrayList<CommandObject>();
    if (!libraryList.isEmpty()) {
      hooks.add(new CommandObject(SysCmd.CHGLIBL).put(ParamCmd.LIBL, String.join(" ", libraryList)));
    }
    if (currentLibrary != null && !currentLibrary.isEmpty()) {
      hooks.add(new CommandObject(SysCmd.CHGCURLIB).put(ParamCmd.CURLIB, currentLibrary));
    }
    return hooks;
  }

  /* Where --push uploads the project when none is given: <home>/mc/<project folder> */
  /* IBM-supplied and shared libraries (QGPL, QSYS, ...): builds must not put objects there */
  public static boolean isSystemLibrary(String library) {
    return library != null && library.toUpperCase().startsWith("Q");
  }

  public String defaultPushDir(File project) {
    String home = homeDirectory.endsWith("/") ? homeDirectory.substring(0, homeDirectory.length() - 1) : homeDirectory;
    return home + "/mc/" + project.getName();
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
