package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The end-of-build summary of a --remote build: failed hook and messages; severity. No IBM i. */
public class RemoteSummaryTest {

  @Test
  void test_Hook_Failure_Names_The_Hook_And_Orders_Messages_By_Severity() throws Exception {
    String json = "{"
        + "\"built\":1,\"failed\":1,\"skipped\":0,\"blocked\":0,"
        + "\"targets\":[{"
        + "\"target\":\"ROBKRAUDY2.LOG100Y.MODULE.RPGLE\","
        + "\"status\":\"failed\","
        + "\"failedAt\":\"hook\","
        + "\"command\":\"CRTRPGMOD MODULE(*CURLIB/LOG100Y) SRCSTMF('LOG100Y.module.rpgle') OPTION(*EVENTF) "
        + "DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB)\","
        + "\"failedCommand\":\"CRTPROGRAM CMD(CRTPGM PGM(ROBKRAUDY2/LOG100Y) MODULE(ROBKRAUDY2/LOG100Y "
        + "ROBKRAUDY2/DUMPST) ENTMOD(ROBKRAUDY2/LOG100Y) ACTGRP(*NEW) REPLACE(*YES) BNDSRVPGM(QSYS/QC2LE))\","
        + "\"errors\":["
        + error("QPROTOSRC/APICALL.RPGLEINC", 24, "RNF7031", 0, "The name or indicator BYTE_PROVIDED is not referenced.") + ","
        + error("QPROTOSRC/APICALL.RPGLEINC", 25, "RNF7031", 0, "The name or indicator BYTE_PROVIDED is not referenced.") + ","
        + error("QPROTOSRC/APICALL.RPGLEINC", 26, "RNF7031", 0, "The name or indicator BYTE_PROVIDED is not referenced.") + ","
        + error("QPROTOSRC/APICALL.RPGLEINC", 27, "RNF7031", 0, "The name or indicator BYTE_PROVIDED is not referenced.") + ","
        + error("QPROTOSRC/APICALL.RPGLEINC", 28, "RNF7031", 0, "The name or indicator BYTE_PROVIDED is not referenced.") + ","
        + error("QPROTOSRC/APICALL.RPGLEINC", 29, "RNF7031", 0, "The name or indicator BYTE_PROVIDED is not referenced.")
        + "],"
        + "\"hookJoblog\":["
        + joblog("CPF0001", 20, "Error found on CRTPROGRAM command.") + ","
        + joblog("CPF0006", 40, "Errors occurred in command.") + ","
        + joblog("RNF7031", 0, "informational hook message") + ","
        + joblog("CPD0030", 30, "Command CRTPROGRAM in library *LIBL not found.")
        + "]"
        + "}]}";

    String summary = RemoteSummary.format(new ObjectMapper().readTree(json), 1, "PUB400.COM");

    assertTrue(summary.contains("command: CRTPROGRAM CMD("), summary);
    assertFalse(summary.contains("CRTRPGMOD"), summary);
    assertFalse(summary.contains("RNF7031"), "informational messages are not errors: " + summary);
    assertFalse(summary.contains("more compile errors"), summary);
    assertTrue(summary.contains("CPF0006"), summary);
    assertTrue(summary.contains("CPD0030"), summary);
    assertTrue(summary.contains("CPF0001"), summary);
    assertTrue(summary.contains("Errors occurred in command."), summary);
    assertTrue(summary.contains("Command CRTPROGRAM in library *LIBL not found."), summary);
    assertTrue(summary.indexOf("CPF0006") < summary.indexOf("CPD0030"), "highest severity first: " + summary);
    assertTrue(summary.indexOf("CPD0030") < summary.indexOf("CPF0001"), "highest severity first: " + summary);
  }

  @Test
  void test_Compile_Failure_Orders_Errors_By_Severity_And_Skips_Informational() throws Exception {
    String json = "{"
        + "\"built\":0,\"failed\":1,\"skipped\":0,\"blocked\":0,"
        + "\"targets\":[{"
        + "\"target\":\"ROBKRAUDY2.LOG100Y.MODULE.RPGLE\","
        + "\"status\":\"failed\","
        + "\"failedAt\":\"compile\","
        + "\"command\":\"CRTRPGMOD MODULE(*CURLIB/LOG100Y) SRCSTMF('LOG100Y.module.rpgle')\","
        + "\"errors\":["
        + error("A.RPGLE", 1, "RNF7031", 0, "info") + ","
        + error("A.RPGLE", 2, "RNF1000", 10, "warning") + ","
        + error("A.RPGLE", 3, "RNF0002", 20, "error") + ","
        + error("A.RPGLE", 4, "RNF7030", 30, "severe")
        + "]"
        + "}]}";

    String summary = RemoteSummary.format(new ObjectMapper().readTree(json), 1, "PUB400.COM");

    assertTrue(summary.contains("RNF7030"), summary);
    assertTrue(summary.contains("RNF0002"), summary);
    assertTrue(summary.contains("severe"), summary);
    assertTrue(summary.contains("error"), summary);
    assertFalse(summary.contains("RNF1000"), summary);
    assertFalse(summary.contains("RNF7031"), summary);
    assertFalse(summary.contains("more compile errors"), summary);
    assertTrue(summary.indexOf("RNF7030") < summary.indexOf("RNF0002"), "highest severity first: " + summary);
  }

  private static String error(String file, int line, String id, int severity, String message) {
    return "{\"file\":\"" + file + "\",\"line\":" + line + ",\"id\":\"" + id + "\",\"severity\":" + severity
        + ",\"message\":\"" + message + "\"}";
  }

  private static String joblog(String id, int severity, String text) {
    return "{\"id\":\"" + id + "\",\"severity\":" + severity + ",\"text\":\"" + text + "\"}";
  }
}
