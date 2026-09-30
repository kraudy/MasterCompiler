package com.github.kraudy.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

/*
 * --from-tobi <project>: builds an MC spec from a TOBi / Bob (Better Object Builder) project.
 *
 * Reads every Rules.mk under the project and iproj.json:
 *   NAME.TYPE: source deps...          target, its source file and the objects it needs
 *   NAME.TYPE: private PARAM = value   per-target compile parameter (TEXT, DBGVIEW, ...)
 *   NAME.CMD: CMD_PGM=PGM              command processing program (default: the command's name)
 *   iproj.json includePath             INCDIR for RPG and CL compiles
 * The object type comes from the rule and the source extension (FAM300.MODULE: FAM300.RPGLE
 * is a module, ARTICLE.FILE: ARTICLE.PF a DDS physical file, POPEMP.PGM: popemp.sqlprc an SQL
 * procedure). Description files (.ILESRVPGM, .BNDDIR, .DTAARA, .DTAQ, .MSGF) hold CL: their
 * CRT* command becomes the target's params, ADDBNDDIRE / ADDMSGD lines become after hooks.
 * Rule dependencies are added to MC's own source-level dependencies before ordering.
 * What MC cannot build (C, C++, COBOL, CRTPGM from modules, panel groups, menus, triggers,
 * make recipes) is left out and listed.
 */
public class TobiConverter {
  private static final Logger logger = LoggerFactory.getLogger(TobiConverter.class);

  private static final Pattern TARGET_LINE = Pattern.compile("^([A-Za-z0-9$#@_]+)\\.([A-Za-z]+)\\s*:(?!=)(.*)$");
  private static final Pattern TARGET_VAR = Pattern.compile("^\\s*(?:private\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:::=|:=|\\+=|\\?=|=)\\s*(.*)$");
  private static final Pattern MAKE_VAR = Pattern.compile("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*(?:::=|:=|\\+=|\\?=|=)");
  private static final Pattern DEP_TOKEN = Pattern.compile("^([A-Za-z0-9$#@_]+)\\.([A-Za-z]+)$");
  private static final List<String> DESCRIPTION_FILES = Arrays.asList("ILESRVPGM", "BNDDIR", "DTAARA", "DTAQ", "MSGF");
  private static final List<String> INCDIR_SOURCES = Arrays.asList("rpgle", "sqlrpgle", "clle");

  private final boolean verbose;
  private final List<String> skipped = new ArrayList<String>();

  public TobiConverter(boolean verbose) {
    this.verbose = verbose;
  }

  /* Targets MC does not build, with the reason */
  public List<String> getSkipped() {
    return skipped;
  }

  /* One Rules.mk target, merged over all its lines */
  private static final class Rule {
    final String name;
    final String type;
    final File dir;
    String source;                       // path relative to the project root
    final List<String> deps = new ArrayList<String>();          // NAME.TYPE
    final Map<String, String> vars = new LinkedHashMap<String, String>();
    boolean recipe;

    Rule(String name, String type, File dir) {
      this.name = name;
      this.type = type;
      this.dir = dir;
    }
  }

