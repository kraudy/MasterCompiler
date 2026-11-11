package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/*
 * protectedLibs: the libraries with production data that run_command, call_program and copy_rows never touch (and
 * without which they refuse). Where the line goes: the project's spec file, else the mc-base.yaml the scan reads.
 * Shared by the refusals, --doctor and the next steps, so all of them name the same file and the same line.
 */
final class ProtectedLibs {
  static final String KEY = "protectedLibs";
  static final String EXAMPLE = KEY + ": [PRODLIB1, PRODLIB2]";
  static final String TOOLS = "run_command, call_program and copy_rows";
  static final String REFUSALS = "run_command refuses until the list is set, and call_program refuses a program that "
      + "changes files; " + TOOLS + " never touch the libraries listed";
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private ProtectedLibs() {}

  /* The file of a project folder: --spec or build.yaml when there, null for a TOBi project, else mc-base.yaml */
  static File file(File project, String specName) {
    if (specName != null) return new File(project, specName);
    if (new File(project, "build.yaml").isFile()) return new File(project, "build.yaml");
    if (new File(project, "Rules.mk").isFile()) return null;
    return new File(project, SpecGenerator.DEFAULT_BASE_NAME);
  }

  /* The libraries the file lists (empty: none, or no such file) */
  static List<String> read(File file) {
    List<String> libs = new ArrayList<String>();
    if (file == null || !file.isFile()) return libs;
    try {
      for (JsonNode lib : YAML.readTree(file).path(KEY)) {
        if (!lib.asText("").trim().isEmpty()) libs.add(lib.asText().trim().toUpperCase());
      }
    } catch (Exception unreadable) { /* the build reports a spec it cannot read */ }
    return libs;
  }

  /* What to do when it is missing: the exact line and the file it goes in */
  static String howToSet(File file) {
    return howToSet(file != null ? file.getAbsolutePath() : null, file != null && file.isFile());
  }

  /* path as the user sees it (a path on their PC may not be one here); exists: whether the file is there */
  static String howToSet(String path, boolean exists) {
    if (path == null) {
      return "A TOBi project (Rules.mk) has no place for it: convert it once with --from-tobi <root> --generate-only -o "
          + "build.yaml, then add the line " + EXAMPLE + " to that build.yaml.";
    }
    return "Ask the user which libraries hold production data, then add this top-level line to " + path
        + (exists ? "" : " (create the file)") + ": " + EXAMPLE + " (a YAML list of library names). " + REFUSALS + ". The spec is read at each call: no restart.";
  }
}
