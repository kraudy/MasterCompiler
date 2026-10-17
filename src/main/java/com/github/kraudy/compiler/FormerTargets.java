package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * The objects MC built, kept in .mc/built-objects.json (LIB.NAME.TYPE -> the target that built it), so a target taken
 * out of the spec is not forgotten: plan and build list its object in formerTargets, and clean formerTargets: true
 * deletes it.
 */
final class FormerTargets {
  private static final Logger logger = LoggerFactory.getLogger(FormerTargets.class);
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  static final String FILE = ".mc/built-objects.json";

  private FormerTargets() {}

  /* LIB.NAME.TYPE of a resolved target string LIB.NAME.TYPE.SOURCE */
  static String object(String target) {
    String[] parts = target.toUpperCase().split("\\.");
    return parts.length < 3 ? target.toUpperCase() : parts[0] + "." + parts[1] + "." + parts[2];
  }

  static ObjectNode read(File project) {
    File file = new File(project, FILE);
    try {
      if (file.isFile()) {
        JsonNode all = mapper.readTree(file);
        if (all.isObject()) return (ObjectNode) all;
      }
    } catch (Exception e) {
      logger.warn("Could not read {}: {}", FILE, e.getMessage());
    }
    return mapper.createObjectNode();
  }

  static void write(File project, ObjectNode all) {
    File file = new File(project, FILE);
    try {
      file.getParentFile().mkdirs();
      mapper.writeValue(file, all);
    } catch (Exception e) {
      logger.warn("Could not write {}: {}", FILE, e.getMessage());
    }
  }

  /* After a build or plan: record what was built (not in a plan), then the recorded objects no target has now */
  static void update(File project, BuildReport report) {
    ObjectNode all = read(project);
    boolean changed = false;
    Set<String> current = new HashSet<String>();
    for (BuildReport.TargetResult t : report.targets) {
      String object = object(t.target);
      current.add(object);
      if (!report.dryRun && BuildReport.BUILT.equals(t.status) && !t.target.toUpperCase().startsWith("*CURLIB.")) {
        if (!t.target.equals(all.path(object).asText(null))) {
          all.put(object, t.target);
          changed = true;
        }
      }
    }
    if (changed) write(project, all);
    List<String> former = new ArrayList<String>();
    for (Iterator<Map.Entry<String, JsonNode>> it = all.fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> e = it.next();
      if (!current.contains(e.getKey())) former.add(e.getValue().asText());
    }
    if (!former.isEmpty()) report.formerTargets = former;
  }

  /* The recorded targets whose object no current target builds */
  static List<String> former(File project, Set<String> currentObjects) {
    List<String> former = new ArrayList<String>();
    for (Iterator<Map.Entry<String, JsonNode>> it = read(project).fields(); it.hasNext();) {
      Map.Entry<String, JsonNode> e = it.next();
      if (!currentObjects.contains(e.getKey())) former.add(e.getValue().asText());
    }
    return former;
  }

  static void forget(File project, String target) {
    ObjectNode all = read(project);
    if (all.remove(object(target)) != null) write(project, all);
  }
}
