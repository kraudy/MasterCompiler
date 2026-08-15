package com.github.kraudy.compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;

/*
 * Inffer object compilation 
 * We need dependency awareness for diff build
 * For a dependency to be considered, it must be in the spec file, otherwise it is ignored.
 */
public class DependencyAwareness {
  private static final Logger logger = LoggerFactory.getLogger(DependencyAwareness.class);

  /** BNDDIR( … ) body, including colon lists: BndDir('A':'B':'C'). */
  private static final Pattern BNDDIR_BLOCK = Pattern.compile(
      "\\bBNDDIR\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** One BNDDIR object name inside the parens (quoted). */
  private static final Pattern BNDDIR_NAME = Pattern.compile(
      "'([^']+)'");

  private static final Pattern DTAARA_PATTERN = Pattern.compile(
    "\\bDTAARA\\s*\\(\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXTNAME_PATTERN = Pattern.compile(
    "\\bEXTNAME\\s*\\(\\s*['\"]?([A-Z0-9$#@_]{1,10})['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE);

  /* Fixed-format F-spec: strict column logic
   * - Exactly 5 characters (usually blanks) before the F (column 6)
   * - F/f in column 6
   * - Capture up to 10 characters for the file name (columns 7-16)
   */
  private static final Pattern FIXED_F_SPEC = Pattern.compile(
      "^.{5}[fF]\\s*([A-Z0-9$#@_]{1,10})\\b", Pattern.CASE_INSENSITIVE);

  // Pattern for free-format DCL-F (captures the file name, possibly qualified)
  private static final Pattern FREE_DCL_F = Pattern.compile(
      "DCL-F\\s+([A-Z0-9$#@_./]+?)\\s+", Pattern.CASE_INSENSITIVE);

  // Pattern for embedded SQL table references (FROM, JOIN, INTO, UPDATE, DELETE FROM, INSERT INTO)
  private static final Pattern SQL_TABLE = Pattern.compile(
    "(FROM|JOIN|INTO|UPDATE|DELETE FROM|INSERT INTO)\\s+([\"']?[\\w$#@_./]+[\"']?)",
    Pattern.CASE_INSENSITIVE);

  // Improved: captures table + optional implicit alias, stops before comma or explicit join parts
  private static final Pattern SQL_FROM_JOIN_PATTERN = Pattern.compile(
      "\\b(FROM|JOIN)\\s+([\"']?[A-Z0-9$#@_./]+[\"']?)(\\s+[A-Z0-9$#@_]+)?"
      + "(?:\\s+(?:INNER|LEFT|RIGHT|FULL)?\\s*(?:OUTER)?\\s*JOIN|\\s+ON|\\s+AS\\s+[A-Z0-9$#@_]+)?",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  // New: for additional comma-separated tables (quoted + optional implicit alias)
  private static final Pattern SQL_COMMA_TABLE_PATTERN = Pattern.compile(
      "\\s*,\\s*([\"']?[A-Z0-9$#@_./]+[\"']?)(\\s+[A-Z0-9$#@_]+)?",
      Pattern.CASE_INSENSITIVE);

  // Pattern for REF(filename) in DDS (for PF)
  private static final Pattern DDS_REF_PATTERN = Pattern.compile(
      "\\bREF\\s*\\(\\s*([A-Z0-9$#@_]{1,10})\\s*\\)", Pattern.CASE_INSENSITIVE);
  
  // Pattern for PFILE(basepf) in DDS (for LF)
  private static final Pattern DDS_PFILE_PATTERN = Pattern.compile(
      "\\bPFILE\\s*\\(\\s*([A-Z0-9$#@_]{1,10})\\s*\\)", Pattern.CASE_INSENSITIVE);

  /* Pattern for REFFLD(filename) in DDS (for DSPF, PRTF) */
  private static final Pattern DDS_REFFLD_PATTERN = Pattern.compile(
    "REFFLD\\s*\\(\\s*([A-Z0-9$#@_]{1,10}(?:/[A-Z0-9$#@_]{1,10})?)"
    + "(?:\\s+(?:(?:\\*LIBL|[A-Z0-9$#@_]{1,10})/)?([A-Z0-9$#@_]{1,10}))?\\s*\\)",
    Pattern.CASE_INSENSITIVE);

  /* Pattern for Extpgm(pgm) in  */
  private static final Pattern EXTPGM_PATTERN = Pattern.compile(
    "\\bEXTPGM\\s*\\(\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);

   // Pattern 1: CALL PGM(ORD100) or CALL PGM('MYLIB/ORD100') 
  private static final Pattern CALL_PGM_PATTERN = Pattern.compile(
    "\\bCALL\\s+PGM\\s*\\(\\s*['\"]?([^'\"\\)]+)['\"]?\\s*\\)",Pattern.CASE_INSENSITIVE);

  // Pattern 2: OPM-style CALL ORD100C or CALL 'ORD100C' (optionally followed by PARM)
  private static final Pattern CALL_DIRECT_PGM_PATTERN = Pattern.compile(
      "\\bCALL\\s+(['\"]?)([A-Z0-9$#@_]{1,10})\\1(?:\\s+PARM|\\s|$)",Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

  // Binder language: EXPORT SYMBOL('PROCNAME')
  private static final Pattern BND_EXPORT_SYMBOL = Pattern.compile(
      "\\bEXPORT\\s+SYMBOL\\s*\\(\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);

  /** RPG /copy or /include path (not //commented). */
  private static final Pattern COPY_INCLUDE_DIR = Pattern.compile(
      "^\\s*/\\s*(copy|include)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

  /** Free-form dcl-pr Name … */
  private static final Pattern DCL_PR_NAME = Pattern.compile(
      "\\bdcl-pr\\s+([A-Z0-9$#@_]+)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Fixed-format D-spec prototype: col 6 = D, name, then PR or … continuation.
   * e.g. {@code      DGetArtDesc       PR} or {@code      DGetArtRefSalPrice...}
   */
  private static final Pattern FIXED_D_PR = Pattern.compile(
      "^.{5}[Dd]\\s*([A-Z0-9$#@_]+)(?:\\s*\\.\\.\\.|\\s+PR\\b)",
      Pattern.CASE_INSENSITIVE);

  private final AS400 system;
  private final boolean debug;
  private final boolean verbose;
  private final Map<String, TargetKey> keyLookup = new HashMap<>();

  /** Project *CMD targets keyed by upper-case object name (command name). */
  private final Map<String, TargetKey> projectCmds = new HashMap<>();

  /** Statement-leading match for project CMD names; rebuilt each detectDependencies run. */
  private Pattern projectCmdStmtPattern;

  private final ConcurrentHashMap<TargetKey, List<String>> targetLogs = new ConcurrentHashMap<>();
  private final AtomicInteger processed = new AtomicInteger();
  private int totalTargets = 0;

  private final Map<String, String> fileOverrideMap = new HashMap<>();  // overriddenName -> actualToFile

  private final ConcurrentHashMap<String, TargetKey> exportedProcToModule = new ConcurrentHashMap<>();

  /** Upper-case export symbol → *SRVPGM that provides it. */
  private final Map<String, TargetKey> exportToSrvpgm = new HashMap<>();

  /** BNDDIR object name (upper) → *SRVPGM targets registered via ADDBNDDIRE. */
  private final Map<String, Set<TargetKey>> bnddirMembership = new HashMap<>();

  /** *MODULE → BNDDIR names from ctl-opt / H-spec (not compile edges). */
  private final ConcurrentHashMap<TargetKey, Set<String>> moduleBndDirs = new ConcurrentHashMap<>();

  /** Scan base directory for resolving relative /copy paths. */
  private String includeBaseDir;

  public DependencyAwareness(AS400 system, boolean debug, boolean verbose) {
    this.system = system;
    this.debug = debug;
    this.verbose = verbose;
  }

  public void detectDependencies(BuildSpec globalSpec) throws Exception{

    if (verbose) logger.info("Detecting source object dependencies");
    /* This let us map name string to object name. TODO: There has to be a better way of doing this */

    this.totalTargets = globalSpec.targets.size();
    this.processed.set(0);
    this.exportedProcToModule.clear();
    this.exportToSrvpgm.clear();
    this.bnddirMembership.clear();
    this.moduleBndDirs.clear();
    this.includeBaseDir = null;

    keyLookup.clear();
    projectCmds.clear();
    projectCmdStmtPattern = null;
    for (TargetKey k : globalSpec.targets.keySet()) {
      keyLookup.put(k.asMapKey(), k);
      if (k.isCmd()) {
        projectCmds.put(k.getObjectName().toUpperCase(), k);
      }
    }
    if (!projectCmds.isEmpty()) {
      StringBuilder alt = new StringBuilder();
      for (String cmdName : projectCmds.keySet()) {
        if (alt.length() > 0) alt.append('|');
        alt.append(Pattern.quote(cmdName));
      }
      projectCmdStmtPattern = Pattern.compile(
          "^\\s*(?:[A-Z0-9$#@_]{1,10}:\\s*)?(" + alt + ")\\b",
          Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    }

    /* Build override map */
    buildFileOverrideMap(globalSpec);

    /* We need the base dir because relative SRCSTMF paths resolve against it */
    String baseDir = globalSpec.getBaseDirectory();
    if (baseDir == null) throw new RuntimeException("Base directory not set in BuildSpec");

    /* Set stream file + apply MODULE from spec params onto TargetKey */
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();

      if (!target.containsStreamFile() && targetSpec.params.containsKey(ParamCmd.SRCSTMF)) {
        String relPath = targetSpec.params.get(ParamCmd.SRCSTMF);
        if (relPath != null && !relPath.isEmpty()) {
          target.setStreamSourceFile(relPath);
        }
      }

      /* MODULE must be on TargetKey before CRTSRVPGM dep resolution */
      if (targetSpec.params.containsKey(ParamCmd.MODULE)) {
        String modules = targetSpec.params.get(ParamCmd.MODULE);
        if (modules != null && !modules.trim().isEmpty()) {
          try {
            target.put(ParamCmd.MODULE, modules.trim());
          } catch (Exception ignore) {
            /* invalid for non-srvpgm — ignore */
          }
        }
      }

      /* Explicit PGM from YAML for CRTCMD (overrides later source inference) */
      if (target.isCmd() && targetSpec.params.containsKey(ParamCmd.PGM)) {
        String pgm = targetSpec.params.get(ParamCmd.PGM);
        if (pgm != null && !pgm.trim().isEmpty()) {
          try {
            target.put(ParamCmd.PGM, pgm.trim());
          } catch (Exception ignore) {
            /* invalid — ignore */
          }
        }
      }
    }

    // Phase 1: Process only modules to populate the export map
    List<CompletableFuture<Void>> moduleFutures = new ArrayList<>();
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();

      if (!target.isModule()) continue;
      if (!target.containsStreamFile()) continue;

      String fullPath = resolveFullPath(baseDir, target.getStreamFile());
      moduleFutures.add(collectExportedProceduresAsync(target, fullPath));
    }
    CompletableFuture.allOf(moduleFutures.toArray(new CompletableFuture[0])).join();
    showLogs(globalSpec);

    globalSpec.setExportedProcedures(exportedProcToModule);

    /* Infer MODULE lists for srvpgms from binder EXPORT symbols + export map */
    inferSrvpgmModules(globalSpec, baseDir);

    this.includeBaseDir = baseDir;
    buildExportToSrvpgmMap(globalSpec, baseDir);
    collectBnddirMembership(globalSpec);

    /* CMD → processing program when PGM param is set (YAML / scan base overlay) */
    applyCmdPgmDeps(globalSpec);

    /* Program → SRVPGM edges from ADDBNDDIRE on consumer targets */
    applyAddBndDirEDeps(globalSpec);

    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();

      if (!target.containsStreamFile()) {
        if (verbose) logger.info("Target " + target.asString() + " does not contains stream file to scan");
        continue;
      }

      String fullPath = resolveFullPath(baseDir, target.getStreamFile());
      futures.add(processTargetAsync(target, fullPath));
    }

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    /* Programs with BNDDIR('X') also depend on srvpgms registered into X via ADDBNDDIRE */
    applyBndDirMembershipFanout(globalSpec);

    /* CRTSRVPGM BNDSRVPGM from member modules' foreign *SRVPGM imports */
    applySrvpgmBndSrvPgmFromModules(globalSpec);

    /* CRTSRVPGM BNDDIR from member modules' ctl-opt (not a module compile edge) */
    applySrvpgmBndDirFromModules(globalSpec);

    showLogs(globalSpec);
  }

  /**
   * For each SRVPGM without an explicit MODULE list, map binder
   * {@code EXPORT SYMBOL('X')} names to modules via the export map.
   */
  private void inferSrvpgmModules(BuildSpec globalSpec, String baseDir) {
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();

      if (!target.isServiceProgram()) continue;

      if (targetSpec.params.containsKey(ParamCmd.MODULE)) {
        String existing = targetSpec.params.get(ParamCmd.MODULE);
        if (existing != null && !existing.trim().isEmpty()) {
          try {
            target.put(ParamCmd.MODULE, existing.trim());
          } catch (Exception ignore) {}
          continue;
        }
      }

      if (!target.containsStreamFile()) continue;

      try {
        String fullPath = resolveFullPath(baseDir, target.getStreamFile());
        String sourceCode = readSource(fullPath);

        Set<String> moduleNames = new LinkedHashSet<>();
        Matcher m = BND_EXPORT_SYMBOL.matcher(sourceCode);
        while (m.find()) {
          String symbol = m.group(1).trim().toUpperCase();
          if (symbol.isEmpty()) continue;
          TargetKey mod = exportedProcToModule.get(symbol);
          if (mod != null && mod.isModule()) {
            moduleNames.add(mod.getObjectName());
          }
        }

        if (moduleNames.isEmpty()) {
          if (verbose) {
            logger.info("Could not infer MODULE list for {} (no matching EXPORT symbols)",
                target.asString());
          }
          continue;
        }

        String joined = String.join(" ", moduleNames);
        targetSpec.params.put(ParamCmd.MODULE, joined);
        target.put(ParamCmd.MODULE, joined);
        if (verbose) {
          logger.info("Inferred MODULE for {}: {}", target.asString(), joined);
        }
      } catch (Exception e) {
        logger.warn("Failed to infer MODULE for {}: {}", target.asString(), e.getMessage());
      }
    }
  }

  /**
   * For each *CMD target with an explicit {@code PGM} param (from YAML or scan
   * base overlay), put it on the TargetKey and add CMD → program when that
   * program is a build target. Does not read CMD definition source — {@code PGM}
   * is not a valid keyword on the CMD statement.
   */
  private void applyCmdPgmDeps(BuildSpec globalSpec) {
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();

      if (!target.isCmd()) continue;

      String pgmName = null;
      if (targetSpec.params.containsKey(ParamCmd.PGM)) {
        String existing = targetSpec.params.get(ParamCmd.PGM);
        if (existing != null && !existing.trim().isEmpty()) {
          pgmName = stripLibQualifier(existing.trim());
        }
      }
      if (pgmName == null && target.containsKey(ParamCmd.PGM)) {
        String existing = target.get(ParamCmd.PGM);
        if (existing != null && !existing.trim().isEmpty()) {
          pgmName = stripLibQualifier(existing.trim());
        }
      }

      if (pgmName == null || pgmName.isEmpty()) continue;
      if (!pgmName.matches("[A-Z0-9$#@_]{1,10}")) continue;

      targetSpec.params.put(ParamCmd.PGM, pgmName);
      try {
        target.put(ParamCmd.PGM, pgmName);
      } catch (Exception ignore) {}

      TargetKey pgmKey = keyLookup.get(pgmName + "." + ObjectType.PGM.name());
      if (pgmKey == null || !pgmKey.isProgram()) {
        if (verbose) {
          logger.info("CRTCMD PGM {} for {} is not a build target (param only)",
              pgmName, target.asString());
        }
        continue;
      }

      target.addChild(pgmKey);
      pgmKey.addFather(target);
      if (verbose) {
        logger.info("CMD PGM dependency: {} depends on processing program {}",
            target.asString(), pgmKey.asString());
      }
    }
  }

  /**
   * Map export symbols to the *SRVPGM that provides them (binder EXPORT and/or
   * module exports for modules listed on the srvpgm).
   */
  private void buildExportToSrvpgmMap(BuildSpec globalSpec, String baseDir) {
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey srv = entry.getKey();
      if (!srv.isServiceProgram()) continue;

      /* Binder EXPORT SYMBOL('X') */
      if (srv.containsStreamFile()) {
        try {
          String code = readSource(resolveFullPath(baseDir, srv.getStreamFile()));
          Matcher m = BND_EXPORT_SYMBOL.matcher(code);
          while (m.find()) {
            String sym = m.group(1).trim().toUpperCase();
            if (sym.isEmpty()) continue;
            putExportSrvpgm(sym, srv);
          }
        } catch (Exception e) {
          if (verbose) {
            logger.warn("Could not read binder source for {}: {}", srv.asString(), e.getMessage());
          }
        }
      }

      /* Module list → exports from those modules */
      Set<String> modNames = new LinkedHashSet<>();
      BuildSpec.TargetSpec ts = entry.getValue();
      if (ts != null && ts.params.containsKey(ParamCmd.MODULE)) {
        String mods = ts.params.get(ParamCmd.MODULE);
        if (mods != null) {
          for (String part : mods.trim().split("\\s+")) {
            if (!part.isEmpty()) modNames.add(stripLibQualifier(part));
          }
        }
      }
      try {
        if (srv.getCompilationCommand() == CompilationPattern.CompCmd.CRTSRVPGM) {
          for (String mod : srv.getModulesNameList()) {
            modNames.add(stripLibQualifier(mod));
          }
        }
      } catch (Exception ignore) {}

      for (Map.Entry<String, TargetKey> ex : exportedProcToModule.entrySet()) {
        TargetKey mod = ex.getValue();
        if (mod == null || !mod.isModule()) continue;
        if (!modNames.contains(mod.getObjectName().toUpperCase())) continue;
        putExportSrvpgm(ex.getKey().toUpperCase(), srv);
      }
    }
    if (verbose) {
      logger.info("Export→SRVPGM map size: {}", exportToSrvpgm.size());
    }
  }

  private void putExportSrvpgm(String symbol, TargetKey srv) {
    TargetKey prev = exportToSrvpgm.putIfAbsent(symbol, srv);
    if (prev != null && !prev.equals(srv) && verbose) {
      logger.info("Export {} claimed by both {} and {} (keeping first)",
          symbol, prev.asString(), srv.asString());
    }
  }

  /** Collect BNDDIR → SRVPGM membership from all ADDBNDDIRE hooks. */
  private void collectBnddirMembership(BuildSpec globalSpec) {
    for (BuildSpec.TargetSpec spec : globalSpec.targets.values()) {
      if (spec == null) continue;
      collectBnddirMembershipFromHooks(spec.before);
      collectBnddirMembershipFromHooks(spec.after);
    }
    collectBnddirMembershipFromHooks(globalSpec.before);
    collectBnddirMembershipFromHooks(globalSpec.after);
  }

  private void collectBnddirMembershipFromHooks(List<CommandObject> hooks) {
    if (hooks == null) return;
    for (CommandObject cmd : hooks) {
      if (cmd == null || cmd.getSystemCommand() != SysCmd.ADDBNDDIRE) continue;
      if (!cmd.containsKey(ParamCmd.BNDDIR) || !cmd.containsKey(ParamCmd.OBJ)) continue;
      String bndName = stripLibQualifier(cmd.get(ParamCmd.BNDDIR));
      if (bndName == null || bndName.isEmpty()) continue;

      for (String token : cmd.get(ParamCmd.OBJ).trim().split("\\s+")) {
        if (token.isEmpty()) continue;
        String objName = stripLibQualifier(token);
        if (objName == null || objName.isEmpty()) continue;
        TargetKey srv = keyLookup.get(objName + "." + ObjectType.SRVPGM.name());
        if (srv == null || !srv.isServiceProgram()) continue;
        bnddirMembership.computeIfAbsent(bndName, k -> new LinkedHashSet<>()).add(srv);
      }
    }
  }

  /**
   * Consumer ADDBNDDIRE (program before/after): program → each OBJ *SRVPGM.
   * Srvpgm-side hooks only contribute membership (see {@link #collectBnddirMembership}).
   */
  private void applyAddBndDirEDeps(BuildSpec globalSpec) {
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();
      if (targetSpec == null) continue;

      applyConsumerAddBndDirE(target, targetSpec.before);
      applyConsumerAddBndDirE(target, targetSpec.after);
    }
  }

  private void applyConsumerAddBndDirE(TargetKey target, List<CommandObject> hooks) {
    if (hooks == null || hooks.isEmpty()) return;

    for (CommandObject cmd : hooks) {
      if (cmd == null || cmd.getSystemCommand() != SysCmd.ADDBNDDIRE) continue;
      if (!cmd.containsKey(ParamCmd.OBJ)) continue;

      String objs = cmd.get(ParamCmd.OBJ);
      if (objs == null || objs.trim().isEmpty()) continue;

      for (String token : objs.trim().split("\\s+")) {
        if (token.isEmpty()) continue;
        String objName = stripLibQualifier(token);
        if (objName == null || objName.isEmpty()) continue;
        if (!objName.matches("[A-Z0-9$#@_]{1,10}")) continue;

        TargetKey srvKey = keyLookup.get(objName + "." + ObjectType.SRVPGM.name());
        if (srvKey == null || !srvKey.isServiceProgram()) {
          if (verbose) {
            logger.info(
                "ADDBNDDIRE OBJ {} for {} is not a build-target SRVPGM (ignored for topo)",
                objName, target.asString());
          }
          continue;
        }

        /* Srvpgm registering itself: membership only (fan-out later) */
        if (target.isServiceProgram() && target.equals(srvKey)) {
          continue;
        }
        /* Srvpgm registering another srvpgm into a BNDDIR: membership only */
        if (target.isServiceProgram()) {
          continue;
        }
        /* Module that is compiled into this srvpgm: edge already exists the other way */
        if (moduleBelongsToSrvpgm(target, srvKey)) {
          if (verbose) {
            logger.info(
                "ADDBNDDIRE dependency skipped: {} is a MODULE of {} (would cycle)",
                target.asString(), srvKey.asString());
          }
          continue;
        }

        target.addChild(srvKey);
        srvKey.addFather(target);
        if (verbose) {
          logger.info(
              "ADDBNDDIRE dependency: {} depends on {} (consumer hook)",
              target.asString(), srvKey.asString());
        }
      }
    }
  }

  /**
   * Lift each member module's foreign *SRVPGM children onto the parent *SRVPGM
   * (bind-time: CRTSRVPGM must resolve those imports). Sets inferred
   * {@code BNDSRVPGM} when the spec did not already name one.
   */
  private void applySrvpgmBndSrvPgmFromModules(BuildSpec globalSpec) {
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey srv = entry.getKey();
      BuildSpec.TargetSpec spec = entry.getValue();
      if (srv == null || spec == null || !srv.isServiceProgram()) continue;

      Set<TargetKey> imported = new LinkedHashSet<TargetKey>();

      for (String modName : srvpgmModuleNames(srv, spec)) {
        TargetKey mod = keyLookup.get(modName + "." + ObjectType.MODULE.name());
        if (mod == null || !mod.isModule()) continue;
        for (TargetKey child : mod.getChildsList()) {
          if (child == null || !child.isServiceProgram() || child.equals(srv)) continue;
          imported.add(child);
        }
      }

      String existing = spec.params.get(ParamCmd.BNDSRVPGM);
      boolean explicit = isExplicitBndSrvPgm(existing);
      if (explicit) {
        for (String token : existing.trim().split("\\s+")) {
          if (token.isEmpty()) continue;
          String name = stripLibQualifier(token);
          if (name == null || name.isEmpty() || name.equals("*NONE") || name.equals("NONE")) {
            continue;
          }
          TargetKey other = keyLookup.get(name + "." + ObjectType.SRVPGM.name());
          if (other == null || !other.isServiceProgram() || other.equals(srv)) continue;
          imported.add(other);
        }
      }

      for (TargetKey other : imported) {
        srv.addChild(other);
        other.addFather(srv);
        if (verbose) {
          logger.info(
              "SRVPGM bind dependency: {} depends on {} (module import / BNDSRVPGM)",
              srv.asString(), other.asString());
        }
      }

      if (explicit || imported.isEmpty()) continue;

      StringBuilder joined = new StringBuilder();
      for (TargetKey other : imported) {
        if (joined.length() > 0) joined.append(' ');
        joined.append(other.getObjectName());
      }
      spec.params.put(ParamCmd.BNDSRVPGM, joined.toString());
      try {
        srv.put(ParamCmd.BNDSRVPGM, joined.toString());
      } catch (Exception ignore) {}
      if (verbose) {
        logger.info("Inferred BNDSRVPGM for {}: {}", srv.asString(), joined);
      }
    }
  }

  private static boolean isExplicitBndSrvPgm(String value) {
    if (value == null) return false;
    String s = value.trim();
    if (s.isEmpty()) return false;
    String upper = s.toUpperCase();
    return !upper.equals("*NONE") && !upper.equals("NONE");
  }

  /**
   * Bind-time: parent *SRVPGM depends on BNDDIRs named in member modules'
   * ctl-opt. Skips D when S is already a member of D (S → D → S).
   */
  private void applySrvpgmBndDirFromModules(BuildSpec globalSpec) {
    if (moduleBndDirs.isEmpty()) return;

    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey srv = entry.getKey();
      BuildSpec.TargetSpec spec = entry.getValue();
      if (srv == null || spec == null || !srv.isServiceProgram()) continue;

      Set<TargetKey> dirs = new LinkedHashSet<TargetKey>();
      for (String modName : srvpgmModuleNames(srv, spec)) {
        TargetKey mod = keyLookup.get(modName + "." + ObjectType.MODULE.name());
        if (mod == null) continue;
        Set<String> names = moduleBndDirs.get(mod);
        if (names == null) continue;
        for (String bndName : names) {
          TargetKey dir = keyLookup.get(bndName + "." + ObjectType.BNDDIR.name());
          if (dir == null || !dir.isBndDir()) continue;
          if (srvpgmIsMemberOf(srv, bndName)) {
            if (verbose) {
              logger.info(
                  "SRVPGM BNDDIR lift skipped: {} is a member of {} (would cycle)",
                  srv.asString(), dir.asString());
            }
            continue;
          }
          dirs.add(dir);
        }
      }

      for (TargetKey dir : dirs) {
        srv.addChild(dir);
        dir.addFather(srv);
        if (verbose) {
          logger.info(
              "SRVPGM bind BNDDIR: {} depends on {} (from member module ctl-opt)",
              srv.asString(), dir.asString());
        }
      }

      String existing = spec.params.get(ParamCmd.BNDDIR);
      boolean explicit = existing != null && !existing.trim().isEmpty();
      if (explicit || dirs.isEmpty()) continue;

      StringBuilder joined = new StringBuilder();
      for (TargetKey dir : dirs) {
        if (joined.length() > 0) joined.append(' ');
        joined.append(dir.getObjectName());
      }
      spec.params.put(ParamCmd.BNDDIR, joined.toString());
      try {
        srv.put(ParamCmd.BNDDIR, joined.toString());
      } catch (Exception ignore) {}
      if (verbose) {
        logger.info("Inferred BNDDIR for {}: {}", srv.asString(), joined);
      }
    }
  }

