package com.github.kraudy.compiler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.github.kraudy.compiler.CompilationPattern.ErrMsg;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;

/*
 * Simple POJO for system commands
 * A new object is created per each target command defined in the spec (Yaml file)
 */
public class CommandObject {
  private SysCmd systemCommand;       // System command
  private ParamMap ParamCmdSequence;  // System command's Param:Value 
  private String commandString;       // System coammnd's formed string

  public CommandObject(SysCmd command) {
    this.systemCommand = command;

    /* Init param map for this command */
    this.ParamCmdSequence = new ParamMap();
  }

  public void putAll(Map<ParamCmd, String> params) {
    this.ParamCmdSequence.putAll(this.systemCommand, params);
  }

  public String get(ParamCmd param) {
    return this.ParamCmdSequence.get(param);
  }

  public String getCommandString(){
    if (this.commandString != null) return this.commandString;
    this.commandString = this.ParamCmdSequence.getCommandString(this.systemCommand);
    return this.commandString;
  }

  public String getCommandStringWithoutSummary(){
    if (this.commandString != null) return this.commandString;
    this.commandString = this.ParamCmdSequence.getCommandStringWithoutSummary(this.systemCommand);
    return this.commandString;
  }

  public String put(ParamCmd param, String value) {
    return this.ParamCmdSequence.put(this.systemCommand, param, value);
  }

  public String put(ParamCmd param, ValCmd value) {
    return this.ParamCmdSequence.put(this.systemCommand, param, value);
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

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;

    // Allow comparison with String directly
    if (obj instanceof String) {
      String str = (String) obj;
      return getCommandStringWithoutSummary().equals(str);
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
