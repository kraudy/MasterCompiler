package com.github.kraudy.compiler;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/*
 * Where the IBM i password comes from, safest first (the password is never logged or printed):
 *   1. --password-stdin: the first line of stdin (a pipe from a password manager; not with --mcp, whose stdin is the protocol)
 *   2. IBMI_PASSWORD_FILE: a file only its owner can read (chmod 600), first line
 *   3. --password-command / IBMI_PASSWORD_COMMAND: a command whose first output line is the password (a keyring or
 *      secrets manager: secret-tool lookup ..., pass show ..., op read ...), run once
 *   4. IBMI_PASSWORD: the environment (what VS Code's password prompt fills in .vscode/mcp.json)
 *   5. SSH_ASKPASS: the program OpenSSH would ask for a password (same rules as ssh: SSH_ASKPASS_REQUIRE, a display)
 *   6. a prompt on the terminal, when there is one and none of the above is set
 * The .env file of the host servers connection is read by IBMiDotEnv, which this does not replace.
 */
final class Passwords {
  private static final int COMMAND_SECONDS = 60;
  private static String stdin;       // read once at start-up
  private static String commandLine; // --password-command, else IBMI_PASSWORD_COMMAND
  private static String commandOrigin;
  private static String fromCommand; // its output, once per run
  private static String asked;       // SSH_ASKPASS's answer, once per run
  private static boolean prompting;  // set by init: off for the MCP server
  private static String prompted;    // typed once per run

  private Passwords() {}

  /* Called once after the arguments were validated: reads --password-stdin, and a prompt is only for non-MCP runs */
  static synchronized void init(ArgParser parser) throws Exception {
    prompting = !parser.isMcp();
    String env = System.getenv("IBMI_PASSWORD_COMMAND");
    commandLine = parser.getPasswordCommand() != null ? parser.getPasswordCommand() : env != null && !env.trim().isEmpty() ? env : null;
    commandOrigin = parser.getPasswordCommand() != null ? "--password-command" : "IBMI_PASSWORD_COMMAND";
    if (!parser.isPasswordStdin()) return;
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line = in.readLine();
    if (line == null || line.isEmpty()) throw new IllegalArgumentException("--password-stdin: stdin has no password on its first line");
    stdin = line;
  }

  /* --password-stdin or IBMI_PASSWORD_FILE; null when neither is used */
  static synchronized String explicit() {
    if (stdin != null) return stdin;
    String path = System.getenv("IBMI_PASSWORD_FILE");
    return path == null || path.isEmpty() ? null : fromFile(new File(path));
  }

  /* --password-stdin, IBMI_PASSWORD_FILE or the password command; null when none is used */
  static String provided() {
    String explicit = explicit();
    return explicit != null ? explicit : command();
  }

  /* SSH_ASKPASS if it applies, else the terminal; null when neither gives one */
  static String interactive(String who) {
    String askpass = askpass(who);
    return askpass != null ? askpass : prompt(who);
  }

  /* The password for who ("user@host"): stdin, file, command, IBMI_PASSWORD, SSH_ASKPASS, else asked on the terminal; null when none */
  static String get(String who) {
    String provided = provided();
    if (provided != null) return provided;
    String env = System.getenv("IBMI_PASSWORD");
    if (env != null && !env.isEmpty()) return env;
    return interactive(who);
  }

  /* Which source supplies it, without running a command or reading a secret from the terminal; null when none */
  static String source() {
    if (stdin != null) return "--password-stdin";
    String path = System.getenv("IBMI_PASSWORD_FILE");
    if (path != null && !path.isEmpty()) return "IBMI_PASSWORD_FILE";
    if (commandLine != null) return commandOrigin;
    String env = System.getenv("IBMI_PASSWORD");
    if (env != null && !env.isEmpty()) return "IBMI_PASSWORD";
    return askpassProgram() != null ? "SSH_ASKPASS (" + askpassProgram() + ")" : null;
  }

  /* The first output line of --password-command / IBMI_PASSWORD_COMMAND, run once; null when none is set */
  static synchronized String command() {
    if (commandLine == null) return null;
    if (fromCommand == null) fromCommand = run(commandOrigin, shell(commandLine));
    return fromCommand;
  }

