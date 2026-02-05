package com.github.kraudy.compiler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

  // Broad regex for BNDDIR('NAME') – works for H-spec and CTL-OPT
  private static final Pattern BNDDIR_PATTERN = Pattern.compile(
      "\\bBNDDIR\\s*\\(\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);

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

  private final AS400 system;
  private final boolean debug;
  private final boolean verbose;
  private final Map<String, TargetKey> keyLookup = new HashMap<>();

  private final ConcurrentHashMap<TargetKey, List<String>> targetLogs = new ConcurrentHashMap<>();
  private final AtomicInteger processed = new AtomicInteger();
  private int totalTargets = 0;

  private final Map<String, String> fileOverrideMap = new HashMap<>();  // overriddenName -> actualToFile

  private final ConcurrentHashMap<String, TargetKey> exportedProcToModule = new ConcurrentHashMap<>();

  public DependencyAwareness(AS400 system, boolean debug, boolean verbose) {
    this.system = system;
    this.debug = debug;
    this.verbose = verbose;
  }

  public void detectDependencies(BuildSpec globalSpec) throws Exception{

    if (verbose) logger.info("Detecting source object dependencies");
    /* This let us map name string to object name. TODO: There has to be a better way of doing this */

    this.totalTargets = globalSpec.targets.size();

    keyLookup.clear();
    for (TargetKey k : globalSpec.targets.keySet()) {
      keyLookup.put(k.asMapKey(), k);
    }

    /* Build override map */
    buildFileOverrideMap(globalSpec);

    /* We need the base dir because IFSFile does not seems to work with curdir relative paths */
    String baseDir = globalSpec.getBaseDirectory();
    if (baseDir == null) throw new RuntimeException("Base directory not set in BuildSpec");

    /* Set source stream file for every target */
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();

      if (target.containsStreamFile()) continue;
      if (!targetSpec.params.containsKey(ParamCmd.SRCSTMF)) continue;
      
      String relPath = targetSpec.params.get(ParamCmd.SRCSTMF);

      if (relPath.isEmpty()) continue;

      target.setStreamSourceFile(relPath);
    }

    // Phase 1: Process only modules to populate the export map
    List<CompletableFuture<Void>> moduleFutures = new ArrayList<>();
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();

      /* If not a module, omit */
      if (!target.isModule()) continue;
      /* If not stream file,  */
      if (!target.containsStreamFile()) continue;

      String fullPath = baseDir + "/" + target.getStreamFile();

      IFSFile sourceFile = new IFSFile(system, fullPath);
      
      if (!sourceFile.exists()) throw new RuntimeException("Source file not found: " + fullPath);

      moduleFutures.add(collectExportedProceduresAsync(target, sourceFile));  // will call collectExportedProcedures

    }
    CompletableFuture.allOf(moduleFutures.toArray(new CompletableFuture[0])).join();
    /* Show modules logs */
    showLogs(globalSpec);

    /* Set exported procs back to spec */
    globalSpec.setExportedProcedures(exportedProcToModule);

    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();

      /* If not source file, omit */
      if (!target.containsStreamFile()) {
        if (verbose) logger.info("Target " + target.asString() + " does not contains stream file to scan");
        continue;
      }

      String fullPath =  baseDir + "/" + target.getStreamFile();

      IFSFile sourceFile = new IFSFile(system, fullPath);
      
      if (!sourceFile.exists()) throw new RuntimeException("Source file not found: " + fullPath);

      CompletableFuture<Void> future = processTargetAsync(target, sourceFile);
      futures.add(future);

    }

    // Wait for all
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    showLogs(globalSpec);

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

  private CompletableFuture<Void> collectExportedProceduresAsync(TargetKey target, IFSFile sourceFile) {
  return CompletableFuture.runAsync(() -> {
    List<String> logs = new ArrayList<>();
    Set<String> exportedProcs = new HashSet<>();

    try{ 
      if (verbose) logs.add("Scannig sources for exports: " + target.asString());

      String sourceCode;
      try (InputStream stream = new IFSFileInputStream(sourceFile)) {
        sourceCode = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }

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

  private CompletableFuture<Void> processTargetAsync(TargetKey target, IFSFile sourceFile) {
  return CompletableFuture.runAsync(() -> {
    List<String> logs = new ArrayList<>();
    try {
      if (verbose) logs.add("Scannig sources: " + target.asString());

      String sourceCode;
      try (InputStream stream = new IFSFileInputStream(sourceFile)) {
        sourceCode = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }

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
          /*  Scan source for BndDir */
          getBndDirDependencies(target, sourceCode, logs);
          break;
          /* At this point, we already have the chain module -> srvpgm -> [bnddir] -> pgm */
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

      /* Get CLP, CLLE dependencies */
      switch (target.getCompilationCommand()) {
        case CRTBNDCL:      
        case CRTCLPGM:
          getClCallDependencies(target, sourceCode, logs);
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
    Matcher m = BNDDIR_PATTERN.matcher(sourceCode);

    /* Get multiple bnddir */
    while (m.find()) {  // ← changed from if(!find()) to while
      String bndDirName = m.group(1).toUpperCase();
      if (bndDirName.isEmpty()) continue;
      bndDirNames.add(bndDirName);
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
