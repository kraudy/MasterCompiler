package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

/**
 * Parses a Docker-style {@code command:} array or a single CL string into a
 * {@link CompCmd} plus {@link ParamCmd} map. Does not execute anything.
 */
public final class CommandStringParser {

  private CommandStringParser() {}

  public static final class ParsedCommand {
    public final CompCmd command;
    public final Map<ParamCmd, String> params;

    ParsedCommand(CompCmd command, Map<ParamCmd, String> params) {
      this.command = command;
      this.params = params;
    }
  }

  /**
   * Alternate YAML encoding of a compile command: either a token list
   * {@code [CRTBNDRPG, PGM(*CURLIB/HELLO), ...]} or one CL string.
   */
  public static final class CommandForm {
    public final boolean stringForm;
    public final List<String> tokens;
    public final String raw;

    private CommandForm(boolean stringForm, List<String> tokens, String raw) {
      this.stringForm = stringForm;
      this.tokens = tokens;
      this.raw = raw;
    }

    public static CommandForm ofString(String raw) {
      return new CommandForm(true, null, raw);
    }

    public static CommandForm ofArray(List<String> tokens) {
      return new CommandForm(false, tokens, null);
    }
  }

  public static class CommandFormDeserializer extends JsonDeserializer<CommandForm> {
    @Override
    public CommandForm deserialize(JsonParser p, DeserializationContext ctxt)
        throws java.io.IOException {
      JsonNode node = p.getCodec().readTree(p);
      if (node == null || node.isNull()) return null;
      if (node.isTextual()) {
        return CommandForm.ofString(node.asText());
      }
      if (node.isArray()) {
        List<String> tokens = new ArrayList<String>();
        for (JsonNode el : node) {
          if (el == null || el.isNull()) {
            throw new IllegalArgumentException("command array entries must be strings");
          }
          tokens.add(el.asText());
        }
        return CommandForm.ofArray(tokens);
      }
      throw new IllegalArgumentException(
          "command must be a string or an array of PARAM(value) tokens");
    }
  }

  public static ParsedCommand parse(CommandForm form) {
    if (form == null) {
      throw new IllegalArgumentException("command is empty");
    }
    if (form.stringForm) {
      return parseString(form.raw);
    }
    return parseArray(form.tokens);
  }

