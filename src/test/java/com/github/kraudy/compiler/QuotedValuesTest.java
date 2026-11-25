package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

/* #45 quotes inside quoted text (COMPILEOPT, TEXT), #46 ADDBNDDIRE entries, #47 several objects in one save file. No IBM i. */
class QuotedValuesTest {
  private static final ObjectMapper mapper = new ObjectMapper();

  /* The command as CL gets it: the QCMDEXC('...') literal's doubled quotes undone */
  private static String cl(String qcmdexc) {
    return qcmdexc.replace("''", "'");
  }

  @Test
  void aQuoteInsideCompileOptIsDoubledForCl() {
    String wanted = "COMPILEOPT('INCDIR(''src/rpglesrc'') TGTCCSID(*JOB)')";
    /* written as CL is typed, as the text the command gets, or as MC stores it: the same command */
    for (String written : new String[] { "INCDIR(''src/rpglesrc'') TGTCCSID(*JOB)", "INCDIR('src/rpglesrc') TGTCCSID(*JOB)",
        "INCDIR(''''src/rpglesrc'''') TGTCCSID(*JOB)" }) {
      String cmd = new TargetKey("MYLIB.SQLHELLO.PGM.SQLRPGLE").put(ParamCmd.SRCSTMF, "/s/sqlhello.sqlrpgle")
          .put(ParamCmd.COMPILEOPT, written).getCommandString();
      assertTrue(cl(cmd).contains(wanted), written + " -> " + cmd);
    }
    assertEquals("INCDIR('src/rpglesrc') TGTCCSID(*JOB)",
        Utilities.unquotedText(Utilities.quotedText("INCDIR(''src/rpglesrc'') TGTCCSID(*JOB)")));
  }

  @Test
  void aBindingDirectoryJoinsAQuotedCompileOpt() {
    String stored = Utilities.quotedText("INCDIR('src') TGTCCSID(*JOB)");
    assertEquals("INCDIR('src') TGTCCSID(*JOB) BNDDIR(*LIBL/APPBND)", MasterCompiler.compileOptWithBndDir(stored, "APPBND"));
    assertEquals("TGTCCSID(*JOB) BNDDIR(*LIBL/APPBND)", MasterCompiler.compileOptWithBndDir("''TGTCCSID(*JOB)''", "APPBND"));
  }

  /* Two quotes in a row in the text (it''s inside INCDIR('...')) are written four each and survive a re-put */
  @Test
  void quotesInARowSurviveReadingBack() {
    String written = "INCDIR(''''src/it''''''''s'''')";
    String stored = Utilities.quotedText(written);
    assertEquals("'INCDIR(''src/it''''s'')'", cl(stored));
    assertEquals(stored, Utilities.quotedText(Utilities.unquotedText(stored)));
    String withDir = MasterCompiler.compileOptWithBndDir(stored, "APPBND");
    assertEquals("'INCDIR(''src/it''''s'') BNDDIR(*LIBL/APPBND)'", cl(Utilities.validateParamValue(ParamCmd.COMPILEOPT, withDir)));
    assertEquals("INCDIR('src/it''s')", Utilities.textOf(stored));
  }

  @Test
  void aPathKeepsItsApostrophe() {
    TargetKey key = new TargetKey("MYLIB.HELLO.PGM.RPGLE").put(ParamCmd.SRCSTMF, "/src/it's.rpgle");
    assertEquals("/src/it's.rpgle", key.getStreamFile());
    assertEquals("/src/it's.rpgle", Utilities.textOf("'/src/it''s.rpgle'"));
    assertEquals("/src/plain.rpgle", Utilities.textOf("/src/plain.rpgle"));
  }