  private boolean srvpgmIsMemberOf(TargetKey srv, String bndName) {
    if (srv == null || bndName == null) return false;
    Set<TargetKey> members = bnddirMembership.get(bndName.toUpperCase());
    if (members == null) return false;
    return members.contains(srv);
  }

  private List<String> srvpgmModuleNames(TargetKey srv, BuildSpec.TargetSpec spec) {
    Set<String> names = new LinkedHashSet<String>();
    try {
      for (String m : srv.getModulesNameList()) {
        String n = stripLibQualifier(m);
        if (n != null && !n.isEmpty()) names.add(n);
      }
    } catch (Exception ignore) {}
    String fromSpec = spec.params.get(ParamCmd.MODULE);
    if (fromSpec != null) {
      for (String part : fromSpec.trim().split("\\s+")) {
        if (part.isEmpty()) continue;
        String n = stripLibQualifier(part);
        if (n != null && !n.isEmpty()) names.add(n);
      }
    }
    return new ArrayList<String>(names);
  }

  /**
   * After source scan, targets that use BNDDIR(D) also depend on every SRVPGM
   * registered into D via ADDBNDDIRE (srvpgm-side or any membership hooks).
   * A *MODULE does not depend on the *SRVPGM it is compiled into.
   */
  private void applyBndDirMembershipFanout(BuildSpec globalSpec) {
    if (bnddirMembership.isEmpty()) return;

    for (TargetKey target : globalSpec.targets.keySet()) {
      for (TargetKey child : new ArrayList<>(target.getChildsList())) {
        if (child == null || !child.isBndDir()) continue;
        String bndName = child.getObjectName().toUpperCase();
        Set<TargetKey> members = bnddirMembership.get(bndName);
        if (members == null || members.isEmpty()) continue;

        for (TargetKey srv : members) {
          if (srv == null || srv.equals(target)) continue;
          if (moduleBelongsToSrvpgm(target, srv)) continue;
          target.addChild(srv);
          srv.addFather(target);
          if (verbose) {
            logger.info(
                "BNDDIR membership: {} depends on {} (registered in {})",
                target.asString(), srv.asString(), bndName);
          }
        }
      }
    }
  }

