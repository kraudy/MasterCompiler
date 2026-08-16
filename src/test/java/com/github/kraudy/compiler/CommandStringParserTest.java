package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CommandStringParser.ParsedCommand;

public class CommandStringParserTest {

  @Test
  void parseArrayTokens() {
    ParsedCommand parsed = CommandStringParser.parseArray(Arrays.asList(
        "CRTBNDRPG",
        "PGM(*CURLIB/HELLO)",
        "SRCSTMF('/home/x.rpgle')",
        "DFTACTGRP(*NO)",
        "ACTGRP(QILE)"));
    assertEquals(CompCmd.CRTBNDRPG, parsed.command);
    assertEquals("*CURLIB/HELLO", parsed.params.get(ParamCmd.PGM));
    assertEquals("/home/x.rpgle", parsed.params.get(ParamCmd.SRCSTMF));
    assertEquals("*NO", parsed.params.get(ParamCmd.DFTACTGRP));
    assertEquals("QILE", parsed.params.get(ParamCmd.ACTGRP));
  }

  @Test
  void parseStringWithQuotedText() {
    ParsedCommand parsed = CommandStringParser.parseString(
        "CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF('/home/x.rpgle') TEXT('Hello World')");
    assertEquals(CompCmd.CRTBNDRPG, parsed.command);
    assertEquals("/home/x.rpgle", parsed.params.get(ParamCmd.SRCSTMF));
    assertEquals("Hello World", parsed.params.get(ParamCmd.TEXT));
  }

  @Test
  void parseModuleListAndIbmQuotes() {
    ParsedCommand parsed = CommandStringParser.parseString(
        "CRTSRVPGM SRVPGM(*CURLIB/SRVHELLO) MODULE(MHELLO MBYE) SRCSTMF(''/home/sources/SRVHELLO.BND'')");
    assertEquals(CompCmd.CRTSRVPGM, parsed.command);
    assertEquals("MHELLO MBYE", parsed.params.get(ParamCmd.MODULE));
    assertEquals("/home/sources/SRVHELLO.BND", parsed.params.get(ParamCmd.SRCSTMF));
  }

  @Test
  void singleArrayElementFallsBackToStringForm() {
    ParsedCommand parsed = CommandStringParser.parseArray(Collections.singletonList(
        "CRTBNDRPG PGM(*CURLIB/HELLO) DBGVIEW(*SOURCE)"));
    assertEquals(CompCmd.CRTBNDRPG, parsed.command);
    assertEquals("*SOURCE", parsed.params.get(ParamCmd.DBGVIEW));
  }

