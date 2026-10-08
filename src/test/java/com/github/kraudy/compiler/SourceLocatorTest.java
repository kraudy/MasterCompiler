package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Copy directives find_source follows into copybooks. No IBM i. */
public class SourceLocatorTest {

  @Test
  void test_Copy_Directives() {
    List<String[]> copies = SourceLocator.copyDirectives(Arrays.asList(
        "**free",
        "/copy qcpysrc,custds",
        "/INCLUDE APPLIB/QRPGLESRC,ORDPR",
        "      /COPY DATEPR",
        "// /copy commented,out",
        "/copy '/home/dev/inc/x.rpgleinc'",
        "     C*/COPY FIXEDCMT",
        "exec sql include sqlca_ext;"), "QSRC");

    assertEquals(4, copies.size());
    assertArrayEquals(new String[] { null, "QCPYSRC", "CUSTDS" }, copies.get(0));
    assertArrayEquals(new String[] { "APPLIB", "QRPGLESRC", "ORDPR" }, copies.get(1));
    assertArrayEquals(new String[] { null, "QRPGLESRC", "DATEPR" }, copies.get(2), "no file: QRPGLESRC");
    assertArrayEquals(new String[] { null, "QSRC", "SQLCA_EXT" }, copies.get(3), "SQL INCLUDE: the source's own file");
  }

  @Test
  void test_Missing_Member_Messages() {
    assertEquals("member ORD1 not found in MYLIB/QRPGLESRC",
        LibraryImporter.missing("MYLIB", Arrays.asList("QRPGLESRC/ORD1")));
    assertEquals("no member matching ORD* in MYLIB/QRPGLESRC",
        LibraryImporter.missing("MYLIB", Arrays.asList("QRPGLESRC/ORD*")));
    assertEquals("source file MYLIB/QDDSSRC not found, or it has no members",
        LibraryImporter.missing("MYLIB", Arrays.asList("QDDSSRC")));
    assertEquals("no source members in library MYLIB", LibraryImporter.missing("MYLIB", Arrays.asList("*")));
  }
}
