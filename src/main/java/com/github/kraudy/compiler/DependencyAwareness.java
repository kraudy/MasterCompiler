package com.github.kraudy.compiler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SourceType;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
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

  private final AS400 system;
  private final boolean debug;
  private final boolean verbose;

  public DependencyAwareness(AS400 system, boolean debug, boolean verbose) {
    this.system = system;
    this.debug = debug;
    this.verbose = verbose;
  }

  public void detectDependencies(BuildSpec globalSpec) throws Exception{

    /* This let us map name string to object name. TODO: There has to be a better way of doing this */
    Map<String, TargetKey> keyLookup = new HashMap<>();
    for (TargetKey k : globalSpec.targets.keySet()) {
      keyLookup.put(k.getObjectName(), k);
    }

    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : globalSpec.targets.entrySet()) {
      TargetKey target = entry.getKey();
      //TODO: maybe i need this to get the strmf
      //BuildSpec.TargetSpec ts = entry.getValue();

      //TODO: This for could also be the start for source dependency awarenees
      switch (target.getCompilationCommand()) {
        case CRTSRVPGM:
          List<String> modulesList = target.getModulesNameList();
          if (modulesList.isEmpty()) break;

          for (String mod : modulesList) {
            TargetKey modDep = keyLookup.getOrDefault(mod, null);
            if (modDep == null) continue;
            if (!modDep.isModule()) continue;

            /* Add SrvPgm dependency to module */
            modDep.addDependedOnBy(target);
            if (verbose) logger.info("Dependency: " + target.asString() + " depends on " + modDep.asString());
          }
          break;
      
        case CRTBNDRPG:
        case CRTSQLRPGI:
          //TODO: Scan source for BndDir
          /* If no stream file, break, could try to migrate  */
          if (!target.containsStreamFile()) break;

          String relPath = target.getStreamFile();  // e.g., "QRPGLESRC/ADDNUM.RPGLE"
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

          Matcher m = BNDDIR_PATTERN.matcher(sourceCode);

          if (m.find()) {
            String bndDirName = m.group(1).toUpperCase(); // e.g., "SAMPLE"
            if (verbose) {
              logger.info("Scanned BNDDIR dep: " + target.asString() +
                  " uses BNDDIR('" + bndDirName + "') ");
            }
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
