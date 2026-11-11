package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * "Next steps for your agent", at the end of --help, --doctor and --install-skills: the command that registers MC as
 * an MCP server in each client, with this jar and this Java filled in. Each client is one row of CLIENTS: how its line
 * reads, and how it writes one environment variable and one argument; text() only fills the rows in.
 */
final class NextSteps {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final boolean WINDOWS = File.separatorChar == '\\';

  /* One client: its line ({env} and {command} are filled in), and how an env variable and an argument are written */
  private static final class Client {
    final String name;
    final String line;
    final String env;       // {k} and {v}
    final String envJoin;
    final boolean json;     // values JSON strings (OpenCode's file); else shell words

    Client(String name, String line, String env, String envJoin, boolean json) {
      this.name = name;
      this.line = line;
      this.env = env;
      this.envJoin = envJoin;
      this.json = json;
    }
  }

  private static final List<Client> CLIENTS = Arrays.asList(
      new Client("Claude Code", "claude mcp add --transport stdio --scope user {env} mastercompiler -- {command}", "--env {k}={v}", " ", false),
      new Client("Codex CLI  ", "codex mcp add mastercompiler {env} -- {command}", "--env {k}={v}", " ", false),
      new Client("OpenCode   ", "opencode.json: \"mcp\": { \"mastercompiler\": { \"type\": \"local\", \"command\": [{command}], "
          + "\"environment\": { {env} } } }", "{k}: {v}", ", ", true));

  private static final String PASSWORD_HINT = "<a command that prints the password, e.g. secret-tool lookup service ibmi>";

  private NextSteps() {}

  /* host, user, port and connection come from --doctor when it knows them (null: a placeholder) */
  static String text(Known known) {
    Known k = known != null ? known : new Known(null, null, null, 0);
    List<String> command = new ArrayList<String>(Arrays.asList(java(), "-jar", VscodeSetup.jarPath(), "--mcp", "--ssh"));
    Map<String, String> env = new LinkedHashMap<String, String>();
    if (k.connection != null) {
      command.addAll(Arrays.asList("--code4i", "--connection", k.connection));
    } else {
      env.put("IBMI_HOSTNAME", k.host != null ? k.host : "<host>");
      env.put("IBMI_USERNAME", k.user != null ? k.user : "<user>");
    }
    if (k.port != 0 && k.port != 22) command.addAll(Arrays.asList("--ssh-port", String.valueOf(k.port)));
    String passwordCommand = System.getenv("IBMI_PASSWORD_COMMAND");
    if (System.getenv("IBMI_PASSWORD_FILE") != null && passwordCommand == null) env.put("IBMI_PASSWORD_FILE", System.getenv("IBMI_PASSWORD_FILE"));
    else env.put("IBMI_PASSWORD_COMMAND", passwordCommand != null ? passwordCommand : PASSWORD_HINT);

    StringBuilder out = new StringBuilder("Next steps for your agent: register MasterCompiler as an MCP server (one entry "
        + "serves every repository: MC uses the folder the client starts in, or the open_project tool):\n");
    for (Client client : CLIENTS) {
      List<String> vars = new ArrayList<String>();
      for (Map.Entry<String, String> e : env.entrySet()) {
        vars.add(client.env.replace("{k}", word(e.getKey(), client.json)).replace("{v}", word(e.getValue(), client.json)));
      }
      List<String> words = new ArrayList<String>();
      for (String arg : command) words.add(word(arg, client.json));
      out.append("  ").append(client.name).append(": ").append(client.line.replace("{env}", String.join(client.envJoin, vars))
          .replace("{command}", String.join(client.json ? ", " : " ", words))).append('\n');
    }
    /* PowerShell runs a quoted program path only after & */
    String run = WINDOWS && !word(java(), false).equals(java()) ? "& " : "";
    out.append("  Copilot    : ").append(run).append(word(java(), false)).append(" -jar ").append(word(VscodeSetup.jarPath(), false))
        .append(" --setup-vscode --project <repository> (in VS Code, per repository)\n");
    out.append("In each repository, set protectedLibs at setup (" + ProtectedLibs.REFUSALS + "): the top-level line "
        + ProtectedLibs.EXAMPLE + " in build.yaml, or in mc-base.yaml for a scanned project; --doctor --project <repository> "
        + "shows it.\n");
    out.append("Then restart the client (Claude Code: /mcp reconnects): a server registered while it runs has no tools "
        + "until then. The password itself never goes in these commands or in the chat.");
    return out.toString();
  }

  /* What --doctor found */
  static final class Known {
    final String host;
    final String user;
    final String connection;  // Code for IBM i
    final int port;

    Known(String host, String user, String connection, int port) {
      this.host = host;
      this.user = user;
      this.connection = connection;
      this.port = port;
    }
  }

  private static String java() {
    String home = System.getProperty("java.home");
    File exe = new File(home, "bin" + File.separator + (WINDOWS ? "java.exe" : "java"));
    return home != null && exe.isFile() ? exe.getAbsolutePath() : "java";
  }

  /* A JSON string, or a shell word: quoted only when it has to be */
  static String word(String value, boolean json) {
    if (json) {
      try {
        return JSON.writeValueAsString(value);
      } catch (Exception e) {
        return "\"" + value + "\"";
      }
    }
    if (value.matches("[A-Za-z0-9_./:@=+,-]+")) return value;
    return "'" + value.replace("'", WINDOWS ? "''" : "'\\''") + "'";  // PowerShell doubles a quote; POSIX shells close and reopen
  }
}
