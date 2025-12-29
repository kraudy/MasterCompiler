package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.kraudy.compiler.CompilationPattern.Command;
import com.github.kraudy.compiler.CompilationPattern.ErrMsg;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;

/*
 * Utility methods
 */
public class Utilities {

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
        targetKey.put(ParamCmd.SRCFILE, targetKey.getQualifiedSourceFile());
        targetKey.put(ParamCmd.SRCMBR, targetKey.getSourceName());
        break;
    }

    /* Set default values */
    switch (targetKey.getCompilationCommand()) {
      case CRTSQLRPGI:
        targetKey.put(ParamCmd.OBJ, targetKey.getQualifiedObject());
        targetKey.put(ParamCmd.OBJ, targetKey.getQualifiedObject(ValCmd.CURLIB));
        targetKey.put(ParamCmd.OBJTYPE, targetKey.getObjectType());
        targetKey.put(ParamCmd.COMMIT, ValCmd.NONE);
        targetKey.put(ParamCmd.DBGVIEW, ValCmd.SOURCE);
        break;
    
      case CRTBNDRPG:
      case CRTBNDCL:
        targetKey.put(ParamCmd.DBGVIEW, ValCmd.ALL);
      case CRTRPGPGM:
      case CRTCLPGM:
        targetKey.put(ParamCmd.PGM, targetKey.getQualifiedObject());
        targetKey.put(ParamCmd.PGM, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;

      case CRTDSPF:
      case CRTPF:
      case CRTLF:
        targetKey.put(ParamCmd.FILE, targetKey.getQualifiedObject());
        targetKey.put(ParamCmd.FILE, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;
      
      case CRTSRVPGM:
        targetKey.put(ParamCmd.SRVPGM, targetKey.getQualifiedObject());
        targetKey.put(ParamCmd.SRVPGM, targetKey.getQualifiedObject(ValCmd.CURLIB));
        targetKey.put(ParamCmd.MODULE, targetKey.getQualifiedObject());
        targetKey.put(ParamCmd.MODULE, targetKey.getQualifiedObject(ValCmd.LIBL));
        targetKey.put(ParamCmd.BNDSRVPGM, ValCmd.NONE);
        targetKey.put(ParamCmd.EXPORT, ValCmd.ALL);
        break;

      case CRTRPGMOD:
      case CRTCLMOD:
        targetKey.put(ParamCmd.DBGVIEW, ValCmd.ALL);
        targetKey.put(ParamCmd.MODULE, targetKey.getQualifiedObject());
        targetKey.put(ParamCmd.MODULE, targetKey.getQualifiedObject(ValCmd.CURLIB));
        break;

      case RUNSQLSTM:
        targetKey.put(ParamCmd.COMMIT, ValCmd.NONE);
        targetKey.put(ParamCmd.DBGVIEW, ValCmd.SOURCE);
        targetKey.put(ParamCmd.OPTION, ValCmd.LIST);
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
        targetKey.put(ParamCmd.OPTION, ValCmd.LSTDBG);
        targetKey.put(ParamCmd.GENOPT, ValCmd.LIST);
        break;
    }

  }

  public static BuildSpec deserializeYaml (String yamlFile) {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    BuildSpec spec = null;

    if (yamlFile == null) throw new RuntimeException("YAML build file must be provided");
    
    File f = new File(yamlFile);
    
    if (!f.exists()) throw new RuntimeException("YAML file not found: " + yamlFile);

    try{
      /* Diserialize yaml file */
      spec = mapper.readValue(f, BuildSpec.class);

      // Post-deserialization sanity check (e.g., targets not empty)
      if (spec.targets.isEmpty()) {
        throw new IllegalArgumentException("YAML must define at least one target in 'targets' section.");
      }

    } catch (JsonMappingException e) {
      throw new RuntimeException("YAML schema error: " + e.getMessage() + "\nCheck required fields like 'targets' or 'params'.", e);
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException("Could not map build file to spec");
    }

    return spec;
  }

  public static String nodeToString(JsonNode node) {
    if (node.isNull()) throw new RuntimeException("Node value can not be null");

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

  /* Validates proper value format per param */
  public static String validateParamValue(ParamCmd param, String value) {
    switch (param) {
      case TEXT:
      case SRCSTMF:
      case FROMSTMF:
      case TOMBR:
      case FROMMBR:
      case TOSTMF:
      case DIR:
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