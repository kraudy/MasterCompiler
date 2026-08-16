package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

public class SpecResolverTest {

  @Test
  void localResolveEmitsDefaultsAndSrcstmfOmitsIdentity() {
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    Map<ParamCmd, String> params = new HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "QRPGLESRC/HELLO.pgm.rpgle");

    Map<ParamCmd, String> resolved = SpecResolver.resolve(key, null, params, null);

    assertEquals("QRPGLESRC/HELLO.pgm.rpgle", resolved.get(ParamCmd.SRCSTMF));
    assertEquals("*ALL", resolved.get(ParamCmd.DBGVIEW));
    assertEquals("*EVENTF", resolved.get(ParamCmd.OPTION));
    assertEquals("*YES", resolved.get(ParamCmd.REPLACE));
    assertEquals("*JOB", resolved.get(ParamCmd.TGTCCSID));
    assertFalse(resolved.containsKey(ParamCmd.PGM), "identity PGM must not be written");
    assertFalse(resolved.containsKey(ParamCmd.SRCFILE), "SRCFILE dropped when SRCSTMF present");
    assertFalse(resolved.containsKey(ParamCmd.SRCMBR));
  }

  @Test
  void yamlParamsWinOverMcDefaults() {
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    Map<ParamCmd, String> params = new HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "hello.rpgle");
    params.put(ParamCmd.DBGVIEW, "*SOURCE");

    Map<ParamCmd, String> resolved = SpecResolver.resolve(key, null, params, null);

    assertEquals("*SOURCE", resolved.get(ParamCmd.DBGVIEW));
  }

  @Test
  void specDefaultsApplyThenTargetParamsWin() {
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    Map<ParamCmd, String> defaults = new HashMap<ParamCmd, String>();
    defaults.put(ParamCmd.TGTRLS, "V7R5M0");
    defaults.put(ParamCmd.DBGVIEW, "*SOURCE");
    Map<ParamCmd, String> params = new HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "hello.rpgle");
    params.put(ParamCmd.DBGVIEW, "*ALL");

    Map<ParamCmd, String> resolved = SpecResolver.resolve(key, defaults, params, null);

    assertEquals("V7R5M0", resolved.get(ParamCmd.TGTRLS));
    assertEquals("*ALL", resolved.get(ParamCmd.DBGVIEW));
  }

  @Test
  void srvpgmKeepsInferredModuleDropsIdentitySrvpgm() {
    TargetKey key = new TargetKey("curlib.ffamilly.srvpgm.bnd");
    Map<ParamCmd, String> params = new HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "QSRVSRC/FFAMILLY.srvpgm.BND");
    params.put(ParamCmd.MODULE, "FAM300");

    Map<ParamCmd, String> resolved = SpecResolver.resolve(key, null, params, null);

    assertTrue(resolved.get(ParamCmd.MODULE).toUpperCase().contains("FAM300"));
    assertFalse(resolved.containsKey(ParamCmd.SRVPGM));
    assertFalse(resolved.containsKey(ParamCmd.EXPORT), "EXPORT removed when SRCSTMF present");
  }

  @Test
  void resolveAllMutatesTargetSpec() {
    BuildSpec spec = new BuildSpec();
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    BuildSpec.TargetSpec ts = new BuildSpec.TargetSpec();
    ts.params.put(ParamCmd.SRCSTMF, "hello.rpgle");
    spec.targets.put(key, ts);

    SpecResolver.resolveAll(spec, null);

    assertEquals("hello.rpgle", ts.params.get(ParamCmd.SRCSTMF));
    assertTrue(ts.params.containsKey(ParamCmd.DBGVIEW));
    assertFalse(ts.params.containsKey(ParamCmd.PGM));
  }

  @Test
  void ddsKeepsSrcstmfEvenWhenNotACompileParam() {
    TargetKey key = new TargetKey("curlib.article.pf.dds");
    Map<ParamCmd, String> params = new HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "QDDSSRC/ARTICLE.pf.dds");

    Map<ParamCmd, String> resolved = SpecResolver.resolve(key, null, params, null);

    assertEquals("QDDSSRC/ARTICLE.pf.dds", resolved.get(ParamCmd.SRCSTMF));
    assertFalse(resolved.containsKey(ParamCmd.FILE), "identity FILE omitted");
  }

  @Test
  void compileScriptIncludesHooksMigrateAndCrt() {
    BuildSpec spec = new BuildSpec();
    spec.before.add(new CommandObject(SysCmd.CHGCURDIR).put(ParamCmd.DIR, "/home/src"));

    TargetKey rpg = new TargetKey("curlib.hello.pgm.rpgle");
    BuildSpec.TargetSpec rpgSpec = new BuildSpec.TargetSpec();
    rpgSpec.params.put(ParamCmd.SRCSTMF, "QRPGLESRC/HELLO.pgm.rpgle");
    spec.targets.put(rpg, rpgSpec);

    TargetKey pf = new TargetKey("curlib.article.pf.dds");
    BuildSpec.TargetSpec pfSpec = new BuildSpec.TargetSpec();
    pfSpec.params.put(ParamCmd.SRCSTMF, "QDDSSRC/ARTICLE.pf.dds");
    spec.targets.put(pf, pfSpec);

    String cl = CompileScriptWriter.toCl(spec);
    assertTrue(cl.contains("CHGCURDIR DIR('/home/src')"));
    assertTrue(cl.contains("CRTBNDRPG PGM(*CURLIB/HELLO)"));
    assertTrue(cl.contains("SRCSTMF('QRPGLESRC/HELLO.pgm.rpgle')"));
    assertTrue(cl.contains("CPYFRMSTMF"));
    assertTrue(cl.contains("QDDSSRC/ARTICLE.pf.dds"));
    assertTrue(cl.contains("CRTPF FILE(*CURLIB/ARTICLE)"));
    assertTrue(cl.indexOf("CPYFRMSTMF") < cl.indexOf("CRTPF"));
    assertFalse(cl.contains("CPYFRMSTMF FROMSTMF('QRPGLESRC/HELLO.pgm.rpgle')"),
        "IFS CRT* must not CPYFRMSTMF");
  }

  @Test
  void specWriterWritesSiblingCl() throws Exception {
    BuildSpec spec = new BuildSpec();
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    BuildSpec.TargetSpec ts = new BuildSpec.TargetSpec();
    ts.params.put(ParamCmd.SRCSTMF, "hello.rpgle");
    spec.targets.put(key, ts);

    Path yaml = Files.createTempFile("build", ".yaml");
    try {
      SpecWriter.writeToFile(spec, yaml.toString());
      Path cl = SpecWriter.clPathFor(yaml);
      assertTrue(Files.isRegularFile(cl), "expected " + cl);
      String body = new String(Files.readAllBytes(cl), StandardCharsets.UTF_8);
      assertTrue(body.contains("CRTBNDRPG"));
    } finally {
      Files.deleteIfExists(yaml);
      Files.deleteIfExists(SpecWriter.clPathFor(yaml));
    }
  }

  @Test
  void specWriterCommentTracksParamEdits() {
    BuildSpec spec = new BuildSpec();
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    BuildSpec.TargetSpec ts = new BuildSpec.TargetSpec();
    ts.params.put(ParamCmd.SRCSTMF, "hello.rpgle");
    spec.targets.put(key, ts);

    String first = SpecWriter.toYaml(spec);
    assertTrue(first.contains("# CRTBNDRPG "));
    assertTrue(first.contains("DBGVIEW(*ALL)"));

    ts.params.put(ParamCmd.DBGVIEW, "*SOURCE");
    String edited = SpecWriter.toYaml(spec);
    assertTrue(edited.contains("DBGVIEW(*SOURCE)"));
    assertFalse(edited.contains("DBGVIEW(*ALL)"));

    ts.params.remove(ParamCmd.DBGVIEW);
    ts.params.put(ParamCmd.TEXT, "Hi there");
    String added = SpecWriter.toYaml(spec);
    assertTrue(added.contains("TEXT('Hi there')"));
    assertTrue(added.contains("DBGVIEW(*ALL)"));
    assertFalse(added.contains("DBGVIEW(*SOURCE)"));
  }

  @Test
  void crtcmdPgmIsKept() {
    TargetKey key = new TargetKey("curlib.ord100.cmd.cmd");
    Map<ParamCmd, String> params = new HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "QCMDSRC/ORD100.cmd.cmd");
    params.put(ParamCmd.PGM, "ORD100");

    Map<ParamCmd, String> resolved = SpecResolver.resolve(key, null, params, null);

    assertTrue(resolved.get(ParamCmd.PGM).toUpperCase().contains("ORD100"));
    assertFalse(resolved.containsKey(ParamCmd.CMD));
  }
}
