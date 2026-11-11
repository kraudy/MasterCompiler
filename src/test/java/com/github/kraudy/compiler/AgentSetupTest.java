package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/* First-run setup for an agent: --mcp with no project yet, open_project, skills folders, next steps, impact names */
public class AgentSetupTest {

  @Test
  void workingProjectIsARepositoryNeverHome() throws Exception {
    Path repo = Files.createTempDirectory("repo");
    Files.createDirectories(repo.resolve(".git"));
    File sub = Files.createDirectories(repo.resolve("qrpglesrc")).toFile();
    File root = repo.toFile().getCanonicalFile();
    assertEquals(root, ArgParser.workingProject(sub, "/nowhere"));        // a client started in a subfolder
    assertNull(ArgParser.workingProject(root, root.getPath()));           // the home folder, even as a repository
    Path plain = Files.createTempDirectory("plain");
    assertNull(ArgParser.workingProject(plain.toFile(), "/nowhere"));     // no build.yaml, Rules.mk or .git
    Files.write(plain.resolve("build.yaml"), "targets: {}\n".getBytes());
    assertEquals(plain.toFile().getCanonicalFile(), ArgParser.workingProject(plain.toFile(), "/nowhere"));
  }

  @Test
  void openProjectSwitchesAndKeepsTheLastOneOnError() throws Exception {
    Path first = Files.createTempDirectory("first");
    Path second = Files.createTempDirectory("second");
    Files.write(second.resolve("build.yaml"), "targets: {}\n".getBytes());
    ArgParser parser = new ArgParser(new String[] { "--mcp", "--ssh", "--project", first.toString() });
    parser.validate();
    assertTrue(parser.hasScan());

    parser.openProject(second.toString());
    assertEquals(second.toFile().getCanonicalPath(), parser.getProjectRoot());
    assertTrue(parser.hasFile());
    assertFalse(parser.hasScan());

    assertThrows(IllegalArgumentException.class, () -> parser.openProject("relative/repo"));
    assertThrows(IllegalArgumentException.class, () -> parser.openProject(second.resolve("missing").toString()));
    assertEquals(second.toFile().getCanonicalPath(), parser.getProjectRoot());
    assertTrue(parser.hasFile());
  }

  @Test
  void mcpSshTakesAProjectFolderNotASpec() {
    assertThrows(IllegalArgumentException.class, new ArgParser(new String[] { "--mcp", "--ssh", "--scan", "." })::validate);
  }

  @Test
  void anUnwritableSkillsFolderSaysWhereElse() throws Exception {
    Path home = Files.createTempDirectory("home");
    Files.createDirectories(home.resolve(".claude"));
    Files.write(home.resolve(".claude/skills"), new byte[0]);  // a file where the folder should be
    VscodeSetup.SkillsFolderException e = assertThrows(VscodeSetup.SkillsFolderException.class,
        () -> VscodeSetup.installSkills(home.resolve(".claude/skills").toFile()));
    assertTrue(e.getMessage().contains(home.resolve(".claude/skills").toString()), e.getMessage());
    assertTrue(e.getMessage().contains("--skills-dir"), e.getMessage());
    assertTrue(e.getMessage().contains("<repository>/.claude/skills"), e.getMessage());
  }

  @Test
  void nextStepsNameThisJarAndTheRestart() {
    String text = NextSteps.text(new NextSteps.Known("pub400.com", "ME", null, 2222));
    assertTrue(text.contains("claude mcp add"), text);
    assertTrue(text.contains("codex mcp add"), text);
    assertTrue(text.contains("opencode.json"), text);
    assertTrue(text.contains("--setup-vscode"), text);
    assertTrue(text.contains(VscodeSetup.jarPath()), text);
    assertTrue(text.contains("--ssh-port 2222"), text);
    assertTrue(text.contains("IBMI_HOSTNAME=pub400.com"), text);
    assertTrue(text.contains("\"IBMI_HOSTNAME\": \"pub400.com\""), text);
    assertTrue(text.contains("/mcp"), text);
    String code4i = NextSteps.text(new NextSteps.Known("h", "u", "DEV", 22));
    assertTrue(code4i.contains("--code4i --connection DEV"), code4i);
    assertFalse(code4i.contains("IBMI_HOSTNAME"), code4i);
  }

  @Test
  void shellWordsAreQuotedOnlyWhenNeeded() {
    assertEquals("/opt/mc/MasterCompiler.jar", NextSteps.word("/opt/mc/MasterCompiler.jar", false));
    assertEquals("'secret-tool lookup service ibmi'", NextSteps.word("secret-tool lookup service ibmi", false));
    /* PowerShell doubles a quote inside quotes; POSIX shells close and reopen them */
    assertEquals(File.separatorChar == '\\' ? "'it''s'" : "'it'\\''s'", NextSteps.word("it's", false));
    assertEquals("\"a \\\"b\\\"\"", NextSteps.word("a \"b\"", true));
  }

  @Test
  void impactNamesAFileByPathNameStemOrMember() {
    String path = "/repo/qrpgleref/base36_h.rpgleinc";
    for (String typed : Arrays.asList("BASE36_H", "base36_h.rpgleinc", "qrpgleref/base36_h.rpgleinc", "QRPGLEREF,BASE36_H",
        "QRPGLEREF(BASE36_H)")) {
      assertTrue(SourceNaming.namesFile(typed, path), typed);
    }
    assertTrue(SourceNaming.namesFile(path, path));                    // the absolute path itself
    assertFalse(SourceNaming.namesFile("BASE36", path));
    assertFalse(SourceNaming.namesFile("ref/base36_h.rpgleinc", path));  // a path matches whole folder names only
  }

  @Test
  void impactTakesTheShortTargetName() {
    TargetKey module = new TargetKey("CURLIB.SRV_BASE36.MODULE.RPGLE");
    TargetKey srvpgm = new TargetKey("CURLIB.SRV_BASE36.SRVPGM.BND");
    assertEquals(Arrays.asList(module),
        TargetSelection.select(Arrays.asList(module, srvpgm), TargetSelection.parse("SRV_BASE36.MODULE")));
  }

  @Test
  void aSourceWarningIsShownOnceWhileItLasts() {
    Set<String> shown = new HashSet<String>();
    BuildReport first = new BuildReport();
    first.warnings = Arrays.asList("two sources build A");
    first.dropShownWarnings(shown);
    assertEquals(Arrays.asList("two sources build A"), first.warnings);
    assertNull(first.warningsShownEarlier);

    BuildReport again = new BuildReport();
    again.warnings = Arrays.asList("two sources build A");
    again.dropShownWarnings(shown);
    assertNull(again.warnings);
    assertEquals(Integer.valueOf(1), again.warningsShownEarlier);

    BuildReport fixed = new BuildReport();  // gone: the next time it comes back, it is new again
    fixed.dropShownWarnings(shown);
    BuildReport back = new BuildReport();
    back.warnings = Arrays.asList("two sources build A");
    back.dropShownWarnings(shown);
    assertEquals(Arrays.asList("two sources build A"), back.warnings);
  }
}
