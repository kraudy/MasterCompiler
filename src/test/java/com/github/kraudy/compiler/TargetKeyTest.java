package com.github.kraudy.compiler;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;

public class TargetKeyTest {
  @Test
  void testValidKeyParsing() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE");

    assertEquals("MYLIB/HELLO", key.getQualifiedObject());
    assertEquals("HELLO", key.getObjectName());
    assertEquals("*PGM", key.getObjectType());
    assertEquals("QRPGLESRC", key.getSourceFile()); // Default from SourceType
    assertEquals("HELLO", key.getSourceName()); // Defaults to object name
    assertEquals("CRTBNDRPG", key.getCompilationCommand().name()); // From pattern
    assertTrue(key.needsRebuild()); // No timestamps yet
  }

  @Test
  void testPgmRgpleCommand() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE");

    key.put(ParamCmd.PGM, "MYLIB/HELLO");
    key.put(ParamCmd.SRCSTMF, "/home/sources/HELLO.RPGLE");
    key.put(ParamCmd.DFTACTGRP, "*NO");
    key.put(ParamCmd.ACTGRP, "QILE");
    key.put(ParamCmd.STGMDL, "*SNGLVL");
    key.put(ParamCmd.OPTION, "*EVENTF");
    key.put(ParamCmd.DBGVIEW, "*SOURCE");
    key.put(ParamCmd.REPLACE, "*YES");
    key.put(ParamCmd.USRPRF, "*USER");
    key.put(ParamCmd.TGTRLS, "V7R5M0");
    key.put(ParamCmd.PRFDTA, "*NOCOL");
    key.put(ParamCmd.TGTCCSID, "*JOB");

    String cmd = key.getCommandString();
    assertEquals(
      "CRTBNDRPG PGM(MYLIB/HELLO) SRCSTMF(''/home/sources/HELLO.RPGLE'') " +  
      "DFTACTGRP(*NO) ACTGRP(QILE) STGMDL(*SNGLVL) OPTION(*EVENTF) DBGVIEW(*SOURCE) REPLACE(*YES) USRPRF(*USER) " +
      "TGTRLS(V7R5M0) PRFDTA(*NOCOL) TGTCCSID(*JOB)", cmd);
  }

  @Test
  void test_Pgm_Rgp_Command() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPG");

    key.put(ParamCmd.PGM, "MYLIB/HELLO");
    key.put(ParamCmd.SRCFILE, "MYLIB/PRACTICAS");
    key.put(ParamCmd.SRCMBR, "HELLO");
    key.put(ParamCmd.TEXT, "hello!");
    key.put(ParamCmd.OPTION, "*LSTDBG");
    key.put(ParamCmd.GENOPT, "*LIST");
    key.put(ParamCmd.REPLACE, "*YES");
    key.put(ParamCmd.TGTRLS, "*CURRENT");
    key.put(ParamCmd.USRPRF, "*USER");

    String cmd = key.getCommandString();
    assertEquals(
      "CRTRPGPGM PGM(MYLIB/HELLO) SRCFILE(MYLIB/PRACTICAS) SRCMBR(HELLO) " +
      "TEXT(''hello!'') OPTION(*LSTDBG) GENOPT(*LIST) REPLACE(*YES) TGTRLS(*CURRENT) USRPRF(*USER)", cmd);
  }

  @Test
  void testPgmSqlRgpleCommand() {
    TargetKey key = new TargetKey("MYLIB.SQLHELLO.PGM.SQLRPGLE");

    key.put(ParamCmd.OBJ, "*LIBL/SQLHELLO");
    key.put(ParamCmd.SRCSTMF, "/home/sources/SQLHELLO.SQLRPGLE");
    key.put(ParamCmd.COMMIT, "*NONE");
    key.put(ParamCmd.OBJTYPE, "*PGM");
    key.put(ParamCmd.TEXT, "Sqlrpgle compilation test");
    key.put(ParamCmd.OPTION, "*EVENTF");
    key.put(ParamCmd.TGTRLS, "V7R5M0");
    key.put(ParamCmd.REPLACE, "*YES");
    key.put(ParamCmd.DBGVIEW, "*SOURCE");
    key.put(ParamCmd.USRPRF, "*USER");
    key.put(ParamCmd.CVTCCSID, "*JOB");


    String cmd = key.getCommandString();
    assertEquals(
      "CRTSQLRPGI OBJ(*CURLIB/SQLHELLO) SRCSTMF(''/home/sources/SQLHELLO.SQLRPGLE'') " +
      "COMMIT(*NONE) OBJTYPE(*PGM) TEXT(''Sqlrpgle compilation test'') OPTION(*EVENTF) TGTRLS(V7R5M0) REPLACE(*YES) " +
      "DBGVIEW(*SOURCE) USRPRF(*USER) CVTCCSID(*JOB)", cmd);
  }

  @Test
  void testModRgpleCommand() {
    TargetKey key = new TargetKey("MYLIB.MODHELLO.MODULE.RPGLE");

    key.put(ParamCmd.MODULE, "MYLIB/MODHELLO");
    key.put(ParamCmd.SRCSTMF, "/home/sources/MODHELLO.RPGLE");
    key.put(ParamCmd.OPTION, "*EVENTF");
    key.put(ParamCmd.DBGVIEW, "*SOURCE");
    key.put(ParamCmd.REPLACE, "*YES");
    key.put(ParamCmd.TGTRLS, "V7R5M0");
    key.put(ParamCmd.TGTCCSID, "*JOB");


    String cmd = key.getCommandString();
    assertEquals(
      "CRTRPGMOD MODULE(MYLIB/MODHELLO) SRCSTMF(''/home/sources/MODHELLO.RPGLE'') " +
      "OPTION(*EVENTF) DBGVIEW(*SOURCE) REPLACE(*YES) TGTRLS(V7R5M0) TGTCCSID(*JOB)", cmd);
  }

  @Test
  void testDspfDdsCommand() {
    TargetKey key = new TargetKey("MYLIB.DSPHELLO.DSPF.DDS");

    key.put(ParamCmd.FILE, "MYLIB/DSPHELLO");
    key.put(ParamCmd.SRCFILE, "MYLIB/QDSPFSRC");
    key.put(ParamCmd.SRCMBR, "DSPHELLO");
    key.put(ParamCmd.OPTION, "*EVENTF");
    key.put(ParamCmd.REPLACE, "*YES");

    String cmd = key.getCommandString();
    assertEquals(
      "CRTDSPF FILE(MYLIB/DSPHELLO) SRCFILE(MYLIB/QDSPFSRC) SRCMBR(DSPHELLO) OPTION(*EVENTF) REPLACE(*YES)", cmd);
  }

  @Test
  void testTableSqlCommand() {
    TargetKey key = new TargetKey("MYLIB.SQLHELLO.TABLE.SQL");

    key.put(ParamCmd.SRCSTMF, "/home/sources/SQLHELLO.SQL");
    key.put(ParamCmd.COMMIT, "*NONE");
    key.put(ParamCmd.OPTION, "*LIST");
    key.put(ParamCmd.TGTRLS, "V7R5M0");
    key.put(ParamCmd.DBGVIEW, "*SOURCE");


    String cmd = key.getCommandString();
    assertEquals(
      "RUNSQLSTM SRCSTMF(''/home/sources/SQLHELLO.SQL'') COMMIT(*NONE) OPTION(*LIST) TGTRLS(V7R5M0) DBGVIEW(*SOURCE)", cmd);
  }

  @Test
  void testSrvPgmBndCommand() {
    TargetKey key = new TargetKey("MYLIB.SRVHELLO.SRVPGM.BND");

    key.put(ParamCmd.SRVPGM, "MYLIB/SRVHELLO");
    key.put(ParamCmd.MODULE, "*LIBL/MODHELLO1 *LIBL/MODHELLO2");
    key.put(ParamCmd.SRCSTMF, "/home/sources/SRVHELLO.BND");
    key.put(ParamCmd.BNDSRVPGM, "*NONE");
    key.put(ParamCmd.OPTION, "*EVENTF");
    key.put(ParamCmd.REPLACE, "*YES");
    key.put(ParamCmd.TGTRLS, "V7R5M0");
   

    String cmd = key.getCommandString();
    assertEquals(
      "CRTSRVPGM SRVPGM(MYLIB/SRVHELLO) MODULE(*LIBL/MODHELLO1 *LIBL/MODHELLO2) SRCSTMF(''/home/sources/SRVHELLO.BND'') " +
      "BNDSRVPGM(*NONE) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(V7R5M0)", cmd);
  }

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
    key.setLastBuild(oldBuild);
    key.setLastEdit(newEdit);
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
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE");
    key.put(ParamCmd.TEXT, "Test Program");
    key.put(ParamCmd.SRCSTMF, "/source/route");

    assertEquals("''Test Program''", key.get(ParamCmd.TEXT));
    assertEquals("''/source/route''", key.get(ParamCmd.SRCSTMF));
  }

}
