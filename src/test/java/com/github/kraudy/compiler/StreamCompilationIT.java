package com.github.kraudy.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;
import com.ibm.as400.access.IFSFileWriter;
import com.ibm.as400.access.User;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@DisplayName("Full Compilation Integration Test using IFS Stream File")
public class StreamCompilationIT {
  static private AS400 system;
  static private Connection connection;
  static private User currentUser;
  static private String curlib;
  static private CommandExecutor commandExecutor;

  @BeforeAll
  static void setupSystem() throws Exception {
    system = IBMiDotEnv.getNewSystemConnection(true);
    connection = new AS400JDBCDataSource(system).getConnection();
    currentUser = new User(system, system.getUserId());
    currentUser.loadUserInformation();
    commandExecutor = new CommandExecutor(connection, false, false, false);

    try(Statement stmt = connection.createStatement();
        ResultSet rsCurLib = stmt.executeQuery(
          "SELECT TRIM(SCHEMA_NAME) As SCHEMA_NAME FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT'" 
        )){
      if (!rsCurLib.next()) {
        throw new CompilerException("Error retrieving current library");
      }
      curlib = rsCurLib.getString("SCHEMA_NAME");

    } catch (SQLException e){
      throw new CompilerException("Error retrieving current library", e);
    }

    /* Clean library list for tests */
    CommandObject chgLibl = new CommandObject(SysCmd.CHGLIBL)
      .put(ParamCmd.LIBL, curlib);

    commandExecutor.executeCommand(chgLibl);

  }

  @AfterAll
  static void teardown() throws Exception {
    if (connection != null) connection.close();
    if (system != null) system.disconnectAllServices();
  }

  @Test
  @Tag("heavy")  // Heavyweight
  void test_Tobi_Bob() throws Exception {    

    masterCompilerTest("tobi.yaml", "https://github.com/kraudy/McOnTobi.git");
    
  }

  @Test
  @Tag("fast")  // Ligther
  void test_Diff() throws Exception {    

    //masterCompilerTest("art200.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("rpgsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("clsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("dtasrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("msgsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    masterCompilerTest("sqlsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    
  }

  private void masterCompilerTest(String yamlResourcePath, String gitRepoUrl) throws Exception {
    boolean errorFound = false;

    String testFolder = currentUser.getHomeDirectory() + "/" + "test_" + System.currentTimeMillis();

    BuildSpec spec = null;

    try {
      // Clone the entire repo once
      System.out.println("Clonning repo: " + gitRepoUrl);
      CommandObject gitClone = new CommandObject(SysCmd.QSH)  // Use QShell for git
          .put(ParamCmd.CMD, "/QOpenSys/pkgs/bin/git clone " + gitRepoUrl + " " + testFolder);

      commandExecutor.executeCommand(gitClone);  // Throws if git fails

      /* Get remote spec */
      String remoteYamlPath = testFolder + "/" + yamlResourcePath;
      System.out.println("Obtaining remote spec: " + remoteYamlPath);
      IFSFile remoteYamlFile = new IFSFile(system, remoteYamlPath);

      spec = Utilities.deserializeYaml(remoteYamlFile);

      /* Change cur dir to test dir */
      CommandObject chgCurDir = new CommandObject(SysCmd.CHGCURDIR)
        .put(ParamCmd.DIR, testFolder);
      commandExecutor.executeCommand(chgCurDir);
       
      /* Run the compiler */
      MasterCompiler compiler = new MasterCompiler(
              system, connection, spec,
              // dryRun, debug, verbose, clean, diff, noMigrate
              false, true, true, true, false, false
      );

      compiler.build();

      errorFound = compiler.foundCompilationError();

    } catch (CompilerException e) {
      System.out.println(e.getFullContext());
    } finally {
      /* Set cur dir back to free test dir */
      CommandObject chgHome = new CommandObject(SysCmd.CHGCURDIR)
        .put(ParamCmd.DIR, currentUser.getHomeDirectory());
      commandExecutor.executeCommand(chgHome);

      try {
        CommandObject rmvDir = new CommandObject(SysCmd.RMVDIR)
          .put(ParamCmd.DIR, testFolder)
          .put(ParamCmd.SUBTREE, ValCmd.ALL);

        commandExecutor.executeCommand(rmvDir);
      } catch (CompilerException e) {
        System.out.println(e.getFullContext());
      }
    }

    assertFalse(errorFound, "Integration Test compilation failed");

  }

