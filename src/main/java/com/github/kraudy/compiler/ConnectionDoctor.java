package com.github.kraudy.compiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;

/*
 * --doctor: where the connection comes from, what SSH and the host servers do with it, and which transport is usable.
 * Never prints the password. Every sign-on goes through SignOnGuard, so a wrong password costs one attempt: when SSH
 * rejects it, the host servers are not tried with it.
 */
final class ConnectionDoctor {
  private static final int SIGNON_PORT = 8476;    // as-signon
  private static final int DATABASE_PORT = 8471;  // as-database

  private ConnectionDoctor() {}

  static int run(ArgParser parser) throws Exception {
    PrintStream out = System.out;
    Code4iConfig code4i = MasterCompiler.code4i(parser);
    String host;
    String user;
    String source;
    if (code4i != null) {
      host = code4i.host;
      user = code4i.username;
      source = "Code for IBM i connection '" + code4i.name + "' (--code4i, VS Code settings)";
    } else if (IBMiDotEnv.isIBMi()) {
      out.println("Connection        : this IBM i (local job): no SSH or host servers sign-on is needed");
      return 0;
    } else {
      io.github.cdimascio.dotenv.Dotenv env = IBMiDotEnv.getDotEnv();
      host = env.get("IBMI_HOSTNAME", "");
      user = env.get("IBMI_USERNAME", "");
      source = new File(".env").isFile() ? ".env file in " + new File(".").getCanonicalPath() + " (IBMI_*)"
          : "IBMI_* environment variables";
      if (host.isEmpty() || user.isEmpty()) {
        out.println("Connection        : none. Use --code4i [--connection <name>], or set IBMI_HOSTNAME and IBMI_USERNAME "
            + "(environment or .env)");
        out.println("  IBMI_HOSTNAME   : " + (host.isEmpty() ? "not set" : host));
        out.println("  IBMI_USERNAME   : " + (user.isEmpty() ? "not set" : user.toUpperCase()));
        String seen = Passwords.source();
        out.println("  password source : " + (seen != null ? seen + " (it only supplies the password: the host and user come from "
            + "--code4i or IBMI_HOSTNAME / IBMI_USERNAME)" : "none (--password-stdin, IBMI_PASSWORD_FILE, --password-command, "
            + "IBMI_PASSWORD or SSH_ASKPASS)"));
        if (Passwords.askpassNote() != null) out.println("  " + Passwords.askpassNote());
        return 1;
      }
    }

    SshTarget.Port port = SshTarget.Port.of(parser, code4i);
    String key = code4i != null && code4i.privateKeyPath != null && new File(code4i.privateKeyPath).isFile() ? code4i.privateKeyPath : null;
    String password;
    try {
      password = Passwords.get(user + "@" + host);
    } catch (IllegalArgumentException e) {
      out.println("Password source   : " + e.getMessage());
      return 1;
    }
    String passwordSource = Passwords.source() != null ? Passwords.source() : password != null ? "typed at the terminal" : null;

    out.println("Connection source : " + source);
    out.println("Host / user       : " + host + " / " + user.toUpperCase());
    out.println("SSH port          : " + port.number + " (" + port.origin + ")");
    out.println("SSH auth          : " + (key != null ? "key " + key : passwordSource != null ? "password from " + passwordSource
        : "keys in ~/.ssh (id_ed25519, id_ecdsa, id_rsa), no password"));
    out.println("Host servers auth : " + (passwordSource != null ? "password from " + passwordSource : "no password available"));
    if (Passwords.askpassNote() != null) out.println("Password note     : " + Passwords.askpassNote());
    out.println("Host key checking : " + (SshTarget.strictHostKey || System.getenv("IBMI_SSH_KNOWN_HOSTS") != null
        ? "strict (unknown keys refused)" : "accept-new (unknown keys trusted and recorded)"));
    String curlib = parser.getCurlib() != null ? parser.getCurlib() + " (--curlib)"
        : code4i != null && code4i.currentLibrary != null ? code4i.currentLibrary + " (Code for IBM i)" : "not set";
    String libl = parser.getLibl() != null ? parser.getLibl() + " (--libl)"
        : code4i != null && !code4i.libraryList.isEmpty() ? String.join(" ", code4i.libraryList) + " (Code for IBM i)" : "not set";
    out.println("Current library   : " + curlib);
    out.println("Library list      : " + libl);
    out.println("Home directory    : " + (code4i != null && code4i.homeKnown ? code4i.homeDirectory + " (Code for IBM i)"
        : "the SSH user's $HOME"));
    out.println();

    SignOnGuard.Target target = new SignOnGuard.Target(code4i != null ? code4i.name : host, host, user, password);
    SignOnGuard.project(new File(parser.getProjectRoot() != null ? parser.getProjectRoot() : "."));

    boolean sshUsable = ssh(out, parser, code4i, target, host, user, port, key);
    out.println();
    boolean hostServersUsable = hostServers(out, target, host, user);
    out.println();

    if (sshUsable && hostServersUsable) {
      out.println("Usable transport  : both. Builds use the host servers by default; --remote builds over SSH, --mcp --ssh serves MCP over SSH");
    } else if (sshUsable) {
      out.println("Usable transport  : SSH only. Build with --remote (or serve MCP with --mcp --ssh); the host servers do not work from here");
    } else if (hostServersUsable) {
      out.println("Usable transport  : host servers only. The default build and --mcp work; --ssh and --remote do not");
    } else {
      out.println("Usable transport  : none. Fix the first failure above; "
          + (port.number == 22 && "default".equals(port.origin)
              ? "if the system uses another SSH port (PUB400: 2222) pass --ssh-port 2222 or set IBMI_SSH_PORT" : "check the host, firewall and password"));
    }
    return sshUsable || hostServersUsable ? 0 : 1;
  }

