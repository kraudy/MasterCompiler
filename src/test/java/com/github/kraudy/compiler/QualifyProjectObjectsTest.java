package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Objects the build creates are referenced in their own library, not through *LIBL. */
public class QualifyProjectObjectsTest {

  @Test
  void test_Project_Names_Get_Their_Library() {
    Map<String, String> libs = new HashMap<String, String>();
    libs.put("MCDCALC", "*CURLIB");
    libs.put("FVAT", "APPLIB");

    assertEquals("*CURLIB/MCDCALC", MasterCompiler.qualifyNames("MCDCALC", libs));
    assertEquals("*CURLIB/MCDCALC", MasterCompiler.qualifyNames("*LIBL/MCDCALC", libs));
    assertEquals("*CURLIB/mcdcalc APPLIB/FVAT", MasterCompiler.qualifyNames("mcdcalc *LIBL/FVAT", libs));
    /* external objects and explicit libraries are left alone */
    assertEquals("*LIBL/QC2LE OTHER/MCDCALC", MasterCompiler.qualifyNames("*LIBL/QC2LE OTHER/MCDCALC", libs));
  }

  @org.junit.jupiter.api.Test
  void test_SqlRpg_BndDir_Goes_To_CompileOpt() {
    org.junit.jupiter.api.Assertions.assertEquals("TGTCCSID(*JOB) BNDDIR(*CURLIB/MCDDEMO *LIBL/OTHER)",
        MasterCompiler.compileOptWithBndDir("''TGTCCSID(*JOB)''", "*CURLIB/MCDDEMO OTHER"));
    org.junit.jupiter.api.Assertions.assertEquals("BNDDIR(X)", MasterCompiler.compileOptWithBndDir("BNDDIR(X)", "Y"), "kept");
  }
}