  @Test
  @Tag("diff")
  void test_Diff_Build() throws Exception {
    boolean errorFound = false;

    List<TargetKey> objectsToDelete = new ArrayList<>();

    String testFolder = currentUser.getHomeDirectory() + "/test_" + System.currentTimeMillis();

    BuildSpec spec = null;

    try {
      // Clone repo
      System.out.println("Cloning repo for diff test: https://github.com/kraudy/McOnTobi.git");
      CommandObject gitClone = new CommandObject(SysCmd.QSH)
          .put(ParamCmd.CMD, "/QOpenSys/pkgs/bin/git clone https://github.com/kraudy/McOnTobi.git " + testFolder);
      commandExecutor.executeCommand(gitClone);

      // Load spec
      String remoteYamlPath = testFolder + "/art200.yaml";
      IFSFile remoteYamlFile = new IFSFile(system, remoteYamlPath);
      spec = Utilities.deserializeYaml(remoteYamlFile);

      objectsToDelete.addAll(spec.targets.keySet());

      // CHGCURDIR to repo root
      CommandObject chgCurDir = new CommandObject(SysCmd.CHGCURDIR)
          .put(ParamCmd.DIR, testFolder);
      commandExecutor.executeCommand(chgCurDir);

      // FIRST BUILD: Full (diff=false) - creates all objects, syncs timestamps
      MasterCompiler fullCompiler = new MasterCompiler(
        system, connection, spec,
        // dryRun, debug, verbose, clean, diff, noMigrate
        false, true, true, false, false, false  // verbose=true for logs
      );
      fullCompiler.build();
      assertFalse(fullCompiler.foundCompilationError(), "Full build failed");

      int totalTargets = spec.targets.size();
      assertEquals(totalTargets, fullCompiler.getBuiltCount(), "Full build should build all targets");

      // Simulate changes: Touch a few sources (updates IFS DATA_CHANGE_TIMESTAMP)
      List<String> changedRelativePaths = List.of(
        "QRPGLESRC/ADDNUM.RPGLE",           // Independent program
        "QDDSSRC/ART200D.dspf.dds",         // DSPF
        "QRPGLESRC/ART200.pgm.sqlrpgle"     // SQLRPGI program source
      );

      for (String relPath : changedRelativePaths) {
        String fullPath = testFolder + "/" + relPath;
        CommandObject touch = new CommandObject(SysCmd.QSH)
            .put(ParamCmd.CMD, "/QOpenSys/pkgs/bin/touch " + fullPath);
        commandExecutor.executeCommand(touch);
      }

      // SECOND BUILD: Diff mode
      MasterCompiler diffCompiler = new MasterCompiler(
        system, connection, spec,
        // dryRun, debug, verbose, clean, diff, noMigrate
        false, true, true, true, true, false  // clean=true, diff=true
      );
      diffCompiler.build();
      errorFound = diffCompiler.foundCompilationError();
      assertFalse(errorFound, "Diff build failed");

      // ASSERTIONS: Only changed targets rebuilt, others skipped
      int expectedRebuilt = changedRelativePaths.size() + 1;  // Add one for bnddir build
      assertEquals(expectedRebuilt, diffCompiler.getBuiltCount(), "Diff build should rebuild only changed sources");
      assertEquals(totalTargets - expectedRebuilt, diffCompiler.getSkippedCount(), "Diff build should skip unchanged");

    } catch (CompilerException e) {
      System.out.println(e.getFullContext());
    } finally {
      /* Set cur dir back to free test dir */
      CommandObject chgHome = new CommandObject(SysCmd.CHGCURDIR)
        .put(ParamCmd.DIR, currentUser.getHomeDirectory());
      commandExecutor.executeCommand(chgHome);

      try {
        CommandObject rmvDir = new CommandObject(SysCmd.RMVDIR)
          .put(ParamCmd.DIR, testFolder)
          .put(ParamCmd.SUBTREE, ValCmd.ALL);

        commandExecutor.executeCommand(rmvDir);
      } catch (CompilerException e) {
        System.out.println(e.getFullContext());
      }
    }
  }
}