  /* --scan -o renders build.cl (grouping the entry) before the build points project names at their library */
  @Test
  void aGroupedEntryStillGetsTheProjectLibrary() {
    Map<String, String> libs = new HashMap<String, String>();
    libs.put("EMPDET", "PRJLIB");
    libs.put("XML", "PRJLIB");
    assertEquals("(PRJLIB/EMPDET *SRVPGM *IMMED) PRJLIB/XML OTHER/X",
        MasterCompiler.qualifyNames("(*LIBL/EMPDET *SRVPGM *IMMED) XML OTHER/X", libs));
    assertEquals("(PRJLIB/EMPDET)", MasterCompiler.qualifyNames("(EMPDET)", libs));
  }

  @Test
  void anApostropheInTextNoLongerEndsTheLiteral() {
    String cmd = new TargetKey("MYLIB.HELLO.PGM.RPGLE").put(ParamCmd.SRCSTMF, "/s/hello.rpgle")
        .put(ParamCmd.TEXT, "Customer's file").getCommandString();
    assertTrue(cmd.contains("TEXT(''Customer''''s file'')"), cmd);
    assertTrue(cl(cmd).contains("TEXT('Customer''s file')"), cmd);
  }

  @Test
  void bindingEntriesKeepTheirTypeAndActivation() {
    String cmd = new CommandObject(SysCmd.ADDBNDDIRE).put(ParamCmd.BNDDIR, "APPBND")
        .put(ParamCmd.OBJ, "*CURLIB/EMPDET *SRVPGM *IMMED").getCommandString();
    assertTrue(cmd.contains("OBJ((*CURLIB/EMPDET *SRVPGM *IMMED))"), cmd);
    cmd = new CommandObject(SysCmd.ADDBNDDIRE).put(ParamCmd.BNDDIR, "APPBND")
        .put(ParamCmd.OBJ, "(EMPDET *SRVPGM *DEFER) (*CURLIB/OTHER *SRVPGM)").getCommandString();
    assertTrue(cmd.contains("OBJ((EMPDET *SRVPGM *DEFER) (*CURLIB/OTHER *SRVPGM))"), cmd);
    cmd = new CommandObject(SysCmd.ADDBNDDIRE).put(ParamCmd.BNDDIR, "APPBND").put(ParamCmd.OBJ, "XML ORDER").getCommandString();
    assertTrue(cmd.contains("OBJ(*LIBL/XML *LIBL/ORDER)"), "names only: one entry each, as before: " + cmd);
    cmd = new CommandObject(SysCmd.ADDBNDDIRE).put(ParamCmd.BNDDIR, "APPBND").put(ParamCmd.OBJ, "A *MODULE B").getCommandString();
    assertTrue(cmd.contains("OBJ((*LIBL/A *MODULE) *LIBL/B)"), cmd);
    assertEquals("*ALL", Utilities.validateParamValue(ParamCmd.OBJ, "*ALL"), "a special value is not qualified");
  }

  @Test
  void aWrittenSpecKeepsQuotesAndEntries(@TempDir Path dir) throws Exception {
    Path first = dir.resolve("build.yaml");
    Files.write(first, ("after:\n"
        + "  - AddBndDirE: { BndDir: APPBND, Obj: \"*CURLIB/EMPDET *SRVPGM *IMMED\" }\n"
        + "targets:\n"
        + "  curlib.sqlhello.pgm.sqlrpgle:\n"
        + "    params:\n"
        + "      SRCSTMF: sqlhello.pgm.sqlrpgle\n"
        + "      TEXT: \"Customer's file\"\n"
        + "      COMPILEOPT: \"INCDIR(''src/rpglesrc'') TGTCCSID(*JOB)\"\n").getBytes(StandardCharsets.UTF_8));
    BuildSpec read = Utilities.deserializeYaml(first.toString());
    String written = SpecWriter.toYaml(read);
    Path second = dir.resolve("again.yaml");
    Files.write(second, written.getBytes(StandardCharsets.UTF_8));
    String writtenAgain = SpecWriter.toYaml(Utilities.deserializeYaml(second.toString()));
    for (String yaml : new String[] { written, writtenAgain }) {
      assertTrue(yaml.contains("TEXT('Customer''s file')") && yaml.contains("COMPILEOPT('INCDIR(''src/rpglesrc'') TGTCCSID(*JOB)')"),
          "the command (its pasteable comment) is the same after reading the written spec back:\n" + yaml);
    }
    assertTrue(read.after.get(0).getCommandString().contains("OBJ((*CURLIB/EMPDET *SRVPGM *IMMED))"), written);
    assertTrue(Utilities.deserializeYaml(second.toString()).after.get(0).getCommandString()
        .contains("OBJ((*CURLIB/EMPDET *SRVPGM *IMMED))"), written);
  }