  /**
   * Resolve /copy|/include (including nested) onto the consumer. The include
   * is not a compile target; its mtime participates in --diff for this target.
   */
  private void attachCopyIncludes(
      TargetKey target, String sourceCode, String programFullPath, List<String> logs) {
    Set<String> resolved = new LinkedHashSet<>();
    collectIncludes(sourceCode, programFullPath, resolved);
    for (String path : resolved) {
      target.addIncludeFile(path);
      if (verbose) logs.add("Include attachment: " + target.asString() + " <- " + path);
    }
  }

  private void collectIncludes(String sourceCode, String fromPath, Set<String> resolved) {
    for (String rel : findCopyIncludePaths(sourceCode)) {
      String path = resolveIncludePath(rel, fromPath);
      if (path == null || !resolved.add(path)) continue;
      try {
        collectIncludes(readSource(path), path, resolved);
      } catch (Exception ignore) {
        /* attachment already recorded */
      }
    }
  }

  /**
   * For ILE programs and SQL modules: extract prototypes from attached includes
   * (not EXTPGM), match names to project exports → depender depends
   * on providing *SRVPGM. A *MODULE does not depend on its own parent *SRVPGM.
   */
  private void getIncludeProtoSrvpgmDependencies(TargetKey target, List<String> logs) {
    if (exportToSrvpgm.isEmpty() && exportedProcToModule.isEmpty()) return;
    if (target.getIncludeFiles().isEmpty()) return;

    Set<String> protoNames = new LinkedHashSet<>();
    for (String resolved : target.getIncludeFiles()) {
      try {
        String inc = readSource(resolved);
        extractPrototypeNames(inc, protoNames);
      } catch (Exception e) {
        if (verbose) logs.add("Could not read include " + resolved + ": " + e.getMessage());
      }
    }

    for (String proto : protoNames) {
      TargetKey srv = exportToSrvpgm.get(proto);
      if (srv == null) {
        TargetKey mod = exportedProcToModule.get(proto);
        if (mod != null && mod.isModule()) {
          srv = findSrvpgmForModule(mod);
        }
      }
      if (srv == null || !srv.isServiceProgram()) {
        if (verbose) {
          logs.add("Include proto " + proto + " not a project SRVPGM export (ignored)");
        }
        continue;
      }
      if (moduleBelongsToSrvpgm(target, srv)) {
        if (verbose) {
          logs.add("Include-proto " + proto + " is export of parent " + srv.asString()
              + "; skipped to avoid module↔srvpgm cycle");
        }
        continue;
      }
      target.addChild(srv);
      srv.addFather(target);
      if (verbose) {
        logs.add("Include-proto dependency: " + target.asString()
            + " uses " + proto + " from " + srv.asString());
      }
    }
  }

