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

    assertEquals("", map.get(ParamCmd.EXPORT)); // Removed due to conflict with SRCSTMF
    assertEquals("CRTSRVPGM SRCSTMF(''/ifs/source.bnd'')", cmd);
  }

  @Test
  void testResolve_CVTCCSID_SRCSTMF_Conflicts() {
    ParamMap map = new ParamMap();
    map.put(CompCmd.CRTSQLRPGI, ParamCmd.SRCSTMF, "/ifs/source.bnd");
    map.put(CompCmd.CRTSQLRPGI, ParamCmd.CVTCCSID, "Job");

    map.remove(ParamCmd.SRCSTMF);

    map.getCommandString(CompCmd.CRTSQLRPGI); // Just get the command to resolve conflicts

    assertEquals("", map.get(ParamCmd.SRCSTMF)); // Removed, expect empty
    assertEquals("", map.get(ParamCmd.CVTCCSID)); // Removed due to missing SRCSTMF
  }

  @Test
  void testResolve_TGTCCSID_SRCSTMF_Conflicts() {
    ParamMap map = new ParamMap();
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCSTMF, "/ifs/source.bnd");
    map.put(CompCmd.CRTBNDRPG, ParamCmd.TGTCCSID, "Job");

    map.remove(ParamCmd.SRCSTMF);

    map.getCommandString(CompCmd.CRTBNDRPG); // Just get the command to resolve conflicts

    assertEquals("", map.get(ParamCmd.SRCSTMF)); // Removed, expect empty
    assertEquals("", map.get(ParamCmd.TGTCCSID)); // Removed due to missing SRCSTMF
  }

  @Test
  void testResolveSourceConflicts() {
    ParamMap map = new ParamMap();
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCFILE, "MYLIB/QRPGLESRC");
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCMBR, "HELLO");
    map.put(CompCmd.CRTBNDRPG, ParamCmd.SRCSTMF, "/home/sources/HELLO.rpgle");

    String cmd = map.getCommandString(CompCmd.CRTBNDRPG);

    assertEquals("", map.get(ParamCmd.SRCFILE)); // Removed due to conflict with SRCSTMF
    assertEquals("", map.get(ParamCmd.SRCMBR)); // Removed due to conflict with SRCSTMF
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
