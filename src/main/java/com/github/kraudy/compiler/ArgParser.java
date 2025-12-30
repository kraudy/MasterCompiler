package com.github.kraudy.compiler;

import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import java.io.File;

/*
 * Simple Unix-style CLI argument parser.
 */
public class ArgParser {
  private final Map<String, Object> options = new HashMap<>();

  private static final Map<String, String> validOptions = new HashMap<>();

  static {
    validOptions.put("f", "yamlFile");
    validOptions.put("file", "yamlFile");

    validOptions.put("dry-run", "dryRun");
    validOptions.put("no-migrate", "noMigrate");

    validOptions.put("x", "debug");

    validOptions.put("v", "verbose");

    validOptions.put("diff", "diff");

  }

  private static final List<String> booleanOptions = Arrays.asList(
    "dryRun", "debug", "verbose", "diff", "noMigrate"
  );

  public ArgParser(String[] args) {
    parse(args);
  }

  private void parse(String[] args) {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];

      if (!arg.startsWith("-")) throw new IllegalArgumentException("Invalid argument: " + arg + ". Use -short or --long options.");

      /* Extract option name (strip leading - or --) */
      String optName = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);

      
      if (optName.isEmpty()) throw new IllegalArgumentException("Empty option: " + arg);
      
      // Handle combined short options (e.g., -vx means -v -x)
      if (!arg.startsWith("--") && optName.length() > 1) {
        for (char c : optName.toCharArray()) {
          String shortOpt = String.valueOf(c);

          String fieldName = validOptions.get(shortOpt);
          if (fieldName == null) throw new IllegalArgumentException("Unknown option: -" + shortOpt);

          if (!booleanOptions.contains(fieldName)) throw new IllegalArgumentException("Combined short options are only supported for boolean flags: -" + shortOpt);

          options.put(fieldName, true);
        }
        continue;
      }

      // Map short/long to valid names
      String fieldName = validOptions.get(optName);
      if (fieldName == null) throw new IllegalArgumentException("Unknown option: " + arg);

      // Check if it's a flag (no value expected next)
      if(booleanOptions.contains(fieldName)){
        options.put(fieldName, true);
        continue;
      }

      // Expect value at next index
      if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);

      /* Extract value from next string in args */
      String value = args[++i];
      if (value.startsWith("-")) throw new IllegalArgumentException("Value for " + arg + " cannot start with '-': " + value);

      options.put(fieldName, value);
    }
  }

  // Getters (with defaults and validation)
  public String getYamlFile() {
      String file = (String) options.get("yamlFile");

      if (file == null) throw new IllegalArgumentException("Required: -f or --file <YAML build file>");

      if (!isValidFile(file)) throw new IllegalArgumentException("Invalid YAML file: " + file + " (must exist and be readable)");

      return file;
  }

  private boolean isValidFile(String path) {
    File f = new File(path);
    return f.exists() && f.canRead() && path.endsWith(".yaml");
  }


  public BuildSpec getSpecFromYamlFile() {
      String file = (String) options.get("yamlFile");

      if (file == null) throw new IllegalArgumentException("Required: -f or --file <YAML build file>");

      return Utilities.deserializeYaml(file);
  }

  public boolean isDryRun() {
    return (boolean) options.getOrDefault("dryRun", false);
  }

  public boolean isDebug() {
    return (boolean) options.getOrDefault("debug", false);
  }

  public boolean isVerbose() {
    return (boolean) options.getOrDefault("verbose", false);
  }

  public boolean isDiff() {
    return (boolean) options.getOrDefault("diff", false);
  }

  public boolean noMigrate() {
    return (boolean) options.getOrDefault("noMigrate", false);
}

  

  // Print usage (call on error)
  public static String getUsage() {
    StringBuilder sb = new StringBuilder();

    sb.append("Usage: compiler [-f|--file <YAML>] [--diff] [--dry-run] [-x] [-v]").append("\n");
    sb.append("  -f, --file     YAML build file (required)").append("\n");
    sb.append("  --diff         Only build changed objects").append("\n");
    sb.append("  --dry-run      Show commands without executing").append("\n");
    sb.append("  --no-migrate   Disable automatic source migration").append("\n");
    sb.append("  -x,            Debug mode").append("\n");
    sb.append("  -v,            Verbose output");

    return sb.toString();
  }

}
