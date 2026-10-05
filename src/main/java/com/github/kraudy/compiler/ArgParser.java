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
    PROJECT       (null, "project",      Kind.VALUE, "Project root: build.yaml if present, else TOBi Rules.mk, else scan"),
    CODE4I        (null, "code4i",       Kind.FLAG,  "Connect with the Code for IBM i connection from VS Code settings (password: IBMI_PASSWORD)"),
    CONNECTION    (null, "connection",   Kind.VALUE, "Code for IBM i connection name, when there are several"),
    SETUP_VSCODE  (null, "setup-vscode", Kind.FLAG,  "Write .vscode/mcp.json (MC over SSH) and .github/skills for Copilot in the --project folder (default: current)"),
    LIBL          (null, "libl",         Kind.VALUE, "Library list for the build job (CHGLIBL), space-separated"),
    CURLIB        (null, "curlib",       Kind.VALUE, "Current library for the build job (CHGCURLIB)"),
    SSH           (null, "ssh",          Kind.FLAG,  "With --mcp: reach the IBM i over SSH (like Code for IBM i) and run MC there"),
    HOST_SERVERS  (null, "host-servers", Kind.FLAG,  "With --setup-vscode: connect through the host servers (like ACS) instead of SSH"),
    INSTALL_SKILLS(null, "install-skills", Kind.FLAG, "Install MC's agent skills for every project: ~/.copilot/skills (Copilot in VS Code)"),
    VERSION       (null, "version",      Kind.FLAG,  "Print the MasterCompiler version"),
    PRINT         (null, "print",        Kind.FLAG,  "With --setup-vscode: show what would be written, write nothing"),
    FROM_TOBI     (null, "from-tobi",    Kind.VALUE, "TOBi / Bob project root: convert its Rules.mk files into an MC spec (and build it)"),
    IMPORT        (null, "import",       Kind.VALUE, "Export source members to -o <dir> as an MC repo + build.yaml: LIB, LIB/SRCPF, LIB/SRCPF/MBR (MBR*), comma-separated"),
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
    KEEP_GOING    ("k", "keep-going", Kind.FLAG,  "After a failed target, keep building everything that does not depend on it"),
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
  private boolean keepGoing;
  private String importSelection;
  private String tobiRoot;
  private String projectRoot;
  private boolean code4i;
  private String connection;
  private boolean setupVscode;
  private boolean print;
  private String libl;
  private String curlib;
  private boolean ssh;
  private boolean hostServers;
  private boolean installSkills;
  private boolean version;

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
      case CODE4I:        code4i = true; break;
      case SETUP_VSCODE:  setupVscode = true; break;
      case PRINT:         print = true; break;
      case SSH:           ssh = true; break;
      case HOST_SERVERS:  hostServers = true; break;
      case INSTALL_SKILLS: installSkills = true; break;
      case VERSION:       version = true; break;
      case KEEP_GOING:    keepGoing = true; break;
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
      case IMPORT:
        importSelection = value;
        break;
      case FROM_TOBI:
        tobiRoot = value;
        break;
      case PROJECT:
        projectRoot = value;
        break;
      case LIBL:
        libl = value;
        break;
      case CURLIB:
        curlib = value;
        break;
      case CONNECTION:
        connection = value;
        code4i = true;
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
    if (ssh && !mcp && !setupVscode) {
      throw new IllegalArgumentException("--ssh goes with --mcp (or --setup-vscode)");
    }
    if (ssh && mcp && projectRoot == null) {
      throw new IllegalArgumentException("--ssh needs --project <local folder> to upload");
    }
    if (installSkills || version) return;  // need nothing else

    if (hostServers && !setupVscode) {
      throw new IllegalArgumentException("--host-servers goes with --setup-vscode");
    }
    if (hostServers && ssh) {
      throw new IllegalArgumentException("Use either --ssh or --host-servers");
    }
    if (print && !setupVscode) {
      throw new IllegalArgumentException("--print goes with --setup-vscode");
    }
    if (setupVscode) {
      if (yamlFile != null || scanRoot != null || tobiRoot != null || mcp || importSelection != null) {
        throw new IllegalArgumentException("--setup-vscode takes only --project, --connection, --host-servers, --print, -v");
      }
      return;
    }

    /* --project: pick the spec the project already has */
    if (projectRoot != null && yamlFile == null && scanRoot == null && tobiRoot == null) {
      File root = new File(projectRoot);
      if (!root.isDirectory()) throw new IllegalArgumentException("--project is not a directory: " + projectRoot);
      if (new File(root, "build.yaml").isFile()) yamlFile = new File(root, "build.yaml").getPath();
      else if (new File(root, "Rules.mk").isFile()) tobiRoot = projectRoot;
      else scanRoot = projectRoot;
    }

    boolean hasFile = yamlFile != null;
    boolean hasScan = scanRoot != null;

    boolean hasTobi = tobiRoot != null;

    if (importSelection != null) {
      if (hasFile || hasScan || hasTobi || mcp || generateOnly) {
        throw new IllegalArgumentException("--import takes only -o <dir>, --lib, -x, -v");
      }
      if (outputFile == null) {
        throw new IllegalArgumentException("--import requires -o|--output <dir>");
      }
      return;
    }

    int sources = (hasFile ? 1 : 0) + (hasScan ? 1 : 0) + (hasTobi ? 1 : 0);
    if (sources == 0) {
      throw new IllegalArgumentException(
          "Required: -f|--file <YAML>, --scan <source-root> or --from-tobi <project-root>");
    }
    if (sources > 1) {
      throw new IllegalArgumentException(
          "Use only one of -f|--file, --scan or --from-tobi");
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

  public String getLibl() {
    return libl;
  }

  public String getCurlib() {
    return curlib;
  }

  public boolean isSsh() {
    return ssh;
  }

  public boolean isVersion() {
    return version;
  }

  public boolean isInstallSkills() {
    return installSkills;
  }

  public boolean isHostServers() {
    return hostServers;
  }

  public boolean isPrint() {
    return print;
  }

  public boolean isSetupVscode() {
    return setupVscode;
  }

  /** {@code --project} folder, or null. */
  public String getProjectRoot() {
    return projectRoot;
  }

  public boolean isCode4i() {
    return code4i;
  }

  /** {@code --connection} name, or null to use the only Code for IBM i connection. */
  public String getConnection() {
    return connection;
  }

  public boolean hasTobi() {
    return tobiRoot != null;
  }

  public String getTobiRoot() {
    return tobiRoot;
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

  /** {@code --import} selection (LIB, LIB/SRCPF, LIB/SRCPF/MBR, comma-separated), or null. */
  public String getImportSelection() {
    return importSelection;
  }

  public boolean isKeepGoing() {
    return keepGoing;
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
    sb.append("Usage: java -jar MasterCompiler.jar (-f <YAML> | --scan <root> | --from-tobi <root> | --project <dir>) [options]")
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
