package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/* Feedback from the PUB400 capture sessions (MC v0.3.35, --mcp --ssh --single-connection), offline */
class SessionFeedbackTest {

  @TempDir
  Path tempDir;

  /* 23. MC on the IBM i keeps its sign-on count in a file the SSH server on the PC reads after it stopped */
  @Test
  void connectionCountIsKeptInTheTallyFile() throws Exception {
    Path tally = tempDir.resolve("mcp-test.opens");
    HostConnections connections = new HostConnections(HostConnections.Mode.SINGLE);
    connections.tallyTo(tally.toString());
    assertEquals("0", read(tally), "written at start: a missing file later means unknown");
    connections.opened(HostConnections.Purpose.BUILD, "1/QUSER/QZDASOINIT", "first call");
    connections.opened(HostConnections.Purpose.MONITOR, null, "stopping a command");
    assertEquals("2", read(tally));
    assertFalse(Files.exists(tempDir.resolve("mcp-test.opens.new")), "the new count is renamed over the old");
  }

  @Test
  void noTallyFileWithoutTheProperty() {
    HostConnections connections = new HostConnections(HostConnections.Mode.SHARED);
    connections.tallyTo(null);
    connections.opened(HostConnections.Purpose.BUILD, null, "first call");
    assertEquals(1, connections.totalOpens());
  }

  /* 17. A run_command is followed like a build, but has no buildId: the SSH proxy follows builds by it */
  @Test
  void runCommandProgressIsNotABuild() {
    BuildProgress command = new BuildProgress(BuildProgress.COMMAND);
    command.running("CALL PGM(HANGS)", "command", null, 0);
    ObjectNode json = command.toJson();
    assertFalse(json.has("buildId"), json.toString());
    assertEquals("run_command", json.path("tool").asText());
    assertEquals("CALL PGM(HANGS)", json.path("running").path("command").asText());
  }

  /* 15. The columns sql leaves out are named */
  @Test
  void omittedColumnsAreNamedOnTheCommandLine() {
    ObjectNode result = new ObjectMapper().createObjectNode();
    result.putArray("columns").add("A");
    result.putArray("rows");
    result.put("rowCount", 0);
    result.put("columnsOmitted", 2);
    result.putArray("omittedColumns").add("B").add("C");
    assertTrue(SqlQuery.format(result).endsWith("2 more columns not shown: B C"), SqlQuery.format(result));
  }

  /* 18. A spooled file query refused for its QTEMP work file points at the spool tool */
  @Test
  void spooledFileDataPointsAtTheSpoolTool() {
    boolean routed = false;
    for (Map.Entry<Pattern, String> route : McpServer.SQL_ROUTES.entrySet()) {
      if (route.getKey().matcher("select * from table(systools.spooled_file_data(job_name => 'x'))").find()) {
        routed = route.getValue().contains("spool tool");
      }
    }
    assertTrue(routed);
  }

  private static String read(Path file) throws Exception {
    return new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
  }
}