  private static boolean ssh(PrintStream out, ArgParser parser, Code4iConfig code4i, SignOnGuard.Target target, String host,
      String user, SshTarget.Port port, String key) {
    String tcp = tcp(host, port.number);
    out.println("SSH TCP " + port.number + "      : " + (tcp == null ? "open" : "closed (" + tcp + ")"));
    if (tcp != null) {
      out.println("SSH sign-on       : not tried");
      return false;
    }
    SshTarget ssh = null;
    try {
      ssh = SignOnGuard.signOn(target, () -> SshTarget.connect(host, port.number, user, target.password, key));
      out.println("SSH sign-on       : ok");
      out.println("  $HOME           : " + ssh.exec("echo $HOME").trim());
      out.println("  pwd             : " + ssh.exec("pwd").trim());
      try {
        out.println("  java            : " + SshMcpProxy.checkRemoteJava(ssh) + " (ok)");
      } catch (IllegalStateException noJava) {
        out.println("  java            : " + noJava.getMessage());
        return false;
      }
      return true;
    } catch (Exception e) {
      out.println("SSH sign-on       : failed: " + reason(e));
      return false;
    } finally {
      if (ssh != null) ssh.close();
    }
  }

  private static boolean hostServers(PrintStream out, SignOnGuard.Target target, String host, String user) {
    String signon = tcp(host, SIGNON_PORT);
    String database = tcp(host, DATABASE_PORT);
    out.println("Host servers TCP  : sign-on " + SIGNON_PORT + " " + (signon == null ? "open" : "closed (" + signon + ")")
        + ", database " + DATABASE_PORT + " " + (database == null ? "open" : "closed (" + database + ")"));
    if (signon != null || database != null) {
      out.println("Host sign-on      : not tried (the host servers are not reachable: blocked port or server not started)");
      return false;
    }
    if (target.password.isEmpty()) {
      out.println("Host sign-on      : not tried (no password: --password-stdin, IBMI_PASSWORD_FILE, --password-command, IBMI_PASSWORD or SSH_ASKPASS)");
      return false;
    }
    AS400 system = null;
    try {
      system = SignOnGuard.signOn(target, () -> {
        AS400 as400 = new AS400(host, user, target.password.toCharArray());
        as400.setGuiAvailable(false);
        if (!as400.validateSignon()) throw new SignOnGuard.Refused("CPF22E2", "IBM i rejected the user profile or password");
        return as400;
      });
      out.println("Host sign-on      : ok");
      try (Connection connection = new AS400JDBCDataSource(system).getConnection(); Statement statement = connection.createStatement()) {
        try (ResultSet rs = statement.executeQuery("SELECT CURRENT SERVER FROM SYSIBM.SYSDUMMY1")) {
          if (rs.next()) out.println("  CURRENT SERVER  : " + rs.getString(1).trim());
        }
        Map<Integer, String> libraries = new TreeMap<Integer, String>();
        try (ResultSet rs = statement.executeQuery("SELECT ORDINAL_POSITION, SCHEMA_NAME, TYPE FROM QSYS2.LIBRARY_LIST_INFO")) {
          while (rs.next()) libraries.put(rs.getInt(1), rs.getString(2).trim() + ("CURRENT".equals(rs.getString(3).trim()) ? " (current)" : ""));
        }
        out.println("  library list    : " + String.join(" ", libraries.values()));
      }
      return true;
    } catch (Exception e) {
      out.println("Host sign-on      : failed: " + reason(e));
      return false;
    } finally {
      if (system != null) system.disconnectAllServices();
    }
  }

  /* null when the port accepts a connection, else why not */
  private static String tcp(String host, int port) {
    long start = System.currentTimeMillis();
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 5000);
      return null;
    } catch (IOException e) {
      return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "") + ", "
          + (System.currentTimeMillis() - start) + " ms";
    }
  }

  /* One line, no password: the first line of the message */
  private static String reason(Exception e) {
    String message = String.valueOf(e.getMessage());
    int end = message.indexOf('\n');
    message = end < 0 ? message : message.substring(0, end);
    return message.length() > 300 ? message.substring(0, 300) + "..." : message;
  }
}
