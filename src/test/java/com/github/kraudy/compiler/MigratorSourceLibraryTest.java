package com.github.kraudy.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MigratorSourceLibraryTest {
  @Test
  void theSourceFileIsLookedForInItsOwnLibrary() {
    TargetKey key = new TargetKey("ROBKRAUDY2.COU200.PGM.RPG");
    assertEquals("ROBKRAUDY2/QRPGSRC", key.getQualifiedSourceFile());
    assertEquals("AND SYSTEM_TABLE_SCHEMA = 'ROBKRAUDY2' ", Migrator.sourceLibraryFilter(key));
  }
}
