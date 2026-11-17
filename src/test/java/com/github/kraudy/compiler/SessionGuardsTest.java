package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/* Items 30–34: read-only commands on protectedLibs, the sign-on budget, inventory differences, the 5250 job, the build ledger */
class SessionGuardsTest {
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final List<String> PROTECTED = Arrays.asList("PRODLIB");

  @TempDir
  File project;

  @AfterEach
  void noBudget() {
    SignOnBudget.reset();
  }

  @Test
  void displayCommandsMayNameProtectedLibraries() {
    assertNull(ReadOnlyCommands.check("DSPFFD FILE(PRODLIB/ARTICLE) OUTPUT(*PRINT)", "DEVLIB", PROTECTED));
    assertNull(ReadOnlyCommands.check("QSYS/DSPDBR FILE(PRODLIB/ARTICLE) OUTPUT(*OUTFILE) OUTFILE(*CURLIB/DBR)", "DEVLIB", PROTECTED));
    assertNull(ReadOnlyCommands.check("DSPFD FILE(PRODLIB/X) TYPE(*ATR) OUTPUT(*OUTFILE) OUTFILE(devlib/fd)", "DEVLIB", PROTECTED));
    assertNull(ReadOnlyCommands.check("DSPPGMREF PGM(PRODLIB/*ALL) OUTPUT(*OUTFILE) OUTFILE(QTEMP/REFS)", "DEVLIB", PROTECTED));
    assertNull(ReadOnlyCommands.check("DSPOBJD OBJ(PRODLIB/*ALL) OBJTYPE(*ALL) DETAIL(*FULL) OUTPUT(*PRINT)", "DEVLIB", PROTECTED));
  }

  @Test
  void anOutfileOutsideTheCurrentLibraryOrAPositionalParameterIsRefused() {
    assertNotNull(ReadOnlyCommands.check("DSPFFD FILE(PRODLIB/A) OUTPUT(*OUTFILE) OUTFILE(PRODLIB/X)", "DEVLIB", PROTECTED));
    assertNotNull(ReadOnlyCommands.check("DSPFFD FILE(PRODLIB/A) OUTPUT(*OUTFILE) OUTFILE(X)", "DEVLIB", PROTECTED), "unqualified: *LIBL");
    assertNotNull(ReadOnlyCommands.check("DSPFFD PRODLIB/A *OUTFILE PRODLIB/X", "DEVLIB", PROTECTED), "positional");
    assertNotNull(ReadOnlyCommands.check("DSPFFD FILE(PRODLIB/A) OUTFILE(*CURLIB/X) OUTFILE(PRODLIB/X)", "DEVLIB", PROTECTED), "twice");
    assertNotNull(ReadOnlyCommands.check("DSPFFD FILE(PRODLIB/A) OUTPUT(*OUTFILE) OUTFILE(*CURLIB/X)", "PRODLIB", PROTECTED),
        "the current library is protected");
    assertFalse(ReadOnlyCommands.isOne("CLRPFM FILE(PRODLIB/A)"));
    assertFalse(ReadOnlyCommands.isOne("MYLIB/DSPFFD FILE(PRODLIB/A)"), "a command of that name in another library");
    assertTrue(ReadOnlyCommands.isOne("dspffd file(PRODLIB/A)"));
    assertFalse(ReadOnlyCommands.isOne("*LIBL/DSPFFD FILE(PRODLIB/A)"), "the library list could find another DSPFFD");
    assertEquals("QSYS/DSPFFD FILE(PRODLIB/A)", ReadOnlyCommands.fromQsys("dspffd FILE(PRODLIB/A)"));
  }

  @Test
  void keywordsKeepQuotesAndNestedParentheses() {
    assertEquals("'A (B)' C", ReadOnlyCommands.keywords(" TEXT('A (B)' C) X(1)").get("TEXT"));
    assertNull(ReadOnlyCommands.keywords(" TEXT('A"));
  }

  @Test
  void theBudgetRefusesTheSignOnAfterTheLast() {
    SignOnBudget.limit(2);
    SignOnBudget.take("SSH");
    SignOnBudget.take("the build connection");
    SignOnBudget.Exhausted e = assertThrows(SignOnBudget.Exhausted.class, () -> SignOnBudget.take("a 5250 sign-on"));
    assertTrue(e.getMessage().contains("SSH; the build connection"), e.getMessage());
    assertEquals(0, SignOnBudget.status().path("left").asInt());
  }

