package com.github.kraudy.compiler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ErrMsg;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;

/*
 * Simple POJO for system commands
 * A new object is created per each target command defined in the spec (Yaml file)
 */
public class CommandObject {
  private static final Logger logger = LoggerFactory.getLogger(CommandObject.class);

  private SysCmd systemCommand;       // System command
  private ParamMap ParamCmdSequence;  // System command's Param:Value 
  private String commandString;       // System coammnd's formed string

  public CommandObject(SysCmd command) {
    this.systemCommand = command;

    /* Init param map for this command */
    this.ParamCmdSequence = new ParamMap();
  }

  public void putAll(Map<ParamCmd, String> params) {
    if (params == null) return;

    params.forEach((param, value) -> {
      /* 
       *  This validation is performed because the map was populated without its compilation command and invalid params are just rejected.
       *  No error is thrown. This is useful for default params and alike.
       */
      if (!Utilities.validateCommandParam(this.getSystemCommand(), param)) {
        logger.info("\nRejected: Parameter " + param.name() + " not valid for command " + getSystemCommandName());
        return;
      }
      put(param, value);
    });

  }

  public String get(ParamCmd param) {
    return this.ParamCmdSequence.get(param);
  }

  public String getCommandString(){
    Utilities.ResolveConflicts(this);
    return this.ParamCmdSequence.getCommandString(this.systemCommand);
  }

  public String getCommandStringWithoutSummary(){
    Utilities.ResolveConflicts(this);
    return this.ParamCmdSequence.getCommandStringWithoutSummary(this.systemCommand);
  }

  public void getChangesSummary() {
    List<ParamCmd> compilationPattern = CompilationPattern.getCommandPattern(this.systemCommand);

    this.ParamCmdSequence.getChangesSummary(compilationPattern, getSystemCommandName());
  }

  public CommandObject put(ParamCmd param, String value) {
    /* At this point there should be no invalid command params. If present, an exception is thrown */
    if (!Utilities.validateCommandParam(this.systemCommand, param)) {
      throw new IllegalArgumentException("Parameters " + param.name() + " not valid for command " + getSystemCommandName());
    }

    this.ParamCmdSequence.put(param, value);

    return this;
  }

  public CommandObject put(ParamCmd param, ValCmd value) {
    return put(param, value.toString());
  }

  public SysCmd getSystemCommand() {
    return this.systemCommand;
  }

  public String getSystemCommandName() {
    return this.systemCommand.name();
  }

  public String asString() {
    return this.systemCommand.name();
  }

  public boolean containsKey(ParamCmd param) {
    return this.ParamCmdSequence.containsKey(param);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;

    // Allow comparison with String directly
    if (obj instanceof String) {
      String str = (String) obj;
      return getCommandStringWithoutSummary().equals(str);
    }

    if (obj instanceof SysCmd) {
      SysCmd command = (SysCmd) obj;
      return this.systemCommand == command;
    }

    /* If the other object is not a CommandObject, return false */
    if (!(obj instanceof CommandObject)) return false;

    /* Perform object casting */
    CommandObject commandObject = (CommandObject) obj;

    // Standard comparison between two CommandObjects
    return this.systemCommand == commandObject.systemCommand &&
           getCommandStringWithoutSummary().equals(commandObject.getCommandStringWithoutSummary());
  }

  @Override
  public int hashCode() {
      return Objects.hash(systemCommand, getCommandStringWithoutSummary());
  }

}