  private TargetKey findSrvpgmForModule(TargetKey mod) {
    if (mod == null || !mod.isModule()) return null;
    for (TargetKey k : keyLookup.values()) {
      if (moduleBelongsToSrvpgm(mod, k)) return k;
    }
    return null;
  }

  /**
   * True when {@code mod} is a *MODULE listed on {@code srv}'s MODULE parameter
   * (explicit or inferred). Used to avoid module → own-parent *SRVPGM edges.
   */
  private boolean moduleBelongsToSrvpgm(TargetKey mod, TargetKey srv) {
    if (mod == null || srv == null || !mod.isModule() || !srv.isServiceProgram()) {
      return false;
    }
    String modName = mod.getObjectName().toUpperCase();
    try {
      for (String m : srv.getModulesNameList()) {
        if (modName.equals(stripLibQualifier(m))) return true;
      }
    } catch (Exception ignore) {}
    return false;
  }

  private static Set<String> findCopyIncludePaths(String sourceCode) {
    Set<String> paths = new LinkedHashSet<>();
    try (java.util.Scanner sc = new java.util.Scanner(sourceCode)) {
      while (sc.hasNextLine()) {
        String line = sc.nextLine();
        if (isRpgCommentLine(line)) continue;
        Matcher m = COPY_INCLUDE_DIR.matcher(line);
        if (!m.find()) continue;
        String path = m.group(2);
        /* Strip trailing junk */
        path = path.replaceAll("[,;]+$", "");
        if (!path.isEmpty()) paths.add(path);
      }
    }
    return paths;
  }

