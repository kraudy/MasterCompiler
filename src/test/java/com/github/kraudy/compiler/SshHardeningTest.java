package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Secrets never synced, password files, the SSH port rule and the --remote / --doctor arguments. No IBM i. */
public class SshHardeningTest {

  @Test
  void test_Secrets_Never_Enter_The_Sync_Set() {
    for (String secret : new String[] { ".env", ".env.local", "app/.env", ".mc/auth-blocked.json", "sub/.mc/x", "tls/server.pem",
        "tls/store.p12", "keys/id_rsa", "keys/id_ed25519.pub", ".netrc", "known_hosts", ".ssh/config", "a/b/secret.KEY" }) {
      assertTrue(SshMcpProxy.isSecret(secret), secret);
    }
    for (String source : new String[] { "build.yaml", "qrpglesrc/id_utils.rpgle", "src/environment.sqlrpgle", "docs/env.md",
        "qcmdsrc/keyboard.cmd" }) {
      assertFalse(SshMcpProxy.isSecret(source), source);
    }
  }

  @Test
  void test_Binary_Files_Are_Not_Text(@TempDir Path dir) throws Exception {
    File text = dir.resolve("a.rpgle").toFile();
    Files.write(text.toPath(), "dcl-s x char(1);\n".getBytes(StandardCharsets.UTF_8));
    File binary = dir.resolve("a.png").toFile();
    Files.write(binary.toPath(), new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 0, 1 });
    assertTrue(SshMcpProxy.isText(text));
    assertFalse(SshMcpProxy.isText(binary));
  }

  @Test
  void test_Password_File_Must_Be_Private_And_Is_Not_Echoed(@TempDir Path dir) throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path file = dir.resolve("pw");
    Files.write(file, "s3cret-pw\nsecond line\n".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    assertEquals("s3cret-pw", Passwords.fromFile(file.toFile()));

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    IllegalArgumentException open = assertThrows(IllegalArgumentException.class, () -> Passwords.fromFile(file.toFile()));
    assertFalse(open.getMessage().contains("s3cret-pw"));

    Files.write(file, "\n".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    assertThrows(IllegalArgumentException.class, () -> Passwords.fromFile(file.toFile()));
  }

  @Test
  void test_Ssh_Port_Rule(@TempDir Path dir) throws Exception {
    assumeTrue(System.getenv("IBMI_SSH_PORT") == null);
    File settings = dir.resolve("settings.json").toFile();
    Files.write(settings.toPath(), ("{ \"code-for-ibmi.connections\": [\n"
        + "  { \"name\": \"WITHPORT\", \"host\": \"a.example.com\", \"port\": 2222, \"username\": \"u\" },\n"
        + "  { \"name\": \"NOPORT\", \"host\": \"b.example.com\", \"username\": \"u\" } ] }").getBytes(StandardCharsets.UTF_8));
    Code4iConfig withPort = Code4iConfig.load(settings, "WITHPORT");
    Code4iConfig noPort = Code4iConfig.load(settings, "NOPORT");

    SshTarget.Port flag = SshTarget.Port.of(new ArgParser(new String[] { "--ssh", "--ssh-port", "2200" }), withPort);
    assertEquals(2200, flag.number);
    assertEquals("--ssh-port", flag.origin);
    assertEquals(2222, SshTarget.Port.of(new ArgParser(new String[] { "--ssh" }), withPort).number);
    assertEquals(22, SshTarget.Port.of(new ArgParser(new String[] { "--ssh" }), noPort).number);
    assertEquals("default", SshTarget.Port.of(new ArgParser(new String[] { "--ssh" }), null).origin);
  }

  @Test
  void test_Remote_And_Doctor_Arguments() {
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] { "--ssh-port", "99999" }));
    assertThrows(IllegalArgumentException.class, () -> validate("--remote"));                          // needs --project
    assertThrows(IllegalArgumentException.class, () -> validate("--remote", "--project", ".", "-f", "x.yaml"));
    assertThrows(IllegalArgumentException.class, () -> validate("--remote", "--project", ".", "--since", "HEAD~1"));
    assertThrows(IllegalArgumentException.class, () -> validate("--mcp", "--password-stdin", "--project", "."));
    assertThrows(IllegalArgumentException.class, () -> validate("--ssh-port", "2222", "--mcp", "--project", "."));
    assertThrows(IllegalArgumentException.class, () -> validate("--ssh"));                              // still needs --mcp
    validate("--doctor", "--code4i", "--ssh-port", "2222");
    validate("--explain-connection");
    validate("--mcp", "--ssh", "--ssh-port", "2222", "--project", ".");
  }

  @Test
  void test_Remote_Args_Drop_The_Connection_Options() {
    ArgParser parser = new ArgParser(new String[] { "--remote", "--project", "/home/me/app", "--code4i", "--connection", "DEV",
        "--ssh-port", "2222", "--password-stdin", "-v", "--libl", "A B", "--diff" });
    assertEquals(Arrays.asList("-v", "--libl", "A B", "--diff", "--project", "/home/u/mc/app"), parser.remoteArgs("/home/u/mc/app"));
  }

  private static void validate(String... args) {
    new ArgParser(args).validate();
  }
}
