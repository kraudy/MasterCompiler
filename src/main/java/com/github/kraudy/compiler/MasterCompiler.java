package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
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

  private BuildSpec globalSpec;     // global build spec
  private boolean dryRun = false;   // Compile commands without executing 
  private boolean debug = false;    // Debug flag
  private boolean verbose = false;  // Verbose output flag
  private boolean diff = false;     // Diff build flag
  private boolean noMigrate = false;  // Source migration

  private boolean compilationError = false;
  private int builtCount = 0;
  private int skippedCount = 0;

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

  public MasterCompiler(AS400 system, Connection connection, BuildSpec globalSpec, boolean dryRun, boolean debug, boolean verbose, boolean diff, boolean noMigrate) throws Exception {
    this(system, connection);

    /* Set params */
    this.globalSpec = globalSpec;
    this.dryRun = dryRun;
    this.debug = debug;
    this.verbose = verbose;
    this.diff = diff;
    this.noMigrate = noMigrate;
  }

  public void build() {

    /* Init command executor */
    commandExec = new CommandExecutor(connection, debug, verbose, dryRun);

    /* Init migrator */
    if (!noMigrate) migrator = new Migrator(connection, debug, verbose, currentUser, commandExec);

    /* Init source descriptor */
    sourceDes = new SourceDescriptor(connection, debug, verbose);

    /* Init object descriptor */
    odes = new ObjectDescriptor(connection, debug, verbose);

    try {
      /* Global before */
      if(!globalSpec.before.isEmpty()){
        if (verbose) logger.info("Executing global before: " + globalSpec.before.size() + " commands found");
        commandExec.executeCommand(globalSpec.before);
      }

      if(verbose) logger.info(showLibraryList());

      /* Build each target */
      buildTargets(globalSpec.targets);

      /* Execute global after */
      if(!globalSpec.after.isEmpty()){
        if (verbose) logger.info("Executing global after: " + globalSpec.after.size() + " commands found");
        commandExec.executeCommand(globalSpec.after);
      }

      /* Execute global success */
      if(!globalSpec.success.isEmpty()){
        if (verbose) logger.info("Executing global success: " + globalSpec.success.size() + " commands found");
        commandExec.executeCommand(globalSpec.success);
      }
      
    } catch (CompilerException e){
      compilationError = true;
      if (verbose) logger.error("Compilation failed");

      /* Get full compiler exception context */
      logger.error(e.getFullContext());

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

    } finally {
      /* Show chain of commands */
      if (verbose) logger.info("\nChain of commands: {}", commandExec.getExecutionChain());

    }

  }

  private void buildTargets(LinkedHashMap<TargetKey, BuildSpec.TargetSpec> targets) throws Exception{
    /* This is intended for a YAML file with multiple objects in a toposort order */
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : targets.entrySet()) {
      TargetKey key = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();

      /* Skip target if diff and no build required */
      if (diff) {
        sourceDes.getObjectTimestamps(key);
        if (!key.needsRebuild()) {
          this.skippedCount++;
          if (verbose) logger.info("Skipping unchanged target: " + key.asString() + key.getTimestmaps());
          continue; 
        }
      }

      this.builtCount++;
      if (verbose) logger.info("Building: " + key.asString());

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

        /* Set specific target params */
        key.putAll(targetSpec.params);

        /* Migrate source file */
        if (!noMigrate) migrator.migrateSource(key);

        /* Execute compilation command */
        commandExec.executeCommand(key);

        /* Per target after */
        if(!targetSpec.after.isEmpty()){
          if (verbose) logger.info("Executing target after: " + targetSpec.after.size() + " commands found");
          commandExec.executeCommand(targetSpec.after);
        } 

        /* Per target success */
        if(!targetSpec.success.isEmpty()){
          if (verbose) logger.info("Executing target success: " + targetSpec.success.size() + " commands found");
          commandExec.executeCommand(targetSpec.success);
        } 

      } catch (CompilerException e){
        compilationError = true;
        if (verbose) logger.error("Target compilation failed: " + key.asString());

        /* Per target failure */
        if(!targetSpec.failure.isEmpty()){
          if (verbose) logger.error("Executing target failure: " + targetSpec.failure.size() + " commands found");
          commandExec.executeCommand(targetSpec.failure);
        } 

        throw e; // Raise

      } catch (Exception e){
        compilationError = true;
        if (verbose) logger.error("Unhandled exception in Target: " + key.asString());

        throw e; // Raise

      } finally {
        //TODO: Do something cool here.
      }
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
    AS400 system = null;
    MasterCompiler compiler = null;
    Connection connection = null;
    try {
      ArgParser parser = new ArgParser(args);
      //TODO: This should be able to run locally in debug mode.
      if (args.length == 0) throw new IllegalArgumentException("Params are required");
        
      system = IBMiDotEnv.getNewSystemConnection(true); // Get system
      connection = new AS400JDBCDataSource(system).getConnection();

      compiler = new MasterCompiler(
            system,
            connection,
            parser.getSpecFromYamlFile(),
            parser.isDryRun(),
            parser.isDebug(),
            parser.isVerbose(),
            parser.isDiff(),
            parser.noMigrate()
        );
      compiler.build();

    } catch (IllegalArgumentException e) {
      logger.error("Parsing error: ", e);
      logger.info(ArgParser.getUsage());
      
    } catch (CompilerException e){
      logger.error(e.getFullContext());

    } catch (Exception e) {
      logger.error("Unhandled  exception", e);
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
  }
}
