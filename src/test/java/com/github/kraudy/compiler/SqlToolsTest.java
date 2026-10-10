package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The sql tool's statement check (the connection itself is read only too). No IBM i. */
public class SqlToolsTest {

  @Test
  void test_Read_Only_Statements_Pass() {
    assertEquals("select * from CUSTMAST", SqlTools.checkReadOnly("  select * from CUSTMAST;"));
    assertEquals("VALUES CURRENT DATE", SqlTools.checkReadOnly("-- today\nVALUES CURRENT DATE"));
    assertTrue(SqlTools.checkReadOnly("/* recent */ WITH X AS (SELECT 1 FROM SYSIBM.SYSDUMMY1) SELECT * FROM X").startsWith("WITH"));
    assertTrue(SqlTools.checkReadOnly("SELECT ';' FROM SYSIBM.SYSDUMMY1").contains("';'"), "a ; inside a literal is fine");
  }

  @Test
  void test_Writes_And_Several_Statements_Refused() {
    assertThrows(IllegalArgumentException.class, () -> SqlTools.checkReadOnly("DELETE FROM CUSTMAST"));
    assertThrows(IllegalArgumentException.class, () -> SqlTools.checkReadOnly("update CUSTMAST set X = 1"));
    assertThrows(IllegalArgumentException.class, () -> SqlTools.checkReadOnly("CALL QSYS2.QCMDEXC('DLTLIB X')"));
    assertThrows(IllegalArgumentException.class, () -> SqlTools.checkReadOnly("SELECT 1 FROM SYSIBM.SYSDUMMY1; DROP TABLE X"));
    assertThrows(IllegalArgumentException.class, () -> SqlTools.checkReadOnly("-- just a comment"));
  }
}
