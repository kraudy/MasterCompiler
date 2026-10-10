package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.kraudy.compiler.CompilationPattern.SysCmd;

/** Hooks: any CL command (Cmd / unknown names), ignore lists, protectedLibs in the spec. No IBM i. */
public class HookFormsTest {

  @Test
  void test_Raw_Unknown_And_Ignored_Hooks(@TempDir Path dir) throws Exception {
    Path yaml = dir.resolve("build.yaml");
    Files.write(yaml, ("protectedLibs: [prodlib]\n"
        + "before:\n"
        + "  - Cmd: CPYOBJS FROMLIB(A) TOLIB(B)\n"
        + "    ignore: [CPF2105]\n"
        + "  - DltObj: { Obj: \"*CURLIB/WORK\", ObjType: \"*FILE\" }\n"
        + "    ignore: CPF2105\n"
        + "  - ShopCmd: { Lib: DEVLIB }\n"
        + "targets:\n"
        + "  curlib.hello.pgm.rpgle: {}\n").getBytes(StandardCharsets.UTF_8));
    BuildSpec spec = Utilities.deserializeYaml(yaml.toString());

    assertEquals(3, spec.before.size());
    CommandObject raw = spec.before.get(0);
    assertTrue(raw.isRaw());
    assertEquals("CPYOBJS FROMLIB(A) TOLIB(B)", raw.getCommandStringWithoutSummary());
    assertEquals("[CPF2105]", raw.getIgnore().toString());

    CommandObject dlt = spec.before.get(1);
    assertEquals(SysCmd.DLTOBJ, dlt.getSystemCommand());
    assertEquals("[CPF2105]", dlt.getIgnore().toString());

    assertEquals("SHOPCMD LIB(DEVLIB)", spec.before.get(2).getCommandStringWithoutSummary(), "unknown command: run as written");
    assertEquals("[prodlib]", spec.protectedLibs.toString());
  }
}
