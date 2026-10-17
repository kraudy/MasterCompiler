package com.github.kraudy.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Database files a program can change, read from its source (for protectedLibs):
 *
 *   RPG fixed F-spec   type U (update) or O (output), or A in column 20 (add records); DISK device only
 *   RPG free dcl-f     usage(*update / *delete / *output); DISK (the default device) only; EXTFILE('LIB/FILE')
 *                      names the file actually opened
 *   embedded SQL       INSERT INTO, UPDATE ... SET, DELETE FROM, MERGE INTO
 *
 * Keys are FILE or LIB/FILE (when the source names the library); values are update / output / delete.
 * Overrides (OVRDBF) and dynamic SQL are not seen.
 */
final class FileWrites {
  private static final String NAME = "([A-Z0-9_$#@\"]+(?:[/.][A-Z0-9_$#@\"]+)?)";
  private static final Pattern SQL_INSERT = Pattern.compile("\\bINSERT\\s+INTO\\s+" + NAME, Pattern.CASE_INSENSITIVE);
  private static final Pattern SQL_UPDATE = Pattern.compile("\\bUPDATE\\s+" + NAME + "(?:\\s+[A-Z0-9_]+)?\\s+SET\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SQL_DELETE = Pattern.compile("\\bDELETE\\s+FROM\\s+" + NAME, Pattern.CASE_INSENSITIVE);
  private static final Pattern SQL_MERGE = Pattern.compile("\\bMERGE\\s+INTO\\s+" + NAME, Pattern.CASE_INSENSITIVE);
  private static final Pattern DCL_F = Pattern.compile("^\\s*DCL-F\\s+([A-Z0-9_$#@]+)(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern USAGE = Pattern.compile("\\bUSAGE\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern EXTFILE = Pattern.compile("\\bEXTFILE\\s*\\(\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern OTHER_DEVICE = Pattern.compile("\\b(PRINTER|WORKSTN|SPECIAL|SEQ)\\b", Pattern.CASE_INSENSITIVE);

  private FileWrites() {}

  /* Commands and APIs with effects outside database files: jobs, the IFS, Qshell, other commands run by text */
  static final List<String> OTHER_EFFECTS = java.util.Arrays.asList("SBMJOB", "ENDJOB", "STRQSH", "QSH", "QCMDEXC",
      "QCAPCMD", "SYSTEM", "CALL", "RUNSQL", "RUNSQLSTM", "CPYTOSTMF", "CPYFRMSTMF", "CPY", "MOV", "RNM", "RMVLNK", "DEL",
      "CRTDIR", "RMVDIR", "CHGJOB", "DLTF", "DLTOBJ", "CLRPFM", "CPYF", "QSNDDTAQ", "SNDDTAQ", "STRTCPSVR", "ENDTCPSVR",
      "STRSBS", "ENDSBS", "CHGSYSVAL", "GRTOBJAUT", "RVKOBJAUT", "CHGOBJOWN", "CHGAUT", "SNDMSG", "SNDBRKMSG",
      "CRTDUPOBJ", "CHGOBJ", "WRKACTJOB", "PWRDWNSYS", "RSTOBJ", "SAVOBJ", "SAVLIB", "RSTLIB", "STRSRVJOB",
      "QZSHSYSTEM", "QP0ZSYSTEM", "JAVA", "RUNJVA", "QSYGETPH", "QWTSETP", "QUSCRTUS", "QUSCHGUS", "QMHSNDPM");
  private static final Pattern EFFECT = Pattern.compile("(?<![A-Z0-9_$#@])(" + String.join("|", OTHER_EFFECTS)
      + ")(?![A-Z0-9_$#@])", Pattern.CASE_INSENSITIVE);

  /* "SBMJOB (lines 12, 40)": the effects outside database files the source names, comments left out */
  static List<String> otherEffects(List<String> lines) {
    Map<String, List<Integer>> found = new LinkedHashMap<String, List<Integer>>();
    boolean inComment = false;
    for (int n = 0; n < lines.size(); n++) {
      String line = lines.get(n);
      String trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("--") || (line.length() > 6 && line.charAt(6) == '*')) continue;
      StringBuilder code = new StringBuilder();
      for (int i = 0; i < line.length(); i++) {  // CL / C comments: /* ... */
        if (!inComment && line.startsWith("/*", i)) { inComment = true; i++; continue; }
        if (inComment && line.startsWith("*/", i)) { inComment = false; i++; continue; }
        if (!inComment) code.append(line.charAt(i));
      }
      java.util.regex.Matcher m = EFFECT.matcher(code);
      while (m.find()) {
        List<Integer> at = found.computeIfAbsent(m.group(1).toUpperCase(Locale.ROOT), k -> new java.util.ArrayList<Integer>());
        if (at.size() < 5 && !at.contains(n + 1)) at.add(n + 1);
      }
    }
    List<String> effects = new java.util.ArrayList<String>();
    for (Map.Entry<String, List<Integer>> e : found.entrySet()) {
      StringBuilder at = new StringBuilder();
      for (int line : e.getValue()) at.append(at.length() > 0 ? ", " : "").append(line);
      effects.add(e.getKey() + " (line" + (e.getValue().size() > 1 ? "s " : " ") + at + ")");
    }
    return effects;
  }

  static Map<String, TreeSet<String>> parse(List<String> lines) {
    Map<String, TreeSet<String>> writes = new LinkedHashMap<String, TreeSet<String>>();
    StringBuilder code = new StringBuilder();
    StringBuilder dcl = null;  // a dcl-f statement can span lines up to its ';'
    boolean free = !lines.isEmpty() && lines.get(0).trim().toUpperCase(Locale.ROOT).startsWith("**FREE");

    for (String raw : lines) {
      String line = raw.length() > 80 && raw.substring(80).trim().isEmpty() ? raw.substring(0, 80) : raw;
      if (line.length() > 6 && line.charAt(6) == '*') continue;  // fixed-form comment
      String trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("--")) continue;
      int comment = line.indexOf("//");
      String text = comment >= 0 ? line.substring(0, comment) : line;

      /* Fixed-form F-spec: F in column 6, file name 7-16, type 17 (I O U C), file addition 20, device 36-42 */
      String fname = !free && text.length() > 17 ? text.substring(6, 16).trim().toUpperCase(Locale.ROOT) : "";
      char ftype = text.length() > 17 ? Character.toUpperCase(text.charAt(16)) : ' ';
      if (text.length() > 17 && (text.charAt(5) == 'F' || text.charAt(5) == 'f')
          && fname.matches("[A-Z0-9_$#@]{1,10}") && "IOUC".indexOf(ftype) >= 0) {
        String file = fname;
        char type = ftype;
        boolean add = text.length() > 19 && Character.toUpperCase(text.charAt(19)) == 'A';
        String device = text.length() > 35 ? text.substring(35, Math.min(42, text.length())).trim().toUpperCase(Locale.ROOT) : "";
        if (device.startsWith("DISK")) {
          if (type == 'U') add(writes, file, "update");
          if (type == 'O' || add) add(writes, file, "output");
        }
        continue;
      }

      if (dcl == null && DCL_F.matcher(text).find()) dcl = new StringBuilder();
      if (dcl != null) {
        dcl.append(' ').append(text.trim());
        if (text.contains(";")) {
          freeFile(dcl.toString(), writes);
          dcl = null;
        }
        continue;
      }
      code.append(text).append('\n');
    }

    String all = code.toString();
    sql(SQL_INSERT, all, "output", writes);
    sql(SQL_MERGE, all, "output", writes);
    sql(SQL_UPDATE, all, "update", writes);
    sql(SQL_DELETE, all, "delete", writes);
    return writes;
  }

  private static void freeFile(String statement, Map<String, TreeSet<String>> writes) {
    Matcher m = DCL_F.matcher(statement.trim());
    if (!m.find()) return;
    String rest = m.group(2);
    if (OTHER_DEVICE.matcher(rest).find()) return;  // not a database file
    Matcher usage = USAGE.matcher(rest);
    if (!usage.find()) return;  // DISK default: *INPUT
    String file = m.group(1).toUpperCase(Locale.ROOT);
    Matcher ext = EXTFILE.matcher(rest);
    if (ext.find() && !ext.group(1).trim().startsWith("*")) file = ext.group(1).trim().toUpperCase(Locale.ROOT);
    String values = usage.group(1).toUpperCase(Locale.ROOT);
    if (values.contains("*UPDATE")) add(writes, file, "update");
    if (values.contains("*DELETE")) add(writes, file, "delete");
    if (values.contains("*OUTPUT")) add(writes, file, "output");
  }

  private static void sql(Pattern pattern, String code, String usage, Map<String, TreeSet<String>> writes) {
    Matcher m = pattern.matcher(code);
    while (m.find()) {
      String name = m.group(1).replace("\"", "").replace('.', '/').toUpperCase(Locale.ROOT);
      if (name.startsWith(":")) continue;
      add(writes, name, usage);
    }
  }

  private static void add(Map<String, TreeSet<String>> writes, String file, String usage) {
    writes.computeIfAbsent(file, k -> new TreeSet<String>()).add(usage);
  }
}
