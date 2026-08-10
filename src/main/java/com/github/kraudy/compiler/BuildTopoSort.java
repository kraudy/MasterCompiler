package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Topological sort of build targets.
 * <p>
 * Convention (from {@link DependencyAwareness}): {@code child = dependency}
 * that must be compiled <em>before</em> the depender. Edge direction for Kahn:
 * dependency → depender. Result order is <strong>dependencies first</strong>.
 */
public class BuildTopoSort {
  private static final Logger logger = LoggerFactory.getLogger(BuildTopoSort.class);

  private final boolean debug;
  private final boolean verbose;

  public BuildTopoSort(boolean debug, boolean verbose) {
    this.debug = debug;
    this.verbose = verbose;
  }

  /**
   * @return build order: dependencies first, dependents last
   * @throws RuntimeException if a cycle is detected
   */
  public List<TargetKey> topologicalSort(BuildSpec globalSpec) {
    Map<TargetKey, Integer> inDegree = new HashMap<>();
    // adjacency: dependency -> list of targets that depend on it
    Map<TargetKey, List<TargetKey>> dependents = new HashMap<>();

    for (TargetKey target : globalSpec.targets.keySet()) {
      inDegree.put(target, 0);
      dependents.put(target, new ArrayList<>());
    }

    for (TargetKey target : globalSpec.targets.keySet()) {
      // children = dependencies that must be built before this target
      for (TargetKey dep : target.getChildsList()) {
        if (!inDegree.containsKey(dep)) {
          continue; // dependency outside the graph
        }
        inDegree.put(target, inDegree.get(target) + 1);
        dependents.get(dep).add(target);
      }
    }

    // Preserve discovery / YAML insertion order among ready nodes
    Queue<TargetKey> queue = new LinkedList<>();
    for (TargetKey target : globalSpec.targets.keySet()) {
      if (inDegree.get(target) == 0) {
        queue.add(target);
      }
    }

    List<TargetKey> order = new ArrayList<>();
    while (!queue.isEmpty()) {
      TargetKey target = queue.poll();
      order.add(target);

      for (TargetKey depender : dependents.get(target)) {
        int newDegree = inDegree.get(depender) - 1;
        inDegree.put(depender, newDegree);
        if (newDegree == 0) {
          queue.add(depender);
        }
      }
    }

    if (order.size() != globalSpec.targets.size()) {
      List<String> remaining = new ArrayList<>();
      for (Map.Entry<TargetKey, Integer> e : inDegree.entrySet()) {
        if (e.getValue() > 0) {
          remaining.add(e.getKey().asString());
        }
      }
      throw new RuntimeException(
          "Cycle detected in dependency graph! Unresolved targets: " + remaining);
    }

    if (verbose) {
      logger.info("Topological build order ({} targets):", order.size());
      for (int i = 0; i < order.size(); i++) {
        logger.info("  {}. {}", i + 1, order.get(i).asString());
      }
    }

    return order;
  }

  /**
   * Reorder {@link BuildSpec#targets} to match topological order.
   * Also refreshes {@link BuildSpec#setTargetsList}.
   */
  public void reorderSpec(BuildSpec globalSpec) {
    List<TargetKey> order = topologicalSort(globalSpec);
    LinkedHashMap<TargetKey, BuildSpec.TargetSpec> reordered = new LinkedHashMap<>();
    for (TargetKey key : order) {
      reordered.put(key, globalSpec.targets.get(key));
    }
    globalSpec.targets.clear();
    globalSpec.targets.putAll(reordered);

    globalSpec.getTargetsList().clear();
    globalSpec.setTargetsList(reordered.keySet());
  }
}
