package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

public class SpecGeneratorTest {

  @TempDir
  Path tempDir;

  @Test
  void scanDiscoversTargetsAndOrdersDependencies() throws Exception {
    // PF
    Path qddssrc = tempDir.resolve("QDDSSRC");
    Files.createDirectories(qddssrc);
    Files.write(qddssrc.resolve("ARTICLE.pf.dds"),
        "     A          R ARTICLER\n".getBytes(StandardCharsets.UTF_8));
    // LF depends on PF via PFILE
    Files.write(qddssrc.resolve("ARTICLE1.lf.dds"),
        "     A          PFILE(ARTICLE)\n".getBytes(StandardCharsets.UTF_8));

    // Module with export
    Path qrpglesrc = tempDir.resolve("QRPGLESRC");
    Files.createDirectories(qrpglesrc);
    Files.write(qrpglesrc.resolve("FAM300.module.rpgle"),
        ("**free\n"
            + "ctl-opt nomain;\n"
            + "dcl-proc GetArtFam export;\n"
            + "  return;\n"
            + "end-proc;\n").getBytes(StandardCharsets.UTF_8));

    // Binder source exports that symbol
    Path qsrvsrc = tempDir.resolve("QSRVSRC");
    Files.createDirectories(qsrvsrc);
    Files.write(qsrvsrc.resolve("FFAMILLY.srvpgm.bnd"),
        ("STRPGMEXP PGMLVL(*CURRENT)\n"
            + "  EXPORT SYMBOL('GETARTFAM')\n"
            + "ENDPGMEXP\n").getBytes(StandardCharsets.UTF_8));

    // Program with F-spec on ARTICLE (file dep) — fixed format col 6 = F
    String fSpec = "     FARTICLE   IF   E           K DISK\n";
    Files.write(qrpglesrc.resolve("ART200.pgm.rpgle"),
        fSpec.getBytes(StandardCharsets.UTF_8));

    SpecGenerator gen = new SpecGenerator(null, false, true);
    BuildSpec spec = gen.generate(tempDir.toString(), "curlib");

    assertTrue(spec.targets.size() >= 4);

    List<String> order = new ArrayList<>();
    for (TargetKey k : spec.targets.keySet()) {
      order.add(k.asString().toUpperCase());
    }

    int pfIdx = indexOfContaining(order, "ARTICLE.PF.DDS");
    int lfIdx = indexOfContaining(order, "ARTICLE1.LF.DDS");
    int modIdx = indexOfContaining(order, "FAM300.MODULE.RPGLE");
    int srvIdx = indexOfContaining(order, "FFAMILLY.SRVPGM.BND");
    int pgmIdx = indexOfContaining(order, "ART200.PGM.RPGLE");

    assertTrue(pfIdx >= 0 && lfIdx >= 0);
    assertTrue(pfIdx < lfIdx, "PF should compile before LF: " + order);

    assertTrue(modIdx >= 0 && srvIdx >= 0);
    assertTrue(modIdx < srvIdx, "Module before srvpgm: " + order);

    assertTrue(pgmIdx >= 0 && pfIdx < pgmIdx, "PF before program: " + order);

    // Srvpgm should have inferred MODULE
    TargetKey srvKey = null;
    for (TargetKey k : spec.targets.keySet()) {
      if (k.getObjectName().equals("FFAMILLY")) {
        srvKey = k;
        break;
      }
    }
    assertNotNull(srvKey);
    BuildSpec.TargetSpec srvSpec = spec.targets.get(srvKey);
    assertTrue(srvSpec.params.containsKey(ParamCmd.MODULE));
    assertTrue(srvSpec.params.get(ParamCmd.MODULE).toUpperCase().contains("FAM300"));
  }

  @Test
  void generatedYamlRoundTrips() throws Exception {
    Path src = tempDir.resolve("QRPGLESRC");
    Files.createDirectories(src);
    Files.write(src.resolve("HELLO.pgm.rpgle"),
        "**free\n".getBytes(StandardCharsets.UTF_8));

    SpecGenerator gen = new SpecGenerator(null, false, false);
    BuildSpec spec = gen.generate(tempDir.toString(), "curlib");

    Path out = tempDir.resolve("generated.yaml");
    SpecWriter.writeToFile(spec, out.toString(), tempDir.toString());

    BuildSpec loaded = Utilities.deserializeYaml(out.toString());
    assertEquals(1, loaded.targets.size());
    TargetKey key = loaded.targets.keySet().iterator().next();
    assertEquals("HELLO", key.getObjectName());
    assertTrue(loaded.targets.get(key).params.containsKey(ParamCmd.SRCSTMF));
  }

  @Test
  void emptyTreeThrows() throws Exception {
    SpecGenerator gen = new SpecGenerator(null, false, false);
    assertThrows(IllegalArgumentException.class,
        () -> gen.generate(tempDir.toString(), "curlib"));
  }

  private static int indexOfContaining(List<String> order, String fragment) {
    for (int i = 0; i < order.size(); i++) {
      if (order.get(i).contains(fragment)) return i;
    }
    return -1;
  }
}