  /** Free-form {@code //} or fixed-form {@code *} in column 7. */
  private static boolean isRpgCommentLine(String line) {
    if (line == null) return false;
    if (line.trim().startsWith("//")) return true;
    return line.length() >= 7
        && line.charAt(6) == '*'
        && !line.substring(0, 6).matches(".*\\S.*");
  }

  private String resolveIncludePath(String rel, String programFullPath) {
    if (rel == null) return null;
    rel = rel.replace("'", "").trim();
    List<String> candidates = new ArrayList<>();
    if (rel.startsWith("/") || (rel.length() > 2 && rel.charAt(1) == ':')) {
      candidates.add(rel);
    } else {
      if (includeBaseDir != null) {
        candidates.add(resolveFullPath(includeBaseDir, rel));
      }
      if (programFullPath != null) {
        File prog = new File(programFullPath);
        File parent = prog.getParentFile();
        if (parent != null) {
          candidates.add(new File(parent, rel).getPath());
          File grand = parent.getParentFile();
          if (grand != null) {
            candidates.add(new File(grand, rel).getPath());
          }
        }
      }
    }
    for (String c : candidates) {
      File f = new File(c);
      if (f.isFile()) return f.getAbsolutePath();
    }
    /* Case-insensitive basename search under base dir (Copy_mbrs vs Copy_Mbrs) */
    if (includeBaseDir != null && !rel.startsWith("/")) {
      File found = findFileIgnoreCase(new File(includeBaseDir), rel);
      if (found != null) return found.getAbsolutePath();
    }
    return null;
  }

