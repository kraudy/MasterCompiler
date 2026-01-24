package com.github.kraudy.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class BuildTopoSort {
  private final boolean debug;
  private final boolean verbose;

  public BuildTopoSort (boolean debug, boolean verbose) {
    this.debug = debug;
    this.verbose = verbose;
  }
  
  public List<TargetKey> topologicalSort(BuildSpec globalSpec) {
    // Compute in-degrees
    Map<TargetKey, Integer> inDegree = new HashMap<>();
    for (TargetKey target : globalSpec.targets.keySet()) {
      inDegree.put(target, 0);
    }
    for (TargetKey target : globalSpec.targets.keySet()) {
      for (TargetKey child : target.getChildsList()) {  // children = dependencies
        inDegree.put(child, inDegree.getOrDefault(child, 0) + 1);
      }
    }

    // Queue for nodes with no incoming edges (ready to build)
    Queue<TargetKey> queue = new LinkedList<>();
    for (Map.Entry<TargetKey, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
      }
    }

    List<TargetKey> order = new ArrayList<>();
    while (!queue.isEmpty()) {
      TargetKey target = queue.poll();
      order.add(target);

      for (TargetKey child : target.getChildsList()) {
        int newDegree = inDegree.get(child) - 1;
        inDegree.put(child, newDegree);
        if (newDegree == 0) {
          queue.add(child);
        }
      }
    }

    if (order.size() != globalSpec.targets.size()) {
      throw new RuntimeException("Cycle detected in dependency graph!");
    }

    return order;  // Build in this order
  }

}
