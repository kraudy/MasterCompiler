package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    assertEquals(1, CallRunner.parameters("[{\"type\":\"varchar\",\"length\":2000,\"value\":\"{}\"}]").size());
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
        + "\"spool\":[{\"file\":\"QPRINT\",\"number\":1,\"job\":\"123456/USER/QZRCSRVS\",\"status\":\"READY\",\"totalLines\":2,\"lines\":[\"dump 1\",\"dump 2\"]}]}");
    String text = CallRunner.format(result);
    assertTrue(text.startsWith("LIB/RUNDUMP: ended in 45 ms, job 123456/USER/QZRCSRVS"), text);
    assertTrue(text.contains("parameter 1: OK"), text);
    assertTrue(text.contains("message: CPF9898 (sev 0) done"), text);
    assertTrue(text.contains("--- QPRINT (1) of job 123456/USER/QZRCSRVS, READY, 2 lines\ndump 1\ndump 2"), text);

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
  void inquiryReplyOnlyForCalls() {
    ArgParser ok = new ArgParser(new String[] {"--call", "LIB/PGM", "--inqmsgry"});
    ok.validate();
    assertTrue(ok.isInqmsgry());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--inqmsgry"}).validate());
    /* MC on the IBM i gets it too */
    assertTrue(new ArgParser(new String[] {"--call", "L/P", "--inqmsgry", "--remote", "--project", "."}).remoteArgs("/x").contains("--inqmsgry"));
  }

  @Test
  void sqlAndFetchFromTheCommandLine() {
    ArgParser sql = new ArgParser(new String[] {"--sql", "select * from lib.probe", "--libl", "LIB QGPL", "--max-rows", "5", "--json", "r.json"});
    sql.validate();
    assertEquals(5, sql.getMaxRows());
    assertEquals(List.of("LIB", "QGPL"), SqlQuery.libraries(sql, null));
    assertEquals(List.of("CUR", "LIB"), SqlQuery.libraries(new ArgParser(new String[] {"--sql", "values 1", "--libl", "lib", "--curlib", "cur"}), null));
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--sql", "DELETE FROM LIB.T"}).validate(),
        "only queries");
    for (String effect : new String[] {"SELECT QSYS2.QCMDEXC('CRTLIB X') FROM SYSIBM.SYSDUMMY1", "values qcmdexc('x')",
        "select \"QCMDEXC\"('x') from sysibm.sysdummy1", "select ifs_write('/tmp/x', 'y') from sysibm.sysdummy1",
        "select /* a /* b */ ' */ qsys2.qcmdexc('CRTLIB X') from sysibm.sysdummy1",  // nested comment hides it from a filter that skips comments
        "select 'qcmdexc' as note from sysibm.sysdummy1"}) {  // a mention in text is refused too: the filter reads the raw text
      assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--sql", effect}).validate(), effect);
    }
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--sql", "values 1", "--fetch", "/a"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--sql", "values 1", "--call", "L/P"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--sql", "values 1", "--scan", "."}).validate());

    new ArgParser(new String[] {"--fetch", "/home/USER/probe.txt", "-o", "out"}).validate();
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--fetch", "probe.txt"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--fetch", "/home/USER/"}).validate());
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--fetch", "/a", "--remote", "--project", "."}).validate());
    assertEquals(new java.io.File("probe.txt"), DataFetch.destination("/home/USER/probe.txt", null));
    assertEquals(new java.io.File("here.txt"), DataFetch.destination("/home/USER/probe.txt", "here.txt"));
    assertEquals(new java.io.File("dir", "probe.txt"), DataFetch.destination("/home/USER/probe.txt", "dir/"));
    /* a hostile IFS file name stays one harmless name here, on any system */
    assertEquals(new java.io.File("dir", ".._.._victim.txt"), DataFetch.destination("/tmp/..\\..\\victim.txt", "dir/"));
    assertEquals(new java.io.File("fetched.file"), DataFetch.destination("/tmp/..", null));
    assertEquals(new java.io.File("C_x.txt"), DataFetch.destination("/tmp/C:x.txt", null));
  }

  @Test
  void fetchMayBeRepeatedInOrder() throws Exception {
    String output = dryRun("--fetch", "/home/USER/a.txt", "--fetch", "/home/USER/b.txt", "-o", "dir/", "--dry-run");
    int first = output.indexOf("/home/USER/a.txt");
    int second = output.indexOf("/home/USER/b.txt");
    assertTrue(first >= 0, output);
    assertTrue(second > first, output);
    assertTrue(output.contains(new File("dir", "a.txt").getPath()), output);   // \ on Windows
    assertTrue(output.contains(new File("dir", "b.txt").getPath()), output);
  }

  @Test
  void fetchManyNeedsOutputFolder() {
    assertThrows(IllegalArgumentException.class,
        () -> new ArgParser(new String[] {"--fetch", "/home/USER/a.txt", "--fetch", "/home/USER/b.txt"}).validate());
  }

  @Test
  void fetchManyNeedsOutputDirectoryNotFile() throws Exception {
    File file = File.createTempFile("mc-fetch-output", ".txt");
    try {
      assertThrows(IllegalArgumentException.class,
          () -> new ArgParser(new String[] {"--fetch", "/home/USER/a.txt", "--fetch", "/home/USER/b.txt",
              "-o", file.getAbsolutePath()}).validate());
      assertThrows(IllegalArgumentException.class,
          () -> new ArgParser(new String[] {"--fetch", "/home/USER/a.txt", "--fetch", "/home/USER/b.txt",
              "-o", file.getAbsolutePath() + File.separator}).validate());
    } finally {
      file.delete();
    }
  }

  @Test
  void fetchManyRejectsDuplicateBaseNames() {
    assertThrows(IllegalArgumentException.class,
        () -> new ArgParser(new String[] {"--fetch", "/one/probe.txt", "--fetch", "/two/probe.txt", "-o", "dir/"}).validate());
  }

  @Test
  void fetchKeepsEveryValueInOrder() {
    ArgParser parser = new ArgParser(new String[] {"--fetch", "/home/USER/a.txt", "--fetch", "/home/USER/b.txt", "-o", "dir/"});
    parser.validate();
    assertEquals(java.util.Arrays.asList("/home/USER/a.txt", "/home/USER/b.txt"), parser.getFetches());
  }

  private static String dryRun(String... args) throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
      assertEquals(0, MasterCompiler.run(args));
    } finally {
      System.setOut(original);
    }
    return new String(captured.toByteArray(), StandardCharsets.UTF_8);
  }

  @Test
  void queryRowsAsText() throws Exception {
    ObjectNode rows = (ObjectNode) new ObjectMapper().readTree("{\"columns\":[\"ID\",\"NOTE\"],\"rows\":[[\"1\",\"a\\tb\"],[\"2\",null]],"
        + "\"rowCount\":2,\"moreRows\":\"Only the first 2 rows are shown\"}");
    assertEquals("ID\tNOTE\n1\ta b\n2\t-\n2 rows. Only the first 2 rows are shown", SqlQuery.format(rows));
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
