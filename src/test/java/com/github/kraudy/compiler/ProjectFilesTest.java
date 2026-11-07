package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The files --remote uploads from a project; git lists them, or the folder does. No IBM i. */
public class ProjectFilesTest {

  @Test
  void test_Ignored_Project_Files_Are_Uploaded(@TempDir Path dir) throws Exception {
    Path repo = Files.createDirectories(dir.resolve("repo"));
    git(repo, "init", "-q");
    Files.write(repo.resolve("README.md"), "readme\n".getBytes(StandardCharsets.UTF_8));
    git(repo, "add", "README.md");
    Path exclude = repo.resolve(".git/info/exclude");
    Files.createDirectories(exclude.getParent());
    Files.write(exclude, "work/\n".getBytes(StandardCharsets.UTF_8));

    Path project = Files.createDirectories(repo.resolve("work/task6"));
    Files.write(project.resolve("build.yaml"), "targets: {}\n".getBytes(StandardCharsets.UTF_8));
    Path source = Files.createDirectories(project.resolve("qsrpglesrc"));
    Files.write(source.resolve("A.rpgle"), "**free\n".getBytes(StandardCharsets.UTF_8));

    Set<String> files = canonical(SshMcpProxy.projectFiles(project.toFile()));
    assertTrue(files.contains(project.resolve("build.yaml").toFile().getCanonicalPath()),
        "the enclosing repo ignores the project: its files must be uploaded");
    assertTrue(files.contains(source.resolve("A.rpgle").toFile().getCanonicalPath()),
        "the enclosing repo ignores the project: its files must be uploaded");
  }

  @Test
  void test_Untracked_File_In_A_Git_Project_Is_Uploaded(@TempDir Path dir) throws Exception {
    Path repo = Files.createDirectories(dir.resolve("repo"));
    git(repo, "init", "-q");
    Files.write(repo.resolve("README.md"), "readme\n".getBytes(StandardCharsets.UTF_8));
    git(repo, "add", "README.md");

    Path project = Files.createDirectories(repo.resolve("project"));
    Files.write(project.resolve("build.yaml"), "targets: {}\n".getBytes(StandardCharsets.UTF_8));
    Path source = Files.createDirectories(project.resolve("src"));
    Files.write(source.resolve("B.rpgle"), "**free\n".getBytes(StandardCharsets.UTF_8));

    Set<String> files = canonical(SshMcpProxy.projectFiles(project.toFile()));
    assertTrue(files.contains(project.resolve("build.yaml").toFile().getCanonicalPath()),
        "an untracked file that git does not ignore is uploaded");
    assertTrue(files.contains(source.resolve("B.rpgle").toFile().getCanonicalPath()),
        "an untracked file that git does not ignore is uploaded");
  }

  private static Set<String> canonical(Set<String> files) throws Exception {
    Set<String> paths = new LinkedHashSet<String>();
    for (String file : files) paths.add(new java.io.File(file).getCanonicalPath());
    return paths;
  }

  private static void git(Path dir, String... args) throws Exception {
    List<String> command = new ArrayList<String>();
    command.add("git");
    for (String arg : args) command.add(arg);
    Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    StringBuilder output = new StringBuilder();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = process.getInputStream().read(buffer)) > 0) output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
    assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + " in " + dir + ":\n" + output);
  }
}
