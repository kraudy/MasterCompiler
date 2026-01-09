package com.github.kraudy.compiler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SourceType;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;

/*
 * Inffer object compilation 
 * We need dependency awareness for diff build
 */
public class DependencyAwareness {
  private static final Logger logger = LoggerFactory.getLogger(DependencyAwareness.class);

  // Broad regex for BNDDIR('NAME') – works for H-spec and CTL-OPT
  private static final Pattern BNDDIR_PATTERN = Pattern.compile(
      "\\bBNDDIR\\s*\\(\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);

  /* Fixed-format F-spec: strict column logic
   * - Exactly 5 characters (usually blanks) before the F (column 6)
   * - F/f in column 6
   * - Capture up to 10 characters for the file name (columns 7-16)
   */
  private static final Pattern FIXED_F_SPEC = Pattern.compile(
      //"^.{5}[fF](.{0,10})\\s*[IOUBC]", Pattern.CASE_INSENSITIVE);
      "^.{5}[fF](.{10})[IOUBC]", Pattern.CASE_INSENSITIVE);
      //"^.{5}[fF]\\s*([A-Z0-9$#@_]{1,10})\\s*[IOUBC]", Pattern.CASE_INSENSITIVE);
      //"^.{5}[fF]([A-Z0-9$#@_]{1,10})\\s*[IOUBC]", Pattern.CASE_INSENSITIVE);

  // Pattern for free-format DCL-F (captures the file name, possibly qualified)
  private static final Pattern FREE_DCL_F = Pattern.compile(
      "\\bDCL-F\\s+([\\w$#@_./]+)", Pattern.CASE_INSENSITIVE);

  // Pattern for embedded SQL table references (FROM, JOIN, INTO, UPDATE, DELETE FROM, INSERT INTO)
  private static final Pattern SQL_TABLE = Pattern.compile(
      "(FROM|JOIN|INTO|UPDATE|DELETE FROM|INSERT INTO)\\s+([\\w$#@_./]+)",
      Pattern.CASE_INSENSITIVE);

  private final AS400 system;
  private final boolean debug;
  private final boolean verbose;

  public DependencyAwareness(AS400 system, boolean debug, boolean verbose) {
    this.system = system;
    this.debug = debug;
    this.verbose = verbose;
  }

  public void detectDependencies(BuildSpec globalSpec) throws Exception{

    if (verbose) logger.info("Detecting source object dependencies");
    /* This let us map name string to object name. TODO: There has to be a better way of doing this */
    Map<String, TargetKey> keyLookup = new HashMap<>();
    for (TargetKey k : globalSpec.targets.keySet()) {
      keyLookup.put(k.asMapKey(), k);
    }

    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      /* If no stream file, continue, could try to migrate  */
      if (!target.containsStreamFile()) continue;

      /* Relative source path */
      String relPath = target.getStreamFile(); 
      /* We need the base dir because IFSFile does not seems to work with curdir relative paths */
      String baseDir = globalSpec.getBaseDirectory();
      if (baseDir == null) throw new RuntimeException("Base directory not set in BuildSpec");

      String fullPath = relPath.startsWith("/")
        ? relPath
        : (baseDir.endsWith("/") ? baseDir : baseDir + "/") + relPath;

      IFSFile sourceFile = new IFSFile(system, fullPath);
      
      if (!sourceFile.exists()) throw new RuntimeException("Source file not found: " + relPath);

      String sourceCode;
      try (InputStream stream = new IFSFileInputStream(sourceFile)) {
        sourceCode = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }

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
            if (verbose) logger.info("Dependency: " + target.asString() + " depends on " + modDep.asString());
          }
          break;
      
        case CRTBNDRPG:
        case CRTSQLRPGI:
          /*  Scan source for BndDir */
          Matcher m = BNDDIR_PATTERN.matcher(sourceCode);

          if (m.find()) {
            String bndDirName = m.group(1).toUpperCase(); // e.g., "SAMPLE"
            if (bndDirName.isEmpty()) break;
            TargetKey bndDirDep = keyLookup.getOrDefault(bndDirName + "." + ObjectType.BNDDIR.name(), null);

            /* Add bnddir as target child */
            target.addChild(bndDirDep);
            /* Add target as bnddir father */
            bndDirDep.addFather(target);
            if (verbose) logger.info("Scanned BNDDIR dep: " + target.asString() + " uses BNDDIR('" + bndDirName + "') ");
          }
          break;
          /* At this point, we already have the chain module -> srvpgm -> [bnddir] -> pgm */

        default:
          break;
      }

      /* Get files */
      switch (target.getCompilationCommand()) {
        case CRTBNDRPG:
        case CRTSQLRPGI:
        case CRTRPGMOD:
          /* Collect unique file names referenced in the source */
          Set<String> depFileNames = new HashSet<>();

          /* 1. Fixed-format F-specs */
          try (java.util.Scanner scanner = new java.util.Scanner(sourceCode)) {
            while (scanner.hasNextLine()) {
              String line = scanner.nextLine();
              Matcher fixedMatcher = FIXED_F_SPEC.matcher(line);
              if (!fixedMatcher.find())  continue;
              String fileName = fixedMatcher.group(1).trim().toUpperCase();
              //TODO: Check for /n or /t characters
              if (fileName.isEmpty()) continue;
              depFileNames.add(fileName);
            }
          }

          /* 2. Free-format DCL-F */
          Matcher freeMatcher = FREE_DCL_F.matcher(sourceCode);
          while (freeMatcher.find()) {
            String rawName = freeMatcher.group(1);
            String fileName = rawName.replaceAll(".*/", "").replaceAll(".*.", "").trim();
            depFileNames.add(fileName);
          }

          /* 3. Embedded SQL table references */
          Matcher sqlMatcher = SQL_TABLE.matcher(sourceCode);
          while (sqlMatcher.find()) {
            String rawName = sqlMatcher.group(2);
            String tableName = rawName.replaceAll(".*/", "").replaceAll(".*.", "").trim();
            depFileNames.add(tableName);
          }

          /* Add dependencies for each referenced file that is also a build target */
          for (String depFileName : depFileNames) {
            TargetKey fileKey = keyLookup.getOrDefault(depFileName + "." + ParamCmd.FILE.name(), null);
            if (fileKey == null || !fileKey.isFile()) {
              if (verbose) logger.info("Referenced file not a build target: " + depFileName + " (in " + target.asString() + ")");
              continue;
            }
            if (verbose) logger.info("File dependency: " + target.asString() +" depends on file " + fileKey.asString() + " (referenced as " + depFileName + ")");
            /* Files are child of Target */
            target.addChild(fileKey);
            /* Target is father of files */
            fileKey.addFather(target);
          }

          break;

        default:
          break;
      }

      //TODO: Another switch for files
      switch (target.getCompilationCommand()) {
        case CRTLF:
          break;
      
        case CRTPF:
          break;

        default:
          break;
      }

      //TODO: Another switch for opm
      switch (target.getCompilationCommand()) {
        case CRTRPGPGM:
          break;
      
        case CRTCLPGM:
          break;

        default:
          break;
      }

      //TODO: Another switch for SQL
      switch (target.getCompilationCommand()) {
        case RUNSQLSTM:
          break;

        default:
          break;
      }
    }
}
}
