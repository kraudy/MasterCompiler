package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;

/*
 * --target NAME[,NAME.TYPE...]: build only these targets of the spec, not their dependents or dependencies.
 * A bare NAME takes every target with that object name (the module and the service program of one name too);
 * NAME.TYPE (DUMPSTATIC.SRVPGM) takes the one of that object type.
 */
final class TargetSelection {
  private static final Pattern NAME = Pattern.compile("[A-Z0-9_#@$]{1,10}");

  private final String objectName;
  private final ObjectType objectType;  // null: any
  private final String text;

  private TargetSelection(String text, String objectName, ObjectType objectType) {
    this.text = text;
    this.objectName = objectName;
    this.objectType = objectType;
  }

  /* Names checked now, before any connection; throws IllegalArgumentException */
  static List<TargetSelection> parse(String selection) {
    List<TargetSelection> out = new ArrayList<TargetSelection>();
    for (String part : selection.split(",")) {
      String text = part.trim().toUpperCase(Locale.ROOT);
      String[] names = text.split("\\.", -1);
      if (names.length > 2 || !NAME.matcher(names[0]).matches()) {
        throw new IllegalArgumentException("--target needs NAME or NAME.TYPE (e.g. DUMPSTATIC or DUMPSTATIC.SRVPGM), comma-separated: "
            + part.trim());
      }
      ObjectType type = null;
      if (names.length == 2) {
        try {
          type = ObjectType.valueOf(names[1]);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("--target: " + names[1] + " is not an object type (PGM, MODULE, SRVPGM, FILE, ...): " + part.trim());
        }
      }
      out.add(new TargetSelection(text, names[0], type));
    }
    return out;
  }

  boolean matches(TargetKey key) {
    return key.getObjectName().equalsIgnoreCase(objectName) && (objectType == null || key.getObjectTypeEnum() == objectType);
  }

  /* The spec's targets these selections name; throws IllegalArgumentException for a selection that names none */
  static List<TargetKey> select(Iterable<TargetKey> targets, List<TargetSelection> selections) {
    Map<TargetKey, Boolean> chosen = new LinkedHashMap<TargetKey, Boolean>();
    for (TargetSelection selection : selections) {
      boolean found = false;
      for (TargetKey key : targets) {
        if (selection.matches(key)) {
          chosen.put(key, Boolean.TRUE);
          found = true;
        }
      }
      if (!found) {
        StringBuilder known = new StringBuilder();
        for (TargetKey key : targets) known.append(known.length() == 0 ? "" : ", ").append(key.getObjectName()).append('.').append(key.getObjectTypeEnum());
        throw new IllegalArgumentException("--target " + selection.text + ": the spec has no such target. Targets: " + known);
      }
    }
    return new ArrayList<TargetKey>(chosen.keySet());
  }
}
