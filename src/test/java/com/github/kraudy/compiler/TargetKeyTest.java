package com.github.kraudy.compiler;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.util.Map;

public class TargetKeyTest {
  @Test
  void testValidKeyParsing() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE");

    assertEquals("MYLIB/HELLO", key.getQualifiedObject());
    assertEquals("HELLO", key.getObjectName());
    assertEquals("*PGM", key.getObjectType());
    assertEquals("QRPGLESRC", key.getSourceFile()); // Default from SourceType
    assertEquals("HELLO", key.getSourceName()); // Defaults to object name
    assertEquals("CRTBNDRPG", key.getCompilationCommandName()); // From pattern
    assertTrue(key.needsRebuild()); // No timestamps yet
  }

  @Test
  void testPgmRgpleCommand() {
    String cmd = new TargetKey("MYLIB.HELLO.PGM.RPGLE")
      .put(ParamCmd.PGM, "MYLIB/HELLO")
      .put(ParamCmd.SRCSTMF, "/home/sources/HELLO.RPGLE")
      .put(ParamCmd.DFTACTGRP, "*NO")
      .put(ParamCmd.ACTGRP, "QILE")
      .put(ParamCmd.STGMDL, "*SNGLVL")
      .put(ParamCmd.OPTION, "*EVENTF")
      .put(ParamCmd.DBGVIEW, "*SOURCE")
      .put(ParamCmd.REPLACE, "*YES")
      .put(ParamCmd.USRPRF, "*USER")
      .put(ParamCmd.TGTRLS, "V7R5M0")
      .put(ParamCmd.PRFDTA, "*NOCOL")
      .put(ParamCmd.TGTCCSID, "*JOB")
      .getCommandString();

    assertEquals(
      "CRTBNDRPG PGM(MYLIB/HELLO) SRCSTMF(''/home/sources/HELLO.RPGLE'') " +  
      "DFTACTGRP(*NO) ACTGRP(QILE) STGMDL(*SNGLVL) OPTION(*EVENTF) DBGVIEW(*SOURCE) REPLACE(*YES) USRPRF(*USER) " +
      "TGTRLS(V7R5M0) PRFDTA(*NOCOL) TGTCCSID(*JOB)", cmd);
  }

  @Test
  void test_Pgm_Rgp_Command() {
    String cmd = new TargetKey("MYLIB.HELLO.PGM.RPG")
      .put(ParamCmd.PGM, "MYLIB/HELLO")
      .put(ParamCmd.SRCFILE, "MYLIB/PRACTICAS")
      .put(ParamCmd.SRCMBR, "HELLO")
      .put(ParamCmd.TEXT, "hello!")
      .put(ParamCmd.OPTION, "*LSTDBG")
      .put(ParamCmd.GENOPT, "*LIST")
      .put(ParamCmd.REPLACE, "*YES")
      .put(ParamCmd.TGTRLS, "*CURRENT")
      .put(ParamCmd.USRPRF, "*USER")
      .getCommandString();

    assertEquals(
      "CRTRPGPGM PGM(MYLIB/HELLO) SRCFILE(MYLIB/PRACTICAS) SRCMBR(HELLO) " +
      "TEXT(''hello!'') OPTION(*LSTDBG) GENOPT(*LIST) REPLACE(*YES) TGTRLS(*CURRENT) USRPRF(*USER)", cmd);
  }

  @Test
  void testPgmSqlRgpleCommand() {
    String cmd = new TargetKey("MYLIB.SQLHELLO.PGM.SQLRPGLE")
      .put(ParamCmd.OBJ, "*LIBL/SQLHELLO")
      .put(ParamCmd.SRCSTMF, "/home/sources/SQLHELLO.SQLRPGLE")
      .put(ParamCmd.COMMIT, "*NONE")
      .put(ParamCmd.OBJTYPE, "*PGM")
      .put(ParamCmd.TEXT, "Sqlrpgle compilation test")
      .put(ParamCmd.OPTION, "*EVENTF")
      .put(ParamCmd.TGTRLS, "V7R5M0")
      .put(ParamCmd.REPLACE, "*YES")
      .put(ParamCmd.DBGVIEW, "*SOURCE")
      .put(ParamCmd.USRPRF, "*USER")
      .put(ParamCmd.CVTCCSID, "*JOB")
      .getCommandString();

    assertEquals(
      "CRTSQLRPGI OBJ(*CURLIB/SQLHELLO) SRCSTMF(''/home/sources/SQLHELLO.SQLRPGLE'') " +
      "COMMIT(*NONE) OBJTYPE(*PGM) TEXT(''Sqlrpgle compilation test'') OPTION(*EVENTF) TGTRLS(V7R5M0) REPLACE(*YES) " +
      "DBGVIEW(*SOURCE) USRPRF(*USER) CVTCCSID(*JOB)", cmd);
  }

  @Test
  void testModRgpleCommand() {
    String cmd = new TargetKey("MYLIB.MODHELLO.MODULE.RPGLE")
      .put(ParamCmd.MODULE, "MYLIB/MODHELLO")
      .put(ParamCmd.SRCSTMF, "/home/sources/MODHELLO.RPGLE")
      .put(ParamCmd.OPTION, "*EVENTF")
      .put(ParamCmd.DBGVIEW, "*SOURCE")
      .put(ParamCmd.REPLACE, "*YES")
      .put(ParamCmd.TGTRLS, "V7R5M0")
      .put(ParamCmd.TGTCCSID, "*JOB")
      .getCommandString();

    assertEquals(
      "CRTRPGMOD MODULE(MYLIB/MODHELLO) SRCSTMF(''/home/sources/MODHELLO.RPGLE'') " +
      "OPTION(*EVENTF) DBGVIEW(*SOURCE) REPLACE(*YES) TGTRLS(V7R5M0) TGTCCSID(*JOB)", cmd);
  }

  @Test
  void testDspfDdsCommand() {
    String cmd = new TargetKey("MYLIB.DSPHELLO.DSPF.DDS")
      .put(ParamCmd.FILE, "MYLIB/DSPHELLO")
      .put(ParamCmd.SRCFILE, "MYLIB/QDSPFSRC")
      .put(ParamCmd.SRCMBR, "DSPHELLO")
      .put(ParamCmd.OPTION, "*EVENTF")
      .put(ParamCmd.REPLACE, "*YES")
      .getCommandString();

    assertEquals(
      "CRTDSPF FILE(MYLIB/DSPHELLO) SRCFILE(MYLIB/QDSPFSRC) SRCMBR(DSPHELLO) OPTION(*EVENTF) REPLACE(*YES)", cmd);
  }

  @Test
  void testTableSqlCommand() {
    String cmd = new TargetKey("MYLIB.SQLHELLO.TABLE.SQL")
      .put(ParamCmd.SRCSTMF, "/home/sources/SQLHELLO.SQL")
      .put(ParamCmd.COMMIT, "*NONE")
      .put(ParamCmd.OPTION, "*LIST")
      .put(ParamCmd.TGTRLS, "V7R5M0")
      .put(ParamCmd.DBGVIEW, "*SOURCE")
      .getCommandString();
    
    assertEquals(
      "RUNSQLSTM SRCSTMF(''/home/sources/SQLHELLO.SQL'') COMMIT(*NONE) OPTION(*LIST) TGTRLS(V7R5M0) DBGVIEW(*SOURCE)", cmd);
  }

  @Test
  void testSrvPgmBndCommand() {
    String cmd = new TargetKey("MYLIB.SRVHELLO.SRVPGM.BND")
        .put(ParamCmd.SRVPGM, "MYLIB/SRVHELLO")
        .put(ParamCmd.MODULE, "*LIBL/MODHELLO1 *LIBL/MODHELLO2")
        .put(ParamCmd.SRCSTMF, "/home/sources/SRVHELLO.BND")
        .put(ParamCmd.BNDSRVPGM, "*NONE")
        .put(ParamCmd.OPTION, "*EVENTF")
        .put(ParamCmd.REPLACE, "*YES")
        .put(ParamCmd.TGTRLS, "V7R5M0")
        .getCommandString();
  
    
    assertEquals(
      "CRTSRVPGM SRVPGM(MYLIB/SRVHELLO) MODULE(*LIBL/MODHELLO1 *LIBL/MODHELLO2) SRCSTMF(''/home/sources/SRVHELLO.BND'') " +
      "BNDSRVPGM(*NONE) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(V7R5M0)", cmd);
  }

  /*
   * Resolution tests
   */

  @Test
  void testResolve_EXPORT_SRCSTMF_Conflicts() {
    TargetKey key = new TargetKey("MYLIB.SRVHELLO.SRVPGM.BND")
        .put(ParamCmd.SRCSTMF, "/ifs/source.bnd")
        .put(ParamCmd.EXPORT, "ALL")
        .ResolveConflicts();

    assertEquals("", key.get(ParamCmd.EXPORT)); // Removed due to conflict with SRCSTMF
    assertEquals("''/ifs/source.bnd''", key.get(ParamCmd.SRCSTMF));
  }

  @Test
  void testResolve_CVTCCSID_SRCSTMF_Conflicts() {
    TargetKey key = new TargetKey("MYLIB.SQLHELLO.pgm.sqlrpgle")
        .put(ParamCmd.SRCSTMF, "/ifs/source.bnd")
        .put(ParamCmd.CVTCCSID, "Job")
        .remove(ParamCmd.SRCSTMF)
        .ResolveConflicts();

    assertEquals("", key.get(ParamCmd.SRCSTMF)); // Removed, expect empty
    assertEquals("", key.get(ParamCmd.CVTCCSID)); // Removed due to missing SRCSTMF
  }

  @Test
  void testResolve_TGTCCSID_SRCSTMF_Conflicts() {
    TargetKey key = new TargetKey("MYLIB.RPGHELLO.pgm.rpgle")
        .put(ParamCmd.SRCSTMF, "/ifs/source.bnd")
        .put(ParamCmd.TGTCCSID, "Job")
        .remove(ParamCmd.SRCSTMF)
        .ResolveConflicts();

    assertEquals("", key.get(ParamCmd.SRCSTMF)); // Removed, expect empty
    assertEquals("", key.get(ParamCmd.TGTCCSID)); // Removed due to missing SRCSTMF
  }

  @Test
  void testResolve_SRCSTMF_SRCFILE_Conflicts() {
    TargetKey key = new TargetKey("MYLIB.RPGHELLO.pgm.rpgle")
      .put(ParamCmd.SRCFILE, "MYLIB/QRPGLESRC")
      .put(ParamCmd.SRCMBR, "HELLO")
      .put(ParamCmd.SRCSTMF, "/home/sources/HELLO.rpgle")
      .ResolveConflicts();

    assertEquals("", key.get(ParamCmd.SRCFILE)); // Removed due to conflict with SRCSTMF
    assertEquals("", key.get(ParamCmd.SRCMBR)); // Removed due to conflict with SRCSTMF
    assertEquals("''/home/sources/HELLO.rpgle''", key.get(ParamCmd.SRCSTMF));
  }

  @Test
  void testResolveSourceCcsidConflicts() {
    TargetKey key = new TargetKey("MYLIB.RPGHELLO.pgm.rpgle")
        .put(ParamCmd.SRCSTMF, "/home/sources/HELLO.rpgle")
        .ResolveConflicts();

    assertEquals("*JOB", key.get(ParamCmd.TGTCCSID)); // Add missing param
    assertEquals("''/home/sources/HELLO.rpgle''", key.get(ParamCmd.SRCSTMF)); 
  }

  @Test
  void testPutAllWithValidation() {
    Map<ParamCmd, String> params = Map.of(
      ParamCmd.TEXT, "Test",
      ParamCmd.OPTIMIZE, "40",  // Valid for CRTRPGMOD
      ParamCmd.CMD, "invalid"
    );

    TargetKey key = new TargetKey("MYLIB.RPGHELLO.module.rpgle")
        .putAll(params);

    // Invalid param (e.g., for CRTRPGMOD) should be rejected silently in putAll
    assertEquals("", key.get(ParamCmd.CMD)); // Not added
  }

  /*
   * Exception tests
   */

  @Test
  void testInvalidKeyThrowsException() {
    assertThrows(IllegalArgumentException.class, () -> new TargetKey("INVALID")); // Wrong parts
    assertThrows(IllegalArgumentException.class, () -> new TargetKey("MYLIB..PGM.RPGLE")); // Empty object
    assertThrows(IllegalArgumentException.class, () -> new TargetKey("MYLIB.HELLO.INVALID.RPGLE")); // Bad object type
    assertThrows(IllegalArgumentException.class, () -> new TargetKey("MYLIB.HELLO.PGM.INVALID")); // Bad source type
  }

  @Test
  void testNeedsRebuildLogic() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE");

    // No timestamps: rebuild
    assertTrue(key.needsRebuild());

    // Set timestamps: source newer -> rebuild
    Timestamp oldBuild = Timestamp.valueOf("2023-01-01 00:00:00");
    Timestamp newEdit = Timestamp.valueOf("2023-01-02 00:00:00");
    
    key.setLastBuild(oldBuild).setLastEdit(newEdit);

    assertTrue(key.needsRebuild());

    // Build newer: no rebuild
    key.setLastEdit(Timestamp.valueOf("2023-01-01 00:00:00"));
    assertFalse(key.needsRebuild());
  }

  @Test
  void testDefaultParamsApplied() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE");

    assertEquals("MYLIB/QRPGLESRC", key.get(ParamCmd.SRCFILE));
    assertEquals("HELLO", key.get(ParamCmd.SRCMBR));
    assertEquals("*YES", key.get(ParamCmd.REPLACE)); // From Utilities
    assertEquals("CRTBNDRPG PGM(*CURLIB/HELLO) SRCFILE(MYLIB/QRPGLESRC) SRCMBR(HELLO) OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES)", key.getCommandString()); // Partial match
  }

  @Test
  void testScapedParams() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE")
        .put(ParamCmd.TEXT, "Test Program")
        .put(ParamCmd.SRCSTMF, "/source/route");

    assertEquals("''Test Program''", key.get(ParamCmd.TEXT));
    assertEquals("''/source/route''", key.get(ParamCmd.SRCSTMF));
  }

}
