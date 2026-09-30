package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Local --diff fan-out on a real McOnTobi scan. Source mtime vs a baseline;
 * no IBM i objects.
 */
@Tag("diff")
public class ScanDiffTest {

  private static final String TOBI = "https://github.com/kraudy/McOnTobi.git";

  private static Path root;
  private static BuildSpec spec;

  @BeforeAll
  static void cloneAndScan() throws Exception {
    assumeTrue(gitAvailable(), "git is not on PATH");
    root = cloneRepo(TOBI);
    spec = new SpecGenerator(null, false, true).generate(root.toString(), "curlib");
  }

  @AfterAll
  static void cleanup() {
    deleteRecursively(root);
  }

  @Test
  void articlePfRebuildsFilesAndConsumers() throws Exception {
    TargetKey article = require("CURLIB.ARTICLE.PF.DDS");
    assertTrue(hasFatherNamed(article, "ARTICLE1"),
        "scan graph: ARTICLE1 should depend on ARTICLE. fathers=" + names(article.getFathersList()));

    Set<TargetKey> rebuild = rebuildAfterTouching(article);

    assertTrue(rebuild.contains(article), "ARTICLE itself. rebuild=" + names(rebuild));
    assertTrue(named(rebuild, "ARTICLE1"), "PFILE ARTICLE1. rebuild=" + names(rebuild));
    assertTrue(named(rebuild, "ARTICLE2"), "PFILE ARTICLE2. rebuild=" + names(rebuild));
    assertTrue(named(rebuild, "ART200"), "program using ARTICLE. rebuild=" + names(rebuild));
    assertFalse(named(rebuild, "FFAMILLY"), "unrelated srvpgm. rebuild=" + names(rebuild));
    assertFalse(named(rebuild, "SAMPLE"), "existing BNDDIR is not a seed. rebuild=" + names(rebuild));
  }

  @Test
  void art301RebuildsFarticleNotFfamilly() throws Exception {
    TargetKey art301 = require("CURLIB.ART301.MODULE.SQLRPGLE");
    Set<TargetKey> rebuild = rebuildAfterTouching(art301);

    assertTrue(rebuild.contains(art301));
    assertTrue(rebuild.contains(require("CURLIB.FARTICLE.SRVPGM.BND")));
    assertTrue(named(rebuild, "ART201"), "program depending on FARTICLE");
    assertFalse(rebuild.contains(require("CURLIB.FFAMILLY.SRVPGM.BND")));
    assertFalse(named(rebuild, "FAM300"));
    assertFalse(rebuild.contains(require("CURLIB.ARTICLE.PF.DDS")));
  }

  @Test
  void art200LeafOnlyArt200() throws Exception {
    TargetKey art200 = require("CURLIB.ART200.PGM.SQLRPGLE");
    Set<TargetKey> rebuild = rebuildAfterTouching(art200);

    assertEquals(1, rebuild.size(), "leaf program has no fathers: " + names(rebuild));
    assertTrue(rebuild.contains(art200));
  }

  @Test
  void articleRpgleincRebuildsArt201() throws Exception {
    TargetKey art201 = require("CURLIB.ART201.PGM.RPGLE");
    Path inc = findInclude("ARTICLE.RPGLEINC");
    assertTrue(hasIncludeNamed(art201, "ARTICLE.RPGLEINC"),
        "ART201 should attach ARTICLE.RPGLEINC: " + art201.getIncludeFiles());

    Set<TargetKey> rebuild = rebuildAfterTouchingPath(inc);

    assertTrue(rebuild.contains(art201), "include edit seeds ART201. rebuild=" + names(rebuild));
    assertTrue(named(rebuild, "ART201"));
    assertFalse(rebuild.contains(require("CURLIB.FFAMILLY.SRVPGM.BND")));
    assertFalse(named(rebuild, "SAMPLE"));
  }

  @Test
  void noTouchEmptyRebuild() {
    Timestamp baseline = new Timestamp(System.currentTimeMillis() + 120_000L);
    Set<TargetKey> seeds = seedsVersusBaseline(baseline);
    assertTrue(seeds.isEmpty(), "all clone mtimes should be before a future baseline: " + names(seeds));
    assertTrue(DiffPlanner.expand(spec, seeds).isEmpty());
  }

