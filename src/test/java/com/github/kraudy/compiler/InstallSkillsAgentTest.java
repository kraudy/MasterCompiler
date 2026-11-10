package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/* --install-skills: Copilot stays the default; --agent adds the terminal agents' folders */
public class InstallSkillsAgentTest {
  private static final String HOME = "/home/u";

  @Test
  void copilotIsTheDefault() {
    assertEquals(Arrays.asList(new File(HOME, ".copilot/skills")), VscodeSetup.skillDirs(null, HOME));
  }

  @Test
  void agentsMapToTheirFolders() {
    assertEquals(Arrays.asList(new File(HOME, ".claude/skills"), new File(HOME, ".agents/skills")),
        VscodeSetup.skillDirs("claude, agents", HOME));
  }

  @Test
  void allIsThreeFoldersWithoutDuplicates() {
    List<File> dirs = VscodeSetup.skillDirs("copilot,all", HOME);
    assertEquals(3, dirs.size());
    assertTrue(dirs.contains(new File(HOME, ".agents/skills")));
  }

  @Test
  void unknownAgentIsRejectedAtValidation() {
    ArgParser parser = new ArgParser(new String[] {"--install-skills", "--agent", "vim"});
    assertThrows(IllegalArgumentException.class, parser::validate);
  }

  @Test
  void emptyAgentSelectionIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> VscodeSetup.skillDirs(",", HOME));
    assertThrows(IllegalArgumentException.class, () -> VscodeSetup.skillDirs("", HOME));
  }

  @Test
  void agentNeedsInstallSkills() {
    ArgParser parser = new ArgParser(new String[] {"--agent", "claude"});
    assertThrows(IllegalArgumentException.class, parser::validate);
  }

  @Test
  void skillsDirIsOneFolderAndNotWithAgent() {
    ArgParser parser = new ArgParser(new String[] {"--install-skills", "--skills-dir", "/sandbox/home/.claude/skills"});
    parser.validate();
    assertEquals("/sandbox/home/.claude/skills", parser.getSkillsDir());
    assertThrows(IllegalArgumentException.class,
        new ArgParser(new String[] {"--install-skills", "--skills-dir", "/d", "--agent", "claude"})::validate);
    assertThrows(IllegalArgumentException.class, new ArgParser(new String[] {"--skills-dir", "/d"})::validate);
  }

  /* --setup-vscode in a subfolder of a repository: .git is found upwards; only real ignore lines for .mc/ count */
  @Test
  void gitRootIsFoundFromASubfolder() throws Exception {
    Path repo = Files.createTempDirectory("repo");
    Files.createDirectories(repo.resolve(".git"));
    File sub = Files.createDirectories(repo.resolve("app/src")).toFile();
    assertEquals(repo.toFile().getAbsoluteFile(), VscodeSetup.gitRoot(sub));
  }

  @Test
  void ignoresMcReadsLinesNotText() {
    assertTrue(VscodeSetup.ignoresMc("target/\n.mc/\n", false));
    assertFalse(VscodeSetup.ignoresMc("# .mc/ is MasterCompiler's\n", false));
    assertFalse(VscodeSetup.ignoresMc(".mc/\n!.mc/\n", false));
    assertFalse(VscodeSetup.ignoresMc("/.mc/\n", false));  // the root's own .mc/ only, not a subfolder's
    assertTrue(VscodeSetup.ignoresMc("/.mc/\n", true));
    assertFalse(VscodeSetup.ignoresMc(" .mc/\n", false));  // a leading space is part of git's pattern
    assertTrue(VscodeSetup.negatesMc("build/\n!.mc/\n"));
    assertTrue(VscodeSetup.negatesMc("!*/\n"));
    assertFalse(VscodeSetup.ignoresMc(".mc/\n!*\n", false));
    assertFalse(VscodeSetup.negatesMc(".mc/\n"));
  }

  @Test
  void installCopiesAllSkillsIncludingTheNewOne() throws Exception {
    Path dir = Files.createTempDirectory("skills");
    assertEquals(VscodeSetup.SKILLS.size(), VscodeSetup.installSkills(dir.toFile()));
    assertTrue(new File(dir.toFile(), "mastercompiler-setup/SKILL.md").isFile());
    assertTrue(new File(dir.toFile(), "mastercompiler-vscode/SKILL.md").isFile());
  }
}
