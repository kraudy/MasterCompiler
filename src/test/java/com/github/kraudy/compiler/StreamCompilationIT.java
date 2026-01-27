package com.github.kraudy.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.kraudy.compiler.BuildSpec.TargetSpec;
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
  @Tag("heavy")  // Heavyweight test before release 
  void test_Compile_Hevy() throws Exception {    

    //masterCompilerTest("tobi.yaml", "https://github.com/kraudy/McOnTobi.git");
    masterCompilerTest("sjlennon.yaml", "https://github.com/kraudy/McOnSJLennon");
    
  }

  @Test
  @Tag("fast")  // Ligther
  void test_Compile_Fast() throws Exception {    

    //masterCompilerTest("art200.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("rpgsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("clsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("dtasrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("msgsrc.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("sqlsrc.yaml", "https://github.com/kraudy/McOnTobi.git");

    //masterCompilerTest("sjlennon.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("apisql.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("5250_Subfile.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("base36.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("dateadj.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("date_udf.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("grp_job.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("pgm_refs.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("prt_cl.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("printing.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("RcdLckDsp.yaml", "https://github.com/kraudy/McOnSJLennon");
    //masterCompilerTest("sngchcfld.yaml", "https://github.com/kraudy/McOnSJLennon");
    masterCompilerTest("usps_address.yaml", "https://github.com/kraudy/McOnSJLennon");
    
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
  @Tag("deps")
  void test_Deps_Build() throws Exception {

    String testFolder = currentUser.getHomeDirectory() + "/test_" + System.currentTimeMillis();

    BuildSpec spec = null;

    try {
      // Clone repo
      System.out.println("Cloning repo for deps test: https://github.com/kraudy/McOnTobi.git");
      CommandObject gitClone = new CommandObject(SysCmd.QSH)
          .put(ParamCmd.CMD, "/QOpenSys/pkgs/bin/git clone https://github.com/kraudy/McOnTobi.git " + testFolder);
      commandExecutor.executeCommand(gitClone);

      // Load spec
      String remoteYamlPath = testFolder + "/tobi.yaml";
      IFSFile remoteYamlFile = new IFSFile(system, remoteYamlPath);
      spec = Utilities.deserializeYaml(remoteYamlFile);

      // CHGCURDIR to repo root
      CommandObject chgCurDir = new CommandObject(SysCmd.CHGCURDIR)
          .put(ParamCmd.DIR, testFolder);
      commandExecutor.executeCommand(chgCurDir);

      DependencyAwareness depAwareness = new DependencyAwareness(system, true, true);

      depAwareness.detectDependencies(spec);

      /* Validate F spec free format */
      TargetKey depsVAT300 = spec.getTargetKey(new TargetKey("CURLIB.VAT300.MODULE.RPGLE"));
      assertNotNull(depsVAT300, "Deps target should not be null");
      assertEquals(1, depsVAT300.getChildsCount(), "Childs of target " + depsVAT300.asString() + " should be 1");

      /* Validate F spec fixed format */
      TargetKey depsFAM301 = spec.getTargetKey(new TargetKey("CURLIB.FAM301.MODULE.RPGLE"));
      assertNotNull(depsFAM301, "Deps target should not be null");
      assertEquals(3, depsFAM301.getChildsCount(), "Childs of target " + depsFAM301.asString() + " should be 3");

      TargetKey depsFAM300 = spec.getTargetKey(new TargetKey("CURLIB.FAM300.MODULE.RPGLE"));
      assertNotNull(depsFAM300, "Deps target should not be null");
      assertEquals(1, depsFAM300.getChildsCount(), "Childs of target " + depsFAM300.asString() + " should be 1");

      // fixed format rpg opm
      TargetKey depsCOU200 = spec.getTargetKey(new TargetKey("CURLIB.COU200.PGM.RPG"));
      assertNotNull(depsCOU200, "Deps target should not be null");
      assertEquals(2, depsCOU200.getChildsCount(), "Childs of target " + depsCOU200.asString() + " should be 2");

      // This also finds BndDir y ExtPgm
      TargetKey depsART200 = spec.getTargetKey(new TargetKey("CURLIB.ART200.PGM.SQLRPGLE"));
      assertNotNull(depsART200, "Deps target should not be null");
      assertEquals(6, depsART200.getChildsCount(), "Childs of target " + depsART200.asString() + " should be 6: 2 Files, 1 Dspf, 1 Table, 1 Bnddir, 1 ExtPgm");

      /* Validate PF REF file */
      TargetKey depsVATDEF = spec.getTargetKey(new TargetKey("CURLIB.VATDEF.PF.DDS"));
      assertNotNull(depsVATDEF, "Deps target should not be null");
      assertEquals(1, depsVATDEF.getChildsCount(), "Childs of target " + depsVATDEF.asString() + " should be 1");

      TargetKey depsARTICLE = spec.getTargetKey(new TargetKey("CURLIB.ARTICLE.PF.DDS"));
      assertNotNull(depsARTICLE, "Deps target should not be null");
      assertEquals(1, depsARTICLE.getChildsCount(), "Childs of target " + depsARTICLE.asString() + " should be 1");

      TargetKey depsFAMILLY = spec.getTargetKey(new TargetKey("CURLIB.FAMILLY.PF.DDS"));
      assertNotNull(depsFAMILLY, "Deps target should not be null");
      assertEquals(1, depsFAMILLY.getChildsCount(), "Childs of target " + depsFAMILLY.asString() + " should be 1");

      /* Validate LF PFILE dependency */
      TargetKey depsARTICLE1 = spec.getTargetKey(new TargetKey("CURLIB.ARTICLE1.LF.DDS"));
      assertNotNull(depsARTICLE1, "Deps target should not be null");
      assertEquals(1, depsARTICLE1.getChildsCount(), "Childs of target " + depsARTICLE1.asString() + " should be 1");

      TargetKey depsARTICLE2 = spec.getTargetKey(new TargetKey("CURLIB.ARTICLE2.LF.DDS"));
      assertNotNull(depsARTICLE2, "Deps target should not be null");
      assertEquals(1, depsARTICLE2.getChildsCount(), "Childs of target " + depsARTICLE2.asString() + " should be 1");

      TargetKey depsFAMILL1 = spec.getTargetKey(new TargetKey("CURLIB.FAMILL1.LF.DDS"));
      assertNotNull(depsFAMILL1, "Deps target should not be null");
      assertEquals(1, depsFAMILL1.getChildsCount(), "Childs of target " + depsFAMILL1.asString() + " should be 1");

      /* Validate DSPF, PRTF  REFFLD dependency */
      TargetKey depsART200D = spec.getTargetKey(new TargetKey("CURLIB.ART200D.DSPF.DDS"));
      assertNotNull(depsART200D, "Deps target should not be null");
      assertEquals(2, depsART200D.getChildsCount(), "REFFLD Childs of target " + depsART200D.asString() + " should be 2");

      TargetKey depsFAM301D = spec.getTargetKey(new TargetKey("CURLIB.FAM301D.DSPF.DDS"));
      assertNotNull(depsFAM301D, "Deps target should not be null");
      assertEquals(1, depsFAM301D.getChildsCount(), "REFFLD Childs of target " + depsFAM301D.asString() + " should be 1");

      TargetKey depsORD100D = spec.getTargetKey(new TargetKey("CURLIB.ORD100D.DSPF.DDS"));
      assertNotNull(depsORD100D, "Deps target should not be null");
      assertEquals(3, depsORD100D.getChildsCount(), "REFFLD Childs of target " + depsORD100D.asString() + " should be 3");

      /* Various validations */
      TargetKey depsART201 = spec.getTargetKey(new TargetKey("CURLIB.ART201.PGM.RPGLE"));
      assertNotNull(depsART201, "Deps target should not be null");
      assertEquals(3, depsART201.getChildsCount(), "REFFLD Childs of target " + depsART201.asString() + " should be 3");

      TargetKey depsART202 = spec.getTargetKey(new TargetKey("CURLIB.ART202.PGM.RPGLE"));
      assertNotNull(depsART202, "Deps target should not be null");
      assertEquals(3, depsART202.getChildsCount(), "Childs of target " + depsART202.asString() + " should be 3");

      TargetKey depsORD201 = spec.getTargetKey(new TargetKey("CURLIB.ORD201.PGM.SQLRPGLE"));
      assertNotNull(depsORD201, "Deps target should not be null");
      assertEquals(11, depsORD201.getChildsCount(), "Childs of target " + depsORD201.asString() + " should be 11");

      /* dtaara */
      TargetKey depsORD100 = spec.getTargetKey(new TargetKey("curlib.ORD100.PGM.RPGLE"));
      assertNotNull(depsORD100, "Deps target should not be null");
      // Tmpdetord uses a ovrdbf but references the same detord files so the childs number does not changes
      assertEquals(6, depsORD100.getChildsCount(), "Childs of target " + depsORD100.asString() + " should be 6. 4 files, 1 bnddir, 1 extpgm, 1 dtaara");

      TargetKey depsORD900 = spec.getTargetKey(new TargetKey("curlib.ORD900.PGM.RPGLE"));
      assertNotNull(depsORD900, "Deps target should not be null");
      assertEquals(2, depsORD900.getChildsCount(), "Childs of target " + depsORD900.asString() + " should be 2. 1 file, 1 dtaara");

      /* Extname */
      TargetKey depsORD700 = spec.getTargetKey(new TargetKey("curlib.ORD700.PGM.RPGLE"));
      assertNotNull(depsORD700, "Deps target should not be null");
      assertEquals(3, depsORD700.getChildsCount(), "Childs of target " + depsORD700.asString() + " should be 3. 1 Bnddir, 1 file, 1 extname");

      /* SQL dependencies */
      TargetKey depsARTLSTDAT = spec.getTargetKey(new TargetKey("curlib.ARTLSTDAT.VIEW.SQL"));
      assertNotNull(depsARTLSTDAT, "Deps target should not be null");
      assertEquals(3, depsARTLSTDAT.getChildsCount(), "Childs of target " + depsARTLSTDAT.asString() + " should be 3. 3 tables");

      TargetKey depsORDERCUS = spec.getTargetKey(new TargetKey("curlib.ORDERCUS.view.sql"));
      assertNotNull(depsORDERCUS, "Deps target should not be null");
      assertEquals(3, depsORDERCUS.getChildsCount(), "Childs of target " + depsORDERCUS.asString() + " should be 3 tables. DETORD, ORDER, CUSTOMER");

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
