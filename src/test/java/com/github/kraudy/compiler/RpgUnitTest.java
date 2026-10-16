package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/* RUCALLTST's XML and completion message into the build report's tests */
public class RpgUnitTest {

  @Test
  void readsTheJenkinsXml() throws Exception {
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
        + "<testsuite errors=\"1\" failures=\"1\" hostname=\"SYS1\" id=\"0\" name=\"LIB1/TESTPGM1\" tests=\"3\">\n"
        + "  <testcase name=\"test_ok\" assertions=\"2\" classname=\"TESTPGM1\" time=\"0.001\" timeUnit=\"s\">\n  </testcase>\n"
        + "  <testcase name=\"test_total\" assertions=\"1\" classname=\"TESTPGM1\" time=\"0.002\" timeUnit=\"s\">\n"
        + "    <failure message=\"Expected 12.50, but was 0.00.\">\n<![CDATA[test_total (TESTPGM1->TESTPGM1:31)\n]]></failure>\n"
        + "  </testcase>\n"
        + "  <testcase name=\"test_sql\" assertions=\"0\" classname=\"TESTPGM1\" time=\"0.003\" timeUnit=\"s\">\n"
        + "    <error message=\"Decimal data error.\" type=\"MCH1202\">\ntest_sql (TESTPGM1->TESTPGM1:44)\n</error>\n"
        + "  </testcase>\n</testsuite>";
    BuildReport.TestRun run = RpgUnit.parseXml(xml);
    assertEquals("xml", run.source);
    assertEquals(3, run.run.intValue());
    assertEquals(3, run.assertions.intValue());
    assertEquals(1, run.failures.intValue());
    assertEquals(1, run.errors.intValue());
    assertEquals("error", run.outcome);
    assertEquals(2, run.failed.size());
    assertEquals("test_total", run.failed.get(0).test);
    assertEquals("failure", run.failed.get(0).kind);
    assertEquals("Expected 12.50, but was 0.00.", run.failed.get(0).message);
    assertEquals("test_total (TESTPGM1->TESTPGM1:31)", run.failed.get(0).at);
    assertEquals("MCH1202 Decimal data error.", run.failed.get(1).message);
  }

  @Test
  void readsExpectedAndActual() throws Exception {
    String xml = "<testsuite tests=\"1\"><testcase name=\"test_total\" assertions=\"1\">"
        + "<failure message=\"COL3 of the test row\"><![CDATA[\nCallstack:\n  test_total (TESTPGM1->TESTPGM1:31)\n\n"
        + "Expected:\n  12.50\n\nActual:\n  0.00\n]]></failure></testcase></testsuite>";
    BuildReport.TestCase t = RpgUnit.parseXml(xml).failed.get(0);
    assertEquals("test_total (TESTPGM1->TESTPGM1:31)", t.at);
    assertEquals("12.50", t.expected);
    assertEquals("0.00", t.actual);
  }

  @Test
  void readsTheVsCodeType() throws Exception {
    String xml = "<testsuite><testcase outcome=\"failure\" name=\"test_total\" assertions=\"1\">"
        + "<failure line=\"31\" name=\"aEqual\" message=\"COL3\"><callstack size=\"1\"><entry program=\"TESTPGM1\" "
        + "programLibrary=\"LIB1\" module=\"TESTPGM1\" moduleLibrary=\"LIB1\" procedure=\"test_total\" line=\"31\"/>"
        + "</callstack><expected>12.50</expected><actual>0.00</actual></failure></testcase></testsuite>";
    BuildReport.TestCase t = RpgUnit.parseXml(xml).failed.get(0);
    assertEquals("test_total (TESTPGM1->TESTPGM1:31)", t.at);
    assertEquals("12.50", t.expected);
    assertEquals("0.00", t.actual);
  }

  @Test
  void allPassed() throws Exception {
    BuildReport.TestRun run = RpgUnit.parseXml("<testsuite tests=\"1\"><testcase name=\"t\" assertions=\"4\"/></testsuite>");
    assertEquals("passed", run.outcome);
    assertNull(run.failed);
  }

  @Test
  void refusesADoctype() {
    try {
      RpgUnit.parseXml("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testsuite>&e;</testsuite>");
      throw new AssertionError("parsed");
    } catch (Exception expected) {
      // no external entity is ever read
    }
  }

  @Test
  void countsFromTheCompletionMessage() {
    BuildReport.TestRun run = new BuildReport.TestRun();
    assertTrue(RpgUnit.summary("FAILURE. 3 test cases, 5 assertions, 1 failure, 0 error.", run));
    assertEquals("failed", run.outcome);
    assertEquals(3, run.run.intValue());
    assertTrue(RpgUnit.summary("Success. 1 test case, 1 assertion, 0 failure, 0 error.", run));
    assertEquals("passed", run.outcome);
    assertFalse(RpgUnit.summary("Command ended normally", new BuildReport.TestRun()));
  }

  @Test
  void addsTheXmlOnlyWhenTheHookHasNone() {
    assertTrue(RpgUnit.isTestRun("RUCALLTST TSTPGM(LIB1/TESTPGM1)"));
    assertTrue(RpgUnit.isTestRun("rpgunit/rucalltst LIB1/TESTPGM1"));
    assertFalse(RpgUnit.isTestRun("CRTSRVPGM SRVPGM(LIB1/RUCALLTST)"));
    assertEquals("RUCALLTST TSTPGM(LIB1/TESTPGM1) XMLSTMF(''/tmp/x.xml'')", RpgUnit.withXml("RUCALLTST TSTPGM(LIB1/TESTPGM1)", "/tmp/x.xml"));
    assertNull(RpgUnit.withXml("RUCALLTST TSTPGM(LIB1/TESTPGM1) XMLSTMF(''/home/USER1/r.xml'')", "/tmp/x.xml"));
    assertEquals("/home/USER1/r.xml", RpgUnit.xmlPath("RUCALLTST TSTPGM(LIB1/TESTPGM1) XMLSTMF(''/home/USER1/r.xml'')", null));
    assertNull(RpgUnit.xmlPath("RUCALLTST TSTPGM(LIB1/TESTPGM1) XMLSTMF(''/home/USER1/<TSTPGM>.xml'')", null));
    assertEquals("LIB1/TESTPGM1", RpgUnit.keyword("RUCALLTST LIB1/TESTPGM1", "TSTPGM"));
  }
}
