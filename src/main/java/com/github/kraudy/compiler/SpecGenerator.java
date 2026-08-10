package com.github.kraudy.compiler;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.SourceNaming.ParsedName;
import com.github.kraudy.compiler.SourceScanner.CandidateSource;
import com.ibm.as400.access.AS400;

/**
 * Orchestrates: scan sources → build targets → detect dependencies → topo sort.
 */
public class SpecGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SpecGenerator.class);

  public static final String DEFAULT_LIBRARY = "curlib";

  private final AS400 system; // may be null for local-only generation without dep scan on IFS
  private final boolean debug;
  private final boolean verbose;

  public SpecGenerator(AS400 system, boolean debug, boolean verbose) {
    this.system = system;
    this.debug = debug;
    this.verbose = verbose;
  }

  /**
   * Full pipeline: discover sources under {@code root}, infer targets with
   * library {@code library}, run dependency awareness, reorder by topo sort.
   *
   * @param root     source tree root (local path or IFS)
   * @param library  library segment for target keys (default {@code curlib})
   */
  public BuildSpec generate(String root, String library) throws Exception {
    if (library == null || library.trim().isEmpty()) {
      library = DEFAULT_LIBRARY;
    }
    library = library.trim();

    SourceScanner scanner = new SourceScanner(system, verbose);
    List<CandidateSource> candidates = scanner.scan(root);

    if (candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "No recognizable sources found under: " + root
              + ". Use naming {object}.{objectType}.{sourceType} (e.g. HELLO.pgm.rpgle).");
    }

    BuildSpec spec = new BuildSpec();
    String baseDir = new java.io.File(root).getAbsolutePath();
    // Prefer absolute path when local; keep original when IFS-only
    java.io.File local = new java.io.File(root);
    if (local.isDirectory()) {
      baseDir = local.getAbsolutePath();
    } else {
      baseDir = root;
      while (baseDir.length() > 1 && baseDir.endsWith("/")) {
        baseDir = baseDir.substring(0, baseDir.length() - 1);
      }
    }
    spec.setBaseDirectory(baseDir);

    int added = 0;
    for (CandidateSource candidate : candidates) {
      Optional<ParsedName> parsed = SourceNaming.parseFileName(candidate.fileName);
      if (!parsed.isPresent()) continue;

      ParsedName name = parsed.get();
      String keyStr = name.toTargetKey(library);
      TargetKey key;
      try {
        key = new TargetKey(keyStr);
      } catch (IllegalArgumentException e) {
        logger.warn("Skipping {}: {}", candidate.relativePath, e.getMessage());
        continue;
      }

      if (spec.targets.containsKey(key)) {
        logger.warn("Duplicate target key {} from {}; keeping first",
            key.asString(), candidate.relativePath);
        continue;
      }

      BuildSpec.TargetSpec targetSpec = new BuildSpec.TargetSpec();
      targetSpec.params.put(ParamCmd.SRCSTMF, candidate.relativePath);
      // Also set stream path on the key for dependency scanning
      key.setStreamSourceFile(candidate.relativePath);

      spec.targets.put(key, targetSpec);
      added++;
      if (verbose) {
        logger.info("Target: {} ← {}", key.asString(), candidate.relativePath);
      }
    }

    if (added == 0) {
      throw new IllegalArgumentException("No valid targets could be created from: " + root);
    }

    spec.setTargetsList(spec.targets.keySet());
    logger.info("Discovered {} targets under {}", added, root);

    // Dependency graph + srvpgm MODULE inference
    DependencyAwareness depAwareness = new DependencyAwareness(system, debug, verbose);
    depAwareness.detectDependencies(spec);

    // Topo sort into compile-safe order
    BuildTopoSort topo = new BuildTopoSort(debug, verbose);
    topo.reorderSpec(spec);

    return spec;
  }
}
