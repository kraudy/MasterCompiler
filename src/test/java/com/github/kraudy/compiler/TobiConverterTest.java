package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

/**
 * {@code --from-tobi} on IBM's own TOBi projects (pinned commits). Offline: Rules.mk + iproj.json in,
 * an ordered MC spec out.
 */
@Tag("deps")
public class TobiConverterTest {

  @Test
  void test_TobiExample() throws Exception {
    Path root = PinnedRepo.TOBI_EXAMPLE.checkout();
    try {
      TobiConverter converter = new TobiConverter(false);
      BuildSpec spec = converter.convert(root.toString(), "curlib");

      assertEquals(113, spec.targets.size(), "converted targets");
      assertEquals(26, converter.getSkipped().size(), "skipped (C, C++, COBOL, CRTPGM, panels, menus, triggers, recipe)");
      assertTrue(skippedFor(converter, "TMPDETORD.FILE").contains("recipe"));
      assertTrue(skippedFor(converter, "PWD.PGM").contains("CRTPGM"));

      /* Object types come from the rules, not the file names */
      assertTarget(spec, "CURLIB.FAM300.MODULE.RPGLE");
      assertTarget(spec, "CURLIB.ARTICLE.PF.DDS");
      assertTarget(spec, "CURLIB.ART801.PROCEDURE.SQL");
      assertTarget(spec, "CURLIB.CUSSEQ.SEQUENCE.SQL");
      assertTarget(spec, "CURLIB.HEB.PGM.RPGLE");

      /* Rule params and description files */
      BuildSpec.TargetSpec fvat = assertTarget(spec, "CURLIB.FVAT.SRVPGM.BND");
      assertEquals("Functions VAT", fvat.params.get(ParamCmd.TEXT));
      assertTrue(fvat.params.get(ParamCmd.MODULE).contains("VAT300"));

      BuildSpec.TargetSpec farticle = assertTarget(spec, "CURLIB.FARTICLE.SRVPGM.BND");
      assertTrue(farticle.params.get(ParamCmd.MODULE).contains("ART300"));
      assertTrue(farticle.params.get(ParamCmd.MODULE).contains("ART301"));
      assertTrue(farticle.params.get(ParamCmd.BNDSRVPGM).contains("FFAMILLY"));
      assertTrue(farticle.params.get(ParamCmd.EXPORT).contains("ALL"));

      BuildSpec.TargetSpec lastOrdNo = assertTarget(spec, "CURLIB.LASTORDNO.DTAARA.DTAARA");
      assertTrue(lastOrdNo.params.get(ParamCmd.VALUE).contains("60719"));

      BuildSpec.TargetSpec sample = assertTarget(spec, "CURLIB.SAMPLE.BNDDIR.BNDDIR");
      assertEquals(SysCmd.ADDBNDDIRE, sample.after.get(0).getSystemCommand());
      assertTrue(sample.after.get(0).get(ParamCmd.OBJ).contains("FARTICLE"));

      assertEquals(7, assertTarget(spec, "CURLIB.SAMMSGF.MSGF.MSGF").after.size(), "ADDMSGD hooks");
      assertTrue(assertTarget(spec, "CURLIB.CRTORD.CMD.CMD").params.get(ParamCmd.PGM).contains("ORD100"));
      assertTrue(assertTarget(spec, "CURLIB.CVTSPLPDF.CMD.CMD").params.get(ParamCmd.PGM).contains("CVTSPLPDF"),
          "TOBi default: processing program named like the command");
      assertTrue(assertTarget(spec, "CURLIB.HEB.PGM.RPGLE").params.get(ParamCmd.INCDIR).contains("QPROTOSRC"),
          "iproj.json includePath");

      /* Rule and source dependencies order the build */
      assertBefore(spec, "SAMREF.PF", "VATDEF.PF");
      assertBefore(spec, "VATDEF.PF", "VAT300.MODULE");
      assertBefore(spec, "VAT300.MODULE", "FVAT.SRVPGM");
      assertBefore(spec, "FFAMILLY.SRVPGM", "FARTICLE.SRVPGM");
      assertBefore(spec, "FARTICLE.SRVPGM", "SAMPLE.BNDDIR");
      assertBefore(spec, "SAMPLE.BNDDIR", "ART201.PGM");
      assertBefore(spec, "LOG300.MODULE", "LOG.SRVPGM");
    } finally {
      PinnedRepo.delete(root);
    }
  }

  @Test
  void test_CompanySystem() throws Exception {
    Path root = PinnedRepo.COMPANY_SYSTEM.checkout();
    try {
      TobiConverter converter = new TobiConverter(false);
      BuildSpec spec = converter.convert(root.toString(), "curlib");

      assertEquals(15, spec.targets.size());
      assertTrue(converter.getSkipped().isEmpty(), "skipped: " + converter.getSkipped());

      /* empdet.sqlrpgle is a module because the rule says EMPDET.MODULE */
      assertTarget(spec, "CURLIB.EMPDET.MODULE.SQLRPGLE");
      assertTarget(spec, "CURLIB.DEPARTMENT.TABLE.SQL");
      assertTarget(spec, "CURLIB.POPEMP.PROCEDURE.SQL");
      assertTarget(spec, "CURLIB.DEPTS.DSPF.DDS");
      assertTrue(assertTarget(spec, "CURLIB.EMPDET.SRVPGM.BND").params.get(ParamCmd.SRCSTMF).endsWith("empdet.bnd"));
      assertTrue(assertTarget(spec, "CURLIB.APP.BNDDIR.BNDDIR").after.get(0).get(ParamCmd.OBJ).contains("EMPDET"));
      assertTrue(assertTarget(spec, "CURLIB.NEWEMP.PGM.SQLRPGLE").params.get(ParamCmd.INCDIR).contains("qrpgleref"));

      assertBefore(spec, "EMPLOYEE.TABLE", "EMPDET.MODULE");
      assertBefore(spec, "EMPDET.MODULE", "EMPDET.SRVPGM");
      assertBefore(spec, "EMPDET.SRVPGM", "APP.BNDDIR");
      assertBefore(spec, "EMPLOYEES.PGM", "DEPTS.PGM");
      assertBefore(spec, "NEWEMP.PGM", "DEPTS.PGM");
    } finally {
      PinnedRepo.delete(root);
    }
  }

  static BuildSpec.TargetSpec assertTarget(BuildSpec spec, String key) {
    for (TargetKey target : spec.targets.keySet()) {
      if (target.asString().equalsIgnoreCase(key)) return spec.targets.get(target);
    }
    fail("missing target " + key);
    return null;
  }

  /* "NAME.TYPE" (object name + MC object type) of a comes before that of b */
  static void assertBefore(BuildSpec spec, String a, String b) {
    List<String> order = new ArrayList<String>();
    for (TargetKey target : spec.targets.keySet()) {
      order.add(target.getObjectName().toUpperCase() + "." + target.getObjectTypeEnum().name());
    }
    int ia = order.indexOf(a.toUpperCase());
    int ib = order.indexOf(b.toUpperCase());
    assertTrue(ia >= 0, "missing " + a);
    assertTrue(ib >= 0, "missing " + b);
    assertTrue(ia < ib, a + " must build before " + b);
  }

  private static String skippedFor(TobiConverter converter, String target) {
    for (String s : converter.getSkipped()) {
      if (s.startsWith(target)) return s;
    }
    fail("not skipped: " + target);
    return null;
  }
}
