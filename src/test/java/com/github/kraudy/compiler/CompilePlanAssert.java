package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.github.kraudy.compiler.CommandStringParser.ParsedCommand;
import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.SourceNaming.ParsedName;
import com.github.kraudy.compiler.SourceScanner.CandidateSource;

/**
 * Exhaustive local compile-plan checks for a scanned spec.
 * Does not execute anything on IBM i — {@link StreamCompilationIT} remains the hard truth.
 */
public final class CompilePlanAssert {

  private CompilePlanAssert() {}

  public static void assertFullCompilePlan(BuildSpec spec, String scanRoot) throws Exception {
    assertNotNull(spec, "spec");
    assertNotNull(scanRoot, "scan root");
    assertFalse(spec.targets.isEmpty(), "spec has no targets");

    String yaml = SpecWriter.toYaml(spec);
    String cl = CompileScriptWriter.toCl(spec);

    assertScanTargetsPresent(spec, scanRoot);
    assertChildrenBeforeParents(spec);
    assertEveryTargetHasCommand(spec, yaml, cl);
    assertYamlCommentOrder(spec, yaml);
  }

  /**
   * Compare every golden CRT / RUNSQLSTM line (and hook/copy lines) to the
   * generated plan. Reports the full missing/extra/mismatch lists.
   */
  public static void assertGoldenCommands(BuildSpec spec, String resourcePath) throws Exception {
    List<String> golden = loadGolden(resourcePath);
    assertFalse(golden.isEmpty(), "empty golden file: " + resourcePath);

    List<ParsedCommand> goldenCrt = new ArrayList<ParsedCommand>();
    List<String> goldenOther = new ArrayList<String>();
    for (String line : golden) {
      if (isCompLine(line)) {
        goldenCrt.add(CommandStringParser.parseString(line));
      } else {
        goldenOther.add(line);
      }
    }

    List<ParsedCommand> generated = new ArrayList<ParsedCommand>();
    List<String> generatedKeys = new ArrayList<String>();
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
      String paste = CommandStringParser.toPasteableCommand(
          entry.getKey(), spec.defaults, entry.getValue().params);
      assertNotNull(paste, "No CRT* for " + entry.getKey().asString());
      ParsedCommand parsed = CommandStringParser.parseString(paste);
      generated.add(parsed);
      generatedKeys.add(commandKey(parsed));
    }

    Map<String, ParsedCommand> genByKey = new LinkedHashMap<String, ParsedCommand>();
    for (ParsedCommand g : generated) {
      genByKey.put(commandKey(g), g);
    }

    List<String> missing = new ArrayList<String>();
    List<String> mismatches = new ArrayList<String>();
    List<String> goldenKeys = new ArrayList<String>();
    for (int i = 0; i < goldenCrt.size(); i++) {
      ParsedCommand want = goldenCrt.get(i);
      String key = commandKey(want);
      goldenKeys.add(key);
      ParsedCommand got = genByKey.get(key);
      if (got == null) {
        missing.add(key + "  // " + goldenCompLine(want));
        continue;
      }
      String diff = paramDiff(want, got);
      if (diff != null) {
        mismatches.add(key + ": " + diff);
      }
    }

    List<String> extra = new ArrayList<String>();
    for (String key : generatedKeys) {
      if (!goldenKeys.contains(key)) {
        extra.add(key);
      }
    }

    List<String> order = new ArrayList<String>();
    int gi = 0;
    for (String wantKey : goldenKeys) {
      if (!genByKey.containsKey(wantKey)) continue;
      while (gi < generatedKeys.size() && !generatedKeys.get(gi).equals(wantKey)) {
        gi++;
      }
      if (gi >= generatedKeys.size()) {
        order.add("lost order at " + wantKey);
        break;
      }
      gi++;
    }

