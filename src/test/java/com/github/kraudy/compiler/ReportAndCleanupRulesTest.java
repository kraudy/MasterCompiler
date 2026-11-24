package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** v0.3.44: the source file check, the summary's words, what keeps a remote path and what start-up prunes. No IBM i. */
public class ReportAndCleanupRulesTest {

  @Test
  void theSourceFileIsLookedForWhereIbmIResolvesIt() {
    TargetKey explicit = new TargetKey("ROBKRAUDYB.BTID.DSPF.DDS");
    assertEquals("'ROBKRAUDYB'", Migrator.sourceFileSchema(explicit), "a library named: only there, on the list or not");

    TargetKey curlib = new TargetKey("curlib.btid.dspf.dds");
    assertTrue(Migrator.sourceFileSchema(curlib).contains("TYPE = 'CURRENT'"), "*CURLIB: the current library only");

    TargetKey libl = new TargetKey("ROBKRAUDYB.BTID.DSPF.DDS");
    libl.setLibrary("*LIBL");
    String first = Migrator.sourceFileSchema(libl);
    assertTrue(first.contains("ORDER BY L.ORDINAL_POSITION FETCH FIRST 1 ROW ONLY"), "*LIBL: the first library that has it");
    assertTrue(first.contains("'" + libl.getSourceFile() + "'"), first);
  }

  @Test
  void aBuiltTargetsMessagesAreDiagnosticsNotErrors() {
    BuildReport.TargetResult built = result(BuildReport.BUILT, 30);
    assertEquals("1 diagnostic, max severity 30", BuildReport.messageCount(built));

    BuildReport.TargetResult failed = result(BuildReport.FAILED, 30, 40);
    assertEquals("2 errors, max severity 40", BuildReport.messageCount(failed));
  }

  @Test
  void aRemotePathStaysWhileAnMcRunsFromItOrNobodyKnows() {
    String ps = "/QOpenSys/usr/bin/sh -c ps\n"
        + "java -jar /home/ME/mc/.mc/MasterCompiler-abc.jar --mcp --project /home/ME/mc/shop\n"
        + "vi /home/ME/mc/other/notes.txt\n";
    assertEquals(SshMcpProxy.Use.RUNNING, SshMcpProxy.use(ps, "/home/ME/mc/.mc/MasterCompiler-abc.jar"));
    assertEquals(SshMcpProxy.Use.RUNNING, SshMcpProxy.use(ps, "/home/ME/mc/shop"));
    assertEquals(SshMcpProxy.Use.FREE, SshMcpProxy.use(ps, "/home/ME/mc/sh"), "a prefix of another name is not that path");
    assertEquals(SshMcpProxy.Use.FREE, SshMcpProxy.use(ps, "/home/ME/mc/other"), "no java -jar there");
    assertNotNull(SshMcpProxy.Use.UNKNOWN.why, "not knowing keeps the path, and says why");
    assertNull(SshMcpProxy.Use.FREE.why);
  }

  @Test
  void startUpPrunesOnlyOtherJarsNobodyUsesAndStaleParts() {
    long now = System.currentTimeMillis() / 1000;
    long[] old = { 1, now - SshMcpProxy.UNUSED_JAR_SECONDS - 5 };
    long[] recent = { 1, now - 10 };

    SshMcpProxy.PruneWhen part = rule("MasterCompiler-abc.jar.part");
    assertTrue(part.test("/x", new long[] { 1, now - SshMcpProxy.STALE_PART_SECONDS - 5 }, null), "a cut upload's piece");
    assertFalse(part.test("/x", recent, null), "an upload still running keeps its piece");

    String jar = "/home/ME/mc/.mc/MasterCompiler-0123abcd.jar";
    SshMcpProxy.PruneWhen other = rule("MasterCompiler-0123abcd.jar");
    assertTrue(other.test(jar, old, "sshd: ME\n"), "not taken for a day and no MC runs from it");
    assertFalse(other.test(jar, recent, "sshd: ME\n"), "taken lately (claim touches it): a client may be about to start it");
    assertFalse(other.test(jar, old, "java -jar " + jar + " --mcp\n"), "an MC runs from it");
    assertFalse(other.test(jar, old, null), "ps could not be listed: kept");
    assertNull(rule("notes.txt"), "anything else stays");
  }

  private static SshMcpProxy.PruneWhen rule(String name) {
    for (Map.Entry<Pattern, SshMcpProxy.Prune> r : SshMcpProxy.PRUNED.entrySet()) {
      if (r.getKey().matcher(name).matches()) return r.getValue().when;
    }
    return null;
  }

  private static BuildReport.TargetResult result(String status, int... severities) {
    BuildReport.TargetResult t = new BuildReport.TargetResult("ROBKRAUDYB.SRV_MSG.SRVPGM.BND", status);
    t.errors = new ArrayList<BuildReport.CompileError>();
    for (int severity : severities) {
      BuildReport.CompileError e = new BuildReport.CompileError();
      e.severity = severity;
      t.errors.add(e);
    }
    return t;
  }
}
