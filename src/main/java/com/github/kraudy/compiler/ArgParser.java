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
    FILE       ("f", "file",       Kind.VALUE, "YAML build file (required)"),
    DEBUG      ("x", null,         Kind.FLAG,  "Debug mode"),
    VERBOSE    ("v", null,         Kind.FLAG,  "Verbose output"),
    CLEAN      ("c", "clean",      Kind.FLAG,  "Delete created objects after build"),
    DRY_RUN    (null, "dry-run",   Kind.FLAG,  "Show commands without executing"),
    DIFF       (null, "diff",      Kind.FLAG,  "Only build changed objects"),
    NO_MIGRATE (null, "no-migrate", Kind.FLAG, "Disable automatic source migration");

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
  private boolean dryRun;
  private boolean debug;
  private boolean verbose;
  private boolean clean;
  private boolean diff;
  private boolean noMigrate;

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
      case DEBUG:      debug = true; break;
      case VERBOSE:    verbose = true; break;
      case CLEAN:      clean = true; break;
      case DRY_RUN:    dryRun = true; break;
      case DIFF:       diff = true; break;
      case NO_MIGRATE: noMigrate = true; break;
      default:
        throw new IllegalStateException("Option is not a flag: " + opt);
    }
  }

  private void setValue(Option opt, String value) {
    switch (opt) {
      case FILE:
        yamlFile = value;
        break;
      default:
        throw new IllegalStateException("Option does not take a value: " + opt);
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

  public String getYamlFile() {
    return requireYamlFile();
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

  public static String getUsage() {
    StringBuilder sb = new StringBuilder();
    sb.append("Usage: compiler [-f|--file <YAML>] [--diff] [--dry-run] [--no-migrate] [-c] [-x] [-v]")
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
    // trim trailing newline for parity with previous single-line last entry style
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
      sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }
}
