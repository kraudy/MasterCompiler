package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** examples/demo (what the setup guide offers new users) must keep scanning into its five MCD* objects, in order. */
public class DemoProjectTest {

  @Test
  void test_Demo_Plan() throws Exception {
    BuildSpec spec = new SpecGenerator(null, false, false).generate("examples/demo", "curlib");

    assertEquals(5, spec.targets.size(), "the demo stays small: " + spec.targets.keySet());
    for (TargetKey key : spec.targets.keySet()) {
      assertTrue(key.getObjectName().startsWith("MCD"), "demo objects are named MCD*: " + key.asString());
    }
    TobiConverterTest.assertBefore(spec, "MCDCALC.MODULE", "MCDCALC.SRVPGM");
    TobiConverterTest.assertBefore(spec, "MCDCALC.SRVPGM", "MCDDEMO.BNDDIR");
    TobiConverterTest.assertBefore(spec, "MCDDEMO.BNDDIR", "MCDHELLO.PGM");
    TobiConverterTest.assertBefore(spec, "MCDITEM.TABLE", "MCDHELLO.PGM");
  }
}