  @Test
  void unknownCommandFails() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> CommandStringParser.parseString("NOTACMD PGM(HELLO)"));
    assertTrue(ex.getMessage().toLowerCase().contains("compilation command"));
  }

  @Test
  void paramNotOnPatternFails() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> CommandStringParser.parseArray(Arrays.asList("CRTBNDRPG", "COMMIT(*NONE)")));
    assertTrue(ex.getMessage().contains("not valid"));
  }

  @Test
  void unbalancedParensFail() {
    assertThrows(IllegalArgumentException.class,
        () -> CommandStringParser.parseString("CRTBNDRPG PGM(*CURLIB/HELLO"));
  }

  @Test
  void commandTargetMismatchFails() {
    TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
    BuildSpec.TargetSpec spec = new BuildSpec.TargetSpec();
    spec.command = CommandStringParser.CommandForm.ofArray(Arrays.asList("CRTRPGMOD", "SRCSTMF('/x')"));
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> CommandStringParser.applyCommandForm(key, spec));
    assertTrue(ex.getMessage().contains("does not match"));
  }

  @Test
  void identityMismatchFails() {
    TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
    BuildSpec.TargetSpec spec = new BuildSpec.TargetSpec();
    spec.command = CommandStringParser.CommandForm.ofArray(
        Arrays.asList("CRTBNDRPG", "PGM(*CURLIB/OTHER)"));
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> CommandStringParser.applyCommandForm(key, spec));
    assertTrue(ex.getMessage().contains("Identity"));
  }

  @Test
  void matchingIdentityIsDropped() {
    TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
    BuildSpec.TargetSpec spec = new BuildSpec.TargetSpec();
    spec.command = CommandStringParser.CommandForm.ofArray(Arrays.asList(
        "CRTBNDRPG", "PGM(*CURLIB/HELLO)", "DBGVIEW(*SOURCE)"));
    CommandStringParser.applyCommandForm(key, spec);
    assertFalse(spec.params.containsKey(ParamCmd.PGM));
    assertEquals("*SOURCE", spec.params.get(ParamCmd.DBGVIEW));
  }

  @Test
  void paramsWinOverCommand() {
    TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
    BuildSpec.TargetSpec spec = new BuildSpec.TargetSpec();
    spec.command = CommandStringParser.CommandForm.ofArray(Arrays.asList(
        "CRTBNDRPG", "DBGVIEW(*SOURCE)"));
    spec.params.put(ParamCmd.DBGVIEW, "*ALL");
    CommandStringParser.applyCommandForm(key, spec);
    assertEquals("*ALL", spec.params.get(ParamCmd.DBGVIEW));
  }

  @Test
  void crtcmdPgmIsNotIdentity() {
    TargetKey key = new TargetKey("mylib.ord100.cmd.cmd");
    BuildSpec.TargetSpec spec = new BuildSpec.TargetSpec();
    spec.command = CommandStringParser.CommandForm.ofArray(Arrays.asList(
        "CRTCMD", "PGM(ORD100)"));
    CommandStringParser.applyCommandForm(key, spec);
    assertEquals("ORD100", spec.params.get(ParamCmd.PGM));
  }

  @Test
  void emptyArrayFails() {
    assertThrows(IllegalArgumentException.class,
        () -> CommandStringParser.parseArray(Collections.<String>emptyList()));
  }

  @Test
  void toPasteableUsesCommandLineQuotes() {
    assertEquals(
        "CRTBNDRPG SRCSTMF('/home/x.rpgle') TEXT('Hello World') REPLACE(*YES)",
        CommandStringParser.toPasteable(
            "CRTBNDRPG SRCSTMF(''/home/x.rpgle'') TEXT(''Hello World'') REPLACE(*YES)"));
  }

  @Test
  void toPasteableCommandIncludesIdentityAndTracksParamEdits() {
    TargetKey key = new TargetKey("curlib.hello.pgm.rpgle");
    java.util.Map<ParamCmd, String> params = new java.util.HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "QRPGLESRC/HELLO.pgm.rpgle");

    String first = CommandStringParser.toPasteableCommand(key, null, params);
    assertNotNull(first);
    assertTrue(first.startsWith("CRTBNDRPG "));
    assertTrue(first.contains("PGM(*CURLIB/HELLO)"));
    assertTrue(first.contains("SRCSTMF('QRPGLESRC/HELLO.pgm.rpgle')"));
    assertFalse(first.contains("''"));
    assertTrue(first.contains("DBGVIEW(*ALL)"));

    params.put(ParamCmd.DBGVIEW, "*SOURCE");
    String edited = CommandStringParser.toPasteableCommand(key, null, params);
    assertTrue(edited.contains("DBGVIEW(*SOURCE)"));
    assertFalse(edited.contains("DBGVIEW(*ALL)"));

    params.remove(ParamCmd.DBGVIEW);
    params.put(ParamCmd.TEXT, "Hi");
    String added = CommandStringParser.toPasteableCommand(key, null, params);
    assertTrue(added.contains("TEXT('Hi')"));
    assertTrue(added.contains("DBGVIEW(*ALL)"), "removed override falls back to MC default");
  }

  @Test
  void toPasteableCommandDdsHasSrcfileNotSrcstmf() {
    TargetKey key = new TargetKey("curlib.article.pf.dds");
    java.util.Map<ParamCmd, String> params = new java.util.HashMap<ParamCmd, String>();
    params.put(ParamCmd.SRCSTMF, "QDDSSRC/ARTICLE.pf.dds");

    String paste = CommandStringParser.toPasteableCommand(key, null, params);
    assertNotNull(paste);
    assertTrue(paste.startsWith("CRTPF "));
    assertTrue(paste.contains("FILE(*CURLIB/ARTICLE)"));
    assertTrue(paste.contains("SRCFILE("));
    assertFalse(paste.contains("SRCSTMF"), "DDS CRT* has no SRCSTMF");
  }
}