  public BuildSpec convert(String projectDir, String library) throws Exception {
    File root = new File(projectDir).getCanonicalFile();
    if (!root.isDirectory()) throw new IllegalArgumentException("Not a directory: " + projectDir);

    List<String> includePath = includePath(root);
    Map<String, Rule> rules = new LinkedHashMap<String, Rule>();
    for (File rulesFile : rulesFiles(root)) parseRules(root, rulesFile, rules);
    if (rules.isEmpty()) throw new IllegalArgumentException("No Rules.mk targets found under " + root);

    BuildSpec spec = new BuildSpec();
    spec.setBaseDirectory(root.getPath());
    Map<String, TargetKey> byRule = new HashMap<String, TargetKey>();  // NAME.TYPE (as rules name it) -> target
    Map<TargetKey, Rule> ruleOf = new LinkedHashMap<TargetKey, Rule>();

    for (Rule rule : rules.values()) {
      try {
        TargetKey key = toTarget(rule, library, root, includePath, spec);
        if (key == null) continue;
        byRule.put(rule.name + "." + rule.type, key);
        ruleOf.put(key, rule);
      } catch (Exception e) {
        skip(rule, e.getMessage());
      }
    }
    if (spec.targets.isEmpty()) throw new IllegalArgumentException("No Rules.mk target could be converted");

    /* Relative SRCSTMF and INCDIR resolve against the IBM i job's current directory */
    spec.before.add(0, new CommandObject(SysCmd.CHGCURDIR).put(ParamCmd.DIR, root.getPath()));
    spec.setTargetsList(spec.targets.keySet());

    /* MC's source-level dependencies, then the ones the rules declare */
    new DependencyAwareness(null, false, verbose).detectDependencies(spec);
    for (Map.Entry<TargetKey, Rule> entry : ruleOf.entrySet()) {
      for (String dep : entry.getValue().deps) {
        TargetKey child = byRule.get(dep);
        if (child == null || child.equals(entry.getKey())) continue;
        entry.getKey().addChild(child);
        child.addFather(entry.getKey());
      }
    }

    new BuildTopoSort(false, verbose).reorderSpec(spec);
    SpecResolver.resolveAll(spec, null);

    logger.info("Converted {} Rules.mk targets from {}; {} skipped", spec.targets.size(), root, skipped.size());
    for (String s : skipped) logger.info("Skipped {}", s);
    return spec;
  }

  /* MC target for a rule, added to the spec; null when MC cannot build it */
  private TargetKey toTarget(Rule rule, String library, File root, List<String> includePath, BuildSpec spec) throws Exception {
    if (rule.recipe && rule.source == null) {
      skip(rule, "built by a make recipe, not from a source");
      return null;
    }
    if (rule.source == null) {
      skip(rule, rule.type.equals("PGM") ? "program bound from modules (CRTPGM), not supported by MC" : "no source file");
      return null;
    }

    String ext = extension(rule.source);
    BuildSpec.TargetSpec target = new BuildSpec.TargetSpec();

    if (DESCRIPTION_FILES.contains(ext)) {
      TargetKey key = new TargetKey(library + "." + rule.name + "." + descriptionType(ext));
      describe(key, rule, new File(root, rule.source), target);
      applyVars(rule, target, key);
      spec.targets.put(key, target);
      return key;
    }

    String[] mc = mcType(rule.type, ext);
    if (mc == null) {
      skip(rule, "source type " + ext + " for *" + rule.type + " is not supported by MC");
      return null;
    }
    TargetKey key = new TargetKey(library + "." + rule.name + "." + mc[0] + "." + mc[1]);
    target.params.put(ParamCmd.SRCSTMF, rule.source);
    key.setStreamSourceFile(rule.source);

    if (mc[0].equals("srvpgm")) {
      String modules = depsOfType(rule, "MODULE");
      if (!modules.isEmpty()) target.params.put(ParamCmd.MODULE, modules);
      String srvpgms = depsOfType(rule, "SRVPGM");
      if (!srvpgms.isEmpty()) target.params.put(ParamCmd.BNDSRVPGM, srvpgms);
    }
    if (!includePath.isEmpty() && INCDIR_SOURCES.contains(mc[1])
        && CompilationPattern.getCommandPattern(key.getCompilationCommand()).contains(ParamCmd.INCDIR)) {
      target.params.put(ParamCmd.INCDIR, String.join(" ", includePath));
    }
    applyVars(rule, target, key);
    /* TOBi's default command processing program has the command's name */
    if (mc[0].equals("cmd") && !target.params.containsKey(ParamCmd.PGM)) target.params.put(ParamCmd.PGM, rule.name);
    spec.targets.put(key, target);
    return key;
  }