  @Test
  void mcOnTheIbmiAsksThePcThatKeepsTheCount() throws Exception {
    SignOnBudget.limit(1);  // the PC's
    CompletableFuture<String> sent = new CompletableFuture<String>();
    SignOnBudget.askPc(sent::complete);  // this process plays MC on the IBM i too: its request goes to 'sent'
    CompletableFuture<Void> asked = CompletableFuture.runAsync(() -> SignOnBudget.take("build connection"));
    JsonNode request = mapper.readTree(sent.get(5, TimeUnit.SECONDS));
    assertEquals(SignOnBudget.METHOD, request.path("method").asText());
    SignOnBudget.askPc(null);  // the PC decides with its own count
    ObjectNode answer = SignOnBudget.decide(request);
    assertTrue(answer.path("result").path("allowed").asBoolean());
    assertTrue(SignOnBudget.isAnswerId(answer), "a client's message with this id is never passed on to MC");
    assertTrue(SignOnBudget.answer(answer));
    asked.get(5, TimeUnit.SECONDS);
    assertFalse(SignOnBudget.decide(request).path("result").path("allowed").asBoolean(), "the budget is spent");
  }

  @Test
  void inventoryDifferenceIsWhatIsNewOrCreatedAgain() throws Exception {
    JsonNode before = mapper.readTree("{\"objects\":[{\"name\":\"A\",\"type\":\"*PGM\",\"created\":\"1\"},"
        + "{\"name\":\"B\",\"type\":\"*FILE\",\"created\":\"1\"}],\"members\":[{\"file\":\"QRPGSRC\",\"member\":\"A\",\"created\":\"1\"}],"
        + "\"ifs\":{\"files\":[{\"path\":\"/tmp/x/old\"}]}}");
    JsonNode now = mapper.readTree("{\"objects\":[{\"name\":\"A\",\"type\":\"*PGM\",\"created\":\"1\"},"
        + "{\"name\":\"B\",\"type\":\"*FILE\",\"created\":\"2\"},{\"name\":\"C\",\"type\":\"*DTAARA\",\"created\":\"3\"},"
        + "{\"name\":\"V\",\"type\":\"*FILE\",\"attribute\":\"LF\",\"created\":\"3\"}],"
        + "\"members\":[{\"file\":\"QRPGSRC\",\"member\":\"A\",\"created\":\"1\"},{\"file\":\"QRPGSRC\",\"member\":\"B\",\"created\":\"2\"}],"
        + "\"ifs\":{\"files\":[{\"path\":\"/tmp/x/old\"},{\"path\":\"/tmp/x/dump.bin\"}]}}");
    JsonNode added = Inventory.diff(before, now);
    assertEquals(3, added.path("objects").size(), "B created again, C and V new");
    assertEquals("V", Inventory.deleteOrder(added.path("objects")).get(0).path("name").asText(), "the logical file first");
    assertEquals(1, added.path("members").size());
    assertEquals("/tmp/x/dump.bin", added.path("files").path(0).path("path").asText());
    assertEquals("DLTDTAARA DTAARA(DEVLIB/C)", Inventory.deleteCommand("DEVLIB", added.path("objects").path(1)));
    assertNull(Inventory.deleteCommand("DEVLIB", mapper.readTree("{\"name\":\"J\",\"type\":\"*JRN\"}")), "no command: kept");
    assertThrows(IllegalArgumentException.class, () -> Inventory.file(project, "../x"));
  }

  @Test
  void theSignOnDisplayNamesTheJob() {
    assertEquals("QPADEV000B", ScreenTools.device(Arrays.asList("01 Welcome", "02    Server name . . . :   PUB400",
        "04                      Display name. . . :   QPADEV000B")));
    assertEquals("QPADEV0012", ScreenTools.device(Arrays.asList("", "                Display . . . . . . . . :   QPADEV0012")));
    assertNull(ScreenTools.device(Collections.singletonList("Display Messages")));
    String sql = ScreenTools.jobSql("QPADEV000B", "ROBKRAUDY");
    assertTrue(sql.contains("'%/ROBKRAUDY/QPADEV000B'") && sql.contains("MCOK"), sql);
  }

  @Test
  void theLedgerKnowsWhatThisProjectBuiltAlsoWhenAHookFailedAfter() {
    BuildReport built = new BuildReport();
    BuildReport.TargetResult ok = built.add("DEVLIB.A.PGM.RPGLE", BuildReport.BUILT);
    ok.compiled = true;
    BuildReport.TargetResult hook = built.add("DEVLIB.B.PGM.RPGLE", BuildReport.FAILED);
    hook.compiled = true;
    FormerTargets.update(project, built);

    BuildReport plan = new BuildReport();
    plan.dryRun = true;
    plan.add("DEVLIB.A.PGM.RPGLE", BuildReport.PLANNED).existed = true;
    plan.add("DEVLIB.B.PGM.RPGLE", BuildReport.PLANNED).existed = true;
    plan.add("DEVLIB.C.PGM.RPGLE", BuildReport.PLANNED).existed = true;   // someone else's
    plan.add("DEVLIB.D.PGM.RPGLE", BuildReport.PLANNED).existed = false;  // new
    assertEquals(Collections.singletonList("DEVLIB.C.PGM"), FormerTargets.foreign(project, plan));
  }
}
