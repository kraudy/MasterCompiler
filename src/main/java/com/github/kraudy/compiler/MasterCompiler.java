package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;
import com.ibm.as400.access.User;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Master compiler for the IBM I platform.
 */
public class MasterCompiler{
  private static final Logger logger = LoggerFactory.getLogger(MasterCompiler.class); // Per-class logger

  public static final String INVARIANT_CCSID = "37"; // EBCDIC
  public static final String UTF8_CCSID = "1208";
  private final AS400 system;
  private final Connection connection;
  private final User currentUser;
  private CommandExecutor commandExec;
  private Migrator migrator;
  private ObjectDescriptor odes;
  private SourceDescriptor sourceDes;
  private DependencyAwareness depAwareness;

  private BuildSpec globalSpec;     // global build spec
  private boolean dryRun = false;   // Compile commands without executing 
  private boolean debug = false;    // Debug flag
  private boolean verbose = false;  // Verbose output flag
  private boolean clean = false;  // Delete spec objects after compilation
  private boolean diff = false;     // Diff build flag
  private boolean noMigrate = false;  // Source migration
  private String jsonReport = null;   // --json <file>: machine-readable build report
  private String since = null;        // --since <git-ref>: build what git says changed, plus dependents
  private String push = null;         // --push <ifs-dir>: upload local sources there before building
  private String compileBaseDir = null; // where the IBM i job resolves relative SRCSTMF (spec dir or pushed dir)
  private boolean collectReport = false; // keep the report in memory (MCP) even without --json
  private Set<String> changedFiles = null; // explicit changed sources (MCP build/plan files), like --since
  private boolean keepGoing = false;  // --keep-going: after a failure, build everything that does not depend on it
  private List<CommandObject> libraryHooks = new ArrayList<CommandObject>(); // --code4i / --libl / --curlib

  private boolean compilationError = false;
  private int builtCount = 0;
  private int skippedCount = 0;
  private Set<TargetKey> rebuildSet = new HashSet<TargetKey>();
  private BuildReport report = new BuildReport();

  public MasterCompiler(AS400 system) throws Exception {
    this(system, new AS400JDBCDataSource(system).getConnection());
  }

  public MasterCompiler(AS400 system, Connection connection) throws Exception {
    this.system = system;

    // Database
    this.connection = connection;
    this.connection.setAutoCommit(true);

    // User
    this.currentUser = new User(system, system.getUserId());
    this.currentUser.loadUserInformation();

  }

  public MasterCompiler(AS400 system, Connection connection, BuildSpec globalSpec, boolean dryRun, boolean debug, 
        boolean verbose, boolean clean, boolean diff, boolean noMigrate) throws Exception {
    this(system, connection);

    /* Set params */
    this.globalSpec = globalSpec;
    this.dryRun = dryRun;
    this.debug = debug;
    this.verbose = verbose;
    this.clean = clean;
    this.diff = diff;
    this.noMigrate = noMigrate;
  }

  public void setJsonReport(String jsonReport) {
    this.jsonReport = jsonReport;
  }

  public void setSince(String since) {
    this.since = since;
  }

  public void setPush(String push) {
    this.push = push;
  }

  public void setCollectReport(boolean collectReport) {
    this.collectReport = collectReport;
  }

  /* Canonical absolute paths of changed sources; targets built = those + dependents */
  public void setChangedFiles(Set<String> changedFiles) {
    this.changedFiles = changedFiles;
  }

  public void setLibraryHooks(List<CommandObject> libraryHooks) {
    this.libraryHooks = libraryHooks;
  }

  public void setKeepGoing(boolean keepGoing) {
    this.keepGoing = keepGoing;
  }

  public BuildReport getReport() {
    return report;
  }

  private boolean reporting() {
    return jsonReport != null || collectReport;
  }

  /* --diff and --since both build a rebuild set and skip the rest */
  private boolean isIncremental() {
    return diff || since != null || changedFiles != null;
  }

