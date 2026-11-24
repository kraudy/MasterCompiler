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

/** The YAML --scan --generate-only writes reads back as the spec it came from (#42). No IBM i. */
public class SpecWriterRoundTripTest {

  @Test
  void aWrittenSpecReadsBackWithItsSettingsHooksAndLibraries(@TempDir Path dir) throws Exception {
    Path first = dir.resolve("build.yaml");
    Files.write(first, ("protectedLibs: [PRODLIB, PRODDTA]\n"
        + "uploadFolders: [/home/me/drop]\n"
        + "uploadSources: [data]\n"
        + "hookTimeout: 120\n"
        + "inquiryReply: \"*SYSRPYL\"\n"
        + "connections:\n"
        + "  build: dev\n"
        + "  readOnly:\n"
        + "    - { name: prod, libraries: [PRODDTA] }\n"
        + "before:\n"
        + "  - Cmd: 'QSH CMD(''echo \"a\\b\" > /tmp/x'')'\n"
        + "    ignore: [CPF2105, CPF9801]\n"
        + "    timeout: 30\n"
        + "  - DltObj: { Obj: \"MYLIB/WORK\", ObjType: \"*FILE\" }\n"
        + "    ignore: CPF2105\n"
        + "after:\n"
        + "  - AddBndDirE: { BndDir: \"*LIBL/UTIL\", Obj: \"OTHERLIB/SRV1\" }\n"
        + "targets:\n"
        + "  curlib.util.bnddir.bnddir:\n"
        + "    recreate: true\n"
        + "  curlib.srv.srvpgm.bnd:\n"
        + "    params:\n"
        + "      MODULE: [MYLIB/MOD1, MOD2, \"*CURLIB/MOD3\"]\n"
        + "      SRCSTMF: srv.srvpgm.bnd\n").getBytes(StandardCharsets.UTF_8));
    BuildSpec read = Utilities.deserializeYaml(first.toString());

    Path second = dir.resolve("again.yaml");
    Files.write(second, SpecWriter.toYaml(read).getBytes(StandardCharsets.UTF_8));
    String written = new String(Files.readAllBytes(second), StandardCharsets.UTF_8);
    BuildSpec again = Utilities.deserializeYaml(second.toString());

    for (String setting : SpecWriter.SETTINGS.keySet()) assertTrue(written.contains(setting + ":"), setting + " written:\n" + written);
    assertEquals(read.protectedLibs, again.protectedLibs);
    assertEquals(read.uploadFolders, again.uploadFolders);
    assertEquals(read.uploadSources, again.uploadSources);
    assertEquals(read.hookTimeout, again.hookTimeout);
    assertEquals(read.inquiryReply, again.inquiryReply);
    assertEquals(read.connections, again.connections);

    assertEquals(hooks(read.before), hooks(again.before));
    assertEquals(hooks(read.after), hooks(again.after));
    assertTrue(again.before.get(0).isRaw(), "Cmd stays a command run as written");
    assertEquals("QSH CMD(''echo \"a\\b\" > /tmp/x'')", again.before.get(0).getCommandString(), written);
    assertEquals(30, again.before.get(0).getTimeoutSeconds());

    assertEquals(Boolean.TRUE, target(again, "UTIL").recreate, "a target with only recreate keeps it");
    String modules = target(again, "SRV").params.get(ParamCmd.MODULE);
    assertEquals(target(read, "SRV").params.get(ParamCmd.MODULE), modules);
    assertTrue(modules.contains("MYLIB/MOD1"), "a library the spec names stays: " + modules);
    assertTrue(modules.contains("*CURLIB/MOD3"), "quoted, or YAML would read *CURLIB as an alias: " + modules);
    assertFalse(written.contains("- *LIBL/MOD2"), "*LIBL/ is what an unqualified name means: not written\n" + written);
  }

  private static List<String> hooks(List<CommandObject> commands) {
    List<String> out = new ArrayList<String>();
    for (CommandObject c : commands) out.add(c.getCommandStringWithoutSummary() + " ignore " + c.getIgnore() + " timeout " + c.getTimeoutSeconds());
    return out;
  }

  private static BuildSpec.TargetSpec target(BuildSpec spec, String name) {
    for (TargetKey key : spec.targets.keySet()) if (key.getObjectName().equals(name)) return spec.targets.get(key);
    throw new AssertionError("no target " + name);
  }
}
