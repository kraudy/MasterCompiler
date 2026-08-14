package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

public class BuildTopoSortTest {

  @Test
  void dependenciesComeBeforeDependents() {
    BuildSpec spec = new BuildSpec();
    TargetKey pf = new TargetKey("curlib.ARTICLE.pf.dds");
    TargetKey lf = new TargetKey("curlib.ARTICLE1.lf.dds");
    TargetKey pgm = new TargetKey("curlib.ART200.pgm.rpgle");

    // pgm depends on lf; lf depends on pf
    lf.addChild(pf);
    pf.addFather(lf);
    pgm.addChild(lf);
    lf.addFather(pgm);

    spec.targets.put(pgm, new BuildSpec.TargetSpec());
    spec.targets.put(lf, new BuildSpec.TargetSpec());
    spec.targets.put(pf, new BuildSpec.TargetSpec());

    List<TargetKey> order = new BuildTopoSort(false, false).topologicalSort(spec);

    assertEquals(3, order.size());
    assertTrue(order.indexOf(pf) < order.indexOf(lf));
    assertTrue(order.indexOf(lf) < order.indexOf(pgm));
  }

  @Test
  void diamondGraph() {
    BuildSpec spec = new BuildSpec();
    TargetKey a = new TargetKey("curlib.A.pf.dds");
    TargetKey b = new TargetKey("curlib.B.lf.dds");
    TargetKey c = new TargetKey("curlib.C.lf.dds");
    TargetKey d = new TargetKey("curlib.D.pgm.rpgle");

    // b→a, c→a, d→b, d→c  (child = dependency)
    b.addChild(a); a.addFather(b);
    c.addChild(a); a.addFather(c);
    d.addChild(b); b.addFather(d);
    d.addChild(c); c.addFather(d);

    spec.targets.put(d, new BuildSpec.TargetSpec());
    spec.targets.put(c, new BuildSpec.TargetSpec());
    spec.targets.put(b, new BuildSpec.TargetSpec());
    spec.targets.put(a, new BuildSpec.TargetSpec());

    List<TargetKey> order = new BuildTopoSort(false, false).topologicalSort(spec);

    assertEquals(0, order.indexOf(a));
    assertTrue(order.indexOf(b) < order.indexOf(d));
    assertTrue(order.indexOf(c) < order.indexOf(d));
  }

  @Test
  void noDependenciesPreservesInsertionOrder() {
    BuildSpec spec = new BuildSpec();
    TargetKey a = new TargetKey("curlib.A.pgm.rpgle");
    TargetKey b = new TargetKey("curlib.B.pgm.rpgle");
    TargetKey c = new TargetKey("curlib.C.pgm.rpgle");
    spec.targets.put(a, new BuildSpec.TargetSpec());
    spec.targets.put(b, new BuildSpec.TargetSpec());
    spec.targets.put(c, new BuildSpec.TargetSpec());

    List<TargetKey> order = new BuildTopoSort(false, false).topologicalSort(spec);
    assertEquals(a, order.get(0));
    assertEquals(b, order.get(1));
    assertEquals(c, order.get(2));
  }

  @Test
  void cycleThrows() {
    BuildSpec spec = new BuildSpec();
    TargetKey a = new TargetKey("curlib.A.pgm.rpgle");
    TargetKey b = new TargetKey("curlib.B.pgm.rpgle");
    a.addChild(b); b.addFather(a);
    b.addChild(a); a.addFather(b);
    spec.targets.put(a, new BuildSpec.TargetSpec());
    spec.targets.put(b, new BuildSpec.TargetSpec());

    RuntimeException ex = assertThrows(RuntimeException.class,
        () -> new BuildTopoSort(false, false).topologicalSort(spec));
    assertTrue(ex.getMessage().contains("Sample path:"), ex.getMessage());
    assertTrue(ex.getMessage().contains("->"), ex.getMessage());
  }

  @Test
  void reorderSpecUpdatesMapOrder() {
    BuildSpec spec = new BuildSpec();
    TargetKey dep = new TargetKey("curlib.DEP.pf.dds");
    TargetKey top = new TargetKey("curlib.TOP.pgm.rpgle");
    top.addChild(dep);
    dep.addFather(top);
    // Insert dependent first
    spec.targets.put(top, new BuildSpec.TargetSpec());
    spec.targets.put(dep, new BuildSpec.TargetSpec());
    spec.setTargetsList(spec.targets.keySet());

    new BuildTopoSort(false, false).reorderSpec(spec);

    Object[] keys = spec.targets.keySet().toArray();
    assertEquals(dep, keys[0]);
    assertEquals(top, keys[1]);
    assertEquals(dep, spec.getTargetsList().get(0));
  }
}