  public void build() {

    /* Init command executor */
    commandExec = new CommandExecutor(connection, debug, verbose, dryRun);

    /* Init migrator */
    if (!noMigrate) migrator = new Migrator(connection, debug, verbose, currentUser, commandExec);

    /* Init dependency awareness */
    if (isIncremental() || keepGoing) depAwareness = new DependencyAwareness(system, debug, verbose);


    /* Init source descriptor */
    String baseDir = globalSpec != null ? globalSpec.getBaseDirectory() : null;
    sourceDes = new SourceDescriptor(system, connection, baseDir, debug, verbose);

    /* Init object descriptor */
    odes = new ObjectDescriptor(connection, debug, verbose);

    compileBaseDir = baseDir;

    qualifyProjectObjects();

    try {
      /* The build job gets the developer's library list (spec hooks run after it) */
      globalSpec.before.addAll(0, libraryHooks);

      /* Push local sources to the IFS; the job then compiles from that copy */
      if (push != null) {
        compileBaseDir = SourcePusher.push(system, baseDir, push, since, changedFiles, dryRun, verbose);
        globalSpec.before.add(0, new CommandObject(SysCmd.CHGCURDIR).put(ParamCmd.DIR, compileBaseDir));
      }

      /* Global before */
      if(!globalSpec.before.isEmpty()){
        if (verbose) logger.info("Executing global before: " + globalSpec.before.size() + " commands found");
        commandExec.executeCommand(globalSpec.before);
      }

      if(verbose) logger.info(showLibraryList());

      /* The dependency graph drives incremental selection and, with keep-going, what a failure blocks */
      if ((isIncremental() || keepGoing) && !globalSpec.isDependenciesDetected()) depAwareness.detectDependencies(globalSpec);
      if (since != null || changedFiles != null) collectSinceRebuildSet();
      else if (diff) collectDiffRebuildSet();

      /* Build each target */
      buildTargets(globalSpec.targets);

      /* Execute global success */
      if(!globalSpec.success.isEmpty()){
        if (verbose) logger.info("Executing global success: " + globalSpec.success.size() + " commands found");
        commandExec.executeCommand(globalSpec.success);
      }

      /* Execute global after */
      if(!globalSpec.after.isEmpty()){
        if (verbose) logger.info("Executing global after: " + globalSpec.after.size() + " commands found");
        commandExec.executeCommand(globalSpec.after);
      }
      
    } catch (CompilerException e){
      compilationError = true;
      if (verbose) logger.error("Compilation failed");

      /* Get full compiler exception context */
      logger.error(e.getFullContext());
      if (report.failed == 0 && report.error == null) report.error = e.getMessage();

      /* Global compiler failure */
      try{
        if(!globalSpec.failure.isEmpty()){
          if (verbose) logger.error("Executing global failure");
          commandExec.executeCommand(globalSpec.failure);
        }
      } catch (Exception failureErr) {
          throw new CompilerException("Target failure hook also failed", failureErr);
      }

    } catch (Exception e) {
      compilationError = true;
      /* Unhandled Exception. Fail loudly */
      logger.error("Unhandled Exception. Fail loudly", e);
      if (report.failed == 0 && report.error == null) report.error = e.toString();

    } finally {
      if (clean) {
        if (verbose) logger.info("Cleaning built objects");
        clenBuiltObjects();
      }
      /* Show chain of commands */
      if (verbose) logger.info("Chain of commands: {}", commandExec.getExecutionChain());

      if (reporting()) finishReport();
    }

  }

