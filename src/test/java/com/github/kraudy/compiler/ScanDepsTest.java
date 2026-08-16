package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Existing McOnTobi / McOnSJLennon deps checks, run locally via {@code --scan}
 * ({@link SpecGenerator#generate}). Clones each repo to {@code java.io.tmpdir}
 * every test and deletes it afterwards. No IBM i connection.
 */
@Tag("deps")
public class ScanDepsTest {

  private static final String TOBI = "https://github.com/kraudy/McOnTobi.git";
  private static final String SJLENNON = "https://github.com/kraudy/McOnSJLennon.git";

  @Test
  void test_McOnTobi_Deps() throws Exception {
    Path root = cloneRepo(TOBI);
    try {
      BuildSpec spec = new SpecGenerator(null, false, true).generate(root.toString(), "curlib");

      TargetKey depsVAT300 = spec.getTargetKey(new TargetKey("CURLIB.VAT300.MODULE.RPGLE"));
      assertNotNull(depsVAT300, "Deps target should not be null");
      assertEquals(1, depsVAT300.getChildsCount(),
          "Childs of target " + depsVAT300.asString() + " should be 1");

      TargetKey depsFAM301 = spec.getTargetKey(new TargetKey("CURLIB.FAM301.MODULE.RPGLE"));
      assertNotNull(depsFAM301, "Deps target should not be null");
      assertEquals(3, depsFAM301.getChildsCount(),
          "Childs of target " + depsFAM301.asString() + " should be 3");

      TargetKey depsFAM300 = spec.getTargetKey(new TargetKey("CURLIB.FAM300.MODULE.RPGLE"));
      assertNotNull(depsFAM300, "Deps target should not be null");
      assertEquals(1, depsFAM300.getChildsCount(),
          "Childs of target " + depsFAM300.asString() + " should be 1");

      TargetKey depsCOU200 = spec.getTargetKey(new TargetKey("CURLIB.COU200.PGM.RPG"));
      assertNotNull(depsCOU200, "Deps target should not be null");
      assertEquals(2, depsCOU200.getChildsCount(),
          "Childs of target " + depsCOU200.asString() + " should be 2");

      TargetKey depsART200 = spec.getTargetKey(new TargetKey("CURLIB.ART200.PGM.SQLRPGLE"));
      assertNotNull(depsART200, "Deps target should not be null");
      assertTrue(depsART200.getChildsCount() >= 6,
          "Childs of " + depsART200.asString()
              + " should include files, SAMPLE, EXTPGM, and FFAMILLY");

      TargetKey depsVATDEF = spec.getTargetKey(new TargetKey("CURLIB.VATDEF.PF.DDS"));
      assertNotNull(depsVATDEF, "Deps target should not be null");
      assertEquals(1, depsVATDEF.getChildsCount(),
          "Childs of target " + depsVATDEF.asString() + " should be 1");

      TargetKey depsARTICLE = spec.getTargetKey(new TargetKey("CURLIB.ARTICLE.PF.DDS"));
      assertNotNull(depsARTICLE, "Deps target should not be null");
      assertEquals(1, depsARTICLE.getChildsCount(),
          "Childs of target " + depsARTICLE.asString() + " should be 1");

      TargetKey depsFAMILLY = spec.getTargetKey(new TargetKey("CURLIB.FAMILLY.PF.DDS"));
      assertNotNull(depsFAMILLY, "Deps target should not be null");
      assertEquals(1, depsFAMILLY.getChildsCount(),
          "Childs of target " + depsFAMILLY.asString() + " should be 1");

      TargetKey depsARTICLE1 = spec.getTargetKey(new TargetKey("CURLIB.ARTICLE1.LF.DDS"));
      assertNotNull(depsARTICLE1, "Deps target should not be null");
      assertEquals(1, depsARTICLE1.getChildsCount(),
          "Childs of target " + depsARTICLE1.asString() + " should be 1");

      TargetKey depsARTICLE2 = spec.getTargetKey(new TargetKey("CURLIB.ARTICLE2.LF.DDS"));
      assertNotNull(depsARTICLE2, "Deps target should not be null");
      assertEquals(1, depsARTICLE2.getChildsCount(),
          "Childs of target " + depsARTICLE2.asString() + " should be 1");

      TargetKey depsFAMILL1 = spec.getTargetKey(new TargetKey("CURLIB.FAMILL1.LF.DDS"));
      assertNotNull(depsFAMILL1, "Deps target should not be null");
      assertEquals(1, depsFAMILL1.getChildsCount(),
          "Childs of target " + depsFAMILL1.asString() + " should be 1");

      TargetKey depsART200D = spec.getTargetKey(new TargetKey("CURLIB.ART200D.DSPF.DDS"));
      assertNotNull(depsART200D, "Deps target should not be null");
      assertEquals(2, depsART200D.getChildsCount(),
          "REFFLD Childs of target " + depsART200D.asString() + " should be 2");

      TargetKey depsFAM301D = spec.getTargetKey(new TargetKey("CURLIB.FAM301D.DSPF.DDS"));
      assertNotNull(depsFAM301D, "Deps target should not be null");
      assertEquals(1, depsFAM301D.getChildsCount(),
          "REFFLD Childs of target " + depsFAM301D.asString() + " should be 1");

      TargetKey depsORD100D = spec.getTargetKey(new TargetKey("CURLIB.ORD100D.DSPF.DDS"));
      assertNotNull(depsORD100D, "Deps target should not be null");
      assertEquals(3, depsORD100D.getChildsCount(),
          "REFFLD Childs of target " + depsORD100D.asString() + " should be 3");

      TargetKey depsART201 = spec.getTargetKey(new TargetKey("CURLIB.ART201.PGM.RPGLE"));
      assertNotNull(depsART201, "Deps target should not be null");
      assertTrue(depsART201.getChildsCount() >= 5,
          "Childs of " + depsART201.asString()
              + " should include files, SAMPLE, FARTICLE, FPROVIDER");

      TargetKey depsART202 = spec.getTargetKey(new TargetKey("CURLIB.ART202.PGM.RPGLE"));
      assertNotNull(depsART202, "Deps target should not be null");
      assertTrue(depsART202.getChildsCount() >= 5,
          "Childs of " + depsART202.asString()
              + " should include files, SAMPLE, and ARTICLE/PROVIDER srvpgms");

      TargetKey depsORD201 = spec.getTargetKey(new TargetKey("CURLIB.ORD201.PGM.SQLRPGLE"));
      assertNotNull(depsORD201, "Deps target should not be null");
      assertTrue(depsORD201.getChildsCount() >= 11,
          "Childs of " + depsORD201.asString() + " should be at least 11");

      TargetKey depsORD100 = spec.getTargetKey(new TargetKey("curlib.ORD100.PGM.RPGLE"));
      assertNotNull(depsORD100, "Deps target should not be null");
      assertTrue(depsORD100.getChildsCount() >= 6,
          "Childs of " + depsORD100.asString() + " should be at least 6");

      TargetKey depsORD900 = spec.getTargetKey(new TargetKey("curlib.ORD900.PGM.RPGLE"));
      assertNotNull(depsORD900, "Deps target should not be null");
      assertEquals(2, depsORD900.getChildsCount(),
          "Childs of target " + depsORD900.asString() + " should be 2. 1 file, 1 dtaara");

      TargetKey depsORD700 = spec.getTargetKey(new TargetKey("curlib.ORD700.PGM.RPGLE"));
      assertNotNull(depsORD700, "Deps target should not be null");
      assertTrue(depsORD700.getChildsCount() >= 3,
          "Childs of " + depsORD700.asString() + " should be at least 3 (BNDDIR, file, EXTNAME)");

      TargetKey depsARTLSTDAT = spec.getTargetKey(new TargetKey("curlib.ARTLSTDAT.VIEW.SQL"));
      assertNotNull(depsARTLSTDAT, "Deps target should not be null");
      assertEquals(3, depsARTLSTDAT.getChildsCount(),
          "Childs of target " + depsARTLSTDAT.asString() + " should be 3. 3 tables");

      TargetKey depsORDERCUS = spec.getTargetKey(new TargetKey("curlib.ORDERCUS.view.sql"));
      assertNotNull(depsORDERCUS, "Deps target should not be null");
      assertEquals(3, depsORDERCUS.getChildsCount(),
          "Childs of target " + depsORDERCUS.asString() + " should be 3 tables. DETORD, ORDER, CUSTOMER");

      TargetKey depsART801 = spec.getTargetKey(new TargetKey("curlib.ART801.procedure.sql"));
      assertNotNull(depsART801, "Deps target should not be null");
      assertEquals(4, depsART801.getChildsCount(),
          "Childs of target " + depsART801.asString()
              + " should be 4 tables. ARTICLE, DETORD, ORDER, CUSTOMER");

      TargetKey depsOPM = spec.getTargetKey(new TargetKey("curlib.OPM.pgm.CLP"));
      assertNotNull(depsOPM, "Deps target should not be null");
      assertEquals(1, depsOPM.getChildsCount(),
          "Childs of target " + depsOPM.asString() + " should be 1 CALL. ORD100C");

      TargetKey depsORD100C2 = spec.getTargetKey(new TargetKey("curlib.ORD100C2.PGM.CLLE"));
      assertNotNull(depsORD100C2, "Deps target should not be null");
      assertEquals(1, depsORD100C2.getChildsCount(),
          "Childs of target " + depsORD100C2.asString() + " should be 1 CALL. ORD100");

      TargetKey depsORD100C = spec.getTargetKey(new TargetKey("curlib.ORD100C.PGM.CLLE"));
      assertNotNull(depsORD100C, "Deps target should not be null");
      assertEquals(1, depsORD100C.getChildsCount(),
          "Childs of target " + depsORD100C.asString() + " should be 1 CMD CRTORD");

      TargetKey depsORD500C = spec.getTargetKey(new TargetKey("curlib.ORD500C.PGM.CLLE"));
      assertNotNull(depsORD500C, "Deps target should not be null");
      assertEquals(1, depsORD500C.getChildsCount(),
          "Childs of target " + depsORD500C.asString() + " should be 1 CMD CVTSPLPDF");

      TargetKey depsCRTORD = spec.getTargetKey(new TargetKey("curlib.CRTORD.CMD.cmd"));
      assertNotNull(depsCRTORD, "CRTORD CMD target should not be null");
      assertEquals(1, depsCRTORD.getChildsCount(),
          "Childs of target " + depsCRTORD.asString() + " should be 1 PGM ORD100");
      assertTrue(depsCRTORD.getChildsList().stream()
              .anyMatch(c -> c != null && "ORD100".equalsIgnoreCase(c.getObjectName()) && c.isProgram()),
          depsCRTORD.asString() + " should depend on processing program ORD100");

      TargetKey depsCVTSPLPDF = spec.getTargetKey(new TargetKey("curlib.CVTSPLPDF.CMD.cmd"));
      assertNotNull(depsCVTSPLPDF, "CVTSPLPDF CMD target should not be null");
      assertEquals(0, depsCVTSPLPDF.getChildsCount(),
          "Childs of " + depsCVTSPLPDF.asString() + " should be 0 (PGM not in build graph)");

      TargetKey depsFARTICLE = spec.getTargetKey(new TargetKey("curlib.FARTICLE.srvpgm.bnd"));
      TargetKey depsFPROVIDER = spec.getTargetKey(new TargetKey("curlib.FPROVIDER.srvpgm.bnd"));
      TargetKey depsFFAMILLY = spec.getTargetKey(new TargetKey("curlib.FFAMILLY.srvpgm.bnd"));
      TargetKey depsFCUSTOMER = spec.getTargetKey(new TargetKey("curlib.FCUSTOMER.srvpgm.bnd"));
      TargetKey depsFVAT = spec.getTargetKey(new TargetKey("curlib.fvat.srvpgm.bnd"));
      assertNotNull(depsFARTICLE, "FARTICLE srvpgm should be scanned");
      assertNotNull(depsFPROVIDER, "FPROVIDER srvpgm should be scanned");
      assertNotNull(depsFFAMILLY, "FFAMILLY srvpgm should be scanned");
      assertNotNull(depsFCUSTOMER, "FCUSTOMER srvpgm should be scanned");

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

      assertTrue(hasChild(depsORD100, depsFVAT),
          "ORD100 should depend on FVAT (ADDBNDDIRE / VAT include)");
      assertTrue(hasChild(depsORD100, depsFCUSTOMER),
          "ORD100 should depend on FCUSTOMER (ADDBNDDIRE / CUSTOMER include)");
      assertTrue(hasChild(depsORD100, depsFARTICLE),
          "ORD100 should depend on FARTICLE via /copy ARTICLE protos");

      assertTrue(hasChild(depsART200, depsFFAMILLY),
          "ART200 should depend on FFAMILLY (include protos / ADDBNDDIRE)");
      assertTrue(hasChild(depsART200, depsFARTICLE),
          "ART200 program should depend on FARTICLE (SAMPLE membership)");

      TargetKey depsLOG = spec.getTargetKey(new TargetKey("curlib.LOG.srvpgm.bnd"));
      if (depsLOG != null) {
        assertTrue(hasChild(depsORD700, depsLOG),
            "ORD700 should depend on LOG when LOG srvpgm is a build target");
      }

      TargetKey depsART301 = spec.getTargetKey(new TargetKey("CURLIB.ART301.MODULE.SQLRPGLE"));
      TargetKey depsCUS301 = spec.getTargetKey(new TargetKey("CURLIB.CUS301.MODULE.SQLRPGLE"));
      TargetKey depsART300 = spec.getTargetKey(new TargetKey("CURLIB.ART300.MODULE.RPGLE"));
      assertNotNull(depsART301, "ART301 module should be scanned");
      assertNotNull(depsCUS301, "CUS301 module should be scanned");
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
      assertTrue(hasChild(depsFARTICLE, depsFFAMILLY),
          "FARTICLE must depend on FFAMILLY (BNDSRVPGM / ART301 import)");
      if (depsART300 != null) {
        assertTrue(hasChild(depsFARTICLE, depsART300),
            "FARTICLE must still depend on ART300 via MODULE");
      }

      assertTrue(spec.containsExport("GETVATDESC", depsVAT300));
      assertTrue(spec.containsExport("GETVATRATE", depsVAT300));
      assertTrue(spec.containsExport("EXISTVATRATE", depsVAT300));
      assertTrue(spec.containsExport("CLCVAT", depsVAT300));

      CompilePlanAssert.assertFullCompilePlan(spec, root.toString());
      CompilePlanAssert.assertGoldenCommands(spec, "golden/mcontobi-commands.txt");
      new BuildTopoSort(false, false).topologicalSort(spec);
    } finally {
      deleteRecursively(root);
    }
  }

  @Test
  void test_Sjlennon_Deps() throws Exception {
    Path root = cloneRepo(SJLENNON);
    try {
      BuildSpec spec = new SpecGenerator(null, false, true).generate(root.toString(), "curlib");

      TargetKey depsGETOBJUR = spec.getTargetKey(new TargetKey("curlib.GETOBJUR.pgm.RPGLE"));
      assertNotNull(depsGETOBJUR, "Deps target should not be null");
      assertEquals(4, depsGETOBJUR.getChildsCount(),
          "Childs of target " + depsGETOBJUR.asString()
              + " should be 4. 1 FILE GETOBJUP, 3 EXTPGM GETJOBTR, SRTUSRSPC, GETOBJUR");

      TargetKey depsGETOBJUC = spec.getTargetKey(new TargetKey("curlib.GETOBJUC.pgm.CLLE"));
      assertNotNull(depsGETOBJUC, "Deps target should not be null");
      assertEquals(1, depsGETOBJUC.getChildsCount(),
          "Childs of target " + depsGETOBJUC.asString() + " should be 1 CALL. GETOBJUR");

      TargetKey depsT9ALLOCMNY = spec.getTargetKey(new TargetKey("CURLIB.T9ALLOCMNY.PGM.CLP"));
      assertNotNull(depsT9ALLOCMNY, "Deps target should not be null");
      assertEquals(1, depsT9ALLOCMNY.getChildsCount(),
          "Childs of target " + depsT9ALLOCMNY.asString() + " should be 1 CALL. T9ALLOC1");

      TargetKey depsSRV_SQL = spec.getTargetKey(new TargetKey("CURLIB.SRV_SQL.MODULE.SQLRPGLE"));
      assertNotNull(depsSRV_SQL, "Deps target should not be null");
      TargetKey depsSRV_MSG = spec.getTargetKey(new TargetKey("CURLIB.SRV_MSG.SRVPGM.BND"));
      TargetKey depsSRV_SQL_SRV = spec.getTargetKey(new TargetKey("CURLIB.SRV_SQL.SRVPGM.BND"));
      assertFalse(hasChildNamed(depsSRV_SQL, "UTIL_BND"),
          "SRV_SQL module must not depend on BNDDIR UTIL_BND");
      assertNotNull(depsSRV_MSG, "SRV_MSG srvpgm should be scanned");
      assertTrue(hasChild(depsSRV_SQL, depsSRV_MSG),
          "SRV_SQL module should depend on foreign SRV_MSG via /include proto");
      assertNotNull(depsSRV_SQL_SRV, "SRV_SQL srvpgm should come from mc-base");
      assertFalse(hasChild(depsSRV_SQL, depsSRV_SQL_SRV),
          "SRV_SQL module must not depend on its own parent SRV_SQL *SRVPGM");

      TargetKey depsUSADRVAL = spec.getTargetKey(new TargetKey("CURLIB.USADRVAL.MODULE.SQLRPGLE"));
      TargetKey depsUSADRVAL_SRV = spec.getTargetKey(new TargetKey("CURLIB.USADRVAL.SRVPGM.BND"));
      TargetKey depsSQL_BND = spec.getTargetKey(new TargetKey("CURLIB.SQL_BND.BNDDIR.BNDDIR"));
      assertNotNull(depsUSADRVAL, "USADRVAL module should be scanned");
      assertNotNull(depsUSADRVAL_SRV, "USADRVAL srvpgm should come from mc-base");
      assertNotNull(depsSQL_BND, "SQL_BND should come from mc-base");
      assertFalse(hasChild(depsUSADRVAL, depsSQL_BND),
          "USADRVAL module must not depend on SQL_BND");
      assertTrue(hasChild(depsUSADRVAL_SRV, depsSQL_BND),
          "USADRVAL srvpgm must depend on SQL_BND (module ctl-opt bind-time lift)");

      TargetKey depsLOADCUSTR = spec.getTargetKey(new TargetKey("CURLIB.LOADCUSTR.PGM.SQLRPGLE"));
      assertNotNull(depsLOADCUSTR, "LOADCUSTR should be scanned");
      assertTrue(hasChildNamed(depsLOADCUSTR, "UTIL_BND"),
          "LOADCUSTR should depend on UTIL_BND");
      assertTrue(hasChildNamed(depsLOADCUSTR, "SQL_BND"),
          "LOADCUSTR should depend on SQL_BND (BNDDIR colon list)");
      assertTrue(hasChildNamed(depsLOADCUSTR, "SRV_BASE36"),
          "LOADCUSTR should depend on SRV_BASE36");

      assertNull(spec.getTargetKey(new TargetKey("CURLIB.SRV_MSG_P.PGM.RPGLE")),
          "*.include.RPGLE must not become a *PGM target");

      CompilePlanAssert.assertFullCompilePlan(spec, root.toString());
      CompilePlanAssert.assertGoldenCommands(spec, "golden/mconsjlennon-commands.txt");
      new BuildTopoSort(false, false).topologicalSort(spec);
    } finally {
      deleteRecursively(root);
    }
  }

  private static Path cloneRepo(String url) throws Exception {
    assumeTrue(gitAvailable(), "git is not on PATH");
    Path dir = Files.createTempDirectory("mc-scan-deps-");
    Process clone = new ProcessBuilder("git", "clone", "--depth", "1", url, dir.toString())
        .inheritIO()
        .start();
    assertEquals(0, clone.waitFor(), "git clone failed: " + url);
    return dir;
  }

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean hasChild(TargetKey parent, TargetKey child) {
    if (parent == null || child == null) return false;
    return parent.getChildsList().stream().anyMatch(c -> c != null && c.equals(child));
  }

  private static boolean hasChildNamed(TargetKey parent, String objectName) {
    if (parent == null || objectName == null) return false;
    return parent.getChildsList().stream()
        .anyMatch(c -> c != null && objectName.equalsIgnoreCase(c.getObjectName()));
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) return;
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.deleteIfExists(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.deleteIfExists(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
      /* best-effort cleanup */
    }
  }
}
