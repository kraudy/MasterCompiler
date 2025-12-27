package com.github.kraudy.compiler;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

public class ParamMapTest {
  @Test
  void testPutAllWithValidation() {
    ParamMap map = new ParamMap();
    Map<ParamCmd, String> params = Map.of(
      ParamCmd.TEXT, "Test",
      ParamCmd.OPTIMIZE, "40",  // Valid for CRTRPGMOD
      ParamCmd.CMD, "invalid"
    );
    map.putAll(CompCmd.CRTRPGMOD, params);

    // Invalid param (e.g., for CRTRPGMOD) should be rejected silently in putAll
    assertEquals("", map.get(ParamCmd.CMD)); // Not added
  }

  @Test
  void testResolveExportConflicts() {
    ParamMap map = new ParamMap();
    map.put(CompCmd.CRTSRVPGM, ParamCmd.SRCSTMF, "/ifs/source.bnd");
    map.put(CompCmd.CRTSRVPGM, ParamCmd.EXPORT, "ALL");

    String cmd = map.getCommandString(CompCmd.CRTSRVPGM);

    //map.ResolveConflicts(CompCmd.CRTSRVPGM);
    assertEquals(null, map.get(ParamCmd.EXPORT)); // Removed due to conflict with SRCSTMF
    assertEquals("CRTSRVPGM SRCSTMF(''/ifs/source.bnd'')", cmd);
  }

  @Test
  void testResolveSourceConflicts() {
    ParamMap map = new ParamMap();
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCFILE, "MYLIB/QRPGLESRC");
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCMBR, "HELLO");
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCSTMF, "/home/sources/HELLO.rpgle");

    String cmd = map.getCommandString(CompCmd.CRTBNDRPG);

    assertEquals(null, map.get(ParamCmd.SRCFILE)); // Removed due to conflict with SRCSTMF
    assertEquals(null, map.get(ParamCmd.SRCMBR)); // Removed due to conflict with SRCSTMF
    assertEquals("CRTBNDRPG SRCSTMF(''/home/sources/HELLO.rpgle'') TGTCCSID(*JOB)", cmd);
  }

  @Test
  void testResolveSourceCcsidConflicts() {
    ParamMap map = new ParamMap();
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCSTMF, "/home/sources/HELLO.rpgle");

    String cmd = map.getCommandString(CompCmd.CRTBNDRPG);

    assertEquals("*JOB", map.get(ParamCmd.TGTCCSID)); // Add missing param
    assertEquals("CRTBNDRPG SRCSTMF(''/home/sources/HELLO.rpgle'') TGTCCSID(*JOB)", cmd);
  }

}
