package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/* Feedback from the v0.3.34 showcase demo (McOnSJLennon BASE36 chain), offline */
class ShowcaseFeedbackTest {

  @TempDir
  Path tempDir;

  private BuildSpec scanWithDuplicates() throws Exception {
    Path src = tempDir.resolve("QRPGLESRC");
    Files.createDirectories(src);
    for (String file : new String[] { "ORD.pgm.rpgle", "ORD.pgm.sqlrpgle", "INV.pgm.rpgle", "INV.pgm.sqlrpgle", "CUS.pgm.rpgle" }) {
      Files.write(src.resolve(file), "**free\n*inlr = *on;\n".getBytes(StandardCharsets.UTF_8));
    }
    return new SpecGenerator(null, false, false).generate(tempDir.toString(), "curlib");
  }

  /* 1. Two sources for one object: collected per object, logged once per process, in a report only when built */
  @Test
  void duplicateSourcesAreCollectedPerObject() throws Exception {
    BuildSpec spec = scanWithDuplicates();
    assertEquals(2, spec.duplicateSources.size(), spec.duplicateSources.toString());
    assertEquals(2, spec.duplicateSources.get("ORD.PGM").size());
    assertTrue(spec.warnings.stream().noneMatch(w -> w.startsWith("Two sources build")), "not repeated in every report");
  }

  @Test
  void duplicateSourcesAreLoggedOnceNotOnEveryScan() throws Exception {
    BuildSpec first = scanWithDuplicates();
    BuildSpec again = new SpecGenerator(null, false, false).generate(tempDir.toString(), "curlib");  // the next MCP call
    assertNull(SpecGenerator.logDuplicates(first), "generate already logged them, in one line");
    assertNull(SpecGenerator.logDuplicates(again), "a second scan logs nothing new");
  }

  @Test
  void reportNamesOnlyDuplicatesOfObjectsItBuilt() throws Exception {
    BuildSpec spec = scanWithDuplicates();
    List<BuildReport.TargetResult> targets = Arrays.asList(
        new BuildReport.TargetResult("ROBKRAUDY1.ORD.PGM.SQLRPGLE", BuildReport.BUILT),
        new BuildReport.TargetResult("ROBKRAUDY1.ORD.PGM.RPGLE", BuildReport.BUILT),
        new BuildReport.TargetResult("ROBKRAUDY1.INV.PGM.RPGLE", BuildReport.SKIPPED),
        new BuildReport.TargetResult("ROBKRAUDY1.CUS.PGM.RPGLE", BuildReport.BUILT));
    List<String> warnings = MasterCompiler.reportWarnings(spec, targets);
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).startsWith("Two sources build ORD *PGM: "), warnings.get(0));
    assertTrue(MasterCompiler.reportWarnings(spec, new ArrayList<BuildReport.TargetResult>()).isEmpty(),
        "nothing built: no duplicate warning");
  }

  /* 2. The spec's library list goes into a 5250 session: the command line is found on the first screen */
  @Test
  void commandLineIsTheFieldAfterTheArrow() {
    List<String> lines = new ArrayList<String>();
    for (int r = 0; r < 24; r++) lines.add(String.format("%-80s", ""));
    lines.set(2, String.format("%-80s", " Select one of the following:"));
    lines.set(19, String.format("%-80s", " ===>"));
    Screen5250.Field option = field(2 * 80 + 40, 2);
    Screen5250.Field command = field(19 * 80 + 7, 153);
    assertSame(command, ScreenTools.commandLine(lines, Arrays.asList(option, command), 80));
    assertNull(ScreenTools.commandLine(lines, Arrays.asList(option), 80), "no ===> row: no command line");
  }

  @Test
  void doctorAndNextStepsSayWhereTheLibraryListGoes() {
    assertTrue(NextSteps.text(null).contains(NextSteps.LIBRARY_LIST));
    assertTrue(NextSteps.LIBRARY_LIST.contains("screen_open"));
    assertTrue(NextSteps.LIBRARY_LIST.contains("libraryListCommand"));
  }

  private static Screen5250.Field field(int start, int length) {
    Screen5250.Field f = new Screen5250.Field();
    f.start = start;
    f.length = length;
    return f;
  }

  /* 3. impact: a binding directory is listed apart, not as a dependent to rebuild */
  @Test
  void bindingDirectoriesAreNotImpactedDependents() {
    assertEquals("bindingDirectories", McpServer.impactList(new TargetKey("CURLIB.MCDDEMO.BNDDIR.BNDDIR"), false));
    assertEquals("dependents", McpServer.impactList(new TargetKey("CURLIB.MCDHELLO.PGM.SQLRPGLE"), false));
    assertEquals("targets", McpServer.impactList(new TargetKey("CURLIB.MCDDEMO.BNDDIR.BNDDIR"), true), "asked about it");
  }

  /* 4. A service program's signature changed: callers bound to another one are found, with the rebind */
  @Test
  void callersOnAnOldSignatureAreFound() {
    for (int separator : new int[] { 0, 1 }) {  // 16 bytes each, with or without a byte between them
      byte[] current = bytes(separator, 0x11, 0x22);  // two signatures (*CURRENT and a *PRV kept by the binder source)
      assertTrue(SignatureCheck.bound(current, 2, sig(0x11)));
      assertTrue(SignatureCheck.bound(current, 2, sig(0x22)), "a signature the binder source kept is still valid");
      assertFalse(SignatureCheck.bound(current, 2, sig(0x33)), "bound to a signature it no longer has");
      assertFalse(SignatureCheck.bound(current, 2, shifted(0x11, 0x22)), "not a window across two signatures");
    }
    assertTrue(SignatureCheck.bound(bytes(0, 0x11), 1, sig(0x11)));
    assertEquals("UPDPGM PGM(LIB1/MCDHELLO) MODULE(*NONE)", String.format(SignatureCheck.REBIND.get("*PGM"), "LIB1", "MCDHELLO"));
    assertEquals("UPDSRVPGM SRVPGM(LIB1/X) MODULE(*NONE)", String.format(SignatureCheck.REBIND.get("*SRVPGM"), "LIB1", "X"));
  }

  @Test
  void onlyBuiltServiceProgramsAreChecked() {
    List<BuildReport.TargetResult> targets = Arrays.asList(
        new BuildReport.TargetResult("LIB1.MCDHELLO.PGM.SQLRPGLE", BuildReport.BUILT),
        new BuildReport.TargetResult("LIB1.MCDCALC.SRVPGM.RPGLE", BuildReport.FAILED));
    assertTrue(SignatureCheck.check(null, targets).isEmpty(), "no service program built: no query at all");
  }

  private static byte[] sig(int fill) {
    byte[] s = new byte[SignatureCheck.SIGNATURE_BYTES];
    Arrays.fill(s, (byte) fill);
    return s;
  }

  private static byte[] bytes(int separator, int... fills) {
    int stride = SignatureCheck.SIGNATURE_BYTES + separator;
    byte[] all = new byte[fills.length * stride - separator];
    for (int i = 0; i < fills.length; i++) System.arraycopy(sig(fills[i]), 0, all, i * stride, SignatureCheck.SIGNATURE_BYTES);
    return all;
  }

  /* The last byte of one signature and the first 15 of the next */
  private static byte[] shifted(int first, int second) {
    byte[] s = sig(second);
    s[0] = (byte) first;
    return s;
  }
}
