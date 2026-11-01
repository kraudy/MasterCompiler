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
 * A bare NAME takes every target with that object name in one library (the module and the service program of one
 * name too); NAME.TYPE (DUMPSTATIC.SRVPGM) takes the one of that object type. The spec's own form works as well,
 * as much of it as needed: LIB/NAME, LIB.NAME.TYPE, LIB.NAME.TYPE.SOURCETYPE (ROBKRAUDY2.ARTPSETL.PGM.RPGLE).
 * A name in several libraries must be qualified with its library.
 */
final class TargetSelection {
  private static final Pattern NAME = Pattern.compile("[A-Z0-9_#@$]{1,10}");

  private final String library;     // null: any
  private final String objectName;
  private final ObjectType objectType;  // null: any
  private final String sourceType;      // null: any
  private final String text;

  private TargetSelection(String text, String library, String objectName, ObjectType objectType, String sourceType) {
    this.text = text;
    this.library = library;
    this.objectName = objectName;
    this.objectType = objectType;
    this.sourceType = sourceType;
  }

  /* Names checked now, before any connection; throws IllegalArgumentException */
  static List<TargetSelection> parse(String selection) {
    List<TargetSelection> out = new ArrayList<TargetSelection>();
    for (String part : selection.split(",")) {
      String text = part.trim().toUpperCase(Locale.ROOT);
      String bad = "--target needs NAME, NAME.TYPE, LIB/NAME or LIB.NAME.TYPE[.SOURCETYPE] (e.g. DUMPSTATIC, DUMPSTATIC.SRVPGM, "
          + "MYLIB.DUMPSTATIC.SRVPGM.RPGLE), comma-separated: " + part.trim();
      String library = null;
      if (text.indexOf('/') >= 0) {
        String[] lib = text.split("/", -1);
        if (lib.length != 2 || !NAME.matcher(lib[0]).matches()) throw new IllegalArgumentException(bad);
        library = lib[0];
        text = lib[1];
      }
      String[] names = text.split("\\.", -1);
      /* Without a slash, three or four parts start with the library */
      int at = 0;
      if (library == null && names.length >= 3) {
        library = names[0];
        at = 1;
        if (!NAME.matcher(library).matches()) throw new IllegalArgumentException(bad);
      }
      int count = names.length - at;
      if (count < 1 || count > 3 || (library != null && at == 0 && count > 2) || !NAME.matcher(names[at]).matches()) {
        throw new IllegalArgumentException(bad);
      }
      ObjectType type = null;
      String source = null;
      if (count >= 2) {
        try {
          type = ObjectType.valueOf(names[at + 1]);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("--target: " + names[at + 1] + " is not an object type (PGM, MODULE, SRVPGM, FILE, ...): " + part.trim());
        }
      }
      if (count == 3) {
        source = names[at + 2];
        if (!NAME.matcher(source).matches()) throw new IllegalArgumentException(bad);
      }
      out.add(new TargetSelection(part.trim().toUpperCase(Locale.ROOT), library, names[at], type, source));
    }
    return out;
  }

  boolean matches(TargetKey key) {
    return key.getObjectName().equalsIgnoreCase(objectName) && (objectType == null || key.getObjectTypeEnum() == objectType)
        && (library == null || key.getLibrary().equalsIgnoreCase(library))
        && (sourceType == null || key.getSourceType().equalsIgnoreCase(sourceType));
  }

  /* The spec's targets these selections name; throws IllegalArgumentException for a selection that names none */
  static List<TargetKey> select(Iterable<TargetKey> targets, List<TargetSelection> selections) {
    Map<TargetKey, Boolean> chosen = new LinkedHashMap<TargetKey, Boolean>();
    for (TargetSelection selection : selections) {
      List<TargetKey> found = new ArrayList<TargetKey>();
      for (TargetKey key : targets) if (selection.matches(key)) found.add(key);
      if (found.isEmpty()) {
        StringBuilder known = new StringBuilder();
        for (TargetKey key : targets) known.append(known.length() == 0 ? "" : ", ").append(key.getObjectName()).append('.').append(key.getObjectTypeEnum());
        throw new IllegalArgumentException("--target " + selection.text + ": the spec has no such target. Targets: " + known);
      }
      /* One name in several libraries is not guessed: the library is asked for */
      if (selection.library == null) {
        Map<String, Boolean> libraries = new LinkedHashMap<String, Boolean>();
        for (TargetKey key : found) libraries.put(key.getLibrary().toUpperCase(Locale.ROOT), Boolean.TRUE);
        if (libraries.size() > 1) {
          StringBuilder qualified = new StringBuilder();
          for (TargetKey key : found) qualified.append(qualified.length() == 0 ? "" : ", ").append(key.asString());
          throw new IllegalArgumentException("--target " + selection.text + " is in several libraries; name the library "
              + "(LIB/NAME or LIB.NAME.TYPE): " + qualified);
        }
      }
      for (TargetKey key : found) chosen.put(key, Boolean.TRUE);
    }
    return new ArrayList<TargetKey>(chosen.keySet());
  }
}