  @Test
  void severalObjectsGoIntoOneSaveFile() {
    ObjectDownload.Item all = ObjectDownload.item("mylib/*all:pgm", "--download");
    assertEquals("SAVOBJ OBJ(*ALL) LIB(MYLIB) DEV(*SAVF) SAVF(W/S) OBJTYPE(*PGM) UPDHST(*NO) CLEAR(*ALL)", all.saveCommand("W/S"));
    assertEquals("MYLIB_-ALL.PGM.savf", all.fileName());
    ObjectDownload.Item generic = ObjectDownload.item("MYLIB/ORD*", "--download");
    assertEquals("MYLIB_ORD-.savf", generic.fileName());
    assertTrue(generic.saveCommand("W/S").startsWith("SAVOBJ OBJ(ORD*) LIB(MYLIB)"));

    ObjectNode args = mapper.createObjectNode();
    args.putArray("objects").add("mylib/ord100").add("MYLIB/ORDSRV").add("MYLIB/ORD100");
    args.put("type", "*PGM *SRVPGM");
    FileDownload.Staging how = FileDownload.staging(args, "/h/c");
    assertEquals("object", FileDownload.stageKind(args));
    assertTrue(how.commands.get(1).startsWith("SAVOBJ OBJ(ORD100 ORDSRV) LIB(MYLIB)"), how.commands.get(1));
    assertEquals("MYLIB_ORD100+ORDSRV.PGM.SRVPGM.savf", how.name);
    assertEquals("MYLIB/ORD100+ORDSRV *PGM *SRVPGM", how.from);

    ObjectNode many = mapper.createObjectNode();
    for (int i = 0; i < 40; i++) many.withArray("objects").add("MYLIB/OBJECT" + i);
    String name = FileDownload.staging(many, "/h/c").name;
    assertTrue(name.length() < 90 && name.startsWith("MYLIB_OBJECT0+") && name.contains("-40-"), name);

    ObjectNode endsInA = mapper.createObjectNode();
    ObjectNode endsInB = mapper.createObjectNode();
    for (int i = 0; i < 6; i++) {
      endsInA.withArray("objects").add("MYLIB/OBJECT000" + i);
      endsInB.withArray("objects").add("MYLIB/OBJECT000" + i);
    }
    endsInA.withArray("objects").add("MYLIB/A_");
    endsInB.withArray("objects").add("MYLIB/B@");
    assertNotEquals(FileDownload.staging(endsInA, "/h/c").name, FileDownload.staging(endsInB, "/h/c").name);

    ObjectNode twoLibraries = mapper.createObjectNode();
    twoLibraries.putArray("objects").add("LIB1/A").add("LIB2/B");
    assertThrows(IllegalArgumentException.class, () -> FileDownload.staging(twoLibraries, "/h/c"));
    ObjectNode both = mapper.createObjectNode().put("object", "LIB1/A");
    both.putArray("objects").add("LIB1/B");
    assertThrows(IllegalArgumentException.class, () -> FileDownload.staging(both, "/h/c"));
    for (String wrong : new String[] { "MYLIB/*", "MYLIB/A+", "MYLIB/*PGM", "MYLIB/ABCDEFGHIJ*", "*ALL/A" }) {
      assertThrows(IllegalArgumentException.class, () -> ObjectDownload.item(wrong, "--download"), wrong);
    }
  }
}
