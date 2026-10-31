package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/* --call: the parts that need no IBM i (names, parameters, the answer's text) and the options around it */
public class CallRunnerTest {

  @Test
  void programNames() {
    CallRunner.Program qualified = CallRunner.parse("art200lib/rundump");
    assertEquals("ART200LIB", qualified.library);
    assertEquals("RUNDUMP", qualified.name);
    assertEquals("ART200LIB/RUNDUMP", qualified.asString());
    CallRunner.Program bare = CallRunner.parse("RUNDUMP");
    assertNull(bare.library);
    assertEquals("*LIBL/RUNDUMP", bare.asString());
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parse("A/B/C"));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parse("LIB/TOOLONGPROGRAM"));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parse("LIB/PGM'); DLTLIB LIB(X"));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parse(""));
  }

  @Test
  void parametersAreCallPrograms() {
    assertEquals(0, CallRunner.parameters(null).size());
    assertEquals(0, CallRunner.parameters("  ").size());
    assertEquals(2, CallRunner.parameters("[{\"type\":\"char\",\"length\":10,\"value\":\"A\"},{\"type\":\"int\",\"bytes\":4}]").size());
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parameters("{\"type\":\"char\"}"));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parameters("[{\"type\":\"float\"}]"));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parameters("[\"A\"]"));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.parameters("not json"));
  }

  @Test
  void libraryList() {
    assertEquals(List.of("ART200LIB", "QGPL", "QTEMP"), CallRunner.libraries(" art200lib qgpl,qtemp "));
    assertThrows(IllegalArgumentException.class, () -> CallRunner.libraries("LIB1 LIB2);DLTLIB(X"));
  }

  @Test
  void answerText() throws Exception {
    ObjectNode result = (ObjectNode) new ObjectMapper().readTree("{\"program\":\"LIB/RUNDUMP\",\"job\":\"123456/USER/QZRCSRVS\","
        + "\"success\":true,\"milliseconds\":45,\"parameters\":[\"OK\"],\"messages\":[\"CPF9898 (sev 0) done\"],"
        + "\"spool\":[{\"file\":\"QPRINT\",\"number\":1,\"totalLines\":2,\"lines\":[\"dump 1\",\"dump 2\"]}]}");
    String text = CallRunner.format(result);
    assertTrue(text.startsWith("LIB/RUNDUMP: ended in 45 ms, job 123456/USER/QZRCSRVS"), text);
    assertTrue(text.contains("parameter 1: OK"), text);
    assertTrue(text.contains("message: CPF9898 (sev 0) done"), text);
    assertTrue(text.contains("--- QPRINT (1), 2 lines\ndump 1\ndump 2"), text);

    ObjectNode failed = (ObjectNode) new ObjectMapper().readTree("{\"program\":\"LIB/P\",\"success\":false,\"milliseconds\":3,"
        + "\"spoolNote\":\"job ending\"}");
    String failedText = CallRunner.format(failed);
    assertTrue(failedText.contains("ended with an error"), failedText);
    assertTrue(failedText.endsWith("Note: job ending"), failedText);
  }

  @Test
  void optionsAroundCall() {
    ArgParser ok = new ArgParser(new String[] {"--call", "LIB/PGM", "--call-params", "[{\"type\":\"char\",\"length\":3}]",
        "--libl", "LIB1 LIB2", "--curlib", "LIB1"});
    ok.validate();
    assertEquals("LIB/PGM", ok.getCall());
    assertEquals("LIB1 LIB2", ok.getLibl());

    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call-params", "[]"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call", "A/B/C"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call", "LIB/PGM", "--call-params", "oops"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call", "LIB/PGM", "--libl", "A);B"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call", "LIB/PGM", "--scan", "."}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call", "LIB/PGM", "--diff"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--call", "LIB/PGM", "--remote"}).validate(),
        "--remote needs the project it uploads");
    assertThrows(IllegalArgumentException.class,
        () -> new ArgParser(new String[] {"--call", "LIB/PGM", "--remote", "--project", ".", "--json", "x.json"}).validate());
    new ArgParser(new String[] {"--call", "LIB/PGM", "--remote", "--project", "."}).validate();
    /* MC on the IBM i gets --project of the uploaded copy: --call accepts it */
    new ArgParser(new String[] {"--call", "LIB/PGM", "--project", "."}).validate();
  }

  @Test
  void dryRunCallsNothing() {
    ArgParser parser = new ArgParser(new String[] {"--call", "lib/pgm", "--libl", "lib qgpl", "--curlib", "lib", "--dry-run"});
    parser.validate();
    String text = CallRunner.dryRun(parser);
    assertTrue(text.contains("LIB/PGM"), text);
    assertTrue(text.contains("nothing was called"), text);
  }

  @Test
  void targetOption() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mc-target");
    try {
      ArgParser ok = new ArgParser(new String[] {"--scan", dir.toString(), "--target", "DUMPSTATIC.SRVPGM,RUNDUMP"});
      ok.validate();
      assertEquals("DUMPSTATIC.SRVPGM,RUNDUMP", ok.getTarget());
      assertThrows(IllegalArgumentException.class,
          () -> new ArgParser(new String[] {"--scan", dir.toString(), "--target", "A", "--diff"}).validate());
      assertThrows(IllegalArgumentException.class,
          () -> new ArgParser(new String[] {"--scan", dir.toString(), "--target", "A", "--since", "HEAD"}).validate());
      assertThrows(IllegalArgumentException.class,
          () -> new ArgParser(new String[] {"--scan", dir.toString(), "--target", "A B"}).validate());
      assertThrows(IllegalArgumentException.class,
          () -> new ArgParser(new String[] {"--scan", dir.toString(), "--target", "A", "--mcp"}).validate());
    } finally {
      java.nio.file.Files.deleteIfExists(dir);
    }
  }
}