  /* {MC object type, MC source type} for a rule type and source extension */
  private static String[] mcType(String type, String ext) {
    switch (type) {
      case "PGM":
        switch (ext) {
          case "RPGLE": case "SQLRPGLE": case "CLLE": case "RPG": case "CLP":
            return new String[] { "pgm", ext.toLowerCase() };
          case "SQLPRC": return new String[] { "procedure", "sql" };
          case "SQLTRG": return new String[] { "trigger", "sql" };
          default: return null;
        }
      case "MODULE":
        switch (ext) {
          case "RPGLE": case "SQLRPGLE": case "CLLE": return new String[] { "module", ext.toLowerCase() };
          default: return null;
        }
      case "SRVPGM":
        switch (ext) {
          case "BND": return new String[] { "srvpgm", "bnd" };
          case "SQLUDF": return new String[] { "function", "sql" };
          default: return null;
        }
      case "FILE":
        switch (ext) {
          case "PF": case "LF": case "DSPF": case "PRTF": return new String[] { ext.toLowerCase(), "dds" };
          case "TABLE": case "PFSQL": return new String[] { "table", "sql" };
          case "VIEW": return new String[] { "view", "sql" };
          case "INDEX": return new String[] { "index", "sql" };
          default: return null;
        }
      case "DTAARA":
        return ext.equals("SQLSEQ") ? new String[] { "sequence", "sql" } : null;
      case "CMD":
        return ext.equals("CMD") || ext.equals("CMDSRC") ? new String[] { "cmd", "cmd" } : null;
      default:
        return null;
    }
  }

  private static String descriptionType(String ext) {
    switch (ext) {
      case "ILESRVPGM": return "srvpgm.bnd";
      case "BNDDIR":    return "bnddir.bnddir";
      case "DTAARA":    return "dtaara.dtaara";
      case "DTAQ":      return "dtaq.dtaq";
      default:          return "msgf.msgf";
    }
  }

  /*
   * Description file: CL statements with &O (object library) and &N (object name).
   * The CRT* statement gives the target's params, ADDBNDDIRE / ADDMSGD become after hooks,
   * "!" marks a statement whose errors are ignored: its create command is used as usual, !DLTOBJ is
   * dropped since MC re-creates these objects itself.
   */
  private void describe(TargetKey key, Rule rule, File file, BuildSpec.TargetSpec target) throws Exception {
    for (String statement : clStatements(file)) {
      if (statement.startsWith("!")) statement = statement.substring(1).trim();
      if (statement.toUpperCase().startsWith("DLTOBJ")) continue;
      String cl = statement.replace("&O/&N", "*CURLIB/" + rule.name).replace("&N", rule.name).replace("&O", "*CURLIB");
      String command = cl.split("\\s+", 2)[0].toUpperCase();

      if (command.startsWith("CRT")) {
        CompCmd create = CompCmd.fromString(command);
        for (Map.Entry<String, String> param : clParams(cl).entrySet()) {
          ParamCmd p = paramOrNull(param.getKey());
          if (p == null || !CompilationPattern.getCommandPattern(create).contains(p)) {
            skipped.add(rule.name + "." + rule.type + ": " + command + " parameter " + param.getKey() + " not converted");
            continue;
          }
          String value = stripQuotes(param.getValue());
          if (SpecResolver.isIdentityParam(key, p) && SpecResolver.namesSameObject(key, value)) continue;
          target.params.put(p, value);
        }
        continue;
      }

      SysCmd hook;
      try {
        hook = SysCmd.fromString(command);
      } catch (IllegalArgumentException e) {
        skipped.add(rule.name + "." + rule.type + ": statement not converted: " + command);
        continue;
      }
      CommandObject after = new CommandObject(hook);
      for (Map.Entry<String, String> param : clParams(cl).entrySet()) {
        ParamCmd p = paramOrNull(param.getKey());
        if (p == null) continue;
        String value = param.getValue();
        if (hook == SysCmd.ADDBNDDIRE && p == ParamCmd.OBJ) value = bndDirEntries(value);
        try {
          after.put(p, hook == SysCmd.ADDBNDDIRE ? value : stripQuotes(value));
        } catch (IllegalArgumentException ignored) {
          /* parameter MC does not model for this command */
        }
      }
      target.after.add(after);
    }

    /* A BNDDIR described only by its rule dependencies */
    if (key.getObjectTypeEnum() == CompilationPattern.ObjectType.BNDDIR && target.after.isEmpty()) {
      String srvpgms = depsOfType(rule, "SRVPGM");
      if (!srvpgms.isEmpty()) {
        target.after.add(new CommandObject(SysCmd.ADDBNDDIRE).put(ParamCmd.BNDDIR, rule.name).put(ParamCmd.OBJ, srvpgms));
      }
    }
  }

