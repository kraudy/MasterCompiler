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

  public String getHistory(ParamCmd param) {
    ParamValue pv = this.paramMap.get(param);
    if (pv == null) return "";
    return pv.getHistory();
  }

  public List<ParamCmd> getPattern(Command cmd) {
    return CompilationPattern.commandToPatternMap.getOrDefault(cmd, Collections.emptyList());
  }

  public String remove(ParamCmd param) {
    ParamValue pv = this.paramMap.get(param);

    if (pv == null) return ""; // If no pv, there is nothing to remove

    return pv.remove();
  }

  public String put(ParamCmd param, ValCmd value) {
    return put(param, value.toString());
  }

  public String put(ParamCmd param, String value) {
    value = Utilities.validateParamValue(param, value);

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
    }

    return sb.toString();

  }

  /* Here we need the command to ResolveConflincts */
  public String getCommandStringWithoutSummary(Command cmd){

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

  public void getChangesSummary(List<ParamCmd> compilationPattern, String commandName) {
    StringBuilder history = new StringBuilder();
    history.append("\nCommand " + commandName + " summary\n");

    for (ParamCmd param : compilationPattern) {
      String value = get(param);
      if (value.isEmpty()) continue;
      history.append(param.name() + ":" + getHistory(param)).append("\n");
    }
    logger.info(history.toString());
  }
}