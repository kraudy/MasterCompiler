package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

/**
 * {@code --scan} on community repos (pinned commits) that use conventions MC must understand:
 * TOBi descriptive names, plain DDS/SQL extensions, NAME.srvpgm.rpgle, CL BNDSRVPGM and
 * commands whose processing program has the command's name.
 */
@Tag("deps")
public class ScanConventionsTest {

  @Test
  void test_NickLitten_DescriptiveNames() throws Exception {
    Path root = PinnedRepo.NICK_LITTEN.checkout();
    try {
      BuildSpec spec = new SpecGenerator(null, false, false).generate(root.toString(), "curlib");

      assertEquals(123, spec.targets.size());
      /* CLRBOBLOG-Clear_Bob_Logs.pgm.clle: the object name stops at the dash */
      TobiConverterTest.assertTarget(spec, "CURLIB.CLRBOBLOG.PGM.CLLE");
      TobiConverterTest.assertTarget(spec, "CURLIB.CLRBOBLOG.CMD.CMD");
      TobiConverterTest.assertTarget(spec, "CURLIB.ACTGRPTST1.PGM.RPGLE");
    } finally {
      PinnedRepo.delete(root);
    }
  }

  @Test
  void test_Pub400Topics_Conventions() throws Exception {
    Path root = PinnedRepo.PUB400_TOPICS.checkout();
    try {
      BuildSpec spec = new SpecGenerator(null, false, false).generate(root.toString(), "curlib");

      assertEquals(30, spec.targets.size());

      /* callstack.srvpgm.rpgle: a module plus a service program exporting everything */
      TobiConverterTest.assertTarget(spec, "CURLIB.CALLSTACK.MODULE.RPGLE");
      BuildSpec.TargetSpec callstack = TobiConverterTest.assertTarget(spec, "CURLIB.CALLSTACK.SRVPGM.BND");
      assertTrue(callstack.params.get(ParamCmd.EXPORT).contains("ALL"));
      TobiConverterTest.assertBefore(spec, "CALLSTACK.MODULE", "CALLSTACK.SRVPGM");

      /* DCLPRCOPT BNDSRVPGM((CALLSTACK)) in CL */
      TobiConverterTest.assertBefore(spec, "CALLSTACK.SRVPGM", "ENVSAV0.PGM");

      /* usrspcdsp.dspf is a display file, built before the program that uses it */
      TobiConverterTest.assertTarget(spec, "CURLIB.USRSPCDSP.DSPF.DDS");
      TobiConverterTest.assertBefore(spec, "USRSPCDSP.DSPF", "USRSPCDSP1.PGM");

      /* CRTCMD without an explicit PGM: the processing program is named like the command */
      TargetKey envsav = new TargetKey("CURLIB.ENVSAV.CMD.CMD");
      assertTrue(envsav.getCommandString().contains("PGM(ENVSAV)"), envsav.getCommandString());
    } finally {
      PinnedRepo.delete(root);
    }
  }
}