  /* OBJ((*LIBL/XML *SRVPGM) (ORDER *SRVPGM)) -> "XML ORDER" (MC's hook form) */
  private static String bndDirEntries(String value) {
    List<String> names = new ArrayList<String>();
    Matcher m = Pattern.compile("\\(\\s*(?:[^()\\s/]+/)?([A-Za-z0-9$#@_]+)\\s+\\*SRVPGM[^()]*\\)", Pattern.CASE_INSENSITIVE).matcher(value);
    while (m.find()) names.add(m.group(1).toUpperCase());
    return String.join(" ", names);
  }

  /* Rule variables: CMD_PGM -> PGM, the rest when MC knows the parameter for this command */
  private void applyVars(Rule rule, BuildSpec.TargetSpec target, TargetKey key) {
    for (Map.Entry<String, String> var : rule.vars.entrySet()) {
      String name = var.getKey().equalsIgnoreCase("CMD_PGM") ? "PGM" : var.getKey();
      ParamCmd p = paramOrNull(name);
      if (p == null || !CompilationPattern.getCommandPattern(key.getCompilationCommand()).contains(p)) {
        skipped.add(rule.name + "." + rule.type + ": parameter " + var.getKey() + " not converted");
        continue;
      }
      target.params.put(p, stripQuotes(var.getValue().trim()));
    }
  }

  private void parseRules(File root, File rulesFile, Map<String, Rule> rules) throws Exception {
    File dir = rulesFile.getParentFile();
    Rule last = null;
    for (String raw : Files.readAllLines(rulesFile.toPath(), StandardCharsets.UTF_8)) {
      if (raw.startsWith("\t")) {  // make recipe for the previous target
        if (last != null && !raw.trim().isEmpty()) last.recipe = true;
        continue;
      }
      String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
      if (line.trim().isEmpty()) continue;

      Matcher target = TARGET_LINE.matcher(line.trim());
      if (!target.matches()) {
        if (!MAKE_VAR.matcher(line).find() && verbose) logger.info("Rules.mk line not understood: {}", line.trim());
        continue;
      }
      String name = target.group(1).toUpperCase();
      String type = target.group(2).toUpperCase();
      Rule rule = rules.computeIfAbsent(name + "." + type, k -> new Rule(name, type, dir));
      last = rule;

      String rest = target.group(3).trim();
      Matcher var = TARGET_VAR.matcher(rest);
      if (var.matches()) {
        rule.vars.put(var.group(1).toUpperCase(), var.group(2));
        continue;
      }
      for (String token : rest.split("\\s+")) {
        if (token.isEmpty()) continue;
        token = token.replace("$(d)/", "");
        File file = findFile(dir, token);
        if (rule.source == null && file != null) {
          rule.source = relative(root, file);
        } else if (file == null && DEP_TOKEN.matcher(token).matches() && !token.contains("/")) {
          rule.deps.add(token.toUpperCase());
        }
        /* other files (copy members named as prerequisites) are found by MC's own scanning */
      }
    }
  }