  private void buildTargets(LinkedHashMap<TargetKey, BuildSpec.TargetSpec> targets) throws Exception{
    /* This is intended for a YAML file with multiple objects in a toposort order */
    Set<TargetKey> blocked = new HashSet<TargetKey>();  // keep-going: dependents of failed targets
    List<String> failedTargets = new ArrayList<String>();

    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : targets.entrySet()) {
      TargetKey key = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();

      /* Skip target if incremental and not in the rebuild set (seed + fathers) */
      if (isIncremental()) {
        if (!rebuildSet.contains(key)) {
          this.skippedCount++;
          if (reporting()) {
            resolveLibrary(key);
            report.add(key.asString(), BuildReport.SKIPPED);
          }
          if (verbose) logger.info("Skipping unchanged target: " + key.asString() + (diff ? key.getTimestmaps() : ""));
          continue;
        }
      }

      if (blocked.contains(key)) {
        if (verbose) logger.info("Blocked by a failed dependency: " + key.asString());
        if (reporting()) {
          resolveLibrary(key);
          report.add(key.asString(), BuildReport.BLOCKED).error = "Depends on a failed target";
        }
        continue;
      }

      this.builtCount++;
      if (verbose) logger.info("Building: " + key.asString());

      Timestamp targetStart = reporting() ? commandExec.getCurrentTime() : null;
      String command = null;

      try{

        /* Resolve curlib */
        if(key.isCurLib()){
          if (verbose) logger.info("Resolving curlib");
          key.setLibrary(getCurLIb());
        }

        /* Per target before */
        if(!targetSpec.before.isEmpty()){
          if (verbose) logger.info("Executing target before: " + targetSpec.before.size() + " commands found");
          commandExec.executeCommand(targetSpec.before);
        }

        /* Files the program can change, resolved like at run time; one in a protected library fails the target */
        checkWrites(key);

        /* Only whether it exists: compile params come from the spec and MC's defaults, never from the
           existing object (that would carry over whatever someone compiled by hand: TGTRLS, TEXT, ...) */
        odes.objectExists(key);

        /* Set global defaults params per target */
        key.putAll(globalSpec.defaults);

        /* Set target specific params */
        key.putAll(targetSpec.params);


        /* Migrate source file */
        if (!noMigrate) migrator.migrateSource(key);

        /* EXPORT(*ALL) takes no binder source: SRCFILE / SRCMBR would only be noise (or a wrong library) */
        if (key.getCompilationCommand() == CompilationPattern.CompCmd.CRTSRVPGM && key.get(ParamCmd.EXPORT).contains("ALL")
            && !key.containsStreamFile()) {
          key.removeSourceFile().removeMember();
        }

        /* Execute compilation command */
        if (reporting()) command = CommandStringParser.toPasteableCommand(key);
        commandExec.executeCommand(key);

        /* Per target success */
        if(!targetSpec.success.isEmpty()){
          if (verbose) logger.info("Executing target success: " + targetSpec.success.size() + " commands found");
          commandExec.executeCommand(targetSpec.success);
        } 

        /* Per target after */
        if(!targetSpec.after.isEmpty()){
          if (verbose) logger.info("Executing target after: " + targetSpec.after.size() + " commands found");
          commandExec.executeCommand(targetSpec.after);
        } 

        if (reporting()) reportTarget(key, dryRun ? BuildReport.PLANNED : BuildReport.BUILT, command, null, targetStart);

      } catch (CompilerException e){
        compilationError = true;
        if (verbose) logger.error("Target compilation failed: " + key.asString());
        if (reporting()) reportTarget(key, BuildReport.FAILED, command, e.getMessage(), targetStart);

        /* Per target failure */
        if(!targetSpec.failure.isEmpty()){
          if (verbose) logger.error("Executing target failure: " + targetSpec.failure.size() + " commands found");
          commandExec.executeCommand(targetSpec.failure);
        } 

        if (!keepGoing) throw e; // Raise
        failedTargets.add(key.asString());
        blocked.addAll(DiffPlanner.expand(globalSpec, Collections.singleton(key)));

      } catch (Exception e){
        compilationError = true;
        if (verbose) logger.error("Unhandled exception in Target: " + key.asString());
        if (reporting()) reportTarget(key, BuildReport.FAILED, command, e.toString(), targetStart);

        if (!keepGoing) throw e; // Raise
        failedTargets.add(key.asString());
        blocked.addAll(DiffPlanner.expand(globalSpec, Collections.singleton(key)));

      } finally {
        //TODO: Do something cool here.
      }
    }