    String cl = CompileScriptWriter.toCl(spec);
    List<String> missingOther = new ArrayList<String>();
    for (String line : goldenOther) {
      if (line.startsWith("CHGCURDIR")) {
        if (!cl.contains("CHGCURDIR")) {
          missingOther.add("CHGCURDIR");
        }
        continue;
      }
      if (line.startsWith("CPYFRMSTMF")) {
        String from = extractParam(line, "FROMSTMF");
        if (from != null && !cl.contains(from)) {
          missingOther.add("CPYFRMSTMF FROMSTMF(" + from + ")");
        }
        continue;
      }
      String verb = line.split("\\s+", 2)[0];
      if (!cl.contains(verb + " ")) {
        missingOther.add(line);
      }
    }

    if (missing.isEmpty() && extra.isEmpty() && mismatches.isEmpty()
        && order.isEmpty() && missingOther.isEmpty()) {
      return;
    }

    StringBuilder msg = new StringBuilder();
    msg.append("Golden command mismatch (").append(resourcePath).append(")\n");
    msg.append("golden CRT*: ").append(goldenCrt.size())
        .append("  generated: ").append(generated.size()).append("\n");
    if (!missing.isEmpty()) {
      msg.append("MISSING (").append(missing.size()).append("):\n");
      for (String s : missing) msg.append("  - ").append(s).append("\n");
    }
    if (!extra.isEmpty()) {
      msg.append("EXTRA (").append(extra.size()).append("):\n");
      for (String s : extra) msg.append("  + ").append(s).append("\n");
    }
    if (!mismatches.isEmpty()) {
      msg.append("PARAM MISMATCH (").append(mismatches.size()).append("):\n");
      for (String s : mismatches) msg.append("  ! ").append(s).append("\n");
    }
    if (!order.isEmpty()) {
      msg.append("ORDER:\n");
      for (String s : order) msg.append("  ").append(s).append("\n");
    }
    if (!missingOther.isEmpty()) {
      msg.append("MISSING HOOKS/COPY (").append(missingOther.size()).append("):\n");
      for (String s : missingOther) msg.append("  - ").append(s).append("\n");
    }
    fail(msg.toString());
  }

  private static final Set<ParamCmd> COMPARE_PARAMS = new HashSet<ParamCmd>(Arrays.asList(
      ParamCmd.SRCSTMF, ParamCmd.MODULE, ParamCmd.TEXT, ParamCmd.TYPE, ParamCmd.LEN,
      ParamCmd.VALUE, ParamCmd.MAXLEN, ParamCmd.SIZE, ParamCmd.CCSID, ParamCmd.OBJTYPE,
      ParamCmd.OPTION, ParamCmd.DBGVIEW, ParamCmd.COMMIT, ParamCmd.EXPORT,
      ParamCmd.BNDSRVPGM, ParamCmd.GENOPT, ParamCmd.AUTORCL, ParamCmd.SRCMBR));

  private static String paramDiff(ParsedCommand want, ParsedCommand got) {
    List<String> diffs = new ArrayList<String>();
    for (ParamCmd p : COMPARE_PARAMS) {
      String w = want.params.get(p);
      if (w == null || w.isEmpty()) continue;
      String g = got.params.get(p);
      if (!sameParam(p, w, g)) {
        diffs.add(p.name() + " expected [" + norm(p, w) + "] got [" + norm(p, g) + "]");
      }
    }
    if (want.command == CompCmd.CRTCMD) {
      String w = want.params.get(ParamCmd.PGM);
      String g = got.params.get(ParamCmd.PGM);
      if (w != null && !sameParam(ParamCmd.PGM, w, g)) {
        diffs.add("PGM expected [" + lastName(w) + "] got [" + lastName(g) + "]");
      }
    }
    String wFile = want.params.get(ParamCmd.SRCFILE);
    String gFile = got.params.get(ParamCmd.SRCFILE);
    if (wFile != null && !wFile.isEmpty()) {
      if (gFile == null || !lastName(wFile).equalsIgnoreCase(lastName(gFile))) {
        diffs.add("SRCFILE expected [" + lastName(wFile) + "] got [" + lastName(gFile) + "]");
      }
    }
    return diffs.isEmpty() ? null : String.join("; ", diffs);
  }

  private static boolean sameParam(ParamCmd p, String want, String got) {
    if (got == null) return false;
    if (p == ParamCmd.MODULE) {
      return moduleSet(want).equals(moduleSet(got));
    }
    if (p == ParamCmd.SRCSTMF) {
      return norm(p, want).equals(norm(p, got));
    }
    return norm(p, want).equalsIgnoreCase(norm(p, got));
  }

  private static Set<String> moduleSet(String raw) {
    Set<String> out = new HashSet<String>();
    if (raw == null) return out;
    for (String part : raw.trim().split("\\s+")) {
      String n = lastName(part);
      if (!n.isEmpty()) out.add(n.toUpperCase(Locale.ROOT));
    }
    return out;
  }

  private static String norm(ParamCmd p, String raw) {
    if (raw == null) return "";
    String s = CommandStringParser.stripClQuotes(raw).trim();
    if (p == ParamCmd.SRCSTMF) return s.replace('\\', '/');
    return s;
  }

  private static String commandKey(ParsedCommand parsed) {
    return parsed.command.name() + "|" + objectId(parsed);
  }

  private static String objectId(ParsedCommand parsed) {
    ParamCmd[] ids = new ParamCmd[] {
        ParamCmd.SRVPGM, ParamCmd.CMD, ParamCmd.FILE, ParamCmd.OBJ,
        ParamCmd.BNDDIR, ParamCmd.DTAARA, ParamCmd.DTAQ, ParamCmd.MSGF,
        ParamCmd.PGM, ParamCmd.MODULE
    };
    for (ParamCmd p : ids) {
      if (parsed.command == CompCmd.CRTCMD && p == ParamCmd.PGM) continue;
      if (parsed.command == CompCmd.CRTSRVPGM && p == ParamCmd.MODULE) continue;
      String v = parsed.params.get(p);
      if (v != null && !v.trim().isEmpty()) {
        return lastName(v);
      }
    }
    String stmf = parsed.params.get(ParamCmd.SRCSTMF);
    if (stmf != null) return lastName(stmf);
    return "?";
  }

  private static String lastName(String raw) {
    if (raw == null) return "";
    String s = CommandStringParser.stripClQuotes(raw).trim();
    int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    if (slash >= 0) s = s.substring(slash + 1);
    return s.replace("*", "").trim();
  }

  private static boolean isCompLine(String line) {
    int sp = line.indexOf(' ');
    String verb = (sp < 0 ? line : line.substring(0, sp)).trim();
    try {
      CompCmd.fromString(verb);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String goldenCompLine(ParsedCommand parsed) {
    return parsed.command.name() + " " + objectId(parsed);
  }

  private static String extractParam(String line, String name) {
    String needle = name + "(";
    int at = line.indexOf(needle);
    if (at < 0) return null;
    int open = at + needle.length() - 1;
    int depth = 0;
    for (int i = open; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) {
          return CommandStringParser.stripClQuotes(line.substring(open + 1, i));
        }
      }
    }
    return null;
  }

  private static List<String> loadGolden(String resourcePath) throws Exception {
    InputStream in = CompilePlanAssert.class.getClassLoader().getResourceAsStream(resourcePath);
    assertNotNull(in, "golden resource not found: " + resourcePath);
    List<String> lines = new ArrayList<String>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        lines.add(line);
      }
    }
    return lines;
  }

  static void assertScanTargetsPresent(BuildSpec spec, String scanRoot) throws Exception {
    String library = spec.targets.keySet().iterator().next().getLibrary();
    SourceScanner scanner = new SourceScanner(null, false);
    Set<String> expected = new HashSet<String>();
    for (CandidateSource candidate : scanner.scan(scanRoot)) {
      Optional<ParsedName> parsed = SourceNaming.parseFileName(candidate.fileName);
      if (!parsed.isPresent()) continue;
      String keyStr = parsed.get().toTargetKey(library);
      try {
        new TargetKey(keyStr);
      } catch (IllegalArgumentException skip) {
        continue;
      }
      expected.add(new TargetKey(keyStr).asString().toUpperCase());
    }

    Set<String> actual = new HashSet<String>();
    for (TargetKey key : spec.targets.keySet()) {
      actual.add(key.asString().toUpperCase());
    }

    Set<String> missing = new HashSet<String>(expected);
    missing.removeAll(actual);
    assertTrue(missing.isEmpty(),
        "Scan found sources that are not compile targets: " + missing);
  }

  static void assertChildrenBeforeParents(BuildSpec spec) {
    Map<TargetKey, Integer> index = new HashMap<TargetKey, Integer>();
    int i = 0;
    for (TargetKey key : spec.targets.keySet()) {
      index.put(key, Integer.valueOf(i++));
    }

    for (TargetKey parent : spec.targets.keySet()) {
      Integer parentIdx = index.get(parent);
      for (TargetKey child : parent.getChildsList()) {
        if (child == null) continue;
        Integer childIdx = index.get(child);
        if (childIdx == null) {
          for (Map.Entry<TargetKey, Integer> e : index.entrySet()) {
            if (e.getKey().equals(child)) {
              childIdx = e.getValue();
              break;
            }
          }
        }
        assertNotNull(childIdx,
            "Child " + child.asString() + " of " + parent.asString()
                + " is not in the compile order");
        assertTrue(childIdx.intValue() < parentIdx.intValue(),
            "Compile order: " + child.asString() + " (index " + childIdx
                + ") must appear before " + parent.asString() + " (index " + parentIdx + ")");
      }
    }
  }

  static void assertEveryTargetHasCommand(BuildSpec spec, String yaml, String cl) {
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
      TargetKey key = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();
      String paste = CommandStringParser.toPasteableCommand(
          key, spec.defaults, targetSpec.params);
      assertNotNull(paste, "No CRT* for " + key.asString());
      assertFalse(paste.trim().isEmpty(), "Empty CRT* for " + key.asString());
      assertTrue(yaml.contains(paste),
          "YAML comment missing command for " + key.asString() + ": " + paste);
      assertTrue(cl.contains(paste),
          ".cl script missing command for " + key.asString() + ": " + paste);

      if (CompileScriptWriter.needsMemberCopy(key, targetSpec)) {
        assertTrue(cl.contains("CPYFRMSTMF"),
            ".cl should CPYFRMSTMF before member CRT* for " + key.asString());
        String stmf = targetSpec.params.get(ParamCmd.SRCSTMF);
        if (stmf != null) {
          assertTrue(cl.contains(CommandStringParser.stripClQuotes(stmf)),
              ".cl CPYFRMSTMF should use SRCSTMF for " + key.asString());
        }
      }
    }
  }

  static void assertYamlCommentOrder(BuildSpec spec, String yaml) {
    int last = -1;
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
      String paste = CommandStringParser.toPasteableCommand(
          entry.getKey(), spec.defaults, entry.getValue().params);
      if (paste == null) continue;
      int commentAt = yaml.indexOf("# " + paste);
      int keyAt = yaml.indexOf("\"" + entry.getKey().asString() + "\"");
      if (keyAt < 0) {
        keyAt = yaml.toUpperCase().indexOf("\"" + entry.getKey().asString().toUpperCase() + "\"");
      }
      assertTrue(commentAt >= 0, "Comment not found for " + entry.getKey().asString());
      assertTrue(keyAt >= 0, "Target key not found in YAML: " + entry.getKey().asString());
      assertTrue(commentAt < keyAt,
          "Comment must sit above target " + entry.getKey().asString());
      assertTrue(commentAt > last,
          "Comment order drifted from target order at " + entry.getKey().asString());
      last = commentAt;
    }
  }
}
