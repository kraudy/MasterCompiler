package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
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

  /* 4. download_file: byte for byte into .mc/results, a name never leaves it, a short copy is refused */
  @Test
  void downloadIsExactAndStaysInResults() throws Exception {
    byte[] data = { 0x00, (byte) 0xC1, (byte) 0xFF, 0x0A, 0x25 };
    FileDownload.Source source = new FileDownload.Source() {
      @Override public long[] stat(String path) { return path.equals("/home/U/probe.bin") ? new long[] { data.length, 0 } : null; }
      @Override public void get(String path, File local) throws Exception { Files.write(local.toPath(), data); }
    };
    ObjectNode args = new ObjectMapper().createObjectNode().put("path", "/home/U/probe.bin");
    ObjectNode result = FileDownload.handle(args, tempDir.toFile(), source);
    assertArrayEquals(data, Files.readAllBytes(tempDir.resolve(".mc/results/probe.bin")));
    assertEquals(".mc/results/probe.bin", result.path("file").asText());
    assertEquals(5, result.path("bytes").asLong());

    args.put("name", "../escape.bin");
    assertThrows(IllegalArgumentException.class, () -> FileDownload.handle(args, tempDir.toFile(), source));
    args.remove("name");
    args.put("path", "/home/U/missing.bin");
    assertThrows(IllegalArgumentException.class, () -> FileDownload.handle(args, tempDir.toFile(), source));

    FileDownload.Source shortCopy = new FileDownload.Source() {
      @Override public long[] stat(String path) { return new long[] { 99, 0 }; }
      @Override public void get(String path, File local) throws Exception { Files.write(local.toPath(), data); }
    };
    args.put("path", "/home/U/other.bin");
    assertThrows(IllegalStateException.class, () -> FileDownload.handle(args, tempDir.toFile(), shortCopy));
    assertFalse(Files.exists(tempDir.resolve(".mc/results/other.bin")), "nothing under its name");
  }

  private static String read(Path file) throws Exception {
    return new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
  }
}
