package com.github.kraudy.compiler;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class UtilitiesTest {
  @Test
  void testDeserializeYaml_BasicTargetWithParams() throws IOException {
    String yamlContent = 
      "targets:\n" +
      "  mylib.hello.pgm.rpgle:\n" +
      "    params:\n" +
      "      TEXT: Hello World\n";

    // Create temp YAML file
    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());
      
    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      assertFalse(spec.targets.isEmpty());
      // Use lowercase to match YAML key (equals/hashCode now handles field matching)
      TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
      assertEquals("Hello World", spec.targets.get(key).params.get(ParamCmd.TEXT));
    } finally {
      // Cleanup
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_KeyMatching() throws IOException {
    String yamlContent = 
      "targets:\n" +
      "  mylib.hello.pgm.rpgle: {}\n";

    // Create temp YAML file
    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());
      
    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      assertFalse(spec.targets.isEmpty());
      TargetKey key = new TargetKey("MyliB.Hello.PGM.RPGle");
      assertNotNull(spec.targets.get(key));
    } finally {
      // Cleanup
      Files.deleteIfExists(tempYaml);
    }
  }

  void testDeserializeYaml_SourceFileWithoutLibrary() throws IOException {
    String yamlContent = 
      "targets:\n" +
      "  mylib.hello.pgm.rpgle:\n" +
      "    params:\n" +
      "      SRCFILE: source\n";

    // Create temp YAML file
    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());
      
    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
      assertEquals("*LIBL/source", spec.targets.get(key).params.get(ParamCmd.SRCFILE));
    } finally {
      // Cleanup
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_GlobalDefaults() throws IOException {
    String yamlContent = 
      "defaults:\n" +
      "  tgtrls: V7R5M0\n" +
      "  dbgview: Source\n" +
      "  replace: *Yes\n" +
      "\n" +
      "targets:\n" +
      "  mylib.hello.pgm.rpgle: {}\n";

    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      assertEquals(3, spec.defaults.size());
      assertEquals("V7R5M0", spec.defaults.get(ParamCmd.TGTRLS));
      assertEquals("*SOURCE", spec.defaults.get(ParamCmd.DBGVIEW));
      assertEquals("*YES", spec.defaults.get(ParamCmd.REPLACE));

      assertFalse(spec.targets.isEmpty());
      TargetKey key = new TargetKey("mylib.hello.pgm.rpgle");
      assertNotNull(spec.targets.get(key));
    } finally {
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_GlobalBeforeAndAfterHooks() throws IOException {
    String yamlContent = 
        "before:\n" +
        "  chglibl:\n" +
        "    LIBL: mylib1 mylib2\n" +
        "  chgcurlib:\n" +
        "    CURLIB: mylib2\n" +
        "\n" +
        "after:\n" +
        "  chglibl:\n" +
        "    LIBL: \"\"\n" +
        "\n" +
        "targets:\n" +
        "  mylib2.hello.pgm.rpgle: {}\n";

    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      assertEquals(2, spec.before.size()); /* 2 commands */
      assertTrue(spec.before.contains("CHGLIBL LIBL(mylib1 mylib2)"));
      assertTrue(spec.before.contains("CHGCURLIB CURLIB(mylib2)"));

      assertEquals(1, spec.after.size()); /* 1 command */
      assertTrue(spec.after.contains("CHGLIBL LIBL()"));

      assertFalse(spec.targets.isEmpty());
    } finally {
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_TargetSpecificHooksAndParams() throws IOException {
    String yamlContent = 
        "targets:\n" +
        "  \"mylib1.hello.pgm.rpgle\":\n" +
        "    before:\n" +
        "      chgcurlib:\n" +
        "        CURLIB: mylib1\n" +
        "    after:\n" +
        "      chgcurlib:\n" +
        "        CURLIB: mylib2\n" +
        "    params:\n" +
        "      TEXT: Target specific text\n" +
        "      SRCSTMF: /home/sources/HELLO.RPGLE\n" +
        "\n" +
        "  mylib2.empty.pgm.rpgle: {}\n";

    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      TargetKey key1 = new TargetKey("mylib1.hello.pgm.rpgle");
      BuildSpec.TargetSpec ts1 = spec.targets.get(key1);
      assertNotNull(ts1);

      assertEquals(1, ts1.before.size()); // 1 command in before
      assertTrue(ts1.before.contains("CHGCURLIB CURLIB(mylib1)"));

      assertEquals(1, ts1.after.size());  // 1 command in after
      assertTrue(ts1.after.contains("CHGCURLIB CURLIB(mylib2)"));

      assertEquals("Target specific text", ts1.params.get(ParamCmd.TEXT));
      assertEquals("/home/sources/HELLO.RPGLE", ts1.params.get(ParamCmd.SRCSTMF));

      TargetKey key2 = new TargetKey("mylib2.empty.pgm.rpgle");
      assertNotNull(spec.targets.get(key2));
      assertTrue(spec.targets.get(key2).params.isEmpty());
      assertTrue(spec.targets.get(key2).before.isEmpty());
      assertTrue(spec.targets.get(key2).after.isEmpty());
    } finally {
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_ListInParams() throws IOException {
    String yamlContent = 
        "targets:\n" +
        "  \"mylib2.srvhello.srvpgm.bnd\":\n" +
        "    params:\n" +
        "      SRCSTMF: /home/sources/SRVHELLO.BND\n" +
        "      MODULE:\n" +
        "        - MHELLO\n" +
        "        - MBYE\n";

    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      TargetKey key = new TargetKey("mylib2.srvhello.srvpgm.bnd");
      Map<ParamCmd, String> params = spec.targets.get(key).params;

      assertEquals("/home/sources/SRVHELLO.BND", params.get(ParamCmd.SRCSTMF));
      // List becomes space-separated string (from nodeToString in deserializer)
      assertEquals("MHELLO MBYE", params.get(ParamCmd.MODULE));
    } finally {
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_OverrideDefaultsInTarget() throws IOException {
    String yamlContent = 
        "defaults:\n" +
        "  tgtrls: V7R5M0\n" +
        "  dbgview: *SOURCE\n" +
        "  replace: *YES\n" +
        "\n" +
        "targets:\n" +
        "  \"mylib1.BASIC4002.pgm.rpgle\":\n" +
        "    params:\n" +
        "      TEXT: Overridden text\n" +
        "      SRCFILE: mylib1/sources\n";

    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      //assertEquals("V7R5M0", spec.defaults.get(ParamCmd.TGTRLS));
      assertEquals(3, spec.defaults.size());

      TargetKey key = new TargetKey("mylib1.BASIC4002.pgm.rpgle");
      Map<ParamCmd, String> targetParams = spec.targets.get(key).params;

      assertEquals("Overridden text", targetParams.get(ParamCmd.TEXT));
      assertEquals("mylib1/sources", targetParams.get(ParamCmd.SRCFILE));
      //TODO: Do the merge
    } finally {
      Files.deleteIfExists(tempYaml);
    }
  }

  @Test
  void testDeserializeYaml_MultipleTargetsAndEmptyOnes() throws IOException {
    String yamlContent = 
        "targets:\n" +
        "  \"mylib2.SQLHELLO.pgm.sqlrpgle\": {}\n" +
        "  \"mylib2.dsphello.dspf.dds\": {}\n" +
        "  \"mylib2.tabhello.table.sql\": {}\n" +
        "  \"mylib2.modhello.module.rpgle\": {}\n";

    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      assertEquals(4, spec.targets.size());

      assertNotNull(spec.targets.get(new TargetKey("mylib2.SQLHELLO.pgm.sqlrpgle")));
      assertNotNull(spec.targets.get(new TargetKey("mylib2.dsphello.dspf.dds")));
      assertNotNull(spec.targets.get(new TargetKey("mylib2.tabhello.table.sql")));
      assertNotNull(spec.targets.get(new TargetKey("mylib2.modhello.module.rpgle")));

      // Empty targets have empty params
      assertTrue(spec.targets.get(new TargetKey("mylib2.SQLHELLO.pgm.sqlrpgle")).params.isEmpty());
    } finally {
      Files.deleteIfExists(tempYaml);
    }
  }

  /*
   * Negative validations
   */

}
