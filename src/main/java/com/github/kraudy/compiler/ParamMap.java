package com.github.kraudy.compiler;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import com.github.kraudy.compiler.CompilationPattern.Command;
import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;


/*
 *  Handles logic to set param:value pairs per command
 */
public class ParamMap {
  private static final Logger logger = LoggerFactory.getLogger(ParamMap.class);

  private final EnumMap<ParamCmd, ParamValue> paramMap = new EnumMap<>(ParamCmd.class);

  public ParamMap() {

  }

  public boolean containsKey(ParamCmd param) {
    if (!this.paramMap.containsKey(param)) return false;
    ParamValue pv = this.paramMap.get(param);
    return !pv.wasRemoved();
  }

  public Set<ParamCmd> keySet(){
    return this.paramMap.keySet();
  }

  /* Get map of ParamCmd and ParamValue */
  public EnumMap<ParamCmd, ParamValue> get() {
    return paramMap;
  }

  /* Get specific param value. Note how we don't need the command here. */
  public String get(ParamCmd param) {
    ParamValue pv = this.paramMap.get(param);
    if (pv == null) return "";
    String current = pv.get();
    if (current == null) return "";
    return current;
  }

  public List<ParamCmd> getPattern(Command cmd) {
    return CompilationPattern.commandToPatternMap.getOrDefault(cmd, Collections.emptyList());
  }

  public String remove(ParamCmd param) {
    ParamValue pv = this.paramMap.get(param);

    if (pv == null) return ""; // If no pv, there is nothing to remove

    return pv.remove();
  }

  public void putAll(Command cmd, Map<ParamCmd, String> params) {
    if (params == null) return;

    params.forEach((param, value) -> {
      /* 
       *  This validation is performed because the map was populated without its compilation command and invalid params are just rejected.
       *  No error is thrown. This is useful for default params and alike.
       */
      if (!Utilities.validateCommandParam(cmd, param)) {
        logger.info("\nRejected: Parameter " + param.name() + " not valid for command " + cmd.name());
        return;
      }
      put(cmd, param, value);
    });

  }

  public String put(Command cmd, ParamCmd param, ValCmd value) {
    return put(cmd, param, value.toString());
  }

  public String put(Command cmd, ParamCmd param, String value) {

    value = Utilities.validateParamValue(param, value);

    /* At this point there should be no invalid command params. If present, an exception is thrown */
    if (!Utilities.validateCommandParam(cmd, param)) {
      throw new IllegalArgumentException("Parameters " + param.name() + " not valid for command " + cmd.name());
    }

    ParamValue pv = this.paramMap.get(param);

    /* If a previous value exists, just append it and early return */
    if (pv != null) return pv.put(value);

    /* If no previous value, create new */
    pv = new ParamValue(value);
    this.paramMap.put(param, pv);
    return pv.getPrevious();
    
  }

  public void getChangesSummary(Command cmd) {
    List<ParamCmd> compilationPattern = getPattern(cmd);

    StringBuilder history = new StringBuilder();
    history.append("\nCommand " + cmd.name() + " summary\n");
    for (ParamCmd param : compilationPattern) {
      ParamValue pv = this.paramMap.get(param);
      if (pv == null) continue;
      history.append(param.name() + ":" + pv.getHistory()).append("\n");
    }
    logger.info(history.toString());
  }
  
  /* Here we need the command to ResolveConflincts */
  public String getCommandString(Command cmd){
    ResolveConflicts(cmd);
    getChangesSummary(cmd);

    List<ParamCmd> compilationPattern = getPattern(cmd);
    StringBuilder sb = new StringBuilder(); 

    sb.append(cmd.name());

    for (ParamCmd param : compilationPattern) {
      ParamValue pv = this.paramMap.get(param);
      if (pv == null) continue;
      String value = pv.get();
      if (value == null) continue;
      if (value.isEmpty()) continue;
      sb.append(param.paramString(value));
      //sb.append(param.paramString(pv.get()));
    }

    return sb.toString();

  }

  /* Here we need the command to ResolveConflincts */
  public String getCommandStringWithoutSummary(Command cmd){
    ResolveConflicts(cmd);

    List<ParamCmd> compilationPattern = getPattern(cmd);
    StringBuilder sb = new StringBuilder(); 

    sb.append(cmd.name());

    for (ParamCmd param : compilationPattern) {
      ParamValue pv = this.paramMap.get(param);
      if (pv == null) continue;
      sb.append(param.paramString(pv.get()));
    }

    return sb.toString();

  }

  public void ResolveConflicts(Command cmd){
    if (cmd instanceof CompCmd) {
      CompCmd compCmd  = (CompCmd) cmd;
      ResolveConflicts(compCmd);
      return;
    }
    if (cmd instanceof SysCmd) {
      SysCmd sysCmd  = (SysCmd) cmd;
      ResolveConflicts(sysCmd);
      return;
    }
  }

  public void ResolveConflicts(CompCmd cmd){

    switch (cmd){
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTRPGMOD:
      case CRTCLMOD:
        if (this.containsKey(ParamCmd.SRCSTMF)) {
          this.put(cmd, ParamCmd.TGTCCSID, ValCmd.JOB);
        }
        /* If  TGTCCSID is present and no stream file, remove it*/
        if (this.containsKey(ParamCmd.TGTCCSID) && !this.containsKey(ParamCmd.SRCSTMF)) {
          this.remove(ParamCmd.TGTCCSID);
        }
        break;

      default: 
        break;
    }

    switch (cmd){
      case CRTBNDRPG:
        if (!this.containsKey(ParamCmd.DFTACTGRP)) {
          this.remove(ParamCmd.STGMDL); 
        }
        break;

      case CRTSQLRPGI:
        if (this.containsKey(ParamCmd.SRCSTMF)) {
          this.put(cmd, ParamCmd.CVTCCSID, ValCmd.JOB);
        }
        /* If  CVTCCSID is present and no stream file, remove it*/
        if (this.containsKey(ParamCmd.CVTCCSID) && !this.containsKey(ParamCmd.SRCSTMF)) {
          this.remove(ParamCmd.CVTCCSID);
        }
        /* OBJ param is used by other commands like AddBndDirE where *LIBL is valid but not here */
        if (this.containsKey(ParamCmd.OBJ)) {
          String obj = get(ParamCmd.OBJ);
          String[] objList = obj.split("/");
          try{
            if (ValCmd.LIBL == ValCmd.fromString(objList[0])){
              this.put(cmd, ParamCmd.OBJ, ValCmd.CURLIB.toString() + "/" + objList[1]);
            }
          } catch (Exception ignore) {}
        }
        break;

      case CRTSRVPGM:
        if (this.containsKey(ParamCmd.SRCSTMF) && 
            this.containsKey(ParamCmd.EXPORT)) {
          this.remove(ParamCmd.EXPORT); 
        }
        break;

      default: 
        break;
    }

    /* Migration logic */
    switch (cmd){
      case CRTRPGMOD:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTSQLRPGI:
      case CRTSRVPGM:
      case RUNSQLSTM:
        if(this.containsKey(ParamCmd.SRCSTMF) &&
            this.containsKey(ParamCmd.SRCFILE)){
          this.remove(ParamCmd.SRCFILE); 
          this.remove(ParamCmd.SRCMBR); 
        }
        break;

      default:
          break;
    }
  }

  public void ResolveConflicts(SysCmd cmd){
    return;
  }
}