  private static Set<TargetKey> rebuildAfterTouching(TargetKey seedTarget) throws Exception {
    return rebuildAfterTouchingPath(resolveSource(seedTarget), seedTarget);
  }

  private static Set<TargetKey> rebuildAfterTouchingPath(Path src) throws Exception {
    return rebuildAfterTouchingPath(src, null);
  }

  private static Set<TargetKey> rebuildAfterTouchingPath(Path src, TargetKey expectedSeed)
      throws Exception {
    Timestamp baseline = new Timestamp(System.currentTimeMillis() + 120_000L);
    assertTrue(Files.isRegularFile(src), "source missing: " + src);
    Files.setLastModifiedTime(src, FileTime.fromMillis(baseline.getTime() + 5_000L));

    Set<TargetKey> seeds = seedsVersusBaseline(baseline);
    if (expectedSeed != null) {
      assertTrue(seeds.contains(expectedSeed), "touched source should be a seed: " + names(seeds));
    } else {
      assertFalse(seeds.isEmpty(), "include touch should seed at least one consumer: " + src);
    }
    Set<TargetKey> rebuild = DiffPlanner.expand(spec, seeds);

    Files.setLastModifiedTime(src, FileTime.fromMillis(baseline.getTime() - 120_000L));
    return rebuild;
  }

  private static Set<TargetKey> seedsVersusBaseline(Timestamp baseline) {
    SourceDescriptor src = new SourceDescriptor(null, null, spec.getBaseDirectory(), false, false);
    Set<TargetKey> seeds = new HashSet<TargetKey>();
    for (TargetKey key : spec.targets.keySet()) {
      key.setLastBuild(baseline);
      key.setLastEdit(src.latestSourceEdit(key));
      if (key.needsRebuild()) {
        seeds.add(key);
      }
    }
    return seeds;
  }

  private static Path resolveSource(TargetKey key) {
    String rel = key.getStreamFile();
    assertNotNull(rel, key.asString() + " has no SRCSTMF");
    return new File(SourceDescriptor.resolveFullPath(root.toString(), rel)).toPath();
  }

  private static TargetKey require(String key) {
    TargetKey needle = new TargetKey(key);
    for (TargetKey t : spec.targets.keySet()) {
      if (needle.equals(t)) return t;
    }
    fail("missing target " + key);
    return null;
  }

  private static boolean named(Set<TargetKey> set, String objectName) {
    for (TargetKey t : set) {
      if (objectName.equalsIgnoreCase(t.getObjectName())) return true;
    }
    return false;
  }

  private static Path findInclude(String basename) throws IOException {
    final Path[] found = new Path[1];
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (basename.equalsIgnoreCase(file.getFileName().toString())) {
          found[0] = file;
          return FileVisitResult.TERMINATE;
        }
        return FileVisitResult.CONTINUE;
      }
    });
    assertNotNull(found[0], "include not in clone: " + basename);
    return found[0];
  }

  private static boolean hasIncludeNamed(TargetKey key, String basename) {
    for (String p : key.getIncludeFiles()) {
      if (p.replace('\\', '/').toUpperCase().endsWith("/" + basename.toUpperCase())
          || p.equalsIgnoreCase(basename)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasFatherNamed(TargetKey key, String objectName) {
    for (TargetKey f : key.getFathersList()) {
      if (objectName.equalsIgnoreCase(f.getObjectName())) return true;
    }
    return false;
  }

  private static String names(Iterable<TargetKey> keys) {
    StringBuilder sb = new StringBuilder();
    for (TargetKey t : keys) {
      if (sb.length() > 0) sb.append(',');
      sb.append(t.asString());
    }
    return sb.toString();
  }

  private static Path cloneRepo(String url) throws Exception {
    Path dir = Files.createTempDirectory("mc-scan-diff-");
    Process clone = new ProcessBuilder("git", "clone", "--depth", "1", url, dir.toString())
        .inheritIO()
        .start();
    assertEquals(0, clone.waitFor(), "git clone failed: " + url);
    return dir;
  }

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) return;
    try {
      Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          file.toFile().setWritable(true);  // git objects are read-only (Windows)
          Files.deleteIfExists(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
          Files.deleteIfExists(d);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
    }
  }
}
