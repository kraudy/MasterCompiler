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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
   * @throws DependencyCycle if a cycle is detected
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
      throw new DependencyCycle(sampleCyclePath(inDegree), remaining);
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
  static List<String> sampleCyclePath(Map<TargetKey, Integer> inDegree) {
    Set<TargetKey> remaining = new HashSet<>();
    for (Map.Entry<TargetKey, Integer> e : inDegree.entrySet()) {
      if (e.getValue() > 0) remaining.add(e.getKey());
    }
    List<String> path = new ArrayList<>();
    for (TargetKey start : remaining) {
      List<TargetKey> stack = new ArrayList<>();
      Set<TargetKey> onStack = new LinkedHashSet<>();
      if (walkCycle(start, remaining, stack, onStack)) {
        /* the walk may reach the cycle from outside it (D, A, B, A): the cycle starts where its last node first is */
        TargetKey closing = stack.get(stack.size() - 1);
        for (TargetKey key : stack.subList(stack.indexOf(closing), stack.size())) path.add(key.asString());
        return path;
      }
    }
    return path;
  }

  /*
   * The targets depend on each other in a circle: no order builds them. Its message is the report, as JSON (the
   * agent reads it as it reads a build report): the circle, every target it leaves unbuilt, and what to look at.
   */
  static final class DependencyCycle extends IllegalArgumentException {
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final List<String> cycle;
    final List<String> unresolved;

    DependencyCycle(List<String> cycle, List<String> unresolved) {
      super("Cycle detected in dependency graph: " + (cycle.isEmpty() ? unresolved : String.join(" -> ", cycle)));
      this.cycle = cycle;
      this.unresolved = unresolved;
    }

    @Override public String getMessage() {
      ObjectNode report = mapper.createObjectNode();
      report.put("error", "dependencyCycle");
      report.put("success", false);
      report.putPOJO("cycle", cycle);
      report.putPOJO("unresolved", unresolved);
      report.put("next", "Each target in the cycle needs the next one built first, so nothing was built. MC finds these "
          + "dependencies in the sources (CALL, a project command at the start of a CL statement, /copy, EXTPGM, files, "
          + "SQL tables) and in the spec (a command's PGM, a service program's MODULE): look in the cycle's sources for "
          + "the reference that is not one (impact on one of them lists what depends on it; MC's log with --verbose names each "
          + "dependency it found and why).");
      try {
        return mapper.writeValueAsString(report);
      } catch (Exception e) {
        return super.getMessage();
      }
    }

    @Override public String toString() {
      return getMessage();
    }
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