    /* Keep-going ran every buildable target; the build still fails (global failure hooks, exit 1) */
    if (!failedTargets.isEmpty()) {
      throw new CompilerException(failedTargets.size() + " target(s) failed: " + String.join(", ", failedTargets));
    }
  }

  private final Map<TargetKey, List<String>> targetWrites = new java.util.HashMap<TargetKey, List<String>>();
  private final Map<String, String> fileLibrary = new java.util.HashMap<String, String>();  // FILE -> library on the library list

  private static final List<String> WRITE_CHECKED = Arrays.asList("RPGLE", "SQLRPGLE", "RPG", "SQLRPG", "CBLLE", "SQLCBLLE");

  /* RPG / COBOL sources: report the files they can change; fail when one resolves into a protectedLibs library */
  private void checkWrites(TargetKey key) throws Exception {
    if (!key.containsStreamFile()) return;
    String stmf = key.getStreamFile();
    String ext = stmf.substring(stmf.lastIndexOf('.') + 1).toUpperCase();
    if (!WRITE_CHECKED.contains(ext)) return;
    java.io.File source = new java.io.File(stmf);
    if (!source.isAbsolute()) {
      java.io.File local = new java.io.File(globalSpec.getBaseDirectory() != null ? globalSpec.getBaseDirectory() : ".", stmf);
      source = local.isFile() || compileBaseDir == null ? local : new java.io.File(compileBaseDir, stmf);
    }
    if (!source.isFile()) return;

    List<String> writes = new ArrayList<String>();
    List<String> forbidden = new ArrayList<String>();
    for (Map.Entry<String, java.util.TreeSet<String>> file : FileWrites.parse(
        java.nio.file.Files.readAllLines(source.toPath(), java.nio.charset.StandardCharsets.UTF_8)).entrySet()) {
      String name = file.getKey();
      String library = name.contains("/") ? name.substring(0, name.indexOf('/')) : libraryOf(name);
      String object = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
      String line = object + " " + String.join("/", file.getValue()) + " -> " + (library != null ? library : "(not found on the library list)");
      writes.add(line);
      final String lib = library;
      if (lib != null && globalSpec.protectedLibs.stream().anyMatch(p -> p.trim().equalsIgnoreCase(lib))) forbidden.add(line);
    }
    if (!writes.isEmpty()) targetWrites.put(key, writes);
    if (!forbidden.isEmpty()) {
      throw new CompilerException("Writes to a protected library (protectedLibs " + globalSpec.protectedLibs + "): "
          + String.join("; ", forbidden) + ". Point those files at a development library (library list, EXTFILE, "
          + "qualified SQL names) before building.");
    }
  }

  /* Where FILE resolves: a project target (its library), else the first library-list library holding it */
  private String libraryOf(String file) {
    if (fileLibrary.containsKey(file)) return fileLibrary.get(file);
    String library = null;
    for (TargetKey target : globalSpec.targets.keySet()) {
      if (target.getObjectName().equalsIgnoreCase(file) && target.isFile()) {
        try {
          library = target.isCurLib() ? getCurLIb() : target.getLibrary();
        } catch (Exception ignored) { /* falls back to the library list */ }
        break;
      }
    }
    if (library == null) {
      try (java.sql.PreparedStatement stmt = connection.prepareStatement(
          "SELECT TRIM(O.OBJLIB), COALESCE(L.ORDINAL_POSITION, 99999) FROM TABLE(QSYS2.OBJECT_STATISTICS('*LIBL', '*FILE', OBJECT_NAME => ?)) O " +
          "LEFT JOIN QSYS2.LIBRARY_LIST_INFO L ON L.SYSTEM_SCHEMA_NAME = O.OBJLIB")) {
        stmt.setString(1, file);
        int best = Integer.MAX_VALUE;
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            if (rs.getInt(2) < best) {
              best = rs.getInt(2);
              library = rs.getString(1);
            }
          }
        }
      } catch (SQLException e) {
        logger.info("Could not resolve file {}: {}", file, e.getMessage());
      }
    }
    fileLibrary.put(file, library);
    return library;
  }

  /* Adds the target to the report with its joblog and, when compiled, its EVFEVENT errors */
  private void reportTarget(TargetKey key, String status, String command, String error, Timestamp since) {
    BuildReport.TargetResult result = report.add(key.asString(), status);
    result.command = command;
    result.error = error;
    result.writes = targetWrites.get(key);
    if (key.objectExists() && CommandExecutor.RECREATED.contains(key.getObjectTypeEnum())) {
      result.warning = (dryRun ? "Would delete" : "Deleted") + " the existing *" + key.getObjectTypeEnum().name()
          + " and create it again" + (key.getObjectTypeEnum() == CompilationPattern.ObjectType.PF ? ": its data is lost" : "");
    }
    if (dryRun || since == null) return;

    try {
      result.joblog = commandExec.getJoblogMessages(since);
      if (command != null && writesEventFile(key)) {
        if (key.getCompilationCommand() == CompilationPattern.CompCmd.RUNSQLSTM) {
          /* The listing also carries warnings (e.g. SQL7905 not journaled): read it only on failure */
          if (!BuildReport.FAILED.equals(status)) return;
          String stmf = key.containsStreamFile() ? key.getStreamFile() : null;
          String spool = stmf != null ? new java.io.File(stmf).getName().split("\\.")[0] : key.getObjectName();
          result.errors = EventFile.readSqlListing(connection, spool, since, stmf);
        } else {
          result.errors = EventFile.read(connection, key.getLibrary(), key.getObjectName(), since, compileBaseDir);
        }
      }
    } catch (Exception e) {
      logger.info("Could not collect report details for {}: {}", key.asString(), e.getMessage());
    }
  }

  /*
   * Modules and service programs this build creates are referred to in their own library (*CURLIB for
   * curlib targets), not *LIBL: a same-named object earlier in the library list must not be bound instead.
   * Covers MODULE / BNDSRVPGM params and ADDBNDDIRE OBJ in hooks; external objects keep *LIBL.
   * ADDBNDDIRE OBJ does not take *CURLIB (CPD0078): there it is the current library's name.
   */
  private void qualifyProjectObjects() {
    java.util.Map<String, String> modules = new java.util.HashMap<String, String>();
    java.util.Map<String, String> srvpgms = new java.util.HashMap<String, String>();
    java.util.Map<String, String> entries = new java.util.HashMap<String, String>();
    String curlib = null;
    for (TargetKey key : globalSpec.targets.keySet()) {
      String lib = key.isCurLib() ? ValCmd.CURLIB.toString() : key.getLibrary();
      if (key.isModule()) modules.put(key.getObjectName().toUpperCase(), lib);
      if (key.isServiceProgram()) {
        srvpgms.put(key.getObjectName().toUpperCase(), lib);
        if (key.isCurLib() && curlib == null) curlib = buildCurLib();
        entries.put(key.getObjectName().toUpperCase(), key.isCurLib() ? curlib : lib);
      }
    }
    for (BuildSpec.TargetSpec spec : globalSpec.targets.values()) {
      if (spec == null) continue;
      qualify(spec.params, ParamCmd.MODULE, modules);
      qualify(spec.params, ParamCmd.BNDSRVPGM, srvpgms);
      for (List<CommandObject> hooks : Arrays.asList(spec.before, spec.after, spec.success, spec.failure)) qualifyHooks(hooks, entries);
    }
    for (List<CommandObject> hooks : Arrays.asList(globalSpec.before, globalSpec.after, globalSpec.success, globalSpec.failure)) {
      qualifyHooks(hooks, entries);
    }
  }

  /* What *CURLIB will be once the job's hooks ran: the last CHGCURLIB among them, else the job's own */
  private String buildCurLib() {
    String lib = null;
    List<CommandObject> hooks = new ArrayList<CommandObject>(libraryHooks);
    hooks.addAll(globalSpec.before);
    for (CommandObject hook : hooks) {
      boolean setsCurlib = hook.getSystemCommand() == SysCmd.CHGCURLIB || hook.getSystemCommand() == SysCmd.CHGLIBL;
      String value = setsCurlib && hook.containsKey(ParamCmd.CURLIB) ? hook.get(ParamCmd.CURLIB) : null;
      if (value != null && !value.trim().equalsIgnoreCase("*SAME")) lib = value.trim();
    }
    if (lib != null && !lib.startsWith("*")) return lib.toUpperCase();
    try {
      return getCurLIb();
    } catch (Exception e) {
      return ValCmd.LIBL.toString();  // no current library: *LIBL still finds it
    }
  }

  private static void qualify(Map<ParamCmd, String> params, ParamCmd param, Map<String, String> libs) {
    if (params == null || params.get(param) == null) return;
    params.put(param, qualifyNames(params.get(param), libs));
  }

  private static void qualifyHooks(List<CommandObject> hooks, Map<String, String> srvpgms) {
    if (hooks == null) return;
    for (CommandObject hook : hooks) {
      if (hook.getSystemCommand() == SysCmd.ADDBNDDIRE && hook.containsKey(ParamCmd.OBJ)) {
        hook.put(ParamCmd.OBJ, qualifyNames(hook.get(ParamCmd.OBJ), srvpgms));
      }
    }
  }

  /* "A *LIBL/B LIB/C" -> project names get their library, others are left as they are */
  static String qualifyNames(String value, Map<String, String> libs) {
    StringBuilder out = new StringBuilder();
    for (String token : value.trim().split("\\s+")) {
      String name = token.startsWith(ValCmd.LIBL.toString() + "/") ? token.substring(token.indexOf('/') + 1) : token;
      if (!name.contains("/") && libs.containsKey(name.toUpperCase())) token = libs.get(name.toUpperCase()) + "/" + name;
      if (out.length() > 0) out.append(' ');
      out.append(token);
    }
    return out.toString();
  }

  /* Report entries of targets that were not built use the same library naming as the built ones */
  private void resolveLibrary(TargetKey key) {
    try {
      if (key.isCurLib()) key.setLibrary(getCurLIb());
    } catch (Exception ignored) {}
  }

  /* Commands that report compile errors (EVFEVENT, or RUNSQLSTM's listing); CRTBNDDIR, CRTDTAARA, ... do not */
  private static boolean writesEventFile(TargetKey key) {
    switch (key.getCompilationCommand()) {
      case CRTBNDDIR: case CRTDTAARA: case CRTDTAQ: case CRTMSGF:
        return false;
      default:
        return true;
    }
  }

  /*
   * MCP mode: stderr shows as warnings in VS Code, so keep it to warnings and errors unless -v,
   * except the commands MC runs (compiles included), one copy-pasteable line each
   */
  static void quietLogs(boolean verbose) {
    if (verbose) return;
    ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.WARN);
    context.getLogger("com.github.kraudy.compiler").setLevel(ch.qos.logback.classic.Level.WARN);
    context.getLogger(CommandExecutor.class).setLevel(ch.qos.logback.classic.Level.INFO);
    /* SSH start-up stages, upload progress and stalls: the first thing to read when the server hangs */
    context.getLogger(SshMcpProxy.class).setLevel(ch.qos.logback.classic.Level.INFO);
    context.getLogger(SshTarget.class).setLevel(ch.qos.logback.classic.Level.INFO);
  }

  /* Targets never reached are listed as not built, then the report is written (--json) */
  private void finishReport() {
    if (compilationError) report.success = false;
    if (!globalSpec.warnings.isEmpty()) report.warnings = new ArrayList<String>(globalSpec.warnings);
    if (commandExec != null && !commandExec.getHooksRun().isEmpty()) report.hooks = new ArrayList<String>(commandExec.getHooksRun());
    report.dryRun = dryRun;

    Set<String> reported = new HashSet<String>();
    for (BuildReport.TargetResult result : report.targets) reported.add(result.target);
    for (TargetKey key : globalSpec.targets.keySet()) {
      if (reported.contains(key.asString())) continue;
      resolveLibrary(key);
      report.add(key.asString(), BuildReport.NOT_BUILT);
    }
    for (TargetKey key : globalSpec.targets.keySet()) {
      for (String object : key.getExternals()) {
        report.external.computeIfAbsent(object, k -> new ArrayList<String>()).add(key.asString());
      }
    }

    if (jsonReport == null) return;
    try {
      report.writeToFile(jsonReport);
      logger.info("Build report: {}", jsonReport);
    } catch (Exception e) {
      logger.error("Could not write build report " + jsonReport, e);
    }
  }

  public boolean foundCompilationError(){
    return this.compilationError;
  }

  public int getBuiltCount() {
    return this.builtCount;
  }

  public int getSkippedCount() {
    return this.skippedCount;
  }

  public BuildSpec getGlobalSpec(){
    return this.globalSpec;
  }

  private void clenBuiltObjects(){
    List<TargetKey> objectsToDelete = globalSpec.getTargetsList();

    // Delete compiled objects in reverse creation order (dependents first)
    for (int i = objectsToDelete.size() - 1; i >= 0; i--) {
      TargetKey key = objectsToDelete.get(i);
      /* If object does not exists, omit */
      if (!key.objectExists()) continue;
      try {
        commandExec.deleteObject(key);
      } catch (Exception ignored) {} // This prevents breaking the loop
    }
  }

  private void collectDiffRebuildSet() throws SQLException {
    Set<TargetKey> seeds = new HashSet<TargetKey>();
    for (TargetKey key : globalSpec.targets.keySet()) {
      sourceDes.getObjectTimestamps(key);
      if (key.needsRebuild()) {
        seeds.add(key);
        if (verbose) logger.info("Diff seed: " + key.asString() + key.getTimestmaps());
      }
    }
    rebuildSet = DiffPlanner.expand(globalSpec, seeds);
    if (verbose) {
      logger.info("Diff rebuild set: " + rebuildSet.size() + " of " + globalSpec.targets.size());
    }
  }

  /* Seeds: targets whose source stream file or any attached /copy|/include changed since the git ref */
  private void collectSinceRebuildSet() {
    Set<String> changed = changedFiles != null
        ? changedFiles
        : GitChanges.changedFiles(globalSpec.getBaseDirectory(), since);
    Set<TargetKey> seeds = new HashSet<TargetKey>();
    for (TargetKey key : globalSpec.targets.keySet()) {
      if (sourceChanged(key, changed)) {
        seeds.add(key);
        if (verbose) logger.info("Since seed: " + key.asString());
      }
    }
    rebuildSet = DiffPlanner.expand(globalSpec, seeds);
    logger.info("{}: {} changed files, rebuilding {} of {} targets",
        since != null ? "Since " + since : "Changed files", changed.size(), rebuildSet.size(), globalSpec.targets.size());
  }

  private boolean sourceChanged(TargetKey key, Set<String> changed) {
    if (key.containsStreamFile()) {
      String source = SourceDescriptor.resolveFullPath(globalSpec.getBaseDirectory(), key.getStreamFile());
      if (changed.contains(GitChanges.canonical(source))) return true;
    }
    for (String include : key.getIncludeFiles()) {
      if (changed.contains(GitChanges.canonical(include))) return true;
    }
    return false;
  }

  private String showLibraryList() throws SQLException{
    StringBuilder sb = new StringBuilder();;
    sb.append("\nLibrary list: \n");
    try(Statement stmt = connection.createStatement();
        ResultSet rsLibList = stmt.executeQuery(
          "SELECT DISTINCT(SCHEMA_NAME) As Libraries FROM QSYS2.LIBRARY_LIST_INFO " + 
          "WHERE TYPE NOT IN ('SYSTEM','PRODUCT') AND SCHEMA_NAME NOT IN ('QGPL', 'GAMES400')"
          
        )){
      while (rsLibList.next()) {
        sb.append(rsLibList.getString("Libraries")).append("\n");
      }
      return sb.toString();

    } catch (SQLException e){
      logger.error("Error retrieving library list");
      throw e;
    }
  }

  private String getCurLIb() throws SQLException{
    String curlib = "";

    try(Statement stmt = connection.createStatement();
        ResultSet rsCurLib = stmt.executeQuery(
          "SELECT TRIM(SCHEMA_NAME) As SCHEMA_NAME FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT'" 
        )){
      if (!rsCurLib.next()) {
        throw new CompilerException("Error retrieving current library");
      }
      curlib = rsCurLib.getString("SCHEMA_NAME");
      return curlib;


    } catch (SQLException e){
      throw new CompilerException("Error retrieving current library", e);
    }
  }

  public static void main(String... args ){
    System.exit(run(args));
  }

  /* Release tag stamped into the jar by the release workflow; "dev" for local builds */
  public static String version() {
    String version = MasterCompiler.class.getPackage().getImplementationVersion();
    return version != null ? version : "dev";
  }

  /* IBM i connection: the Code for IBM i one (--code4i), else .env / IBMI_* variables (or the local system on IBM i) */
  static AS400 connect(ArgParser parser) throws Exception {
    Code4iConfig code4i = code4i(parser);
    if (code4i != null) return code4i.connect();
    return IBMiDotEnv.getNewSystemConnection(true);
  }

  static Code4iConfig code4i(ArgParser parser) throws Exception {
    return parser.isCode4i() ? Code4iConfig.load(parser.getConnection()) : null;
  }

  /* CHGLIBL / CHGCURLIB for the build job: --libl / --curlib, else the Code for IBM i connection's */
  static List<CommandObject> libraryHooks(ArgParser parser, Code4iConfig code4i) {
    List<CommandObject> hooks = new ArrayList<CommandObject>();
    if (parser.getLibl() != null || parser.getCurlib() != null) {
      if (parser.getLibl() != null) {
        hooks.add(new CommandObject(SysCmd.CHGLIBL).put(ParamCmd.LIBL, parser.getLibl().trim().toUpperCase()));
      }
      if (parser.getCurlib() != null) {
        hooks.add(new CommandObject(SysCmd.CHGCURLIB).put(ParamCmd.CURLIB, parser.getCurlib().trim().toUpperCase()));
      }
      return hooks;
    }
    return code4i != null ? code4i.libraryHooks() : hooks;
  }

  /* --push, or with --code4i off the IBM i: <home>/mc/<project folder> */
  static String pushDir(ArgParser parser, Code4iConfig code4i, BuildSpec spec) {
    if (parser.getPush() != null) return parser.getPush();
    if (code4i == null || IBMiDotEnv.isIBMi() || spec.getBaseDirectory() == null) return null;
    return code4i.defaultPushDir(new java.io.File(spec.getBaseDirectory()));
  }

  /* Exit code: 0 success, 1 build failed, 2 invalid arguments */
  public static int run(String... args){
    AS400 system = null;
    MasterCompiler compiler = null;
    Connection connection = null;
    int exitCode = 0;
    try {
      /* Invalid arguments: one line saying what is wrong, then the usage (no stack trace) */
      if (ArgParser.wantsHelp(args)) {
        System.out.println(ArgParser.getUsage());
        return 0;
      }
      ArgParser parser;
      try {
        if (args.length == 0) throw new IllegalArgumentException("Params are required");
        parser = new ArgParser(args);
        parser.validate();
      } catch (IllegalArgumentException e) {
        /* plain text: no logger timestamps on the usage lines */
        System.err.println("Error: " + e.getMessage());
        System.err.println();
        System.err.println(ArgParser.getUsage());
        return 2;
      }

      if (parser.isVersion()) {
        System.out.println("MasterCompiler " + version());
        return exitCode;
      }

      /* VS Code + Copilot setup: .vscode/mcp.json and .github/skills in the project */
      if (parser.isSetupVscode()) {
        return VscodeSetup.run(parser);
      }

      /* Personal skills: Copilot in VS Code loads ~/.copilot/skills in every workspace */
      if (parser.isInstallSkills()) {
        java.io.File dir = new java.io.File(System.getProperty("user.home"), ".copilot/skills");
        int installed = VscodeSetup.installSkills(dir);
        logger.info("Installed {} MasterCompiler skills in {}. In any repository, ask Copilot (Agent mode): "
            + "\"set up MasterCompiler for this repository\".", installed, dir);
        return exitCode;
      }

      /* Import: source members -> MC repository (stream files, report, scanned build.yaml) */
      if (parser.getImportSelection() != null) {
        system = connect(parser);
        connection = new AS400JDBCDataSource(system).getConnection();
        String outDir = parser.getOutputFile();
        LibraryImporter.ImportReport imported = new LibraryImporter(system, connection, parser.isVerbose())
            .run(parser.getImportSelection(), outDir);

        BuildSpec spec = new SpecGenerator(system, connection, parser.isDebug(), parser.isVerbose())
            .generate(outDir, parser.getLibrary(), null);
        SpecWriter.writeToFile(spec, outDir + "/build.yaml", outDir);
        logger.info("Generated YAML: {}/build.yaml", outDir);
        return imported.errors > 0 ? 1 : exitCode;
      }

      /* MCP server: one IBM i job kept across tool calls; stdout becomes the protocol channel */
      if (parser.isMcp()) {
        if (parser.isSsh()) new SshMcpProxy(parser).serve();  // MC runs on the IBM i, reached over SSH
        else new McpServer(parser).serve();
        return exitCode;
      }

      /* Generate-only can run without IBMi when scanning a local tree or rewriting a spec */
      if (parser.isGenerateOnly()) {
        system = null;
        try {
          system = connect(parser);
        } catch (Exception ignore) {
          /* local-only generation */
          if (parser.isVerbose()) {
            logger.info("No IBMi connection; generating from local filesystem only");
          }
        }
        if (system != null) {
          try {
            connection = new AS400JDBCDataSource(system).getConnection();
          } catch (Exception ignore) {
            if (parser.isVerbose()) {
              logger.info("No JDBC connection; generating without object inspection");
            }
          }
        }

        BuildSpec generated;
        String scanRootComment = null;
        if (parser.hasTobi()) {
          generated = new TobiConverter(parser.isVerbose()).convert(parser.getTobiRoot(), parser.getLibrary());
          scanRootComment = parser.getTobiRoot();
        } else if (parser.hasScan()) {
          SpecGenerator generator = new SpecGenerator(
              system, connection, parser.isDebug(), parser.isVerbose());
          generated = generator.generate(
              parser.getScanRoot(), parser.getLibrary(), parser.getBaseFile());
          scanRootComment = parser.getScanRoot();
        } else {
          generated = parser.getSpecFromYamlFile();
          ObjectDescriptor descriptor = connection == null
              ? null
              : new ObjectDescriptor(connection, parser.isDebug(), parser.isVerbose());
          SpecResolver.resolveAll(generated, descriptor);
        }
        SpecWriter.writeToFile(generated, parser.getOutputFile(), scanRootComment);
        logger.info("Generated YAML: {}", parser.getOutputFile());
        return exitCode;
      }

      system = connect(parser);
      connection = new AS400JDBCDataSource(system).getConnection();

      BuildSpec spec;
      if (parser.hasTobi()) {
        spec = new TobiConverter(parser.isVerbose()).convert(parser.getTobiRoot(), parser.getLibrary());
        if (parser.getOutputFile() != null) {
          SpecWriter.writeToFile(spec, parser.getOutputFile(), parser.getTobiRoot());
          logger.info("Generated YAML: {}", parser.getOutputFile());
        }
      } else if (parser.hasScan()) {
        /* no object inspection: a build uses the sources and MC's defaults, not the existing objects */
        SpecGenerator generator = new SpecGenerator(
            system, null, parser.isDebug(), parser.isVerbose());
        spec = generator.generate(
            parser.getScanRoot(), parser.getLibrary(), parser.getBaseFile());
        if (parser.getOutputFile() != null) {
          SpecWriter.writeToFile(spec, parser.getOutputFile(), parser.getScanRoot());
          logger.info("Generated YAML: {}", parser.getOutputFile());
        }
      } else {
        spec = parser.getSpecFromYamlFile();
        if (parser.getOutputFile() != null) {
          SpecResolver.resolveAll(spec, null);  // the build must not take params from existing objects
          SpecWriter.writeToFile(spec, parser.getOutputFile(), null);
          logger.info("Generated YAML: {}", parser.getOutputFile());
        }
      }

      compiler = new MasterCompiler(
            system,
            connection,
            spec,
            parser.isDryRun(),
            parser.isDebug(),
            parser.isVerbose(),
            parser.isClean(),
            parser.isDiff(),
            parser.isNoMigrate()
        );
      compiler.setJsonReport(parser.getJsonReport());
      compiler.setSince(parser.getSince());
      Code4iConfig code4i = code4i(parser);
      compiler.setLibraryHooks(libraryHooks(parser, code4i));
      compiler.setPush(pushDir(parser, code4i, spec));
      compiler.setKeepGoing(parser.isKeepGoing());
      compiler.build();
      if (compiler.foundCompilationError()) exitCode = 1;

    } catch (IllegalArgumentException e) {
      /* configuration problems found while running (connection, settings, ...): the message says it all */
      logger.error(e.getMessage());
      exitCode = 1;
      
    } catch (CompilerException e){
      logger.error(e.getFullContext());
      exitCode = 1;

    } catch (Exception e) {
      logger.error("Unhandled  exception", e);
      exitCode = 1;
    } finally {
       try {
        if (connection != null && !connection.isClosed()) {
          connection.close();
        }
        if (system != null) {
          system.disconnectAllServices();
        }

      } catch (SQLException e) {
        logger.error("Error cleaning up", e);
      }
    }
    return exitCode;
  }
}
