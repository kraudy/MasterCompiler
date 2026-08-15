package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class DiffPlannerTest {

  @Test
  void noSeedsEmpty() {
    Graph g = pfLfPgm();
    assertTrue(DiffPlanner.expand(g.spec, Collections.<TargetKey>emptySet()).isEmpty());
  }

  @Test
  void dirtyPfRebuildsLfAndPgm() {
    Graph g = pfLfPgm();
    Set<TargetKey> rebuild = DiffPlanner.expand(g.spec, set(g.pf));
    assertEquals(set(g.pf, g.lf, g.pgm), rebuild);
  }

  @Test
  void dirtyModuleRebuildsSrvpgmAndPgm() {
    Graph g = moduleSrvPgm();
    Set<TargetKey> rebuild = DiffPlanner.expand(g.spec, set(g.mod));
    assertEquals(set(g.mod, g.srv, g.pgm), rebuild);
    assertFalse(rebuild.contains(g.other));
  }

  @Test
  void dirtyLeafProgramOnlyThatProgram() {
    Graph g = pfLfPgm();
    Set<TargetKey> rebuild = DiffPlanner.expand(g.spec, set(g.pgm));
    assertEquals(set(g.pgm), rebuild);
  }

  @Test
  void existingBndDirNotASeedStaysOut() {
    Graph g = moduleSrvPgm();
    Set<TargetKey> rebuild = DiffPlanner.expand(g.spec, set(g.mod));
    assertFalse(rebuild.contains(g.bnddir));
  }

  @Test
  void missingBndDirAsSeedStaysAloneIfNoFathers() {
    Graph g = moduleSrvPgm();
    Set<TargetKey> rebuild = DiffPlanner.expand(g.spec, set(g.bnddir));
    assertEquals(set(g.bnddir), rebuild);
  }

  private static Graph pfLfPgm() {
    Graph g = new Graph();
    g.pf = new TargetKey("curlib.ARTICLE.pf.dds");
    g.lf = new TargetKey("curlib.ARTICLE1.lf.dds");
    g.pgm = new TargetKey("curlib.ART200.pgm.rpgle");
    g.spec = new BuildSpec();
    link(g.lf, g.pf);
    link(g.pgm, g.lf);
    g.spec.targets.put(g.pf, new BuildSpec.TargetSpec());
    g.spec.targets.put(g.lf, new BuildSpec.TargetSpec());
    g.spec.targets.put(g.pgm, new BuildSpec.TargetSpec());
    return g;
  }

  private static Graph moduleSrvPgm() {
    Graph g = new Graph();
    g.mod = new TargetKey("curlib.ART301.module.sqlrpgle");
    g.srv = new TargetKey("curlib.FARTICLE.srvpgm.bnd");
    g.pgm = new TargetKey("curlib.ART201.pgm.rpgle");
    g.other = new TargetKey("curlib.FFAMILLY.srvpgm.bnd");
    g.bnddir = new TargetKey("curlib.SAMPLE.bnddir.bnddir");
    g.spec = new BuildSpec();
    link(g.srv, g.mod);
    link(g.pgm, g.srv);
    g.spec.targets.put(g.mod, new BuildSpec.TargetSpec());
    g.spec.targets.put(g.srv, new BuildSpec.TargetSpec());
    g.spec.targets.put(g.pgm, new BuildSpec.TargetSpec());
    g.spec.targets.put(g.other, new BuildSpec.TargetSpec());
    g.spec.targets.put(g.bnddir, new BuildSpec.TargetSpec());
    return g;
  }

  /** parent depends on child (child compiles first). */
  private static void link(TargetKey parent, TargetKey child) {
    parent.addChild(child);
    child.addFather(parent);
  }

  private static Set<TargetKey> set(TargetKey... keys) {
    Set<TargetKey> s = new HashSet<TargetKey>();
    for (TargetKey k : keys) s.add(k);
    return s;
  }

  private static final class Graph {
    BuildSpec spec;
    TargetKey pf;
    TargetKey lf;
    TargetKey pgm;
    TargetKey mod;
    TargetKey srv;
    TargetKey other;
    TargetKey bnddir;
  }
}