  /* SSH_ASKPASS, when ssh itself would use it: always with SSH_ASKPASS_REQUIRE force/prefer, never with never, else only without a terminal and with a display */
  static String askpassProgram() {
    String program = System.getenv("SSH_ASKPASS");
    if (program == null || program.trim().isEmpty()) return null;
    String require = System.getenv("SSH_ASKPASS_REQUIRE");
    if ("never".equals(require)) return null;
    if ("force".equals(require) || "prefer".equals(require)) return program;
    boolean display = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    return System.console() == null && display ? program : null;
  }

  /* Why SSH_ASKPASS is set but not used here (for --doctor); null when it is unset or used */
  static String askpassNote() {
    String program = System.getenv("SSH_ASKPASS");
    if (program == null || program.trim().isEmpty() || askpassProgram() != null) return null;
    return "SSH_ASKPASS is set (" + program + ") but not used: "
        + ("never".equals(System.getenv("SSH_ASKPASS_REQUIRE")) ? "SSH_ASKPASS_REQUIRE=never"
            : "there is a terminal or no display; SSH_ASKPASS_REQUIRE=force uses it anyway");
  }

  private static synchronized String askpass(String who) {
    String program = askpassProgram();
    if (program == null) return null;
    if (asked == null) asked = run("SSH_ASKPASS", new String[] { program, "IBM i password for " + who + ": " });  // a program, as ssh runs it
    return asked;
  }

  /*
   * Runs a command and returns the first line of what it prints. The output is never logged: a failure says only
   * which source failed and how.
   */
  private static String run(String origin, String[] command) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);  // its own messages (a keyring unlock, an error) reach the terminal
    try {
      final Process process = builder.start();
      process.getOutputStream().close();  // never waits for input
      java.util.concurrent.CompletableFuture<String> first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try {
          return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
        } catch (java.io.IOException e) {
          return null;
        }
      });
      if (!process.waitFor(COMMAND_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalArgumentException(origin + " did not finish in " + COMMAND_SECONDS + " s");
      }
      if (process.exitValue() != 0) throw new IllegalArgumentException(origin + " failed (exit " + process.exitValue() + ")");
      String password = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
      if (password == null || password.replace("\r", "").isEmpty()) throw new IllegalArgumentException(origin + " printed no password");
      return password.replace("\r", "");
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(origin + " could not run: " + e.getClass().getSimpleName());
    }
  }

  /* --password-command is a shell command line (pipes and arguments); SSH_ASKPASS is not: ssh runs the program itself */
  private static String[] shell(String line) {
    boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    return windows ? new String[] { "cmd", "/c", line } : new String[] { "sh", "-c", line };
  }

  static synchronized String prompt(String who) {
    if (prompted != null) return prompted;
    Console console = prompting ? System.console() : null;
    if (console == null) return null;
    char[] typed = console.readPassword("IBM i password for %s: ", who);
    if (typed == null || typed.length == 0) return null;
    prompted = new String(typed);
    Arrays.fill(typed, '\0');
    return prompted;
  }

  /* First line of a file nobody else can read (group/others permission bits are refused on POSIX systems) */
  static String fromFile(File file) {
    try {
      if (!file.isFile()) throw new IllegalArgumentException("IBMI_PASSWORD_FILE does not exist: " + file);
      try {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.toPath());
        for (PosixFilePermission permission : permissions) {
          if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
            throw new IllegalArgumentException("IBMI_PASSWORD_FILE " + file + " can be read by other users: chmod 600 it");
          }
        }
      } catch (UnsupportedOperationException notPosix) { /* Windows: the file's ACL is the user's profile */ }
      String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      int end = text.indexOf('\n');
      String line = (end < 0 ? text : text.substring(0, end)).replace("\r", "");
      if (line.isEmpty()) throw new IllegalArgumentException("IBMI_PASSWORD_FILE " + file + " is empty");
      return line;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("IBMI_PASSWORD_FILE " + file + " cannot be read");
    }
  }
}
