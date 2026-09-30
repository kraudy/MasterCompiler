package com.github.kraudy.compiler;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/*
 * Simple Unix-style CLI argument parser.
 *
 * Options are defined once in the Option catalog. Parse results land in typed
 * fields. Adding a flag = one enum constant + one field + one getter.
 */
public class ArgParser {

  private enum Kind { FLAG, VALUE }

  /**
   * Single source of truth for every CLI option.
   * shortName / longName may be null when only one form exists.
   */
  private enum Option {
    FILE          ("f", "file",          Kind.VALUE, "YAML build file"),
    SCAN          (null, "scan",         Kind.VALUE, "Scan source root and generate ordered YAML / build"),
    BASE          (null, "base",         Kind.VALUE, "Base overlay YAML for non-inferable params (default: <scan>/mc-base.yaml)"),
    OUTPUT        ("o", "output",        Kind.VALUE, "Write generated YAML to this path"),
    LIB           (null, "lib",          Kind.VALUE, "Default library for scanned targets (default: curlib)"),
    GENERATE_ONLY (null, "generate-only", Kind.FLAG, "Write YAML only, do not compile (--scan or -f, requires -o)"),
    DEBUG         ("x", null,            Kind.FLAG,  "Debug mode"),
    VERBOSE       ("v", null,            Kind.FLAG,  "Verbose output"),
    CLEAN         ("c", "clean",         Kind.FLAG,  "Delete created objects after build"),
    DRY_RUN       (null, "dry-run",      Kind.FLAG,  "Show commands without executing"),
    DIFF          (null, "diff",         Kind.FLAG,  "Only build changed objects"),
    SINCE         (null, "since",        Kind.VALUE, "Only build targets whose sources changed since this git ref, plus dependents"),
    PUSH          (null, "push",         Kind.VALUE, "Upload the local git repo's sources to this IFS directory and build from there"),
    MCP           (null, "mcp",          Kind.FLAG,  "Run as an MCP server on stdio (tools: build, plan, impact, joblog)"),
    NO_MIGRATE    (null, "no-migrate",   Kind.FLAG,  "Disable automatic source migration"),
    JSON          (null, "json",         Kind.VALUE, "Write a JSON build report (status, commands, joblog, compile errors) to this path");

    final String shortName;
    final String longName;
    final Kind kind;
    final String description;

    Option(String shortName, String longName, Kind kind, String description) {
      this.shortName = shortName;
      this.longName = longName;
      this.kind = kind;
      this.description = description;
    }

    boolean isFlag() {
      return kind == Kind.FLAG;
    }
  }

  private static final Map<String, Option> SHORT_OPTIONS;
  private static final Map<String, Option> LONG_OPTIONS;

  static {
    Map<String, Option> shorts = new HashMap<String, Option>();
    Map<String, Option> longs = new HashMap<String, Option>();
    for (Option opt : Option.values()) {
      if (opt.shortName != null) {
        shorts.put(opt.shortName, opt);
      }
      if (opt.longName != null) {
        longs.put(opt.longName, opt);
      }
    }
    SHORT_OPTIONS = Collections.unmodifiableMap(shorts);
    LONG_OPTIONS = Collections.unmodifiableMap(longs);
  }

  private String yamlFile;
  private String scanRoot;
  private String baseFile;
  private String outputFile;
  private String library = SpecGenerator.DEFAULT_LIBRARY;
  private boolean generateOnly;
  private boolean dryRun;
  private boolean debug;
  private boolean verbose;
  private boolean clean;
  private boolean diff;
  private boolean noMigrate;
  private String jsonReport;
  private String since;
  private String push;
  private boolean mcp;

  public ArgParser(String[] args) {
    parse(args);
  }

  private void parse(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];

      if (!arg.startsWith("-")) {
        throw new IllegalArgumentException(
            "Invalid argument: " + arg + ". Use -short or --long options.");
      }