  public static ParsedCommand parseArray(List<String> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      throw new IllegalArgumentException("command array is empty");
    }
    if (tokens.size() == 1) {
      String only = tokens.get(0) == null ? "" : tokens.get(0).trim();
      if (only.indexOf('(') > 0 && only.indexOf(' ') > 0) {
        return parseString(only);
      }
    }
    CompCmd cmd = CompCmd.fromString(requireToken(tokens.get(0), "command name"));
    Map<ParamCmd, String> params = new LinkedHashMap<ParamCmd, String>();
    for (int i = 1; i < tokens.size(); i++) {
      parseParamToken(cmd, params, requireToken(tokens.get(i), "command token"));
    }
    return new ParsedCommand(cmd, params);
  }

  public static ParsedCommand parseString(String cl) {
    if (cl == null || cl.trim().isEmpty()) {
      throw new IllegalArgumentException("command string is empty");
    }
    String s = cl.trim();
    int i = 0;
    while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
      i++;
    }
    CompCmd cmd = CompCmd.fromString(s.substring(0, i));
    Map<ParamCmd, String> params = new LinkedHashMap<ParamCmd, String>();
    i = skipWs(s, i);
    while (i < s.length()) {
      int nameStart = i;
      while (i < s.length() && s.charAt(i) != '(' && !Character.isWhitespace(s.charAt(i))) {
        i++;
      }
      if (i >= s.length() || s.charAt(i) != '(') {
        throw new IllegalArgumentException(
            "Expected PARAM(value) in command, got: " + s.substring(nameStart));
      }
      String name = s.substring(nameStart, i);
      int close = matchParen(s, i);
      String value = s.substring(i + 1, close);
      putParam(cmd, params, name, value);
      i = skipWs(s, close + 1);
    }
    return new ParsedCommand(cmd, params);
  }

  /**
   * Fold {@code command:} into {@code params:} for every target.
   * Explicit {@code params:} win. Matching identity tokens are dropped.
   */
  public static void applyCommandForms(BuildSpec spec) {
    if (spec == null || spec.targets == null) return;
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
      BuildSpec.TargetSpec targetSpec = entry.getValue();
      if (targetSpec == null || targetSpec.command == null) continue;
      applyCommandForm(entry.getKey(), targetSpec);
      targetSpec.command = null;
    }
  }

  static void applyCommandForm(TargetKey key, BuildSpec.TargetSpec targetSpec) {
    ParsedCommand parsed = parse(targetSpec.command);
    if (parsed.command != key.getCompilationCommand()) {
      throw new IllegalArgumentException(
          "command " + parsed.command.name()
              + " does not match target " + key.asString()
              + " (expected " + key.getCompilationCommandName() + ")");
    }

    Map<ParamCmd, String> fromCommand = new LinkedHashMap<ParamCmd, String>();
    for (Map.Entry<ParamCmd, String> pe : parsed.params.entrySet()) {
      ParamCmd param = pe.getKey();
      String value = pe.getValue();
      if (SpecResolver.isIdentityParam(key, param)) {
        if (!SpecResolver.namesSameObject(key, value)) {
          throw new IllegalArgumentException(
              "Identity parameter " + param.name() + "(" + value + ")"
                  + " does not match target " + key.asString());
        }
        continue;
      }
      fromCommand.put(param, value);
    }

    Map<ParamCmd, String> merged = new LinkedHashMap<ParamCmd, String>(fromCommand);
    if (targetSpec.params != null) {
      merged.putAll(targetSpec.params);
    }
    targetSpec.params.clear();
    targetSpec.params.putAll(merged);
  }

  private static void parseParamToken(CompCmd cmd, Map<ParamCmd, String> params, String token) {
    String t = token.trim();
    int open = t.indexOf('(');
    if (open <= 0) {
      throw new IllegalArgumentException("Expected PARAM(value), got: " + token);
    }
    int close = matchParen(t, open);
    if (close != t.length() - 1) {
      throw new IllegalArgumentException("Trailing characters after PARAM(value): " + token);
    }
    putParam(cmd, params, t.substring(0, open), t.substring(open + 1, close));
  }

  private static void putParam(CompCmd cmd, Map<ParamCmd, String> params, String name, String rawValue) {
    ParamCmd param = ParamCmd.fromString(name);
    if (!CompilationPattern.getCommandPattern(cmd).contains(param)) {
      throw new IllegalArgumentException(
          "Parameter " + param.name() + " not valid for command " + cmd.name());
    }
    params.put(param, stripClQuotes(rawValue));
  }

  /**
   * QCMDEXC form ({@code SRCSTMF(''/path'')}) → IBM i command-line form
   * ({@code SRCSTMF('/path')}). {@code *YES} / {@code *CURLIB} are unchanged.
   */
  public static String toPasteable(String qcmdexc) {
    if (qcmdexc == null) return null;
    return qcmdexc.replace("''", "'");
  }

  /** Paste-ready CRT* from a {@link TargetKey}. Resolves conflicts first. */
  public static String toPasteableCommand(TargetKey key) {
    if (key == null) return null;
    key.ResolveConflicts();
    StringBuilder sb = new StringBuilder(key.getCompilationCommandName());
    boolean any = false;
    for (ParamCmd param : CompilationPattern.getCommandPattern(key.getCompilationCommand())) {
      if (!key.containsKey(param)) continue;
      String value = key.get(param);
      if (value == null || value.isEmpty()) continue;
      sb.append(param.paramString(value));
      any = true;
    }
    if (!any) return null;
    return toPasteable(sb.toString());
  }

  /**
   * Full paste-ready CRT* string for the current defaults + target params.
   * Identity params are included. Returns null when there is nothing to paste.
   */
  public static String toPasteableCommand(
      TargetKey identity,
      Map<ParamCmd, String> specDefaults,
      Map<ParamCmd, String> targetParams) {
    if (identity == null) return null;

    TargetKey scratch = new TargetKey(identity.asString());
    if (identity.containsStreamFile()) {
      scratch.setStreamSourceFile(identity.getStreamFile());
    } else if (targetParams != null && targetParams.get(ParamCmd.SRCSTMF) != null) {
      scratch.setStreamSourceFile(targetParams.get(ParamCmd.SRCSTMF));
    }
    scratch.putAll(specDefaults);
    scratch.putAll(targetParams);
    scratch.ResolveConflicts();
    return toPasteableCommand(scratch);
  }

  static String stripClQuotes(String value) {
    if (value == null) return null;
    String s = value.trim();
    boolean stripped = true;
    while (stripped && s.length() >= 2) {
      stripped = false;
      if (s.startsWith("''") && s.endsWith("''") && s.length() >= 4) {
        s = s.substring(2, s.length() - 2).trim();
        stripped = true;
      } else if (s.startsWith("'") && s.endsWith("'")) {
        s = s.substring(1, s.length() - 1).trim();
        stripped = true;
      }
    }
    return s;
  }

  private static int matchParen(String s, int open) {
    if (open >= s.length() || s.charAt(open) != '(') {
      throw new IllegalArgumentException("Expected '(' in: " + s);
    }
    int depth = 0;
    boolean inQuote = false;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\'') {
        if (inQuote && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
          i++;
          continue;
        }
        inQuote = !inQuote;
        continue;
      }
      if (inQuote) continue;
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) return i;
      }
    }
    throw new IllegalArgumentException("Unbalanced parentheses in: " + s.substring(open));
  }

  private static int skipWs(String s, int i) {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    return i;
  }

  private static String requireToken(String token, String what) {
    if (token == null || token.trim().isEmpty()) {
      throw new IllegalArgumentException(what + " is empty");
    }
    return token.trim();
  }
}
