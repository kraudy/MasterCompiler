package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/* run_command's failure line and listing, download_file's staged copy, and the downloads' file mode */
class CommandFailureTest {
  private static final ObjectMapper mapper = new ObjectMapper();

  private static BuildReport.JoblogMessage message(String id, int severity, String text) {
    BuildReport.JoblogMessage m = new BuildReport.JoblogMessage();
    m.id = id;
    m.severity = severity;
    m.text = text;
    return m;
  }

  @Test
  void summaryNamesTheEscapeAndEachDiagnosticOnce() {
    List<BuildReport.JoblogMessage> joblog = new ArrayList<BuildReport.JoblogMessage>(Arrays.asList(
        message("CPD5D02", 30, "Definition not found for symbol 'x'."),
        message("CPF5D04", 30, "Not authorized to service program A in library QSYS."),
        message("CPF5D04", 30, "Not authorized to service program B in library QSYS."),
        message("CPC2191", 0, "Object deleted."),
        message("CPF3C50", 40, "Program MCLBND not created.")));
    assertEquals("CPF3C50 Program MCLBND not created. Diagnostics: CPD5D02 Definition not found for symbol 'x'.; "
        + "CPF5D04 Not authorized to service program A in library QSYS. (and 1 more CPF5D04)",
        CommandFailure.summary(joblog, Collections.<BuildReport.CompileError>emptyList(), "ignored"));
    assertEquals("[SQL0443] failed", CommandFailure.summary(Collections.<BuildReport.JoblogMessage>emptyList(),
        Collections.<BuildReport.CompileError>emptyList(), "[SQL0443] failed"));
  }

  @Test
  void precompilerListingIsReadAndJoined() {
    assertEquals("MCLBAD", CommandFailure.listingName("CRTSQLCI OBJ(ROBKRAUDYB/MCLBAD) SRCSTMF('/a/b.c') COMMIT(*NONE)"));
    assertEquals("A801SQL", CommandFailure.listingName("qsys/crtsqlci obj(a801sql) srcfile(x/y)"));
    assertNull(CommandFailure.listingName("CRTPGM PGM(L/P)"));
    List<BuildReport.CompileError> listing = EventFile.parseListing(Arrays.asList(
        "MSG ID  SEV  RECORD  TEXT",
        "SQL0104  30       4  Position 12 Token SELEC was not valid. Valid tokens: (",
        "                     CL END GET SET TAG CALL DROP FREE HOLD LOCK OPEN WITH",
        "                     ALTER.",
        "SQL0312  30       4  Position 33 Variable N not defined or not usable for",
        "                     reason code 1.",
        "                    Message Summary"), null);
    assertEquals(2, listing.size());
    assertEquals("Position 12 Token SELEC was not valid. Valid tokens: ( CL END GET SET TAG CALL DROP FREE HOLD LOCK "
        + "OPEN WITH ALTER.", listing.get(0).message);
    assertEquals("Position 33 Variable N not defined or not usable for reason code 1.", listing.get(1).message);
    assertEquals(12, listing.get(0).column);
    String line = CommandFailure.summary(Arrays.asList(message("SQL9001", 30, "SQL precompile failed.")), listing, null);
    assertTrue(line.startsWith("SQL9001 SQL precompile failed. Listing: SQL0104 record 4: Position 12"), line);
  }

  @Test
  void stagedCopyCommands() {
    ObjectNode member = mapper.createObjectNode().put("member", "qtemp/qsqltemp(mclgood)");
    assertEquals("member", FileDownload.stageKind(member));
    assertEquals("CPYTOSTMF FROMMBR('/QSYS.LIB/QTEMP.LIB/QSQLTEMP.FILE/MCLGOOD.MBR') TOSTMF('/h/.mc-stage-1.txt') "
        + "STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF)", FileDownload.stageCommand(member, "/h/.mc-stage-1.txt"));
    assertEquals("/QSYS.LIB/LIB1.LIB/QSQDSRC.FILE/M1.MBR", FileDownload.source(mapper.createObjectNode().put("member", "LIB1/QSQDSRC/M1")));
    ObjectNode path = mapper.createObjectNode().put("path", "/home/U/it's.txt").put("toCcsid", 819);
    assertEquals("CPY OBJ('/home/U/it''s.txt') TOOBJ('/h/c') TOCCSID(819) DTAFMT(*TEXT) REPLACE(*YES)",
        FileDownload.stageCommand(path, "/h/c"));
    assertNull(FileDownload.stageKind(mapper.createObjectNode().put("path", "/a")));
    for (String wrong : new String[] { "LIB/FILE", "LIB/FILE/M') X(", "A.B/F/M", "LIB/FILE/MEMBERTOOLONG" }) {
      assertThrows(IllegalArgumentException.class, () -> FileDownload.source(mapper.createObjectNode().put("member", wrong)), wrong);
    }
    assertThrows(IllegalArgumentException.class, () -> FileDownload.ccsid(mapper.createObjectNode().put("toCcsid", "utf8")));
    assertThrows(IllegalArgumentException.class,
        () -> FileDownload.source(mapper.createObjectNode().put("member", "L/F/M").put("path", "/a")));
  }

  @Test
  void downloadsFollowTheUmask(@TempDir Path folder) throws Exception {
    assumeTrue(folder.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX file modes here (Windows)");
    File partial = DataFetch.partialFile(folder.toFile(), ".mc-download-");
    try {
      assertTrue(Files.getPosixFilePermissions(partial.toPath()).contains(PosixFilePermission.OWNER_WRITE));
      /* java.io's temp file is made 0666 & ~umask, like any file the user makes (nio's is 0600 whatever the umask) */
      File plain = new File(folder.toFile(), "plain");
      assertTrue(plain.createNewFile());
      assertEquals(Files.getPosixFilePermissions(plain.toPath()), Files.getPosixFilePermissions(partial.toPath()));
    } finally {
      partial.delete();
    }
  }
}
