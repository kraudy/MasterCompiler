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

    try {
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
      if (isIncremental() || keepGoing) depAwareness.detectDependencies(globalSpec);
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

        /* If the object exists, we try to extract its compilation params */
        odes.getObjectInfo(key);

        /* Set global defaults params per target */
        key.putAll(globalSpec.defaults);

        /* Set target specific params */
        key.putAll(targetSpec.params);


        /* Migrate source file */
        if (!noMigrate) migrator.migrateSource(key);

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

  /* Adds the target to the report with its joblog and, when compiled, its EVFEVENT errors */
  private void reportTarget(TargetKey key, String status, String command, String error, Timestamp since) {
    BuildReport.TargetResult result = report.add(key.asString(), status);
    result.command = command;
    result.error = error;
    if (dryRun || since == null) return;

    try {
      result.joblog = commandExec.getJoblogMessages(since);
      if (command != null) {
        result.errors = EventFile.read(connection, key.getLibrary(), key.getObjectName(), since, compileBaseDir);
      }
    } catch (Exception e) {
      logger.info("Could not collect report details for {}: {}", key.asString(), e.getMessage());
    }
  }

  /* Report entries of targets that were not built use the same library naming as the built ones */
  private void resolveLibrary(TargetKey key) {
    try {
      if (key.isCurLib()) key.setLibrary(getCurLIb());
    } catch (Exception ignored) {}
  }

  /* Targets never reached are listed as not built, then the report is written (--json) */
  private void finishReport() {
    if (compilationError) report.success = false;
    report.dryRun = dryRun;

    Set<String> reported = new HashSet<String>();
    for (BuildReport.TargetResult result : report.targets) reported.add(result.target);
    for (TargetKey key : globalSpec.targets.keySet()) {
      if (reported.contains(key.asString())) continue;
      resolveLibrary(key);
      report.add(key.asString(), BuildReport.NOT_BUILT);
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

  /* Exit code: 0 success, 1 build failed, 2 invalid arguments */
  public static int run(String... args){
    AS400 system = null;
    MasterCompiler compiler = null;
    Connection connection = null;
    int exitCode = 0;
    try {
      if (args.length == 0) throw new IllegalArgumentException("Params are required");

      ArgParser parser = new ArgParser(args);
      parser.validate();

      /* Import: source members -> MC repository (stream files, report, scanned build.yaml) */
      if (parser.getImportSelection() != null) {
        system = IBMiDotEnv.getNewSystemConnection(true);
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
        new McpServer(parser).serve();
        return exitCode;
      }

      /* Generate-only can run without IBMi when scanning a local tree or rewriting a spec */
      if (parser.isGenerateOnly()) {
        system = null;
        try {
          system = IBMiDotEnv.getNewSystemConnection(true);
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

      system = IBMiDotEnv.getNewSystemConnection(true); // Get system
      connection = new AS400JDBCDataSource(system).getConnection();

      BuildSpec spec;
      if (parser.hasTobi()) {
        spec = new TobiConverter(parser.isVerbose()).convert(parser.getTobiRoot(), parser.getLibrary());
        if (parser.getOutputFile() != null) {
          SpecWriter.writeToFile(spec, parser.getOutputFile(), parser.getTobiRoot());
          logger.info("Generated YAML: {}", parser.getOutputFile());
        }
      } else if (parser.hasScan()) {
        SpecGenerator generator = new SpecGenerator(
            system, connection, parser.isDebug(), parser.isVerbose());
        spec = generator.generate(
            parser.getScanRoot(), parser.getLibrary(), parser.getBaseFile());
        if (parser.getOutputFile() != null) {
          SpecWriter.writeToFile(spec, parser.getOutputFile(), parser.getScanRoot());
          logger.info("Generated YAML: {}", parser.getOutputFile());
        }
      } else {
        spec = parser.getSpecFromYamlFile();
        if (parser.getOutputFile() != null) {
          SpecResolver.resolveAll(
              spec, new ObjectDescriptor(connection, parser.isDebug(), parser.isVerbose()));
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
      compiler.setPush(parser.getPush());
      compiler.setKeepGoing(parser.isKeepGoing());
      compiler.build();
      if (compiler.foundCompilationError()) exitCode = 1;

    } catch (IllegalArgumentException e) {
      logger.error("Parsing error: ", e);
      logger.info(ArgParser.getUsage());
      exitCode = 2;
      
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
