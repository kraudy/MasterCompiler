package com.github.kraudy.compiler;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
 * run_command and protectedLibs: these display commands only read the objects they name, so they may name a protected
 * library. What they write is their output: the spooled listing (*PRINT), or an outfile, which must be in the current
 * library (qualified: *CURLIB/NAME or CURLIB/NAME, the current library not protected) or in QTEMP. Every parameter is
 * in keyword form, so no outfile hides in a positional one.
 */
final class ReadOnlyCommands {

  /* command -> its parameters that write (an outfile) */
  static final Map<String, Set<String>> COMMANDS = new HashMap<String, Set<String>>();
  static {
    Set<String> outfile = Collections.singleton("OUTFILE");
    Set<String> none = Collections.emptySet();
    for (String c : new String[] { "DSPFFD", "DSPFD", "DSPDBR", "DSPPGMREF", "DSPOBJD", "DSPOBJAUT", "DSPBNDDIR" }) COMMANDS.put(c, outfile);
    for (String c : new String[] { "DSPPGM", "DSPSRVPGM", "DSPMOD", "DSPLIB", "DSPLIBD", "DSPDTAARA", "DSPCMD", "DSPMSGD" }) COMMANDS.put(c, none);
  }

  private static final Set<String> QUALIFIERS = new HashSet<String>(Arrays.asList("QSYS", "*SYSTEM"));

  private ReadOnlyCommands() {}

  /* The command's name, without a QSYS/ (or *SYSTEM/) qualifier, upper case */
  static String name(String command) {
    String first = command.trim().split("[\\s(]", 2)[0].toUpperCase(Locale.ROOT);
    int slash = first.indexOf('/');
    return slash > 0 && QUALIFIERS.contains(first.substring(0, slash)) ? first.substring(slash + 1) : first;
  }

  static boolean isOne(String command) {
    String first = command.trim().split("[\\s(]", 2)[0];
    return COMMANDS.containsKey(name(command)) && (!first.contains("/") || QUALIFIERS.contains(first.substring(0, first.indexOf('/')).toUpperCase(Locale.ROOT)));
  }

  /* As it runs: QSYS's command, never one of that name earlier in the library list */
  static String fromQsys(String command) {
    String trimmed = command.trim();
    String first = trimmed.split("[\\s(]", 2)[0];
    return "QSYS/" + name(command) + trimmed.substring(first.length());
  }

  /* null when it may run although it names protected libraries; else why not */
  static String check(String command, String curlib, List<String> protectedLibs) {
    String name = name(command);
    Set<String> writes = COMMANDS.get(name);
    if (writes == null) return name + " is not one of the display commands allowed on protectedLibs: " + COMMANDS.keySet();
    Map<String, String> params = keywords(command.trim().substring(command.trim().split("[\\s(]", 2)[0].length()));
    if (params == null) {
      return name + " on protectedLibs takes every parameter in keyword form, e.g. " + name + " FILE(LIB/NAME) OUTPUT(*PRINT)";
    }
    for (String keyword : writes) {
      String value = params.get(keyword);
      if (value == null) continue;
      String[] parts = value.trim().toUpperCase(Locale.ROOT).split("/");
      String library = parts.length == 2 ? parts[0].trim() : null;
      if ("QTEMP".equals(library)) continue;  // the job's own: gone when it ends
      boolean inCurlib = "*CURLIB".equals(library) || (library != null && library.equalsIgnoreCase(curlib));
      if (!inCurlib) {
        return keyword + "(" + value.trim() + ") must be in the current library or QTEMP, qualified: " + keyword
            + "(*CURLIB/NAME)";
      }
      for (String p : protectedLibs) {
        if (p.trim().equalsIgnoreCase(curlib)) return "the current library " + curlib + " is in protectedLibs: no outfile there";
      }
    }
    return null;
  }

  /* KEYWORD(value) KEYWORD(value) ... with nested parentheses and quotes; null when anything else is there */
  static Map<String, String> keywords(String rest) {
    Map<String, String> params = new LinkedHashMap<String, String>();
    int i = 0, n = rest.length();
    while (true) {
      while (i < n && Character.isWhitespace(rest.charAt(i))) i++;
      if (i == n) return params;
      int start = i;
      while (i < n && Character.isLetterOrDigit(rest.charAt(i))) i++;
      if (i == start || i == n || rest.charAt(i) != '(') return null;
      String keyword = rest.substring(start, i).toUpperCase(Locale.ROOT);
      int depth = 0, valueStart = i + 1;
      boolean quoted = false;
      for (; i < n; i++) {
        char c = rest.charAt(i);
        if (c == '\'') quoted = !quoted;
        else if (!quoted && c == '(') depth++;
        else if (!quoted && c == ')' && --depth == 0) break;
      }
      if (i == n || params.containsKey(keyword)) return null;  // not closed, or the keyword twice
      params.put(keyword, rest.substring(valueStart, i));
      i++;
    }
  }
}
