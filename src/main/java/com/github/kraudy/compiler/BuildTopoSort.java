package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

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
      String path = sampleCyclePath(inDegree);
      throw new RuntimeException(
          "Cycle detected in dependency graph! Unresolved targets: " + remaining
              + (path.isEmpty() ? "" : " Sample path: " + path));
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
   * Walk remaining nodes along child edges and return one closed path
   * ({@code A -> B -> A}) when a cycle exists inside the unresolved set.
   */
  private static String sampleCyclePath(Map<TargetKey, Integer> inDegree) {
    Set<TargetKey> remaining = new HashSet<>();
    for (Map.Entry<TargetKey, Integer> e : inDegree.entrySet()) {
      if (e.getValue() > 0) remaining.add(e.getKey());
    }
    if (remaining.isEmpty()) return "";

    for (TargetKey start : remaining) {
      List<TargetKey> stack = new ArrayList<>();
      Set<TargetKey> onStack = new LinkedHashSet<>();
      if (walkCycle(start, remaining, stack, onStack)) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
          if (i > 0) sb.append(" -> ");
          sb.append(stack.get(i).asString());
        }
        return sb.toString();
      }
    }
    return "";
  }

  private static boolean walkCycle(
      TargetKey node, Set<TargetKey> remaining, List<TargetKey> stack, Set<TargetKey> onStack) {
    if (!remaining.contains(node)) return false;
    if (onStack.contains(node)) {
      stack.add(node);
      return true;
    }
    stack.add(node);
    onStack.add(node);
    for (TargetKey dep : node.getChildsList()) {
      if (dep == null || !remaining.contains(dep)) continue;
      if (walkCycle(dep, remaining, stack, onStack)) return true;
    }
    stack.remove(stack.size() - 1);
    onStack.remove(node);
    return false;
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
