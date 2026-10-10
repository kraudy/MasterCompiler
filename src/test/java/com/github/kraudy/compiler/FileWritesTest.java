package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** Files a program can change, read from its source (protectedLibs). No IBM i. */
public class FileWritesTest {

  @Test
  void test_Fixed_Form_FSpecs() {
    Map<String, TreeSet<String>> w = FileWrites.parse(Arrays.asList(
        "     FCUSTMAST  UF   E           K DISK",
        "     FORDHIST   O    E           K DISK",
        "     FITEMS     IF A E           K DISK",
        "     FRATES     IF   E           K DISK",
        "     FQSYSPRT   O    F  132        PRINTER",
        "     F*OLDFILE  UF   E           K DISK",
        "",
        "     C                   EVAL      X = 1"));
    assertEquals("[update]", w.get("CUSTMAST").toString());
    assertEquals("[output]", w.get("ORDHIST").toString());
    assertEquals("[output]", w.get("ITEMS").toString(), "A in column 20 adds records");
    assertNull(w.get("RATES"), "input only");
    assertNull(w.get("QSYSPRT"), "printer, not a database file");
    assertEquals(3, w.size());
  }

  @Test
  void test_Free_Form_And_Sql() {
    Map<String, TreeSet<String>> w = FileWrites.parse(Arrays.asList(
        "**free",
        "dcl-f custmast usage(*update:*delete) keyed;",
        "dcl-f table3 usage(*output)",
        "      extfile('LIB1/TABLE3');",
        "dcl-f rates keyed;",
        "dcl-f report printer(132) usage(*output);",
        "     for i = 1 to 10;",
        "exec sql insert into ORDHIST values(:x);",
        "exec sql update LIB1.ITEMS i set qty = 0 where id = :id;",
        "exec sql delete from TEMPWORK;",
        "exec sql select * into :r from RATES;",
        "// exec sql delete from COMMENTED;"));
    assertEquals("[delete, update]", w.get("CUSTMAST").toString());
    assertEquals("[output]", w.get("LIB1/TABLE3").toString(), "EXTFILE names the file opened");
    assertNull(w.get("RATES"));
    assertNull(w.get("REPORT"));
    assertEquals("[output]", w.get("ORDHIST").toString());
    assertEquals("[update]", w.get("LIB1/ITEMS").toString());
    assertEquals("[delete]", w.get("TEMPWORK").toString());
    assertNull(w.get("COMMENTED"));
    assertEquals(5, w.size(), w.toString());
  }
}
