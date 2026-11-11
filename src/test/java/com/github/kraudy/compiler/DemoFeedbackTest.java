package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kraudy.compiler.CompilationPattern.ObjectType;

/* v0.3.33 demo feedback: rebuilt objects and the 5250 session, protectedLibs at setup, impact presence, clean's members */
public class DemoFeedbackTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text.replace('\'', '"'));
  }

  @Test
  void programsAndServiceProgramsAJobKeepsActivatedAreNamed() throws Exception {
    JsonNode targets = json("[{'target':'ROBKRAUDY1.BTIR.PGM.RPGLE','status':'built'},"
        + "{'target':'ROBKRAUDY1.SRV_BASE36.SRVPGM.BND','status':'failed','failedAt':'tests'},"
        + "{'target':'ROBKRAUDY1.SRV_BASE36.MODULE.RPGLE','status':'built'},"
        + "{'target':'ROBKRAUDY1.BTID.DSPF.DDS','status':'built'},"
        + "{'target':'ROBKRAUDY1.LOADCUSTR.PGM.SQLRPGLE','status':'failed','failedAt':'compile'},"
        + "{'target':'ROBKRAUDY1.MTNCUSTR.PGM.SQLRPGLE','status':'skipped'}]");
    assertEquals(Arrays.asList("BTIR *PGM", "SRV_BASE36 *SRVPGM"), ScreenTools.replacedObjects(targets));
    String hint = ScreenTools.sessionHint(Arrays.asList("BTIR *PGM"));
    assertTrue(hint.startsWith("BTIR *PGM rebuilt while this 5250 session was open"), hint);
    assertTrue(hint.contains("screen_close") && hint.contains("RCLACTGRP"), hint);
    assertNull(ScreenTools.sessionHint(Collections.<String>emptyList()));
    assertTrue(ScreenTools.built(targets).isEmpty());  // no 5250 session open: nothing to say
  }

  @Test
  void protectedLibsGoInTheSpecElseMcBase() throws Exception {
    Path scanned = Files.createTempDirectory("scanned");
    File base = ProtectedLibs.file(scanned.toFile(), null);
    assertEquals(new File(scanned.toFile(), "mc-base.yaml"), base);
    assertTrue(ProtectedLibs.read(base).isEmpty());
    String how = ProtectedLibs.howToSet(base);
    assertTrue(how.contains(base.getAbsolutePath()) && how.contains("(create the file)"), how);
    assertTrue(how.contains("protectedLibs: [PRODLIB1, PRODLIB2]"), how);
    assertTrue(how.contains("run_command refuses until the list is set"), how);

    Files.write(scanned.resolve("mc-base.yaml"), "curlib: DEVLIB\nprotectedLibs: [prod1, PROD2]\n".getBytes());
    assertEquals(Arrays.asList("PROD1", "PROD2"), ProtectedLibs.read(base));
    assertFalse(ProtectedLibs.howToSet(base).contains("create the file"));

    Path spec = Files.createTempDirectory("spec");
    Files.write(spec.resolve("build.yaml"), "targets: {}\n".getBytes());
    assertEquals(new File(spec.toFile(), "build.yaml"), ProtectedLibs.file(spec.toFile(), null));
    assertEquals(new File(spec.toFile(), "tobi.yaml"), ProtectedLibs.file(spec.toFile(), "tobi.yaml"));
    Path tobi = Files.createTempDirectory("tobi");
    Files.write(tobi.resolve("Rules.mk"), new byte[0]);
    assertNull(ProtectedLibs.file(tobi.toFile(), null));
    assertTrue(ProtectedLibs.howToSet(null).contains("--generate-only"));
    /* over SSH the path is the PC's, kept as typed there (a Windows path is not one on the IBM i) */
    String pc = ProtectedLibs.howToSet("C:\\repos\\lennon\\mc-base.yaml", true);
    assertTrue(pc.contains("to C:\\repos\\lennon\\mc-base.yaml: protectedLibs"), pc);
  }

  @Test
  void doctorAndNextStepsMentionProtectedLibs() throws Exception {
    Path project = Files.createTempDirectory("repo");
    Files.write(project.resolve("build.yaml"), "protectedLibs: [PRODLIB]\ntargets: {}\n".getBytes());
    ArgParser parser = new ArgParser(new String[] { "--doctor", "--project", project.toString() });
    parser.validate();
    assertTrue(ConnectionDoctor.protectedLibs(parser).startsWith("PRODLIB ("), ConnectionDoctor.protectedLibs(parser));
    Files.write(project.resolve("build.yaml"), "targets: {}\n".getBytes());
    assertTrue(ConnectionDoctor.protectedLibs(parser).startsWith("NOT SET"), ConnectionDoctor.protectedLibs(parser));
    assertTrue(NextSteps.text(null).contains("protectedLibs: [PRODLIB1, PRODLIB2]"));
  }

  @Test
  void impactChecksPresenceOnlyWhereTheObjectNameIsTheTargetName() {
    for (ObjectType type : Arrays.asList(ObjectType.PGM, ObjectType.SRVPGM, ObjectType.MODULE, ObjectType.DSPF, ObjectType.BNDDIR)) {
      assertTrue(McpServer.PRESENCE_CHECKED.contains(type), type.name());
    }
    for (ObjectType type : Arrays.asList(ObjectType.FUNCTION, ObjectType.PROCEDURE, ObjectType.ALIAS, ObjectType.TABLE)) {
      assertFalse(McpServer.PRESENCE_CHECKED.contains(type), type.name());  // SQL names: reported as presenceUnknown
    }
  }

  @Test
  void cleanRemovesOnlyMembersMcCreatedForThatTarget() throws Exception {
    String id = "PUB400 ROBKRAUDY1/QDSPFSRC(BTID)";
    String target = "ROBKRAUDY1.BTID.DSPF.DDS";
    JsonNode created = json("{'changed':'a','sourceUpdated':'b','created':true,'target':'" + target + "'}");
    assertTrue(Migrator.cleanable(id, created, "PUB400", "robkraudy1", target));
    assertFalse(Migrator.cleanable(id, created, "PUB400", "ROBKRAUDY1", "ROBKRAUDY1.BTID.PGM.CLP"));  // X.PGM's member is not X.DSPF's
    assertFalse(Migrator.cleanable(id, created, "OTHER", "ROBKRAUDY1", target));                      // another IBM i
    assertFalse(Migrator.cleanable(id, created, "PUB400", "ROBKRAUDY2", target));                     // another library
    JsonNode replaced = json("{'changed':'a','sourceUpdated':'b','target':'" + target + "'}");        // there before MC
    assertFalse(Migrator.cleanable(id, replaced, "PUB400", "ROBKRAUDY1", target));
    JsonNode older = json("{'changed':'a','sourceUpdated':'b'}");                                     // v0.3.32/33 record
    assertFalse(Migrator.cleanable(id, older, "PUB400", "ROBKRAUDY1", target));
  }
}
