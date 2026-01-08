package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.kraudy.compiler.CompilationPattern.Command;
import com.github.kraudy.compiler.CompilationPattern.ErrMsg;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;

/*
 * Utility methods
 */
public class Utilities {
  private static final Logger logger = LoggerFactory.getLogger(Utilities.class);

  public static final String CteLibraryList = 
    "Libs (Libraries) As ( " +
      "SELECT DISTINCT(SCHEMA_NAME) FROM QSYS2.LIBRARY_LIST_INFO " + 
      "WHERE TYPE NOT IN ('SYSTEM','PRODUCT') AND SCHEMA_NAME NOT IN ('QGPL', 'GAMES400') " +
    ") "
  ;

  public static void SetDefaultParams(TargetKey targetKey) {

    /* Set source Pf and source member values */
    switch (targetKey.getCompilationCommand()) {
      case CRTSQLRPGI:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTRPGPGM:
      case CRTCLPGM:
      case CRTDSPF:
      case CRTPF:
      case CRTLF:
      case CRTSRVPGM:
      case CRTRPGMOD:
      case CRTCLMOD:
      case RUNSQLSTM:
      case CRTCMD:
        targetKey.put(ParamCmd.SRCFILE, targetKey.getQualifiedSourceFile())
          .put(ParamCmd.SRCMBR, targetKey.getSourceName());
        break;
    }

    /* Set default values */
    switch (targetKey.getCompilationCommand()) {
      case CRTSQLRPGI:
        targetKey.put(ParamCmd.OBJ, targetKey.getQualifiedObject())
          .put(ParamCmd.OBJ, targetKey.getQualifiedObject(ValCmd.CURLIB))
          .put(ParamCmd.OBJTYPE, targetKey.getObjectType())
          .put(ParamCmd.COMMIT, ValCmd.NONE)
          .put(ParamCmd.DBGVIEW, ValCmd.SOURCE);
        break;
    
      case CRTBNDRPG:
      case CRTBNDCL:
        targetKey.put(ParamCmd.DBGVIEW, ValCmd.ALL);
      case CRTRPGPGM:
      case CRTCLPGM:
        targetKey.put(ParamCmd.PGM, targetKey.getQualifiedObject())
          .put(ParamCmd.PGM, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;

      case CRTDSPF:
      case CRTPF:
      case CRTLF:
        targetKey.put(ParamCmd.FILE, targetKey.getQualifiedObject())
          .put(ParamCmd.FILE, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;
      
      case CRTSRVPGM:
        targetKey.put(ParamCmd.SRVPGM, targetKey.getQualifiedObject())
          .put(ParamCmd.SRVPGM, targetKey.getQualifiedObject(ValCmd.CURLIB))
          .put(ParamCmd.MODULE, targetKey.getQualifiedObject())
          .put(ParamCmd.MODULE, targetKey.getQualifiedObject(ValCmd.LIBL))
          .put(ParamCmd.BNDSRVPGM, ValCmd.NONE)
          .put(ParamCmd.EXPORT, ValCmd.ALL);
        break;

      case CRTRPGMOD:
      case CRTCLMOD:
        targetKey.put(ParamCmd.DBGVIEW, ValCmd.ALL)
          .put(ParamCmd.MODULE, targetKey.getQualifiedObject())
          .put(ParamCmd.MODULE, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;

      case RUNSQLSTM:
        targetKey.put(ParamCmd.COMMIT, ValCmd.NONE)
          .put(ParamCmd.DBGVIEW, ValCmd.SOURCE)
          .put(ParamCmd.OPTION, ValCmd.LIST);
        break;

      default:
        break;
    }

    /* Cmd specific params */
    switch (targetKey.getCompilationCommand()) {
      case CRTCMD:
        targetKey.put(ParamCmd.CMD, targetKey.getQualifiedObject())
          .put(ParamCmd.CMD, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;
    
      default:
        break;
    }

    /* Set creation override value */
    switch (targetKey.getCompilationCommand()) {
      case CRTSRVPGM:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTRPGMOD:
      case CRTCLMOD:
      case CRTSQLRPGI:
      case CRTRPGPGM:
      case CRTCLPGM:
      case CRTDSPF:
      case CRTPRTF:
        targetKey.put(ParamCmd.REPLACE, ValCmd.YES);
        break;
    
      default:
        break;
    }

    /* Set option and genopt */
    switch (targetKey.getCompilationCommand()) {
      case CRTSRVPGM:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTRPGMOD:
      case CRTCLMOD:
      case CRTSQLRPGI:
      case CRTDSPF:
      case CRTPF:
      case CRTLF:
        targetKey.put(ParamCmd.OPTION, ValCmd.EVENTF);
        break;
    }

    switch (targetKey.getCompilationCommand()) {
      case CRTRPGPGM:
      case CRTCLPGM:
        targetKey.put(ParamCmd.OPTION, ValCmd.LSTDBG)
          .put(ParamCmd.GENOPT, ValCmd.LIST);
        break;
    }

  }

  /**
   * Overload 1: Deserialize from a local file path (used by CLI, unit tests, etc.)
   */
  public static BuildSpec deserializeYaml(String yamlPath) {
    if (yamlPath == null) throw new RuntimeException("YAML build file must be provided");

    File f = new File(yamlPath);

    if (!f.exists()) throw new RuntimeException("YAML file not found: " + yamlPath);

    if (!f.canRead()) throw new RuntimeException("YAML file not readable: " + yamlPath);

    // Optional: enforce extension for local files
    if (!yamlPath.toLowerCase().endsWith(".yaml") && !yamlPath.toLowerCase().endsWith(".yml")) {
        throw new RuntimeException("File does not appear to be a YAML file (missing .yaml/.yml extension): " + yamlPath);
    }

    logger.info("Deserializing local spec: {}", yamlPath);

    // Convert local File → InputStream and delegate to shared InputStream logic
    try (InputStream stream = new FileInputStream(f)) {
      BuildSpec spec = deserializeYaml(stream);

      // Capture absolute base directory (parent of the YAML file)
      String baseDir = f.getAbsoluteFile().getParent();
      if (baseDir == null) {
        baseDir = ".";  // Fallback to current directory
      }
      spec.setBaseDirectory(baseDir);

      return spec;
    } catch (IOException e) {
        throw new RuntimeException("IO error opening local YAML file: " + e.getMessage(), e);
    }
  }

  public static BuildSpec deserializeYaml(IFSFile remoteYamlFile) throws Exception {
    if (remoteYamlFile == null) throw new RuntimeException("Remote YAML build file must be provided");

    if (!remoteYamlFile.exists()) throw new RuntimeException("Remote YAML not found: " + remoteYamlFile.toString());

    if (!remoteYamlFile.canRead()) throw new RuntimeException("YAML file not readable: " + remoteYamlFile.toString());

    // Optional: enforce extension for local files
    if (!remoteYamlFile.toString().toLowerCase().endsWith(".yaml") && !remoteYamlFile.toString().toLowerCase().endsWith(".yml")) {
        throw new RuntimeException("File does not appear to be a YAML file (missing .yaml/.yml extension): " + remoteYamlFile.toString());
    }

    logger.info("Deserializing local spec: {}", remoteYamlFile.toString());

    // Convert local File → InputStream and delegate to shared InputStream logic
    try (InputStream stream = new IFSFileInputStream(remoteYamlFile)) {
      BuildSpec spec = deserializeYaml(stream);

      // Capture absolute base directory (parent of the YAML file)
      String baseDir = remoteYamlFile.getParent();
      if (baseDir == null || baseDir.isEmpty()) {
        baseDir = "/";  // Fallback to root (common on IFS)
      }
      spec.setBaseDirectory(baseDir);

      return spec;
    } catch (IOException e) {
        throw new RuntimeException("IO error opening local YAML file: " + e.getMessage(), e);
    }
  }

  /**
   * Deserialize directly from any InputStream (remote IFS, classpath, etc.)
   */
  public static BuildSpec deserializeYaml(InputStream yamlStream) {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    BuildSpec spec;

    if (yamlStream == null) throw new RuntimeException("YAML InputStream must be provided");

    logger.info("Deserializing spec from InputStream");

    try {
      spec = mapper.readValue(yamlStream, BuildSpec.class);

      // Post-deserialization sanity check
      if (spec.targets == null || spec.targets.isEmpty()) {
        throw new IllegalArgumentException("YAML must define at least one target in 'targets' section.");
      }

    } catch (MismatchedInputException e) {
        throw new RuntimeException("Invalid or empty YAML content: " + e.getMessage(), e);
    } catch (JsonMappingException e) {
        throw new RuntimeException("YAML schema error: " + e.getMessage() + 
            "\nCheck required fields like 'targets' or 'params'.", e);
    } catch (IOException e) {
        throw new RuntimeException("IO error reading YAML: " + e.getMessage(), e);
    } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException("Could not map YAML to BuildSpec: " + e.getMessage(), e);
    }

    return spec;
  }

  public static String nodeToString(JsonNode node) {
    if (node.isNull()) throw new RuntimeException("Node value can not be null");

    if (node.isBoolean()) {
      if (node.asBoolean()) {
        return ValCmd.YES.toString();
      } else {
        return ValCmd.NO.toString();
      }
    }

    /* Convert list to space separated string */
    if (node.isArray()) {
        List<String> elements = new ArrayList<>();
        node.elements().forEachRemaining(child -> {
            if (!child.isNull()) {
                elements.add(child.asText());
            }
        });
        return String.join(" ", elements).trim(); // Space sparated list
    }

    /* Try to get ValCmd string from node */
    try { return ValCmd.fromString(node.asText()).toString(); }
    catch (Exception ignored) { 
      try {
        /* Try to get text from node */
        return node.asText();
      } catch (Exception e) {
        throw new RuntimeException("Could not extract text from node");
      }
    }
    
  }

  /* 
   * Validates proper value format per param 
   * Called from ParamMap since it is a common point between TargetKey and CommandObject classes
   */
  public static String validateParamValue(ParamCmd param, String value) {
    switch (param) {
      case TEXT:
      case SRCSTMF:
      case FROMSTMF:
      case TOMBR:
      case FROMMBR:
      case TOSTMF:
      case DIR:
      case MSG:
        return "''" + value + "''";
    
      case MODULE:
      case OBJ:
      case TOFILE:
        String[] list = value.split(" ");
        if(list.length <= 1) {
          if (value.contains("/")) return value;
          return ValCmd.LIBL.toString() + "/" + value;
        }

        value = "";
        for(String item : list){
          if(item.contains("/")) {
            value += item; // If already qualified, just append
          } else {
            value += ValCmd.LIBL.toString() + "/" + item; // If not qualifed, append LIBL
          }
          value += " "; // Add separator.
        }
        return value.trim();

      case SRCFILE:
        /* If not qualified, set to LIBL */
        if(!value.contains("/")) value = ValCmd.LIBL.toString() + "/" + value;
        break;

      case BNDDIR:
      case DTAARA:
      case DTAQ:
        /* If not qualified, set to CURLIB */
        if(!value.contains("/")) value = ValCmd.CURLIB.toString() + "/" + value;
        break;

      case CVTOPT:
        String[] cvtoptList = value.split(" ");
        if(cvtoptList.length <= 1) {
          return value; // If just one value, no need to do ValCmd parse because the deserializer already does it.
        }
        value = "";
        /* Do ValCmd parsing */
        for(String item : cvtoptList){
          try { value += ValCmd.fromString(item).toString(); }
          catch (Exception ignored) {
            value += item;
          }
          value += " "; // Add separator.
        }
        return value.trim();
    
      default:
        break;
    }
    return value;
  }

  public static void ResolveConflicts(TargetKey key){
    switch (key.getCompilationCommand()){
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTRPGMOD:
      case CRTCLMOD:
        if (key.containsKey(ParamCmd.SRCSTMF)) {
          key.put(ParamCmd.TGTCCSID, ValCmd.JOB);
        }
        /* If  TGTCCSID is present and no stream file, remove it*/
        if (key.containsKey(ParamCmd.TGTCCSID) && !key.containsKey(ParamCmd.SRCSTMF)) {
          key.remove(ParamCmd.TGTCCSID);
        }
        break;

      default: 
        break;
    }

    switch (key.getCompilationCommand()){
      case CRTBNDRPG:
        if (!key.containsKey(ParamCmd.DFTACTGRP)) {
          key.remove(ParamCmd.STGMDL); 
        }

        /* ACTGRP not allowed with DFTACTGRP(*YES) */
        if (key.containsKey(ParamCmd.ACTGRP) && !key.containsKey(ParamCmd.DFTACTGRP)) {
          key.put(ParamCmd.DFTACTGRP, ValCmd.NO);
        }
        break;

      case CRTSQLRPGI:
        if (key.containsKey(ParamCmd.SRCSTMF)) {
          key.put(ParamCmd.CVTCCSID, ValCmd.JOB);
        }
        /* If  CVTCCSID is present and no stream file, remove it*/
        if (key.containsKey(ParamCmd.CVTCCSID) && !key.containsKey(ParamCmd.SRCSTMF)) {
          key.remove(ParamCmd.CVTCCSID);
        }
        /* OBJ param is used by other commands like AddBndDirE where *LIBL is valid but not here */
        if (key.containsKey(ParamCmd.OBJ)) {
          String obj = key.get(ParamCmd.OBJ);
          String[] objList = obj.split("/");
          try{
            if (ValCmd.LIBL == ValCmd.fromString(objList[0])){
              key.put(ParamCmd.OBJ, ValCmd.CURLIB.toString() + "/" + objList[1]);
            }
          } catch (Exception ignore) {}
        }
        /* *ALL value not valid for DBGVIEW for CRTSQLRPGI */
        if (key.containsKey(ParamCmd.DBGVIEW)) {
          String debugView = key.get(ParamCmd.DBGVIEW);
          try{
            if (ValCmd.ALL == ValCmd.fromString(debugView)){
              key.put(ParamCmd.DBGVIEW, ValCmd.SOURCE.toString());
            }
          } catch (Exception ignore) {}
        }

        break;
      
      case CRTRPGPGM:
        /* *NOEVENTF value not valid for OPTION for CRTRPGPGM */
        if (key.containsKey(ParamCmd.OPTION)) {
          String option = key.get(ParamCmd.OPTION);
          try{
            if (ValCmd.NOEVENTF == ValCmd.fromString(option)){
              key.put(ParamCmd.OPTION, ValCmd.LSTDBG.toString());
            }
          } catch (Exception ignore) {}
        } 
        break;

      case CRTSRVPGM:
        if (key.containsKey(ParamCmd.SRCSTMF) && 
            key.containsKey(ParamCmd.EXPORT)) {
          key.remove(ParamCmd.EXPORT); 
        }
        break;

      default: 
        break;
    }

    /* Migration logic between SRCSTMF and  SRCFILE, SRCMBR */
    switch (key.getCompilationCommand()){
      case CRTRPGMOD:
      case CRTCLMOD:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTSQLRPGI:
      case CRTSRVPGM:
      case RUNSQLSTM:
      case CRTCMD:
        if(key.containsKey(ParamCmd.SRCSTMF) &&
            key.containsKey(ParamCmd.SRCFILE)){
          key.remove(ParamCmd.SRCFILE); 
          key.remove(ParamCmd.SRCMBR); 
        }
        break;

      default:
          break;
    }
  }

  public static void ResolveConflicts(CommandObject commandObject){
    switch (commandObject.getSystemCommand()){
      /* Scape command */
      case QSH:
        if (commandObject.containsKey(ParamCmd.CMD)){
          String cmd = commandObject.get(ParamCmd.CMD);
          cmd = "''" + cmd + "''";
          commandObject.put(ParamCmd.CMD, cmd);
        }
        break;

      default: 
        break;
    }
    return;
  }


  /* Validates param against command pattern */
  public static boolean validateCommandParam(Command cmd, ParamCmd param) {
    if (!CompilationPattern.getCommandPattern(cmd).contains(param)) {
      return false;
    }

    return true;
  }

  public static String validParamList() {
    return String.join(", ", 
        java.util.Arrays.stream(ParamCmd.values())
            .map(Enum::name)
            .toList());
  }
}