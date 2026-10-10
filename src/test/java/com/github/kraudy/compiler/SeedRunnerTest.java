package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** seeds.yaml: where seeds may write and the SQL they run. No IBM i. */
public class SeedRunnerTest {

  @Test
  void test_Targets_Only_Current_Library_Or_SeedLibs() {
    List<String> protectedLibs = Arrays.asList("LIB4");
    assertEquals("DEVLIB/TABLE1", SeedRunner.target("table1", "DEVLIB", Collections.<String>emptyList(), protectedLibs));
    assertEquals("LIB2/TABLE2", SeedRunner.target("LIB2.TABLE2", "DEVLIB", Arrays.asList("lib2"), protectedLibs));
    assertThrows(IllegalArgumentException.class,
        () -> SeedRunner.target("LIB3/TABLE2", "DEVLIB", Collections.<String>emptyList(), protectedLibs), "not allowed");
    assertThrows(IllegalArgumentException.class,
        () -> SeedRunner.target("LIB4/TABLE2", "DEVLIB", Arrays.asList("LIB4"), protectedLibs), "protected wins");
  }

  @Test
  void test_Copy_And_Values_Statements() {
    SeedRunner.Seed copy = new SeedRunner.Seed();
    copy.to = "TABLE1";
    copy.from = "LIB1.TABLE1";
    copy.where = "COL1 = 'A01'";
    copy.set.put("col2", "'X'");
    copy.deleteWhere = "COL1 = 'A01'";
    List<String> sql = SeedRunner.statements(copy, "DEVLIB/TABLE1", Arrays.asList("COL1", "COL2", "NAME"));
    assertEquals("DELETE FROM DEVLIB/TABLE1 WHERE COL1 = 'A01'", sql.get(0));
    assertEquals("INSERT INTO DEVLIB/TABLE1 (COL1, COL2, NAME) SELECT COL1, 'X' AS COL2, NAME "
        + "FROM LIB1/TABLE1 WHERE COL1 = 'A01'", sql.get(1));

    SeedRunner.Seed row = new SeedRunner.Seed();
    row.to = "TABLE2";
    row.values = new java.util.LinkedHashMap<String, Object>();
    row.values.put("col1", 1);
    row.values.put("col2", "'test'");
    assertEquals("INSERT INTO DEVLIB/TABLE2 (COL1, COL2) VALUES (1, 'test')",
        SeedRunner.statements(row, "DEVLIB/TABLE2", Collections.<String>emptyList()).get(0));
  }
}
