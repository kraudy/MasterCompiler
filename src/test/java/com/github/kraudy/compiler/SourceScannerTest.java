package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.kraudy.compiler.SourceScanner.CandidateSource;

public class SourceScannerTest {

  @TempDir
  Path tempDir;

  @Test
  void findsRecognizedSourcesAndSkipsOthers() throws Exception {
    Path qrpg = tempDir.resolve("QRPGLESRC");
    Files.createDirectories(qrpg);
    Files.write(qrpg.resolve("HELLO.pgm.rpgle"), "x".getBytes(StandardCharsets.UTF_8));
    Files.write(qrpg.resolve("SRV_MSG_P.include.RPGLE"), "dcl-pr x;".getBytes(StandardCharsets.UTF_8));
    Files.write(qrpg.resolve("notes.md"), "skip".getBytes(StandardCharsets.UTF_8));
    Files.write(tempDir.resolve("build.yaml"), "targets: {}".getBytes(StandardCharsets.UTF_8));

    SourceScanner scanner = new SourceScanner(null, false);
    List<CandidateSource> found = scanner.scan(tempDir.toString());

    assertEquals(1, found.size());
    assertEquals("HELLO.pgm.rpgle", found.get(0).fileName);
    assertEquals("QRPGLESRC/HELLO.pgm.rpgle", found.get(0).relativePath);
  }
}