  private static File findFileIgnoreCase(File root, String relative) {
    String[] parts = relative.replace('\\', '/').split("/");
    File cur = root;
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        cur = cur.getParentFile();
        if (cur == null) return null;
        continue;
      }
      File[] kids = cur.listFiles();
      if (kids == null) return null;
      File next = null;
      for (File k : kids) {
        if (k.getName().equalsIgnoreCase(part)) {
          next = k;
          break;
        }
      }
      if (next == null) return null;
      cur = next;
    }
    return cur.isFile() ? cur : null;
  }

  private static void extractPrototypeNames(String includeSource, Set<String> out) {
    String[] lines = includeSource.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      String trimmed = line.trim();
      if (trimmed.startsWith("//")) continue;

      /* Free-form dcl-pr */
      Matcher dcl = DCL_PR_NAME.matcher(line);
      if (dcl.find()) {
        String name = dcl.group(1).toUpperCase();
        String window = line;
        for (int j = i; j < Math.min(i + 15, lines.length); j++) {
          window += " " + lines[j];
          if (lines[j].toLowerCase().contains("end-pr")) break;
        }
        String winLow = window.toLowerCase();
        if (winLow.contains("extpgm") || winLow.contains("extproc(*system")) {
          continue; // dynamic call, not bound export
        }
        if (name.matches("[A-Z0-9$#@_]{1,128}")) out.add(name);
        continue;
      }

      /* Fixed-format D…PR */
      if (line.length() >= 6) {
        Matcher fix = FIXED_D_PR.matcher(line);
        if (fix.find()) {
          String name = fix.group(1).toUpperCase();
          String low = line.toLowerCase();
          if (low.contains("extpgm")) continue;
          /* continuation name... — still a proto name */
          if (name.matches("[A-Z0-9$#@_]{1,128}")) out.add(name);
        }
      }
    }
  }

  private static String stripLibQualifier(String qualified) {
    if (qualified == null) return null;
    String s = qualified.trim().toUpperCase();
    while (s.startsWith("''") && s.endsWith("''") && s.length() > 4) {
      s = s.substring(2, s.length() - 2).trim();
    }
    s = s.replace("'", "").trim();
    if (s.contains("/")) {
      s = s.replaceAll("^.*[\\\\/]", "");
    }
    return s;
  }

  private static String resolveFullPath(String baseDir, String streamFile) {
    if (streamFile == null) return baseDir;
    String rel = streamFile.replace("'", "").trim();
    if (rel.startsWith("/") || (rel.length() > 2 && rel.charAt(1) == ':')) {
      return rel;
    }
    if (baseDir.endsWith("/") || baseDir.endsWith("\\")) {
      return baseDir + rel;
    }
    return baseDir + "/" + rel;
  }

  /** Read source from local filesystem if present, otherwise from IFS. */
  private String readSource(String fullPath) throws Exception {
    File local = new File(fullPath);
    if (local.isFile()) {
      try (InputStream stream = new FileInputStream(local)) {
        return new String(readAllBytes(stream), StandardCharsets.UTF_8);
      }
    }

    if (system == null) {
      throw new RuntimeException(
          "Source file not found locally and no AS400 connection: " + fullPath);
    }

    IFSFile sourceFile = new IFSFile(system, fullPath);
    if (!sourceFile.exists()) {
      throw new RuntimeException("Source file not found: " + fullPath);
    }
    try (InputStream stream = new IFSFileInputStream(sourceFile)) {
      return new String(readAllBytes(stream), StandardCharsets.UTF_8);
    }
  }

  private static byte[] readAllBytes(InputStream stream) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = stream.read(chunk)) != -1) {
      buffer.write(chunk, 0, n);
    }
    return buffer.toByteArray();
  }

  private void showLogs(BuildSpec globalSpec){
    // Print logs in original order
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      List<String> logs = targetLogs.getOrDefault(target, List.of());
      if (logs.size() == 0) continue;

      for (String log : logs) {
        logger.info(log);
      }
    }

    /* Reset logs after show */
    targetLogs.clear();
  }

  private CompletableFuture<Void> collectExportedProceduresAsync(TargetKey target, String fullPath) {
  return CompletableFuture.runAsync(() -> {
    List<String> logs = new ArrayList<>();
    Set<String> exportedProcs = new HashSet<>();

    try{ 
      if (verbose) logs.add("Scannig sources for exports: " + target.asString());

      String sourceCode = readSource(fullPath);

      logs.add("Dependencies of " + target.asString());

      // Pattern for free-format: dcl-proc ProcName export;  (EXPORT can appear after name or params)
      // We capture the word immediately after dcl-proc as the procedure name
      Pattern exportProcPattern = Pattern.compile(
          "\\bdcl-proc\\s+([A-Z0-9_]+)\\b.*?\\bexport\\b",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

      Matcher matcher = exportProcPattern.matcher(sourceCode);

      while (matcher.find()) {
        String procName = matcher.group(1).toUpperCase();
        if (procName.isEmpty()) continue;
        exportedProcs.add(procName);
      }

      // NEW: fixed-format pattern for P-spec procedures with EXPORT
      Pattern fixedExportProcPattern = Pattern.compile(
          "^\\s*P\\s*([A-Z0-9]+)\\s+B\\b.*\\bEXPORT\\b",
          Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

      Matcher fixedMatcher = fixedExportProcPattern.matcher(sourceCode);

      while (fixedMatcher.find()) {
        String procName = fixedMatcher.group(1).toUpperCase();
        if (procName.isEmpty()) continue;
        exportedProcs.add(procName);
      }

      if (exportedProcs.isEmpty()){
        logs.add("No Exported procedures found in " + target.asString());
        return;
      }

      logs.add("Exported procedures found in " + target.asString());
      for (String export: exportedProcs) {
        logs.add("EXPORT " + export);
        exportedProcToModule.put(export, target);
      } 

    } catch (Exception e) {
      logs.add("ERROR processing exports " + target.asString() + ": " + e.getMessage());
    } finally {
      targetLogs.put(target, logs);  // Store for later ordered printing
    }
    }
    );
  }

  private CompletableFuture<Void> processTargetAsync(TargetKey target, String fullPath) {
  return CompletableFuture.runAsync(() -> {
    List<String> logs = new ArrayList<>();
    try {
      if (verbose) logs.add("Scannig sources: " + target.asString());

      String sourceCode = readSource(fullPath);

      logs.add("Dependencies of " + target.asString());

      //TODO: Improve this.
      /* Get srvpgm module dependencies */
      switch (target.getCompilationCommand()) {
        /* Get srvpgm modules */
        case CRTSRVPGM:
          List<String> modulesList = target.getModulesNameList();
          if (modulesList.isEmpty()) break;

          for (String mod : modulesList) {
            TargetKey modDep = keyLookup.getOrDefault(mod + "." + ObjectType.MODULE.name(), null);
            if (modDep == null) continue;
            if (!modDep.isModule()) continue;

              /* Add module dependency to SrvPgm */
            target.addChild(modDep);
            /* Add SrvPgm dependency to module */
            modDep.addFather(target);
            if (verbose) logs.add("Dependency: " + target.asString() + " depends on " + modDep.asString());
          }
          break;
      
        case CRTBNDRPG:
        case CRTSQLRPGI:
        case CRTRPGMOD:
          getBndDirDependencies(target, sourceCode, logs);
          break;
      }

      /* Non-target /copy|/include files attach to the consumer for --diff */
      switch (target.getCompilationCommand()) {
        case CRTBNDRPG:
        case CRTSQLRPGI:
        case CRTRPGMOD:
          attachCopyIncludes(target, sourceCode, fullPath, logs);
          break;
      }

      /* PGM → SRVPGM via /copy|/include prototypes ∩ project exports */
      switch (target.getCompilationCommand()) {
        case CRTBNDRPG:
        case CRTSQLRPGI:
          getIncludeProtoSrvpgmDependencies(target, logs);
          break;
      }

      /* Get extpgm */
      switch (target.getCompilationCommand()) {
        case CRTBNDRPG:
        case CRTSQLRPGI:
        case CRTRPGMOD:
          /* Collect unique external program names referenced via EXTPGM */
          getExtPgmDependencies(target, sourceCode, logs);
          break;
      }

      /* Get dtaara and extname dependencies */
      switch (target.getCompilationCommand()) {
        case CRTBNDRPG:
        case CRTSQLRPGI:
        case CRTRPGMOD:
        case CRTRPGPGM:
          /* Collect dtaara referenced via DTAARA */
          getDtaAraDependencies(target, sourceCode, logs);

          /* Collect EXTNAME */
          getExtNameDependencies(target, sourceCode, logs);
          break;
      }

      /* Get f spec files dependencies */
      switch (target.getCompilationCommand()) {
        case CRTBNDRPG:
        case CRTSQLRPGI:
        case CRTRPGMOD:
          getFixedFormatFilesDependencies(target, sourceCode, logs);

          getFreeFormatFileDependencies(target, sourceCode, logs);    
          break;

        case CRTRPGPGM:
          getFixedFormatFilesDependencies(target, sourceCode, logs);
          break;
      }

      /* Get SQLRPGLE embedded dependencies */
      switch (target.getCompilationCommand()) {
        case CRTSQLRPGI:
          getEmbeddedSqlDependencies(target, sourceCode, logs);          
          break;
      }

      /* Get PF and LF dependencies */
      switch (target.getCompilationCommand()) {
        case CRTLF:
          getLfPFILEDependencies(target, sourceCode, logs);
          break;
      
        case CRTPF:
          getPfREFDependencies(target, sourceCode, logs);
          break;

        case CRTDSPF:
        case CRTPRTF:
          geDdsREFFLDDependencies(target, sourceCode, logs);
          break;
      }

      /* Get CLP, CLLE dependencies (CALL + project user-defined commands) */
      switch (target.getCompilationCommand()) {
        case CRTBNDCL:
        case CRTCLPGM:
        case CRTCLMOD:
          getClCallDependencies(target, sourceCode, logs);
          getClUserCmdDependencies(target, sourceCode, logs);
          break;
      }

      /* Get SQL dependencies */
      switch (target.getCompilationCommand()) {
        case RUNSQLSTM:
          // Existing embedded if needed
          getEmbeddedSqlDependencies(target, sourceCode, logs);  // Keep for procedures/functions
          // New DDL detection
          getSqlDdlTableDependencies(target, sourceCode, logs);
          break;
      }

    } catch (Exception e) {
      logs.add("ERROR processing " + target.asString() + ": " + e.getMessage());
    } finally {
      if (target.getChildsCount() == 0) logs.add(target.asString() + ": No dependencies found");
      targetLogs.put(target, logs);  // Store for later ordered printing
      int count = processed.incrementAndGet();
      double percent = count * 100.0 / totalTargets;
      logger.info("Processed {} of {} targets ({}%)", count, totalTargets, String.format("%.1f", percent));
    }
    }
    );
  }

  /* This only works if you have the spec */
  private void buildFileOverrideMap(BuildSpec globalSpec) {
    // Global before hooks
    populateOverrideMap(globalSpec.before);

    // Per-target before hooks
    for (BuildSpec.TargetSpec spec : globalSpec.targets.values()) {
      populateOverrideMap(spec.before);
    }
  }

  private void populateOverrideMap(List<CommandObject> commands) {
    //if (!commands.contains(new CommandObject(SysCmd.OVRDBF))) return;

    for (CommandObject cmd : commands) {
      if (cmd.getSystemCommand() != SysCmd.OVRDBF) continue;
      String overridden = cmd.get(ParamCmd.FILE);     // "tmpdetord"
      String actual = cmd.get(ParamCmd.TOFILE).replaceAll("^.*[\\/]", "");       // "detord"
      if (overridden == null || actual == null) continue; 
      overridden = overridden.toUpperCase();
      actual = actual.toUpperCase();
      this.fileOverrideMap.put(overridden, actual);
      if (verbose) logger.info("Detected OVRDBF: {} -> {}", overridden, actual);
  }
  }

  private void getBndDirDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> bndDirNames = new HashSet<>();
    StringBuilder live = new StringBuilder();
    try (java.util.Scanner sc = new java.util.Scanner(sourceCode)) {
      while (sc.hasNextLine()) {
        String line = sc.nextLine();
        if (isRpgCommentLine(line)) continue;
        live.append(line).append('\n');
      }
    }
    Matcher block = BNDDIR_BLOCK.matcher(live);
    while (block.find()) {
      Matcher names = BNDDIR_NAME.matcher(block.group(1));
      while (names.find()) {
        String bndDirName = names.group(1).trim().toUpperCase();
        if (bndDirName.isEmpty()) continue;
        bndDirNames.add(bndDirName);
      }
    }

    if (target.isModule()) {
      if (!bndDirNames.isEmpty()) {
        moduleBndDirs.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).addAll(bndDirNames);
        if (verbose) {
          logs.add("Module BNDDIR recorded (bind-time on parent SRVPGM, not a module compile edge): "
              + target.asString() + " ctl-opt " + bndDirNames);
        }
      }
      return;
    }

    for (String bndDirName : bndDirNames) {
      TargetKey bndDirDep = keyLookup.getOrDefault(bndDirName + "." + ObjectType.BNDDIR.name(), null);
      if (bndDirDep == null || !bndDirDep.isBndDir()) {
        if (verbose) logs.add("Referenced BNDDIR not a build target, ignored: " + bndDirName + " (in " + target.asString() + ")");
        continue;
      }

      target.addChild(bndDirDep);
      bndDirDep.addFather(target);
      if (verbose) logs.add("BNDDIR dependency: " + target.asString() + " uses BNDDIR('" + bndDirName + "') ");
    }
  }

  private void getExtNameDependencies(TargetKey target, String sourceCode, List<String> logs) {
    Set<String> extNameFiles = new HashSet<>();

    Matcher matcher = EXTNAME_PATTERN.matcher(sourceCode);
    while (matcher.find()) {
      String fileName = matcher.group(1).trim().toUpperCase();
      if (fileName.isEmpty()) continue;
      extNameFiles.add(fileName);
    }

    for (String fileName : extNameFiles) {
      TargetKey fileKey = keyLookup.getOrDefault(fileName + "." + ParamCmd.FILE.name(), null);
      if (fileKey == null || !fileKey.isFile()) {
        if (verbose) logs.add("Referenced EXTNAME file not a build target, ignored: " + fileName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("EXTNAME dependency: " + target.asString() + " uses record format from file " + fileKey.asString() + " (EXTNAME(" + fileName + "))");
      target.addChild(fileKey);
      fileKey.addFather(target);
    }
  }

  private void getDtaAraDependencies(TargetKey target, String sourceCode, List<String> logs) {
    Set<String> dtaAraNames = new HashSet<>();

    Matcher matcher = DTAARA_PATTERN.matcher(sourceCode);
    while (matcher.find()) {
      String name = matcher.group(1).trim().toUpperCase();
      if (name.isEmpty()) continue;
      dtaAraNames.add(name);
    }

    for (String name : dtaAraNames) {
      TargetKey dtaKey = keyLookup.getOrDefault(name + "." + ObjectType.DTAARA.name(), null);

      if (dtaKey == null || !dtaKey.isDtaara()) {
        if (verbose) logs.add("Referenced DTAARA not a build target, ignored: " + name + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("DTAARA dependency: " + target.asString() + " uses DTAARA('" + name + "')");
      /* Dtaara are child of Target */
      target.addChild(dtaKey);
      /* Target is father of dtaara */
      dtaKey.addFather(target);
    }
  }

  private void getClCallDependencies(TargetKey target, String sourceCode, List<String> logs) {
    Set<String> calledPgms = new HashSet<>();

    Matcher m = CALL_PGM_PATTERN.matcher(sourceCode);
    while (m.find()) {
      String full = m.group(1).trim().toUpperCase();
      // Strip library if qualified (MYLIB/PGM -> PGM)
      String pgm = full.replaceAll("^.*[\\/]", "");
      if (pgm.isEmpty()) continue;
      if (!pgm.matches("[A-Z0-9$#@_]{1,10}")) continue;
      calledPgms.add(pgm);
    }

    m = CALL_DIRECT_PGM_PATTERN.matcher(sourceCode);
    while (m.find()) {
      String pgm = m.group(2).toUpperCase();
      if (pgm.isEmpty()) continue;
      calledPgms.add(pgm);
    }

    for (String pgmName : calledPgms) {
      TargetKey pgmKey = keyLookup.getOrDefault(pgmName + "." + ObjectType.PGM.name(), null);
      if (pgmKey == null || !pgmKey.isProgram()) {
        if (verbose) logs.add("Referenced CALL CL program not a build target, ignored: " + pgmName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("CL CALL dependency: " + target.asString() + " calls program " + pgmKey.asString() + " (CALL " + pgmName + ")");
      target.addChild(pgmKey);
      pgmKey.addFather(target);
    }
  }

  /**
   * Find project *CMD objects used as free-standing commands in CL source
   * (e.g. {@code CRTORD CUID(&CUID)} when {@code CRTORD} is a build target).
   * Only statement-leading tokens are matched so system commands and mid-line
   * mentions are ignored unless they equal a project CMD name.
   */
  private void getClUserCmdDependencies(TargetKey target, String sourceCode, List<String> logs) {
    if (projectCmdStmtPattern == null || projectCmds.isEmpty()) return;

    Set<String> found = new LinkedHashSet<>();
    Matcher m = projectCmdStmtPattern.matcher(sourceCode);
    while (m.find()) {
      String name = m.group(1).trim().toUpperCase();
      if (!name.isEmpty()) found.add(name);
    }

    for (String cmdName : found) {
      TargetKey cmdKey = projectCmds.get(cmdName);
      if (cmdKey == null || !cmdKey.isCmd()) {
        if (verbose) logs.add("Referenced CL command not a build target, ignored: " + cmdName
            + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("CL CMD dependency: " + target.asString() + " uses command "
          + cmdKey.asString() + " (" + cmdName + ")");
      target.addChild(cmdKey);
      cmdKey.addFather(target);
    }
  }

  private void getExtPgmDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> extPgmNames = new HashSet<>();

    Matcher extpgmMatcher = EXTPGM_PATTERN.matcher(sourceCode);
    while (extpgmMatcher.find()) {
      String pgmName = extpgmMatcher.group(1).trim().toUpperCase();
      if (pgmName.isEmpty()) continue;
      extPgmNames.add(pgmName);
    }

    /* Add dependencies for each referenced *PGM that is also a build target */
    for (String pgmName : extPgmNames) {
      TargetKey pgmKey = keyLookup.getOrDefault(pgmName + "." + ObjectType.PGM.name(), null);
      if (pgmKey == null || !pgmKey.isProgram()) { 
        if (verbose) logs.add("Referenced EXTPGM program not a build target, ignored: " + pgmName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("EXTPGM dependency: " + target.asString() + " calls program " + pgmKey.asString() + " (EXTPGM('" + pgmName + "'))");
      /* Files are child of Target */
      target.addChild(pgmKey);
      /* Target is father of files */
      pgmKey.addFather(target);
    }
  }

  private void getLfPFILEDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> basePfNames = new HashSet<>();

    try (java.util.Scanner scanner = new java.util.Scanner(sourceCode)) {
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        Matcher pfileMatcher = DDS_PFILE_PATTERN.matcher(line);
        while (pfileMatcher.find()) {
          String basePfName = pfileMatcher.group(1).trim().toUpperCase();
          if (!basePfName.isEmpty()) {
            basePfNames.add(basePfName);
          }
        }
      }
    }

    for (String basePfName : basePfNames) {
      TargetKey basePfKey = keyLookup.getOrDefault(basePfName + "." + ParamCmd.FILE.name(), null);
      if (basePfKey == null || !basePfKey.isFile()) {
        if (verbose) logs.add("Base PFILE not a build target: " + basePfName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("PFILE dependency: " + target.asString() + " depends on base PF " + basePfKey.asString() + " (PFILE(" + basePfName + "))");
      /* Files are child of Target */
      target.addChild(basePfKey);
      /* Target is father of files */
      basePfKey.addFather(target);
    }
  }

  private void getPfREFDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> refFileNames = new HashSet<>();

    try (java.util.Scanner scanner = new java.util.Scanner(sourceCode)) {
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        Matcher refMatcher = DDS_REF_PATTERN.matcher(line);
        while (refMatcher.find()) {
          String refFileName = refMatcher.group(1).trim().toUpperCase();
          if (refFileName.isEmpty()) continue;
          refFileNames.add(refFileName);
        }
      }
    }

    for (String refFileName : refFileNames) {
      TargetKey refFileKey = keyLookup.getOrDefault(refFileName + "." + ParamCmd.FILE.name(), null);
      if (refFileKey == null || !refFileKey.isFile()) {
        if (verbose) logs.add("Referenced REF file not a build target: " + refFileName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("REF dependency: " + target.asString() + " references file " + refFileKey.asString() + " (REF(" + refFileName + "))");
      /* Files are child of Target */
      target.addChild(refFileKey);
      /* Target is father of files */
      refFileKey.addFather(target);
    }
  }

  private void geDdsREFFLDDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> reffldFilesNames = new HashSet<>();

    try (java.util.Scanner scanner = new java.util.Scanner(sourceCode)) {
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        Matcher reffldMatcher = DDS_REFFLD_PATTERN.matcher(line);
        while (reffldMatcher.find()) {
          String refFileName = reffldMatcher.group(2);
          if (refFileName != null && !refFileName.trim().isEmpty()) {
            reffldFilesNames.add(refFileName.trim().toUpperCase());
          }
        }
      }
    }

    for (String refFileName : reffldFilesNames) {
      TargetKey refFileKey = keyLookup.getOrDefault(refFileName + "." + ParamCmd.FILE.name(), null);
      if (refFileKey == null || !refFileKey.isFile()) {
        if (verbose) logs.add("Referenced REFFLD file not a build target: " + refFileName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("REFFLD dependency: " + target.asString() + " references file " + refFileKey.asString() + " (REFFLD(... " + refFileName + "))");
      /* Files are child of Target */
      target.addChild(refFileKey);
      /* Target is father of files */
      refFileKey.addFather(target);
    }
  }

  /* 1. Fixed-format F-specs */
  private void getFixedFormatFilesDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> depFileNames = new HashSet<>();

    /* 1. Fixed-format F-specs */
    try (java.util.Scanner scanner = new java.util.Scanner(sourceCode)) {
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        Matcher fixedMatcher = FIXED_F_SPEC.matcher(line);
        if (!fixedMatcher.find())  continue;
        String fileName = fixedMatcher.group(1).trim().toUpperCase();
        fileName = fileName.replaceAll("[\\t\\n]", ""); // Remove /t /n
        if (fileName.isEmpty()) continue;
        // Skip non-files
        //if (fileName.matches("(?i)SFL.*|CTL.*|KEY.*|INFO.*|INDDS|INFDS|RENAME|SFILE|RRN.*|OPT.*")) {
        //  continue;
        //}
        depFileNames.add(fileName);
      }
    }

    addFileDependencies(target, depFileNames, logs);
  }

  /* 2. Free-format DCL-F */
  private void getFreeFormatFileDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> depFileNames = new HashSet<>();

    Matcher freeMatcher = FREE_DCL_F.matcher(sourceCode);
    while (freeMatcher.find()) {
      String rawName = freeMatcher.group(1).trim().toUpperCase();
      if (rawName.isEmpty()) continue;

      // Strip library if qualified (handles MYLIB/MYFILE or rare MYLIB.MYFILE)
      String fileName = rawName.replaceAll("^.*[\\/.]", "");
      if (fileName.isEmpty()) continue;
      depFileNames.add(fileName);
    }

    addFileDependencies(target, depFileNames, logs);
  }

  /* 3. Embedded SQL table references */
  private void getEmbeddedSqlDependencies(TargetKey target, String sourceCode, List<String> logs){
    Set<String> depFileNames = new HashSet<>();

    Matcher sqlMatcher = SQL_TABLE.matcher(sourceCode);
    while (sqlMatcher.find()) {
      String rawName = sqlMatcher.group(2).trim().toUpperCase();
      rawName = rawName.replaceAll("^[\"']|[\"']$", "");
      if (rawName.isEmpty()) continue;

      String tableName = rawName.replaceAll("^.*[\\/.]", "");
      if (tableName.isEmpty()) continue;
      depFileNames.add(tableName);

    }

    addFileDependencies(target, depFileNames, logs);
  }

  private void getSqlDdlTableDependencies(TargetKey target, String sourceCode, List<String> logs) {
    Set<String> tableNames = new HashSet<>();

    Matcher fromMatcher = SQL_FROM_JOIN_PATTERN.matcher(sourceCode);
    while (fromMatcher.find()) {
      String rawTable = fromMatcher.group(2).trim().toUpperCase();
      rawTable = rawTable.replaceAll("^[\"']|[\"']$", "");  // Strip quotes
      String tableName = rawTable.replaceAll("^.*[\\/.]", "");  // Strip schema/lib
      if (!tableName.isEmpty() && tableName.matches("[A-Z0-9$#@_]{1,10}")) {
          tableNames.add(tableName);
      }

      // Chain comma-separated tables from current position
      int pos = fromMatcher.end();
      Matcher commaMatcher = SQL_COMMA_TABLE_PATTERN.matcher(sourceCode);
      commaMatcher.region(pos, sourceCode.length());
      while (commaMatcher.find()) {
        String rawCommaTable = commaMatcher.group(1).trim().toUpperCase();
        rawCommaTable = rawCommaTable.replaceAll("^[\"']|[\"']$", "");
        String commaTableName = rawCommaTable.replaceAll("^.*[\\/.]", "");
        if (!commaTableName.isEmpty() && commaTableName.matches("[A-Z0-9$#@_]{1,10}")) {
            tableNames.add(commaTableName);
        }
        pos = commaMatcher.end();
        commaMatcher.region(pos, sourceCode.length());
      }
    }

    // Add as file deps
    for (String tableName : tableNames) {
      TargetKey tableKey = keyLookup.getOrDefault(tableName + "." + ParamCmd.FILE.name(), null);
      // Or if SQL tables: tableName + ".TABLE.SQL" or ".PF.DDS"
      if (tableKey == null || !tableKey.isFile()) {
        if (verbose) logs.add("Referenced SQL table not a build target, ignored: " + tableName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("SQL TABLE dependency: " + target.asString() + " references table " + tableKey.asString() + " (table " + tableName + ")");
      target.addChild(tableKey);
      tableKey.addFather(target);
    }
  }

  /* Add dependencies for each referenced file that is also a build target */
  private void addFileDependencies(TargetKey target, Set<String> fileNames, List<String> logs) {
    for (String depFileName : fileNames) {
      String override = this.fileOverrideMap.getOrDefault(depFileName, null);
      if (override != null) {
        if (verbose) logs.add("Change override " + depFileName + " for actual file " + override);
        depFileName = override; // Change override name to actual file
      }
      TargetKey fileKey = keyLookup.getOrDefault(depFileName + "." + ParamCmd.FILE.name(), null);
      if (fileKey == null || !fileKey.isFile()) {
        if (verbose) logs.add("Referenced FILE not a build target, ignored: " + depFileName + " (in " + target.asString() + ")");
        continue;
      }
      if (verbose) logs.add("FILE dependency: " + target.asString() + " depends on file " + fileKey.asString() + " (referenced as " + depFileName + ")");
      /* Files are child of Target */
      target.addChild(fileKey);
      /* Target is father of files */
      fileKey.addFather(target);
    }
  }

}
