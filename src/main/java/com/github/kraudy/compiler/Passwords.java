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
 *   3. IBMI_PASSWORD: the environment (what VS Code's password prompt fills in .vscode/mcp.json)
 *   4. a prompt on the terminal, when there is one and none of the above is set
 * The .env file of the host servers connection is read by IBMiDotEnv, which this does not replace.
 */
final class Passwords {
  private static String stdin;       // read once at start-up
  private static boolean prompting;  // set by init: off for the MCP server
  private static String prompted;    // typed once per run

  private Passwords() {}

  /* Called once after the arguments were validated: reads --password-stdin, and a prompt is only for non-MCP runs */
  static synchronized void init(ArgParser parser) throws Exception {
    prompting = !parser.isMcp();
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

  /* The password for who ("user@host"): stdin, file, IBMI_PASSWORD, else asked on the terminal; null when none */
  static String get(String who) {
    String explicit = explicit();
    if (explicit != null) return explicit;
    String env = System.getenv("IBMI_PASSWORD");
    if (env != null && !env.isEmpty()) return env;
    return prompt(who);
  }

  /* Which source supplies it, without reading a secret from the terminal; null when none */
  static String source() {
    if (stdin != null) return "--password-stdin";
    String path = System.getenv("IBMI_PASSWORD_FILE");
    if (path != null && !path.isEmpty()) return "IBMI_PASSWORD_FILE";
    String env = System.getenv("IBMI_PASSWORD");
    return env != null && !env.isEmpty() ? "IBMI_PASSWORD" : null;
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
