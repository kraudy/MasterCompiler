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

    masterCompilerTest("tobi.yaml", "https://github.com/kraudy/McOnTobi.git");
    //masterCompilerTest("sjlennon.yaml", "https://github.com/kraudy/McOnSJLennon");
    
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
    test_McOnTobi_Deps();
    //test_Sjlennon_Deps();
  }

  void test_Sjlennon_Deps() throws Exception {
    String testFolder = currentUser.getHomeDirectory() + "/test_" + System.currentTimeMillis();

    BuildSpec spec = null;

    try {
      // Clone repo    
      System.out.println("Cloning repo for deps test: https://github.com/kraudy/McOnSJLennon.git");
      CommandObject gitClone = new CommandObject(SysCmd.QSH)
          .put(ParamCmd.CMD, "/QOpenSys/pkgs/bin/git clone https://github.com/kraudy/McOnSJLennon.git " + testFolder);
      commandExecutor.executeCommand(gitClone);

      // Load spec
      String remoteYamlPath = testFolder + "/sjlennon.yaml";
      IFSFile remoteYamlFile = new IFSFile(system, remoteYamlPath);
      spec = Utilities.deserializeYaml(remoteYamlFile);

      // CHGCURDIR to repo root
      CommandObject chgCurDir = new CommandObject(SysCmd.CHGCURDIR)
          .put(ParamCmd.DIR, testFolder);
      commandExecutor.executeCommand(chgCurDir);

      DependencyAwareness depAwareness = new DependencyAwareness(system, true, true);

      depAwareness.detectDependencies(spec);

      TargetKey depsGETOBJUR = spec.getTargetKey(new TargetKey("curlib.GETOBJUR.pgm.RPGLE"));
      assertNotNull(depsGETOBJUR, "Deps target should not be null");
      assertEquals(4, depsGETOBJUR.getChildsCount(), "Childs of target " + depsGETOBJUR.asString() + " should be 4. 1 FILE GETOBJUP, 3 EXTPGM GETJOBTR, SRTUSRSPC, GETOBJUR");

      TargetKey depsGETOBJUC = spec.getTargetKey(new TargetKey("curlib.GETOBJUC.pgm.CLLE"));
      assertNotNull(depsGETOBJUC, "Deps target should not be null");
      assertEquals(1, depsGETOBJUC.getChildsCount(), "Childs of target " + depsGETOBJUC.asString() + " should be 1 CALL. GETOBJUR");

      TargetKey depsT9ALLOCMNY = spec.getTargetKey(new TargetKey("CURLIB.T9ALLOCMNY.PGM.CLP"));
      assertNotNull(depsT9ALLOCMNY, "Deps target should not be null");
      assertEquals(1, depsT9ALLOCMNY.getChildsCount(), "Childs of target " + depsT9ALLOCMNY.asString() + " should be 1 CALL. T9ALLOC1");

      TargetKey depsSRV_SQL = spec.getTargetKey(new TargetKey("CURLIB.SRV_SQL.MODULE.SQLRPGLE"));
      assertNotNull(depsSRV_SQL, "Deps target should not be null");
      /* BNDDIR UTIL_BND + /include SRV_MSG_P → SRV_MSG (+ UTIL_BND membership SRV_STR). Not own SRV_SQL. */
      assertTrue(depsSRV_SQL.getChildsCount() >= 2,
          "Childs of " + depsSRV_SQL.asString()
              + " should include UTIL_BND and foreign SRV_MSG (not own SRV_SQL srvpgm)");
      TargetKey depsSRV_MSG = spec.getTargetKey(new TargetKey("CURLIB.SRV_MSG.SRVPGM.BND"));
      TargetKey depsSRV_SQL_SRV = spec.getTargetKey(new TargetKey("CURLIB.SRV_SQL.SRVPGM.BND"));
      assertTrue(hasChildNamed(depsSRV_SQL, "UTIL_BND"),
          "SRV_SQL module should depend on BNDDIR UTIL_BND");
      if (depsSRV_MSG != null) {
        assertTrue(hasChild(depsSRV_SQL, depsSRV_MSG),
            "SRV_SQL module should depend on foreign SRV_MSG via /include proto / UTIL_BND membership");
      }
      if (depsSRV_SQL_SRV != null) {
        assertFalse(hasChild(depsSRV_SQL, depsSRV_SQL_SRV),
            "SRV_SQL module must not depend on its own parent SRV_SQL *SRVPGM");
      }


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

  void test_McOnTobi_Deps() throws Exception {
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

      // This also finds BndDir y ExtPgm; SAMPLE membership / include-proto add *SRVPGMs
      TargetKey depsART200 = spec.getTargetKey(new TargetKey("CURLIB.ART200.PGM.SQLRPGLE"));
      assertNotNull(depsART200, "Deps target should not be null");
      assertTrue(depsART200.getChildsCount() >= 6,
          "Childs of " + depsART200.asString()
              + " should include files, SAMPLE, EXTPGM, and FFAMILLY (deduped ADDBNDDIRE / include-proto / membership)");

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
      /* files + SAMPLE + SRVPGMs from /copy protos and ADDBNDDIRE / BNDDIR membership */
      assertTrue(depsART201.getChildsCount() >= 5,
          "Childs of " + depsART201.asString() + " should include files, SAMPLE, FARTICLE, FPROVIDER");

      TargetKey depsART202 = spec.getTargetKey(new TargetKey("CURLIB.ART202.PGM.RPGLE"));
      assertNotNull(depsART202, "Deps target should not be null");
      assertTrue(depsART202.getChildsCount() >= 5,
          "Childs of " + depsART202.asString() + " should include files, SAMPLE, and ARTICLE/PROVIDER srvpgms");

      TargetKey depsORD201 = spec.getTargetKey(new TargetKey("CURLIB.ORD201.PGM.SQLRPGLE"));
      assertNotNull(depsORD201, "Deps target should not be null");
      assertTrue(depsORD201.getChildsCount() >= 11,
          "Childs of " + depsORD201.asString() + " should be at least 11 (prior set + optional SRVPGM edges)");

      /* dtaara */
      TargetKey depsORD100 = spec.getTargetKey(new TargetKey("curlib.ORD100.PGM.RPGLE"));
      assertNotNull(depsORD100, "Deps target should not be null");
      // files, SAMPLE, extpgm, dtaara + srvpgms from ADDBNDDIRE / /copy protos / membership
      assertTrue(depsORD100.getChildsCount() >= 6,
          "Childs of " + depsORD100.asString() + " should be at least 6");

      TargetKey depsORD900 = spec.getTargetKey(new TargetKey("curlib.ORD900.PGM.RPGLE"));
      assertNotNull(depsORD900, "Deps target should not be null");
      assertEquals(2, depsORD900.getChildsCount(), "Childs of target " + depsORD900.asString() + " should be 2. 1 file, 1 dtaara");

      /* Extname + BNDDIR; may also pull SRVPGMs via SAMPLE membership / LOG if present */
      TargetKey depsORD700 = spec.getTargetKey(new TargetKey("curlib.ORD700.PGM.RPGLE"));
      assertNotNull(depsORD700, "Deps target should not be null");
      assertTrue(depsORD700.getChildsCount() >= 3,
          "Childs of " + depsORD700.asString() + " should be at least 3 (BNDDIR, file, EXTNAME)");

      /* SQL dependencies */
      TargetKey depsARTLSTDAT = spec.getTargetKey(new TargetKey("curlib.ARTLSTDAT.VIEW.SQL"));
      assertNotNull(depsARTLSTDAT, "Deps target should not be null");
      assertEquals(3, depsARTLSTDAT.getChildsCount(), "Childs of target " + depsARTLSTDAT.asString() + " should be 3. 3 tables");
      
      TargetKey depsORDERCUS = spec.getTargetKey(new TargetKey("curlib.ORDERCUS.view.sql"));
      assertNotNull(depsORDERCUS, "Deps target should not be null");
      assertEquals(3, depsORDERCUS.getChildsCount(), "Childs of target " + depsORDERCUS.asString() + " should be 3 tables. DETORD, ORDER, CUSTOMER");

      TargetKey depsART801 = spec.getTargetKey(new TargetKey("curlib.ART801.procedure.sql"));
      assertNotNull(depsART801, "Deps target should not be null");
      assertEquals(4, depsART801.getChildsCount(), "Childs of target " + depsART801.asString() + " should be 4 tables. ARTICLE, DETORD, ORDER, CUSTOMER");

      /* CLP, CLLE dependencies */
      TargetKey depsOPM = spec.getTargetKey(new TargetKey("curlib.OPM.pgm.CLP"));
      assertNotNull(depsOPM, "Deps target should not be null");
      assertEquals(1, depsOPM.getChildsCount(), "Childs of target " + depsOPM.asString() + " should be 1 CALL. ORD100C");

      TargetKey depsORD100C2 = spec.getTargetKey(new TargetKey("curlib.ORD100C2.PGM.CLLE"));
      assertNotNull(depsORD100C2, "Deps target should not be null");
      assertEquals(1, depsORD100C2.getChildsCount(), "Childs of target " + depsORD100C2.asString() + " should be 1 CALL. ORD100");

      TargetKey depsORD100C = spec.getTargetKey(new TargetKey("curlib.ORD100C.PGM.CLLE"));
      assertNotNull(depsORD100C, "Deps target should not be null");
      assertEquals(1, depsORD100C.getChildsCount(), "Childs of target " + depsORD100C.asString() + " should be 1 CMD CRTORD");

      /* User-defined command: ORD500C invokes CVTSPLPDF */
      TargetKey depsORD500C = spec.getTargetKey(new TargetKey("curlib.ORD500C.PGM.CLLE"));
      assertNotNull(depsORD500C, "Deps target should not be null");
      assertEquals(1, depsORD500C.getChildsCount(), "Childs of target " + depsORD500C.asString() + " should be 1 CMD CVTSPLPDF");

      /* CMD → processing program: CRTORD PGM(ORD100) when ORD100 is a build target */
      TargetKey depsCRTORD = spec.getTargetKey(new TargetKey("curlib.CRTORD.CMD.cmd"));
      assertNotNull(depsCRTORD, "CRTORD CMD target should not be null");
      assertEquals(1, depsCRTORD.getChildsCount(),
          "Childs of target " + depsCRTORD.asString() + " should be 1 PGM ORD100");
      boolean crtordDependsOnOrd100 = depsCRTORD.getChildsList().stream()
          .anyMatch(c -> "ORD100".equalsIgnoreCase(c.getObjectName()) && c.isProgram());
      assertTrue(crtordDependsOnOrd100,
          depsCRTORD.asString() + " should depend on processing program ORD100");

      /* CVTSPLPDF has PGM(CVTSPLPDF) but that program is not a build target — param only, no edge */
      TargetKey depsCVTSPLPDF = spec.getTargetKey(new TargetKey("curlib.CVTSPLPDF.CMD.cmd"));
      assertNotNull(depsCVTSPLPDF, "CVTSPLPDF CMD target should not be null");
      assertEquals(0, depsCVTSPLPDF.getChildsCount(),
          "Childs of " + depsCVTSPLPDF.asString()
              + " should be 0 (PGM not in build graph)");

      /*
       * PGM → SRVPGM: /copy|/include prototypes matched to module/srvpgm exports,
       * plus ADDBNDDIRE consumer hooks (and BNDDIR membership fan-out).
       * ART201: /copy ARTICLE + PROVIDER; AddBndDirE FARTICLE, FPROVIDER.
       */
      TargetKey depsFARTICLE = spec.getTargetKey(new TargetKey("curlib.FARTICLE.srvpgm.bnd"));
      TargetKey depsFPROVIDER = spec.getTargetKey(new TargetKey("curlib.FPROVIDER.srvpgm.bnd"));
      TargetKey depsFFAMILLY = spec.getTargetKey(new TargetKey("curlib.FFAMILLY.srvpgm.bnd"));
      TargetKey depsFCUSTOMER = spec.getTargetKey(new TargetKey("curlib.FCUSTOMER.srvpgm.bnd"));
      TargetKey depsFVAT = spec.getTargetKey(new TargetKey("curlib.fvat.srvpgm.bnd"));
      assertNotNull(depsFARTICLE, "FARTICLE srvpgm should be in tobi.yaml");
      assertNotNull(depsFPROVIDER, "FPROVIDER srvpgm should be in tobi.yaml");
      assertNotNull(depsFFAMILLY, "FFAMILLY srvpgm should be in tobi.yaml");
      assertNotNull(depsFCUSTOMER, "FCUSTOMER srvpgm should be in tobi.yaml");

      assertTrue(hasChild(depsART201, depsFARTICLE),
          "ART201 should depend on FARTICLE (include protos / ADDBNDDIRE)");
      assertTrue(hasChild(depsART201, depsFPROVIDER),
          "ART201 should depend on FPROVIDER (include protos / ADDBNDDIRE)");
      assertTrue(hasChildNamed(depsART201, "SAMPLE"),
          "ART201 should depend on BNDDIR SAMPLE");

      assertTrue(hasChild(depsART202, depsFARTICLE),
          "ART202 should depend on FARTICLE via /copy ARTICLE protos");
      assertTrue(hasChild(depsART202, depsFPROVIDER),
          "ART202 should depend on FPROVIDER via /copy PROVIDER protos");

      /* ORD100: AddBndDirE fvat + FCUSTOMER; /copy CUSTOMER, ARTICLE, VAT */
      assertTrue(hasChild(depsORD100, depsFVAT),
          "ORD100 should depend on FVAT (ADDBNDDIRE / VAT include)");
      assertTrue(hasChild(depsORD100, depsFCUSTOMER),
          "ORD100 should depend on FCUSTOMER (ADDBNDDIRE / CUSTOMER include)");
      assertTrue(hasChild(depsORD100, depsFARTICLE),
          "ORD100 should depend on FARTICLE via /copy ARTICLE protos");

      /* ART200: /copy FAMILLY → FFAMILLY; AddBndDirE FFAMILLY (deduped if also via SAMPLE) */
      assertTrue(hasChild(depsART200, depsFFAMILLY),
          "ART200 should depend on FFAMILLY (include protos / ADDBNDDIRE)");
      assertTrue(hasChild(depsART200, depsFARTICLE),
          "ART200 program should depend on FARTICLE (SAMPLE membership / later bind)");

      /* ORD700: /copy LOG_functions → ADDLOGENTRY when LOG *SRVPGM is in the graph */
      TargetKey depsLOG = spec.getTargetKey(new TargetKey("curlib.LOG.srvpgm.bnd"));
      if (depsLOG != null) {
        assertTrue(hasChild(depsORD700, depsLOG),
            "ORD700 should depend on LOG when LOG srvpgm is a build target");
      }

      /*
       * SQL *MODULE /copy of own proto must not reverse the SRVPGM → MODULE edge.
       * ART301 /copy ARTICLE (own FARTICLE) + FAMILLY (foreign FFAMILLY).
       * CUS301 /copy CUSTOMER (own FCUSTOMER) only.
       */
      TargetKey depsART301 = spec.getTargetKey(new TargetKey("CURLIB.ART301.MODULE.SQLRPGLE"));
      TargetKey depsCUS301 = spec.getTargetKey(new TargetKey("CURLIB.CUS301.MODULE.SQLRPGLE"));
      assertNotNull(depsART301, "ART301 module should be in tobi.yaml");
      assertNotNull(depsCUS301, "CUS301 module should be in tobi.yaml");
      assertFalse(hasChild(depsART301, depsFARTICLE),
          "ART301 must not depend on parent FARTICLE (module↔srvpgm cycle)");
      assertTrue(hasChild(depsFARTICLE, depsART301),
          "FARTICLE must still depend on ART301 via MODULE");
      assertTrue(hasChild(depsART301, depsFFAMILLY),
          "ART301 should depend on foreign FFAMILLY via /copy FAMILLY");
      assertFalse(hasChild(depsCUS301, depsFCUSTOMER),
          "CUS301 must not depend on parent FCUSTOMER (module↔srvpgm cycle)");
      assertTrue(hasChild(depsFCUSTOMER, depsCUS301),
          "FCUSTOMER must still depend on CUS301 via MODULE");

      TargetKey depsART300 = spec.getTargetKey(new TargetKey("CURLIB.ART300.MODULE.RPGLE"));
      assertTrue(hasChild(depsFARTICLE, depsFFAMILLY),
          "FARTICLE must depend on FFAMILLY (BNDSRVPGM / ART301 import of GETARTFAMDESC)");
      if (depsART300 != null) {
        assertTrue(hasChild(depsFARTICLE, depsART300),
            "FARTICLE must still depend on ART300 via MODULE");
      }

      /* Graph must stay acyclic after include-proto + ADDBNDDIRE + SAMPLE fan-out */
      new BuildTopoSort(false, false).topologicalSort(spec);

      /* Validate exported procs */
      assertTrue(spec.containsExport("GETVATDESC", depsVAT300), "depsVAT300 Should export proc GETVATDESC");
      assertTrue(spec.containsExport("GETVATRATE", depsVAT300), "depsVAT300 Should export proc GETVATRATE");
      assertTrue(spec.containsExport("EXISTVATRATE", depsVAT300), "depsVAT300 Should export proc EXISTVATRATE");
      assertTrue(spec.containsExport("CLCVAT", depsVAT300), "depsVAT300 Should export proc CLCVAT");

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

  /** True if parent lists child as a direct dependency. */
  private static boolean hasChild(TargetKey parent, TargetKey child) {
    if (parent == null || child == null) return false;
    return parent.getChildsList().stream().anyMatch(c -> c != null && c.equals(child));
  }

  /** True if parent has a child with the given object name (any object type). */
  private static boolean hasChildNamed(TargetKey parent, String objectName) {
    if (parent == null || objectName == null) return false;
    return parent.getChildsList().stream()
        .anyMatch(c -> c != null && objectName.equalsIgnoreCase(c.getObjectName()));
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
