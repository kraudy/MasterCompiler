package com.github.kraudy.compiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/*
 * Files changed since a git ref ({@code --since <ref>}): committed and uncommitted
 * changes against the ref plus untracked files, as canonical absolute paths.
 * Git decides what changed, so fresh clones and branch switches do not rebuild everything
 * (unlike --diff, which compares file mtimes with object creation times).
 */
public final class GitChanges {

  private GitChanges() {}

  /* Top-level directory of the git repository containing dir */
  public static String root(String dir) {
    return canonical(run(dir, "git", "rev-parse", "--show-toplevel").get(0));
  }

  /* Every file git tracks plus untracked files that are not ignored */
  public static Set<String> allFiles(String dir) {
    String root = root(dir);
    List<String> relative = new ArrayList<String>();
    relative.addAll(run(root, "git", "ls-files"));
    relative.addAll(run(root, "git", "ls-files", "--others", "--exclude-standard"));

    Set<String> files = new LinkedHashSet<String>();
    for (String path : relative) {
      if (!path.trim().isEmpty()) files.add(canonical(new File(root, path.trim()).getPath()));
    }
    return files;
  }

  public static Set<String> changedFiles(String dir, String ref) {
    String root = root(dir);

    List<String> relative = new ArrayList<String>();
    relative.addAll(run(root, "git", "diff", "--name-only", ref));
    relative.addAll(run(root, "git", "ls-files", "--others", "--exclude-standard"));

    Set<String> changed = new LinkedHashSet<String>();
    for (String path : relative) {
      if (!path.trim().isEmpty()) changed.add(canonical(new File(root, path.trim()).getPath()));
    }
    return changed;
  }

  public static String canonical(String path) {
    try {
      return new File(path).getCanonicalPath();
    } catch (Exception e) {
      return new File(path).getAbsolutePath();
    }
  }

  private static List<String> run(String dir, String... command) {
    List<String> lines = new ArrayList<String>();
    try {
      Process process = new ProcessBuilder(command)
          .directory(new File(dir))
          .redirectErrorStream(true)
          .start();
      try (BufferedReader out = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = out.readLine()) != null) lines.add(line);
      }
      if (process.waitFor() != 0) {
        throw new CompilerException("git failed in " + dir + ": " + String.join(" ", command) + "\n" + String.join("\n", lines));
      }
    } catch (CompilerException e) {
      throw e;
    } catch (Exception e) {
      throw new CompilerException("Could not run git in " + dir + " (is it a git repository?)", e);
    }
    if (lines.isEmpty() && command[1].equals("rev-parse")) {
      throw new CompilerException("Not a git repository: " + dir);
    }
    return lines;
  }
}