  /* Rules.mk files anywhere under the project (SUBDIRS is followed implicitly) */
  private static List<File> rulesFiles(File root) {
    List<File> out = new ArrayList<File>();
    File[] kids = root.listFiles();
    if (kids == null) return out;
    Arrays.sort(kids);
    for (File kid : kids) {
      if (kid.getName().startsWith(".")) continue;
      if (kid.isDirectory()) out.addAll(rulesFiles(kid));
      else if (kid.getName().equals("Rules.mk")) out.add(kid);
    }
    return out;
  }

  private static List<String> includePath(File root) {
    List<String> dirs = new ArrayList<String>();
    File iproj = new File(root, "iproj.json");
    if (!iproj.isFile()) return dirs;
    try {
      JsonNode node = new ObjectMapper().readTree(iproj).path("includePath");
      for (JsonNode dir : node) dirs.add(dir.asText());
    } catch (Exception e) {
      logger.info("Could not read {}: {}", iproj, e.getMessage());
    }
    return dirs;
  }

  /* Statements of a CL-like description file: comments removed, "+" continuations joined */
  private static List<String> clStatements(File file) throws Exception {
    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ");
    List<String> statements = new ArrayList<String>();
    StringBuilder current = new StringBuilder();
    for (String line : text.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.endsWith("+")) {
        current.append(trimmed, 0, trimmed.length() - 1).append(' ');
        continue;
      }
      current.append(trimmed);
      statements.add(current.toString().trim());
      current.setLength(0);
    }
    if (current.length() > 0) statements.add(current.toString().trim());
    return statements;
  }

  /* PARAM(value) pairs of a CL statement; nested parentheses and quotes kept */
  private static Map<String, String> clParams(String cl) {
    Map<String, String> params = new LinkedHashMap<String, String>();
    int i = cl.indexOf(' ');
    while (i > 0 && i < cl.length()) {
      while (i < cl.length() && Character.isWhitespace(cl.charAt(i))) i++;
      int open = cl.indexOf('(', i);
      if (open < 0) break;
      String name = cl.substring(i, open).trim();
      int depth = 0;
      boolean quoted = false;
      int close = open;
      for (; close < cl.length(); close++) {
        char c = cl.charAt(close);
        if (c == '\'') quoted = !quoted;
        if (quoted) continue;
        if (c == '(') depth++;
        if (c == ')' && --depth == 0) break;
      }
      if (close >= cl.length()) break;
      params.put(name.toUpperCase(), cl.substring(open + 1, close).trim());
      i = close + 1;
    }
    return params;
  }

  private static ParamCmd paramOrNull(String name) {
    try {
      return ParamCmd.fromString(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String stripQuotes(String value) {
    String v = value.trim();
    return v.length() >= 2 && v.startsWith("'") && v.endsWith("'") ? v.substring(1, v.length() - 1) : v;
  }

  private static String depsOfType(Rule rule, String type) {
    List<String> names = new ArrayList<String>();
    for (String dep : rule.deps) {
      if (dep.endsWith("." + type)) names.add(dep.substring(0, dep.indexOf('.')));
    }
    return String.join(" ", names);
  }

  /* Rules.mk names sources with any case (FAM300.RPGLE for fam300.rpgle) */
  private static File findFile(File dir, String name) {
    File exact = new File(dir, name);
    if (exact.isFile()) return exact;
    File[] kids = new File(dir, name).getParentFile().listFiles();
    if (kids == null) return null;
    String base = new File(name).getName();
    for (File kid : kids) {
      if (kid.isFile() && kid.getName().equalsIgnoreCase(base)) return kid;
    }
    return null;
  }

  private static String extension(String path) {
    String name = new File(path).getName();
    return name.substring(name.lastIndexOf('.') + 1).toUpperCase();
  }

  private static String relative(File root, File file) {
    return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
  }

  private void skip(Rule rule, String reason) {
    skipped.add(rule.name + "." + rule.type + (rule.source != null ? " (" + rule.source + ")" : "") + ": " + reason);
  }
}
