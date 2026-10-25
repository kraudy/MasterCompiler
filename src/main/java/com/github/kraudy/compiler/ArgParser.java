package com.github.kraudy.compiler;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    READ_ONLY     (null, "read-only",    Kind.FLAG,  "With --mcp: a read-only server for another IBM i (sql, find_object, find_source, object_info, export_rows); --libl lists the only libraries it reads"),
    MAX_ROWS      (null, "max-rows",     Kind.VALUE, "With --read-only: most rows one query or copy may read"),
    MASK_COLUMNS  (null, "mask-columns", Kind.VALUE, "With --read-only: columns (comma-separated) sql shows masked and copy_rows must replace"),
    SSH           (null, "ssh",          Kind.FLAG,  "With --mcp: reach the IBM i over SSH (like Code for IBM i) and run MC there"),
    SSH_PORT      (null, "ssh-port",     Kind.VALUE, "SSH port; wins over the Code for IBM i connection's port and IBMI_SSH_PORT (default 22)"),
    REMOTE        (null, "remote",       Kind.FLAG,  "Build over SSH: upload MC and the --project folder, run the build on the IBM i and stream its output (no host servers needed)"),
    SSH_BUILD     (null, "ssh-build",    Kind.FLAG,  "Same as --remote"),
    DOCTOR        (null, "doctor",       Kind.FLAG,  "Explain and test the connection (where it comes from, SSH, host servers, libraries); never prints the password"),
    EXPLAIN_CONNECTION(null, "explain-connection", Kind.FLAG, "Same as --doctor"),
    PASSWORD_STDIN(null, "password-stdin", Kind.FLAG, "Read the IBM i password from the first line of stdin (not with --mcp); IBMI_PASSWORD_FILE names a chmod 600 file instead"),
    PASSWORD_COMMAND(null, "password-command", Kind.VALUE, "Command whose first output line is the IBM i password (secret-tool lookup ..., pass show ...); IBMI_PASSWORD_COMMAND is the same"),
    CLEAN_REMOTE  (null, "clean-remote", Kind.FLAG,  "With --remote: after a successful build, delete the uploaded project copy and MC's jar from the IBM i"),
    LOCAL_PROJECT (null, "local-project", Kind.VALUE, "Internal, set by --remote: the project folder on the PC that uploaded it; CHGCURDIR hooks naming it point at --project instead"),
    DOWNLOAD      (null, "download",     Kind.VALUE, "Over SSH, save objects (*MODULE, *PGM, *SRVPGM) into a save file each and download them to -o <dir> (default .): LIB/OBJ, comma-separated"),
    SRVPGM_FROM_EXPORTS(null, "srvpgm-from-exports", Kind.FLAG, "With --scan: a module that exports procedures, and that no service program binds, gets a service program (EXPORT(*ALL))"),
    STRICT_HOST_KEY(null, "strict-host-key", Kind.FLAG, "SSH: refuse a host key that is not in known_hosts (fails with its fingerprint) instead of trusting it; IBMI_SSH_KNOWN_HOSTS names the known_hosts file"),
    HOST_SERVERS  (null, "host-servers", Kind.FLAG,  "With --setup-vscode: connect through the host servers (like ACS) instead of SSH"),
    INSTALL_SKILLS(null, "install-skills", Kind.FLAG, "Install MC's agent skills for every project: ~/.copilot/skills (Copilot in VS Code)"),
    VERSION       (null, "version",      Kind.FLAG,  "Print the MasterCompiler version"),
    HELP          ("h", "help",          Kind.FLAG,  "Print this help"),
    PRINT         (null, "print",        Kind.FLAG,  "With --setup-vscode: show what would be written, write nothing"),
    NEXT          (null, "next",         Kind.VALUE, "With --setup-vscode: the request to carry on in the repository's window (Copilot Chat: /mastercompiler-continue)"),
    NEXT_FILE     (null, "next-file",    Kind.VALUE, "Like --next, read from a UTF-8 text file (no shell quoting problems)"),
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
    MCP           (null, "mcp",          Kind.FLAG,  "Run as an MCP server on stdio (tools: build, plan, impact, clean, find_source, import_source, joblog)"),
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
  private int sshPort;  // 0: not given
  private boolean remote;
  private boolean doctor;
  private boolean passwordStdin;
  private String passwordCommand;
  private boolean cleanRemote;
  private String localProject;
  private String download;
  private boolean srvpgmFromExports;
  private boolean strictHostKey;
  private boolean readOnly;
  private int maxRows;
  private String maskColumns;
  private boolean hostServers;
  private boolean installSkills;
  private boolean version;
  private boolean help;
  private String next;
  private String nextFile;  // --next-file: removed once setup saved the request

  private final String[] args;

  public ArgParser(String[] args) {
    this.args = args.clone();
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
      case REMOTE:
      case SSH_BUILD:     remote = true; break;
      case DOCTOR:
      case EXPLAIN_CONNECTION: doctor = true; break;
      case PASSWORD_STDIN: passwordStdin = true; break;
      case CLEAN_REMOTE:  cleanRemote = true; break;
      case SRVPGM_FROM_EXPORTS: srvpgmFromExports = true; break;
      case STRICT_HOST_KEY: strictHostKey = true; break;
      case READ_ONLY:     readOnly = true; break;
      case HOST_SERVERS:  hostServers = true; break;
      case INSTALL_SKILLS: installSkills = true; break;
      case VERSION:       version = true; break;
      case HELP:          help = true; break;
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
      case NEXT:          next = value; break;
      case NEXT_FILE:     next = readNextFile(value); nextFile = value; break;
      case IMPORT:
        importSelection = value;
        break;
      case FROM_TOBI:
        tobiRoot = value;
        break;
      case PROJECT:
        projectRoot = value;
        break;
      case PASSWORD_COMMAND: passwordCommand = value; break;
      case LOCAL_PROJECT: localProject = value; break;
      case DOWNLOAD:      download = value; break;
      case LIBL:
        libl = value;
        break;
      case CURLIB:
        curlib = value;
        break;
      case MAX_ROWS:
        try {
          maxRows = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("--max-rows needs a number: " + value);
        }
        break;
      case MASK_COLUMNS:  maskColumns = value; break;
      case SSH_PORT:
        try {
          sshPort = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
          sshPort = -1;
        }
        if (sshPort < 1 || sshPort > 65535) throw new IllegalArgumentException("--ssh-port needs a port from 1 to 65535: " + value);
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
    if (doctor) {
      if (mcp || setupVscode || remote || installSkills || version || importSelection != null || yamlFile != null
          || scanRoot != null || tobiRoot != null || push != null) {
        throw new IllegalArgumentException("--doctor takes only --code4i, --connection, --ssh-port, --strict-host-key, "
            + "--password-stdin, --password-command, --project, --libl, --curlib, -v");
      }
      return;
    }
    if (download != null) {
      if (mcp || setupVscode || remote || installSkills || version || importSelection != null || yamlFile != null
          || scanRoot != null || tobiRoot != null || push != null || projectRoot != null || generateOnly) {
        throw new IllegalArgumentException("--download takes only LIB/OBJ[,LIB/OBJ...], -o <folder>, --code4i, --connection, "
            + "--ssh-port, --strict-host-key, --password-stdin, --password-command, --curlib, -v");
      }
      ObjectDownload.parse(download);  // names checked now, not after the connection
      return;
    }
    if (ssh && !mcp && !setupVscode && !remote) {
      throw new IllegalArgumentException("--ssh goes with --mcp (or --setup-vscode); to build over SSH use --remote");
    }
    if (sshPort != 0 && !ssh && !remote) {
      throw new IllegalArgumentException("--ssh-port goes with --ssh, --remote or --doctor");
    }
    if (cleanRemote && !remote) {
      throw new IllegalArgumentException("--clean-remote goes with --remote");
    }
    if (passwordStdin && mcp) {
      throw new IllegalArgumentException("--password-stdin cannot be used with --mcp: stdin is the protocol channel");
    }
    if (remote) {
      if (mcp) throw new IllegalArgumentException("--remote builds from the command line; the MCP server over SSH is --mcp --ssh");
      if (projectRoot == null) throw new IllegalArgumentException("--remote needs --project <local folder> to upload");
      if (yamlFile != null || scanRoot != null || tobiRoot != null || importSelection != null || baseFile != null
          || push != null || outputFile != null || generateOnly || since != null) {
        throw new IllegalArgumentException("--remote takes --project and the build options (-x, -v, -c, -k, --dry-run, --diff, "
            + "--no-migrate, --lib, --libl, --curlib, --json, --clean-remote); the sources come from the --project folder, "
            + "and the IBM i copy has no git history");
      }
      if (hostServers) throw new IllegalArgumentException("Use either --remote or --host-servers");
    }
    if (ssh && mcp && projectRoot == null) {
      throw new IllegalArgumentException("--ssh needs --project <local folder> to upload");
    }
    if (installSkills || version) return;  // need nothing else
    if (readOnly && !mcp) throw new IllegalArgumentException("--read-only goes with --mcp");
    if ((maxRows != 0 || maskColumns != null) && !readOnly) {
      throw new IllegalArgumentException("--max-rows and --mask-columns go with --read-only");
    }

    if (hostServers && !setupVscode) {
      throw new IllegalArgumentException("--host-servers goes with --setup-vscode");
    }
    if (hostServers && ssh) {
      throw new IllegalArgumentException("Use either --ssh or --host-servers");
    }
    if (next != null && !setupVscode) {
      throw new IllegalArgumentException("--next / --next-file go with --setup-vscode");
    }
    if (print && !setupVscode) {
      throw new IllegalArgumentException("--print goes with --setup-vscode");
    }
    if (setupVscode) {
      if (yamlFile != null || scanRoot != null || tobiRoot != null || mcp || importSelection != null) {
        throw new IllegalArgumentException("--setup-vscode takes only --project, --connection, --host-servers, --next, --print, -v");
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
    if (srvpgmFromExports && !hasScan) {
      throw new IllegalArgumentException("--srvpgm-from-exports goes with --scan (or a --project that is scanned)");
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

  /** {@code --ssh-port}, or 0 when not given. */
  public int getSshPort() {
    return sshPort;
  }

  /** {@code --remote} / {@code --ssh-build}: build on the IBM i over SSH. */
  public boolean isRemote() {
    return remote;
  }

  /** {@code --doctor} / {@code --explain-connection}. */
  public boolean isDoctor() {
    return doctor;
  }

  public boolean isPasswordStdin() {
    return passwordStdin;
  }

  public boolean isStrictHostKey() {
    return strictHostKey;
  }

  /** {@code --password-command}, or null. */
  public String getPasswordCommand() {
    return passwordCommand;
  }

  /** {@code --clean-remote}: delete the IBM i copy after a successful {@code --remote} build. */
  public boolean isCleanRemote() {
    return cleanRemote;
  }

  /** {@code --local-project}: where the project was on the PC that uploaded it (set by {@code --remote}), or null. */
  public String getLocalProject() {
    return localProject;
  }

  /** {@code --download} selection (LIB/OBJ, comma-separated), or null. */
  public String getDownload() {
    return download;
  }

  /** {@code --srvpgm-from-exports}. */
  public boolean isSrvpgmFromExports() {
    return srvpgmFromExports;
  }

  /*
   * The arguments for MC running on the IBM i (--remote): the build options as typed, without the connection ones
   * (they only mean something on this PC), and --project pointing at the uploaded copy.
   */
  public List<String> remoteArgs(String remoteProject) {
    java.util.Set<Option> local = java.util.EnumSet.of(Option.CODE4I, Option.CONNECTION, Option.SSH, Option.SSH_PORT,
        Option.REMOTE, Option.SSH_BUILD, Option.PASSWORD_STDIN, Option.PASSWORD_COMMAND, Option.STRICT_HOST_KEY, Option.PROJECT,
        Option.CLEAN_REMOTE, Option.JSON, Option.LOCAL_PROJECT);
    List<String> out = new java.util.ArrayList<String>();
    for (int i = 0; i < args.length; i++) {
      Option opt = args[i].startsWith("--") ? LONG_OPTIONS.get(args[i].substring(2))
          : args[i].length() == 2 ? SHORT_OPTIONS.get(args[i].substring(1)) : null;
      boolean value = opt != null && !opt.isFlag();
      if (opt == null || !local.contains(opt)) {
        out.add(args[i]);
        if (value && i + 1 < args.length) out.add(args[i + 1]);
      }
      if (value) i++;
    }
    out.add("--project");
    out.add(remoteProject);
    return out;
  }

  /** {@code --read-only}: an MCP server that only reads, for a second IBM i. */
  public boolean isReadOnly() {
    return readOnly;
  }

  /** {@code --max-rows}, or 0 when not given. */
  public int getMaxRows() {
    return maxRows;
  }

  /** {@code --mask-columns}, upper case, or an empty list. */
  public List<String> getMaskColumns() {
    List<String> columns = new java.util.ArrayList<String>();
    if (maskColumns == null) return columns;
    for (String c : maskColumns.split("[,\\s]+")) if (!c.trim().isEmpty()) columns.add(c.trim().toUpperCase());
    return columns;
  }

  private static String readNextFile(String path) {
    try {
      String text = new String(java.nio.file.Files.readAllBytes(new File(path).toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
      if (text.startsWith("\uFEFF")) text = text.substring(1).trim();  // Windows PowerShell 5.1 writes a BOM
      if (text.isEmpty()) throw new IllegalArgumentException("--next-file is empty: " + path);
      return text;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("--next-file cannot be read: " + path);
    }
  }

  /** {@code --next} / {@code --next-file}: the request the repository's window carries on with, or null. */
  public String getNext() {
    return next;
  }

  public String getNextFile() {
    return nextFile;
  }

  public boolean isHelp() {
    return help;
  }

  /* -h / --help anywhere: help wins over everything else, including invalid options */
  public static boolean wantsHelp(String[] args) {
    for (String arg : args) if (arg.equals("-h") || arg.equals("--help")) return true;
    return false;
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
