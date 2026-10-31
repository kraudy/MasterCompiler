package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.SourceType;
import com.github.kraudy.compiler.SourceNaming.ParsedName;

/* C sources as targets (CRTCMOD, CRTBNDC) and --target's selection of targets */
public class CTargetsAndSelectionTest {

  @TempDir
  Path tempDir;

  private BuildSpec scan() throws Exception {
    Path qcsrc = tempDir.resolve("QCSRC");
    Files.createDirectories(qcsrc);
    Files.write(qcsrc.resolve("DUMPSTATIC.module.c"), "int dump(void) { return 0; }\n".getBytes(StandardCharsets.UTF_8));
    Files.write(qcsrc.resolve("RUNDUMP.pgm.c"), "int main(void) { return 0; }\n".getBytes(StandardCharsets.UTF_8));
    /* a C file with no object type in its name stays what it was: not a target */
    Files.write(qcsrc.resolve("helper.c"), "int helper(void) { return 0; }\n".getBytes(StandardCharsets.UTF_8));
    Files.write(qcsrc.resolve("helper.h"), "int helper(void);\n".getBytes(StandardCharsets.UTF_8));
    Path qrpglesrc = tempDir.resolve("QRPGLESRC");
    Files.createDirectories(qrpglesrc);
    Files.write(qrpglesrc.resolve("HELLO.pgm.rpgle"), "**free\ndsply 'hi';\nreturn;\n".getBytes(StandardCharsets.UTF_8));
    return new SpecGenerator(null, false, true).generate(tempDir.toString(), "curlib");
  }

  @Test
  void cSourceNamesNeedTheObjectType() {
    ParsedName module = SourceNaming.parseFileName("DUMPSTATIC.module.c").get();
    assertEquals("DUMPSTATIC", module.objectName);
    assertEquals(ObjectType.MODULE, module.objectType);
    assertEquals(SourceType.C, module.sourceType);

    ParsedName pgm = SourceNaming.parseFileName("RUNDUMP.pgm.C").get();
    assertEquals(ObjectType.PGM, pgm.objectType);

    ParsedName own = SourceNaming.parseFileName("DUMPSTATIC.srvpgm.c").get();
    assertEquals(ObjectType.MODULE, own.objectType);
    assertTrue(own.ownServiceProgram);

    assertFalse(SourceNaming.parseFileName("helper.c").isPresent(), "a bare .c file is not a target");
    assertFalse(SourceNaming.parseFileName("HELPER.table.c").isPresent());
    assertFalse(SourceNaming.parseFileName("helper.h").isPresent());
  }

  @Test
  void scanBuildsCModulesAndPrograms() throws Exception {
    BuildSpec spec = scan();
    List<String> names = new ArrayList<String>();
    for (TargetKey key : spec.targets.keySet()) names.add(key.asString().toUpperCase());
    assertTrue(names.contains("CURLIB.DUMPSTATIC.MODULE.C"), names.toString());
    assertTrue(names.contains("CURLIB.RUNDUMP.PGM.C"), names.toString());
    assertTrue(names.contains("CURLIB.HELLO.PGM.RPGLE"), names.toString());
    assertEquals(3, spec.targets.size(), "helper.c / helper.h must not become targets: " + names);

    String cl = CompileScriptWriter.toCl(spec);
    assertTrue(cl.contains("CRTCMOD MODULE("), cl);
    assertTrue(cl.contains("CRTBNDC PGM("), cl);
    assertTrue(cl.contains("CRTBNDRPG PGM("), cl);
    for (String line : cl.split("\n")) {
      if (line.contains("CRTCMOD") || line.contains("CRTBNDC ")) {
        assertTrue(line.contains("SRCSTMF("), line);
        assertTrue(line.contains("DBGVIEW(*ALL)"), line);
        assertTrue(line.contains("REPLACE(*YES)"), line);
        assertTrue(line.contains("OPTION(*EVENTF)"), line);
        assertFalse(line.contains("SRCFILE("), line);
      }
    }
  }

  @Test
  void targetSelectionByNameAndType() throws Exception {
    BuildSpec spec = scan();
    List<TargetKey> byName = TargetSelection.select(spec.targets.keySet(), TargetSelection.parse("dumpstatic"));
    assertEquals(1, byName.size());
    assertEquals("DUMPSTATIC", byName.get(0).getObjectName());

    List<TargetKey> two = TargetSelection.select(spec.targets.keySet(), TargetSelection.parse("RUNDUMP.PGM, hello"));
    assertEquals(2, two.size());

    assertEquals(1, TargetSelection.select(spec.targets.keySet(), TargetSelection.parse("DUMPSTATIC,DUMPSTATIC.MODULE")).size(),
        "the same target named twice is selected once");
  }

  @Test
  void targetSelectionRefusesWhatTheSpecDoesNotHave() throws Exception {
    BuildSpec spec = scan();
    IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
        () -> TargetSelection.select(spec.targets.keySet(), TargetSelection.parse("NOSUCH")));
    assertTrue(missing.getMessage().contains("NOSUCH"), missing.getMessage());
    assertTrue(missing.getMessage().contains("DUMPSTATIC"), "the answer lists the targets there are: " + missing.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> TargetSelection.select(spec.targets.keySet(), TargetSelection.parse("DUMPSTATIC.SRVPGM")));
  }

  @Test
  void targetSelectionChecksNames() {
    assertThrows(IllegalArgumentException.class, () -> TargetSelection.parse("TOOLONGOBJECTNAME"));
    assertThrows(IllegalArgumentException.class, () -> TargetSelection.parse("A.B.C"));
    assertThrows(IllegalArgumentException.class, () -> TargetSelection.parse("A.NOTATYPE"));
    assertThrows(IllegalArgumentException.class, () -> TargetSelection.parse("A;DLTLIB LIB(X)"));
    assertThrows(IllegalArgumentException.class, () -> TargetSelection.parse(""));
  }

  /* Checked against IBM's parameter tables of CRTCMOD and CRTBNDC: these keywords are not on them */
  @Test
  void cPatternsHaveOnlyParametersTheCommandsHave() {
    for (CompilationPattern.ParamCmd notThere : new CompilationPattern.ParamCmd[] {
        CompilationPattern.ParamCmd.SRTSEQ, CompilationPattern.ParamCmd.LANGID }) {
      assertFalse(CompilationPattern.C_Mod_Pattern.contains(notThere), "CRTCMOD has no " + notThere);
      assertFalse(CompilationPattern.C_Pgm_Pattern.contains(notThere), "CRTBNDC has no " + notThere);
    }
    assertFalse(CompilationPattern.C_Pgm_Pattern.contains(CompilationPattern.ParamCmd.ACTGRP), "CRTBNDC has no ACTGRP");
    assertFalse(CompilationPattern.C_Pgm_Pattern.contains(CompilationPattern.ParamCmd.BNDDIR), "CRTBNDC has no BNDDIR");
  }
}