      if (arg.startsWith("--")) {
        String name = arg.substring(2);
        if (name.isEmpty()) {
          throw new IllegalArgumentException("Empty option: " + arg);
        }
        Option opt = LONG_OPTIONS.get(name);
        if (opt == null) {
          throw new IllegalArgumentException("Unknown option: " + arg);
        }
        i = apply(opt, arg, args, i);
        continue;
      }

      // Short form: -x, -v, -f, or combined flags -xv
      String body = arg.substring(1);
      if (body.isEmpty()) {
        throw new IllegalArgumentException("Empty option: " + arg);
      }

      if (body.length() > 1) {
        applyCombinedShorts(body, arg);
        continue;
      }

      Option opt = SHORT_OPTIONS.get(body);
      if (opt == null) {
        throw new IllegalArgumentException("Unknown option: " + arg);
      }
      i = apply(opt, arg, args, i);
    }
  }

  /** Combined short flags only (e.g. -xv). Value options cannot be clustered. */
  private void applyCombinedShorts(String body, String rawArg) {
    for (int c = 0; c < body.length(); c++) {
      String shortOpt = String.valueOf(body.charAt(c));
      Option opt = SHORT_OPTIONS.get(shortOpt);
      if (opt == null) {
        throw new IllegalArgumentException("Unknown option: -" + shortOpt);
      }
      if (!opt.isFlag()) {
        throw new IllegalArgumentException(
            "Combined short options are only supported for boolean flags: -" + shortOpt);
      }
      setFlag(opt);
    }
  }

  /**
   * Apply a resolved option. For VALUE options, consumes the next argv token.
   * @return updated index into args
   */
  private int apply(Option opt, String rawArg, String[] args, int index) {
    if (opt.isFlag()) {
      setFlag(opt);
      return index;
    }

    if (index + 1 >= args.length) {
      throw new IllegalArgumentException("Missing value for " + rawArg);
    }
    String value = args[index + 1];
    if (value.startsWith("-")) {
      throw new IllegalArgumentException(
          "Value for " + rawArg + " cannot start with '-': " + value);
    }
    setValue(opt, value);
    return index + 1;
  }

  private void setFlag(Option opt) {
    switch (opt) {
      case DEBUG:         debug = true; break;
      case VERBOSE:       verbose = true; break;
      case CLEAN:         clean = true; break;
      case DRY_RUN:       dryRun = true; break;
      case DIFF:          diff = true; break;
      case NO_MIGRATE:    noMigrate = true; break;
      case GENERATE_ONLY: generateOnly = true; break;
      case MCP:           mcp = true; break;
      default:
        throw new IllegalStateException("Option is not a flag: " + opt);
    }
  }

  private void setValue(Option opt, String value) {
    switch (opt) {
      case FILE:
        yamlFile = value;
        break;
      case SCAN:
        scanRoot = value;
        break;
      case BASE:
        baseFile = value;
        break;
      case OUTPUT:
        outputFile = value;
        break;
      case LIB:
        library = value;
        break;
      case JSON:
        jsonReport = value;
        break;
      case SINCE:
        since = value;
        break;
      case PUSH:
        push = value;
        break;
      default:
        throw new IllegalStateException("Option does not take a value: " + opt);
    }
  }

  /**
   * Validate mutual exclusion / required inputs after parse.
   * Call before using getters that require a mode.
   */
  public void validate() {
    boolean hasFile = yamlFile != null;
    boolean hasScan = scanRoot != null;

    if (!hasFile && !hasScan) {
      throw new IllegalArgumentException(
          "Required: -f|--file <YAML> or --scan <source-root>");
    }
    if (hasFile && hasScan) {
      throw new IllegalArgumentException(
          "Use either -f|--file or --scan, not both");
    }
    if (generateOnly && outputFile == null) {
      throw new IllegalArgumentException(
          "--generate-only requires -o|--output <file>");
    }
    if (generateOnly && jsonReport != null) {
      throw new IllegalArgumentException(
          "--json reports a build; it cannot be used with --generate-only");
    }
    if (since != null && diff) {
      throw new IllegalArgumentException("Use either --diff or --since, not both");
    }
    if (since != null && generateOnly) {
      throw new IllegalArgumentException("--since selects targets to build; it cannot be used with --generate-only");
    }
    if (mcp && (generateOnly || jsonReport != null || since != null || diff || dryRun || clean)) {
      throw new IllegalArgumentException(
          "--mcp takes -f|--scan, --push, --lib, --base, --no-migrate, -x, -v; builds are chosen per tool call");
    }
    if (push != null && !push.startsWith("/")) {
      throw new IllegalArgumentException("--push needs an absolute IFS directory: " + push);
    }
    if (push != null && generateOnly) {
      throw new IllegalArgumentException("--push uploads sources for a build; it cannot be used with --generate-only");
    }
    if (hasFile && !isValidFile(yamlFile)) {
      throw new IllegalArgumentException(
          "Invalid YAML file: " + yamlFile + " (must exist and be readable)");
    }
    if (baseFile != null) {
      if (!hasScan) {
        throw new IllegalArgumentException("--base requires --scan <source-root>");
      }
      if (!isValidFile(baseFile)) {
        throw new IllegalArgumentException(
            "Invalid base overlay: " + baseFile + " (must exist and be readable .yaml)");
      }
    }
  }

  /** Required path: non-null, exists, readable, ends with .yaml */
  private String requireYamlFile() {
    if (yamlFile == null) {
      throw new IllegalArgumentException("Required: -f or --file <YAML build file>");
    }
    if (!isValidFile(yamlFile)) {
      throw new IllegalArgumentException(
          "Invalid YAML file: " + yamlFile + " (must exist and be readable)");
    }
    return yamlFile;
  }

  private boolean isValidFile(String path) {
    File f = new File(path);
    return f.exists() && f.canRead() && path.endsWith(".yaml");
  }

  public boolean hasScan() {
    return scanRoot != null;
  }

  public boolean hasFile() {
    return yamlFile != null;
  }

  public String getYamlFile() {
    return requireYamlFile();
  }

  public String getScanRoot() {
    if (scanRoot == null) {
      throw new IllegalArgumentException("Required: --scan <source-root>");
    }
    return scanRoot;
  }

  /** Explicit {@code --base} path, or null to use default {@code mc-base.yaml} under scan root. */
  public String getBaseFile() {
    return baseFile;
  }

  public String getOutputFile() {
    return outputFile;
  }

  public String getLibrary() {
    return library != null ? library : SpecGenerator.DEFAULT_LIBRARY;
  }

  public boolean isGenerateOnly() {
    return generateOnly;
  }

  public BuildSpec getSpecFromYamlFile() {
    return Utilities.deserializeYaml(requireYamlFile());
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public boolean isDebug() {
    return debug;
  }

  public boolean isVerbose() {
    return verbose;
  }

  public boolean isClean() {
    return clean;
  }

  public boolean isDiff() {
    return diff;
  }

  public boolean isNoMigrate() {
    return noMigrate;
  }

  /** {@code --since} git ref, or null for a full (or --diff) build. */
  public String getSince() {
    return since;
  }

  public boolean isMcp() {
    return mcp;
  }

  /** {@code --push} IFS directory, or null when sources are already on the IBM i. */
  public String getPush() {
    return push;
  }

  /** {@code --json} report path, or null when no report is requested. */
  public String getJsonReport() {
    return jsonReport;
  }

  public static String getUsage() {
    StringBuilder sb = new StringBuilder();
    sb.append("Usage: compiler (-f|--file <YAML> | --scan <root>) [options]")
        .append("\n");
    for (Option opt : Option.values()) {
      sb.append("  ");
      if (opt.shortName != null && opt.longName != null) {
        sb.append("-").append(opt.shortName).append(", --").append(opt.longName);
      } else if (opt.shortName != null) {
        sb.append("-").append(opt.shortName);
      } else {
        sb.append("--").append(opt.longName);
      }
      if (opt.kind == Kind.VALUE) {
        sb.append(" <value>");
      }
      sb.append("\t").append(opt.description).append("\n");
    }
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
      sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }
}
