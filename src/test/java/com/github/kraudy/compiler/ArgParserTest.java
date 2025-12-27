package com.github.kraudy.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class ArgParserTest {
  private Path tempYaml; // Shared temp file for tests that need a valid YAML

  @BeforeEach
  void setUp() throws IOException {
    // Create a standard empty YAML file for tests that require a valid file
    this.tempYaml = Files.createTempFile("build", ".yaml");
    Files.write(this.tempYaml, Collections.emptyList());
  }

  @AfterEach
  void tearDown() throws IOException {
    // Cleanup the shared temp file after each test
    if (this.tempYaml != null) {
      Files.deleteIfExists(this.tempYaml);
    }
  }

  @Test
  void testValidFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
  }

  @Test
  void testValidFileLongFormat() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"--file", filePath};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
  }

  @Test
  void testVerboseWithFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath, "-v"};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
    assertTrue(parser.isVerbose());
  }

  @Test
  void testDiffWithFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath, "--diff"};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
    assertTrue(parser.isDiff());
  }

  @Test
  void testDebugWithFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath, "-x"};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
    assertTrue(parser.isDebug());
  }

  @Test
  void testDebugAndVerboseWithFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath, "-x", "-v"};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
    assertTrue(parser.isDebug());
    assertTrue(parser.isVerbose());
  }

  @Test
  void testDebugVerboseCombinedWithFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath, "-xv"};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
    assertTrue(parser.isVerbose());
    assertTrue(parser.isDebug());
  }

  @Test
  void testVerboseDebugCombinedWithFile() {
    String filePath = this.tempYaml.toString();
 
    String[] args = {"-f", filePath, "-vx"};
    ArgParser parser = new ArgParser(args);

    assertEquals(filePath, parser.getYamlFile());
    assertTrue(parser.isVerbose());
    assertTrue(parser.isDebug());
  }

  @Test
  void testBooleanFlagsDefaults() {
    String filePath = this.tempYaml.toString();
    
    String[] args = {"-f", filePath};
    ArgParser parser = new ArgParser(args);

    assertFalse(parser.isDryRun());
    assertFalse(parser.isDebug());
    assertFalse(parser.isVerbose());
    assertFalse(parser.isDiff());
  }

  @Test
  void testDryRunFlag() {
    String filePath = this.tempYaml.toString();
    
    String[] args = {"--file", filePath, "--dry-run"};
    ArgParser parser = new ArgParser(args);

    assertTrue(parser.isDryRun());  
  }

  @Test
  void testInvalidOptionThrowsException() {
    String filePath = this.tempYaml.toString();
    
    String[] args = {"-f", filePath, "-z"}; // Invalid -z
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(args));
  }

  @Test
  void testInvalidLongOptionThrowsException() {
    String filePath = this.tempYaml.toString();
    
    String[] args = {"-f", filePath, "--noise"}; // Invalid -z
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(args));
  }

  @Test
  void testInvalidCombinedOptionThrowsException() {
    String filePath = this.tempYaml.toString();
    
    String[] args = {"-f", filePath, "-xz"}; // Invalid -z
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(args));
  }

  @Test
  void testMissingFileThrowsException() {
    String[] args = {"-xv"}; // No file
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(args).getYamlFile());
  }

  @Test
  void testInvalidFilePath() {
    String[] args = {"-f", "/nonexistent.yaml"};
    assertThrows(IllegalArgumentException.class, () -> new ArgParser(args).getYamlFile());
  }

}
