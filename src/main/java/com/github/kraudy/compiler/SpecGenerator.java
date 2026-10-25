package com.github.kraudy.compiler;

import java.io.File;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SourceType;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.SourceNaming.ParsedName;
import com.github.kraudy.compiler.SourceScanner.CandidateSource;
import com.ibm.as400.access.AS400;

/**
 * Orchestrates: scan sources → merge base overlay → detect dependencies → topo sort.
 */
public class SpecGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SpecGenerator.class);

  public static final String DEFAULT_LIBRARY = "curlib";

  /** Default base overlay filename under the scan root. */
  public static final String DEFAULT_BASE_NAME = "mc-base.yaml";

  private final AS400 system; // may be null for local-only generation without dep scan on IFS
  private final Connection connection; // may be null; used to inspect existing objects
  private final boolean debug;
  private final boolean verbose;
  private boolean serviceProgramsFromExports;  // --srvpgm-from-exports

  public SpecGenerator(AS400 system, boolean debug, boolean verbose) {
    this(system, null, debug, verbose);
  }

  public SpecGenerator(AS400 system, Connection connection, boolean debug, boolean verbose) {
    this.system = system;
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
  }

  /** A module that exports procedures and that no service program binds gets a service program ({@code --srvpgm-from-exports}). */
  public void setServiceProgramsFromExports(boolean on) {
    this.serviceProgramsFromExports = on;
  }

  /**
   * Full pipeline: discover sources under {@code root}, infer targets with
   * library {@code library}, merge optional base overlay, run dependency
   * awareness, reorder by topo sort.
   *
   * @param root     source tree root (local path or IFS)
   * @param library  library segment for target keys (default {@code curlib})
   */
  public BuildSpec generate(String root, String library) throws Exception {
    return generate(root, library, null);
  }

  /**
   * @param basePath optional overlay path; when null, uses {@code root/mc-base.yaml}
   *                 if that file exists. When non-null, the file must exist.
   */
  public BuildSpec generate(String root, String library, String basePath) throws Exception {
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
    java.util.Map<TargetKey, String> moduleTexts = new java.util.LinkedHashMap<TargetKey, String>();  // module sources read here
    java.util.Set<String> boundSymbols = new java.util.HashSet<String>();                            // EXPORT SYMBOL of the binder sources
    java.util.Map<String, String> objects = new java.util.HashMap<String, String>();  // LIB.NAME.TYPE -> file
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

      /* One object, one source: e.g. ORD.pgm.rpgle left behind when it became ORD.pgm.sqlrpgle. Both stay
         targets (the later build replaces the object), but the report says so. */
      String object = key.getLibrary() + "." + key.getObjectName() + "." + key.getObjectTypeEnum();
      if (objects.containsKey(object)) {
        String warning = "Two sources build " + key.getObjectName() + " *" + key.getObjectTypeEnum() + ": "
            + objects.get(object) + " and " + candidate.relativePath + "; the one built last wins. "
            + "Delete or rename the one that is not in use.";
        logger.warn(warning);
        spec.warnings.add(warning);
      } else {
        objects.put(object, candidate.relativePath);
      }
      if (spec.targets.containsKey(key)) {
        logger.warn("Duplicate target key {} from {}; keeping first", key.asString(), candidate.relativePath);
        continue;
      }

      String text = readLocal(candidate);
      if (text != null) {
        for (String warning : SourceLint.check(candidate.relativePath, key.getObjectTypeEnum(), name.sourceType, text)) {
          logger.warn(warning);
          spec.warnings.add(warning);
        }
        if (key.isModule()) moduleTexts.put(key, text);
        if (name.sourceType == SourceType.BND) boundSymbols.addAll(SourceLint.binderSymbols(text));
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

      /* NAME.srvpgm.rpgle: the module plus a service program made of it (unless binder source exists) */
      if (name.ownServiceProgram) {
        TargetKey srvKey = new TargetKey(library + "." + name.objectName + ".SRVPGM.BND");
        if (!spec.targets.containsKey(srvKey)) {
          BuildSpec.TargetSpec srvSpec = new BuildSpec.TargetSpec();
          srvSpec.params.put(ParamCmd.MODULE, name.objectName);
          srvSpec.params.put(ParamCmd.EXPORT, "*ALL");
          spec.targets.put(srvKey, srvSpec);
          added++;
          if (verbose) logger.info("Target: {} ← module {} (EXPORT(*ALL))", srvKey.asString(), name.objectName);
        }
      }
    }

    if (added == 0) {
      throw new IllegalArgumentException("No valid targets could be created from: " + root);
    }

    /* Relative SRCSTMF resolves against the IBM i job curdir — set it to the scan root */
    if (baseDir != null && !baseDir.trim().isEmpty()) {
      CommandObject chgCurDir = new CommandObject(SysCmd.CHGCURDIR)
          .put(ParamCmd.DIR, baseDir);
      spec.before.add(0, chgCurDir);
      if (verbose) {
        logger.info("Injected global before: CHGCURDIR DIR('{}')", baseDir);
      }
    }

    /* Merge non-inferable params from base overlay (mc-base.yaml / --base) */
    mergeBaseOverlay(spec, root, basePath);

    /* After the overlay: its MODULE lists count as service programs that already take a module */
    if (serviceProgramsFromExports) added += addServicePrograms(spec, library, moduleTexts, boundSymbols);

    spec.setTargetsList(spec.targets.keySet());
    logger.info("Discovered {} targets under {}", added, root);

    // Dependency graph + srvpgm MODULE inference + CMD PGM deps from params
    DependencyAwareness depAwareness = new DependencyAwareness(system, debug, verbose);
    depAwareness.detectDependencies(spec);

    // Topo sort into compile-safe order
    BuildTopoSort topo = new BuildTopoSort(debug, verbose);
    topo.reorderSpec(spec);

    ObjectDescriptor descriptor = null;
    if (connection != null) {
      descriptor = new ObjectDescriptor(connection, debug, verbose);
      if (verbose) logger.info("Resolving generated params with object inspection");
    } else if (verbose) {
      logger.info("Resolving generated params without object inspection");
    }
    SpecResolver.resolveAll(spec, descriptor);

    return spec;
  }

  /* The source as text when it is a local file this scan can read; null for an IFS source or one that cannot be read */
  private static String readLocal(CandidateSource candidate) {
    try {
      java.io.File file = new java.io.File(candidate.absolutePath);
      if (!file.isFile() || file.length() > 4L * 1024 * 1024) return null;
      return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }

  /*
   * --srvpgm-from-exports: NAME.SRVPGM for each module that exports procedures (DCL-PROC ... EXPORT) when no service
   * program of the project takes it: not listed in a srvpgm target's MODULE, and none of its exports named by a binder
   * source's EXPORT SYMBOL. The service program is the module with EXPORT(*ALL), like NAME.srvpgm.rpgle.
   */
  private int addServicePrograms(BuildSpec spec, String library, java.util.Map<TargetKey, String> moduleTexts,
      java.util.Set<String> boundSymbols) throws Exception {
    java.util.Set<String> listed = new java.util.HashSet<String>();
    for (java.util.Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
      String modules = entry.getKey().isServiceProgram() ? entry.getValue().params.get(ParamCmd.MODULE) : null;
      if (modules == null) continue;
      for (String module : modules.trim().split("\\s+")) listed.add(module.replaceAll("^.*/", "").toUpperCase());
    }
    int added = 0;
    for (java.util.Map.Entry<TargetKey, String> module : moduleTexts.entrySet()) {
      String name = module.getKey().getObjectName();
      java.util.List<String> exports = SourceLint.exportedProcedures(module.getValue());
      if (exports.isEmpty() || listed.contains(name.toUpperCase())) continue;
      boolean named = false;
      for (String export : exports) named |= boundSymbols.contains(export);
      TargetKey srvKey = new TargetKey(library + "." + name + ".SRVPGM.BND");
      if (named || spec.targets.containsKey(srvKey)) continue;
      BuildSpec.TargetSpec srvSpec = new BuildSpec.TargetSpec();
      srvSpec.params.put(ParamCmd.MODULE, name);
      srvSpec.params.put(ParamCmd.EXPORT, "*ALL");
      spec.targets.put(srvKey, srvSpec);
      added++;
      logger.info("Target: {} <- module {} exports {} (EXPORT(*ALL))", srvKey.asString(), name, exports);
    }
    return added;
  }

  /**
   * Resolve and merge a base overlay into the scanned spec.
   *
   * @param explicitBase if non-null, must exist; if null, load {@code root/mc-base.yaml} when present
   */
  private void mergeBaseOverlay(BuildSpec scanned, String scanRoot, String explicitBase)
      throws Exception {
    String path = resolveBasePath(scanRoot, explicitBase);
    if (path == null) {
      if (verbose) logger.info("No base overlay ({} not found under scan root)", DEFAULT_BASE_NAME);
      return;
    }

    logger.info("Merging base overlay: {}", path);
    BuildSpec base = Utilities.deserializeOverlay(path);

    /* Global defaults: base fills / overrides */
    if (base.defaults != null && !base.defaults.isEmpty()) {
      scanned.defaults.putAll(base.defaults);
      if (verbose) logger.info("Merged {} default param(s) from base", base.defaults.size());
    }

    for (String lib : base.protectedLibs) scanned.protectedLibs.add(lib.trim().toUpperCase());
    scanned.uploadFolders.addAll(base.uploadFolders);
    scanned.uploadSources.addAll(base.uploadSources);
    if (base.hookTimeout != null) scanned.hookTimeout = base.hookTimeout;
    if (base.inquiryReply != null) scanned.inquiryReply = base.inquiryReply;

    /* Global hooks after scan-injected CHGCURDIR */
    if (base.before != null && !base.before.isEmpty()) {
      scanned.before.addAll(base.before);
    }
    if (base.after != null && !base.after.isEmpty()) {
      scanned.after.addAll(base.after);
    }
    if (base.success != null && !base.success.isEmpty()) {
      scanned.success.addAll(base.success);
    }
    if (base.failure != null && !base.failure.isEmpty()) {
      scanned.failure.addAll(base.failure);
    }

    int merged = 0;
    int added = 0;
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : base.targets.entrySet()) {
      TargetKey baseKey = entry.getKey();
      BuildSpec.TargetSpec baseSpec = entry.getValue();

      TargetKey scannedKey = findScannedKey(scanned, baseKey);
      if (scannedKey == null) {
        /* Base-only target (no source) — BNDDIR, DTAARA, DTAQ, MSGF, … */
        BuildSpec.TargetSpec newSpec = copyTargetSpec(baseSpec);
        scanned.targets.put(baseKey, newSpec);
        added++;
        if (verbose) {
          logger.info("Added base-only target {}", baseKey.asString());
        }
        continue;
      }

      BuildSpec.TargetSpec scannedSpec = scanned.targets.get(scannedKey);
      if (scannedSpec == null) continue;

      if (baseSpec.recreate != null) scannedSpec.recreate = baseSpec.recreate;

      /* Params: base fills/overrides; keep scan SRCSTMF when base omits it */
      String scanSrcstmf = scannedSpec.params.get(ParamCmd.SRCSTMF);
      if (baseSpec.params != null) {
        for (Map.Entry<ParamCmd, String> pe : baseSpec.params.entrySet()) {
          if (pe.getKey() == null || pe.getValue() == null) continue;
          scannedSpec.params.put(pe.getKey(), pe.getValue());
        }
      }
      if (!scannedSpec.params.containsKey(ParamCmd.SRCSTMF) && scanSrcstmf != null) {
        scannedSpec.params.put(ParamCmd.SRCSTMF, scanSrcstmf);
      }

      if (baseSpec.before != null && !baseSpec.before.isEmpty()) {
        scannedSpec.before.addAll(baseSpec.before);
      }
      if (baseSpec.after != null && !baseSpec.after.isEmpty()) {
        scannedSpec.after.addAll(baseSpec.after);
      }
      if (baseSpec.success != null && !baseSpec.success.isEmpty()) {
        scannedSpec.success.addAll(baseSpec.success);
      }
      if (baseSpec.failure != null && !baseSpec.failure.isEmpty()) {
        scannedSpec.failure.addAll(baseSpec.failure);
      }

      merged++;
      if (verbose) {
        logger.info("Merged base params for {}", scannedKey.asString());
      }
    }

    logger.info("Merged base overlay: {} scanned target(s) updated, {} base-only target(s) added",
        merged, added);
  }

  private static BuildSpec.TargetSpec copyTargetSpec(BuildSpec.TargetSpec src) {
    BuildSpec.TargetSpec dst = new BuildSpec.TargetSpec();
    if (src.params != null) {
      dst.params.putAll(src.params);
    }
    if (src.before != null) dst.before.addAll(src.before);
    if (src.after != null) dst.after.addAll(src.after);
    if (src.success != null) dst.success.addAll(src.success);
    if (src.failure != null) dst.failure.addAll(src.failure);
    dst.recreate = src.recreate;
    return dst;
  }

  private static String resolveBasePath(String scanRoot, String explicitBase) {
    if (explicitBase != null && !explicitBase.trim().isEmpty()) {
      File f = new File(explicitBase.trim());
      if (!f.isFile() || !f.canRead()) {
        throw new IllegalArgumentException(
            "Base overlay not found or not readable: " + explicitBase);
      }
      return f.getAbsolutePath();
    }

    File defaultBase = new File(scanRoot, DEFAULT_BASE_NAME);
    if (defaultBase.isFile() && defaultBase.canRead()) {
      return defaultBase.getAbsolutePath();
    }
    return null;
  }

  /** Match by TargetKey equality (library/object/type/source, case-insensitive). */
  private static TargetKey findScannedKey(BuildSpec scanned, TargetKey baseKey) {
    if (scanned.targets.containsKey(baseKey)) {
      return baseKey;
    }
    for (TargetKey k : scanned.targets.keySet()) {
      if (k.equals(baseKey)) return k;
    }
    return null;
  }
}
