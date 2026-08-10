package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.SourceType;
import com.github.kraudy.compiler.SourceNaming.ParsedName;

public class SourceNamingTest {

  @Test
  void parseModuleRpgle() {
    ParsedName p = SourceNaming.parseFileName("FAM300.module.RPGLE").get();
    assertEquals("FAM300", p.objectName);
    assertEquals(ObjectType.MODULE, p.objectType);
    assertEquals(SourceType.RPGLE, p.sourceType);
    assertEquals("curlib.FAM300.MODULE.RPGLE", p.toTargetKey("curlib"));
  }

  @Test
  void parsePgmSqlrpgle() {
    ParsedName p = SourceNaming.parseFileName("ART200.pgm.sqlrpgle").get();
    assertEquals("ART200", p.objectName);
    assertEquals(ObjectType.PGM, p.objectType);
    assertEquals(SourceType.SQLRPGLE, p.sourceType);
  }

  @Test
  void parsePfDds() {
    ParsedName p = SourceNaming.parseFileName("ARTICLE.pf.dds").get();
    assertEquals("ARTICLE", p.objectName);
    assertEquals(ObjectType.PF, p.objectType);
    assertEquals(SourceType.DDS, p.sourceType);
  }

  @Test
  void parseSrvpgmBnd() {
    ParsedName p = SourceNaming.parseFileName("FFAMILLY.srvpgm.BND").get();
    assertEquals("FFAMILLY", p.objectName);
    assertEquals(ObjectType.SRVPGM, p.objectType);
    assertEquals(SourceType.BND, p.sourceType);
  }

  @Test
  void parseBareRpgleDefaultsToPgm() {
    ParsedName p = SourceNaming.parseFileName("ADDNUM.RPGLE").get();
    assertEquals("ADDNUM", p.objectName);
    assertEquals(ObjectType.PGM, p.objectType);
    assertEquals(SourceType.RPGLE, p.sourceType);
  }

  @Test
  void parseTableSql() {
    ParsedName p = SourceNaming.parseFileName("ARTIINF.table.sql").get();
    assertEquals("ARTIINF", p.objectName);
    assertEquals(ObjectType.TABLE, p.objectType);
    assertEquals(SourceType.SQL, p.sourceType);
  }

  @Test
  void parseDescriptiveMiddleTokensUsesFirstSegment() {
    // hello2.nomain.module.rpgle → object HELLO2
    ParsedName p = SourceNaming.parseFileName("hello2.nomain.module.rpgle").get();
    assertEquals("HELLO2", p.objectName);
    assertEquals(ObjectType.MODULE, p.objectType);
    assertEquals(SourceType.RPGLE, p.sourceType);
  }

  @Test
  void ddsWithoutObjectTypeRejected() {
    assertFalse(SourceNaming.parseFileName("ARTICLE.dds").isPresent());
  }

  @Test
  void unknownExtensionRejected() {
    assertFalse(SourceNaming.parseFileName("readme.md").isPresent());
    assertFalse(SourceNaming.parseFileName("build.yaml").isPresent());
  }

  @Test
  void invalidObjectNameRejected() {
    assertFalse(SourceNaming.parseFileName("thisnameistoolong.pgm.rpgle").isPresent());
  }

  @Test
  void pathStrippedToBasename() {
    Optional<ParsedName> p = SourceNaming.parseFileName("QRPGLESRC/FAM300.module.rpgle");
    assertTrue(p.isPresent());
    assertEquals("FAM300", p.get().objectName);
  }
}
