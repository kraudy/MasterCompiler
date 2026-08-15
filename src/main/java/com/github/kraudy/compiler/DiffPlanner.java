package com.github.kraudy.compiler;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Incremental rebuild set: seeds plus every father (dependent), transitively.
 * Child = compile first. No IBM i, no files.
 */
public final class DiffPlanner {

  private DiffPlanner() {}

  public static Set<TargetKey> expand(BuildSpec spec, Set<TargetKey> seeds) {
    if (seeds == null || seeds.isEmpty()) {
      return Collections.emptySet();
    }

    Set<TargetKey> rebuild = new LinkedHashSet<TargetKey>();
    Deque<TargetKey> queue = new ArrayDeque<TargetKey>();

    for (TargetKey seed : seeds) {
      if (seed == null) continue;
      TargetKey node = resolve(spec, seed);
      if (rebuild.add(node)) {
        queue.add(node);
      }
    }

    while (!queue.isEmpty()) {
      TargetKey node = queue.removeFirst();
      for (TargetKey father : node.getFathersList()) {
        if (father == null) continue;
        TargetKey canon = resolve(spec, father);
        if (rebuild.add(canon)) {
          queue.add(canon);
        }
      }
    }

    return rebuild;
  }

  private static TargetKey resolve(BuildSpec spec, TargetKey key) {
    if (spec == null || spec.targets == null) return key;
    if (spec.targets.containsKey(key)) {
      for (TargetKey t : spec.targets.keySet()) {
        if (key.equals(t)) return t;
      }
    }
    TargetKey fromList = spec.getTargetKey(key);
    return fromList != null ? fromList : key;
  }
}
