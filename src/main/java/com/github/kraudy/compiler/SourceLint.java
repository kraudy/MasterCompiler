package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.SourceType;

/*
 * Mistakes in free-form RPG sources that only show up as a compiler error on the IBM i, found by the scan instead.
 * They are warnings (in the scan log and the report's warnings): the compiler has the last word.
 */
final class SourceLint {
  private static final Pattern CTL_OPT = Pattern.compile("\\bctl-opt\\b([^;]*);", Pattern.CASE_INSENSITIVE);
  private static final Pattern MAIN_PROC = Pattern.compile("\\bdcl-proc\\s+main\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern MAIN_KEYWORD = Pattern.compile("\\bmain\\s*\\(", Pattern.CASE_INSENSITIVE);
  private static final Pattern NOMAIN = Pattern.compile("\\bnomain\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern ACTGRP = Pattern.compile("\\b(dftactgrp|actgrp)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern EXPORTED_PROC = Pattern.compile("\\bdcl-proc\\s+([A-Z0-9_]+)\\b[^;]*\\bexport\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern EXPORTED_P_SPEC = Pattern.compile("^\\s*P\\s*([A-Z0-9_]+)\\s+B\\b.*\\bEXPORT\\b",
      Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
  private static final Pattern BINDER_SYMBOL = Pattern.compile("\\bEXPORT\\s+SYMBOL\\s*\\(\\s*'([^']+)'\\s*\\)",
      Pattern.CASE_INSENSITIVE);

  private SourceLint() {}

  /* The source without // comments and string literals (what a keyword search must not look into) */
  private static String code(String text) {
    return text.replaceAll("(?m)//.*$", "").replaceAll("'[^'\\r\\n]*'", "''");
  }

  /* Names of the procedures a module source exports (free-form DCL-PROC ... EXPORT, fixed-form P-specs), upper case */
  static List<String> exportedProcedures(String text) {
    String code = code(text);
    List<String> names = new ArrayList<String>();
    for (Pattern pattern : new Pattern[] { EXPORTED_PROC, EXPORTED_P_SPEC }) {
      Matcher found = pattern.matcher(code);
      while (found.find()) {
        String name = found.group(1).toUpperCase();
        if (!names.contains(name)) names.add(name);
      }
    }
    return names;
  }

  /* EXPORT SYMBOL('X') names in a binder source, upper case */
  static List<String> binderSymbols(String text) {
    List<String> names = new ArrayList<String>();
    Matcher found = BINDER_SYMBOL.matcher(text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", ""));
    while (found.find()) names.add(found.group(1).trim().toUpperCase());
    return names;
  }

  static List<String> check(String path, ObjectType type, SourceType source, String text) {
    List<String> warnings = new ArrayList<String>();
    if (source != SourceType.RPGLE && source != SourceType.SQLRPGLE) return warnings;
    String code = code(text);
    StringBuilder controlOptions = new StringBuilder();
    Matcher ctl = CTL_OPT.matcher(code);
    while (ctl.find()) controlOptions.append(ctl.group(1)).append(' ');
    String options = controlOptions.toString();

    if (type == ObjectType.PGM && MAIN_PROC.matcher(code).find() && !MAIN_KEYWORD.matcher(options).find()) {
      warnings.add(path + " has DCL-PROC main but no ctl-opt main(main): unless the program also has mainline code, the compiler "
          + "cannot tell how it ends (RNF7023). Add ctl-opt main(main); the DCL-PI of main is the program's parameter list");
    }
    if (type == ObjectType.MODULE && NOMAIN.matcher(options).find() && ACTGRP.matcher(options).find()) {
      warnings.add(path + " is a NOMAIN module with DFTACTGRP/ACTGRP in ctl-opt: they belong to programs and the compiler "
          + "rejects them in a module; remove them (the activation group is chosen when the program or service program is created)");
    }
    return warnings;
  }
}
