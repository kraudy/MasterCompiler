package com.github.kraudy.compiler;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*  
 *  Commmands patterns to be extracted from the build spec (YAML file )
 */
public class BuildSpec {
  private String baseDirectory;  // Absolute path to the directory containing the YAML

  public String getBaseDirectory() { return baseDirectory; }
  public void setBaseDirectory(String baseDirectory) { this.baseDirectory = baseDirectory; }
  //TODO: Add getTargetByMap()

  /* Global compilation command params */
  @JsonProperty(value = "defaults", required = false)
  @JsonDeserialize(using = ParamMapDeserializer.class)
  public final Map<ParamCmd, String> defaults = new HashMap<>();

  /* Global pre-compilation system commands */
  @JsonProperty(value = "before", required = false)
  @JsonDeserialize(using = CommandMapDeserializer.class)
  public final List<CommandObject> before = new ArrayList<>();

  /* Global post-compilation system commands */
  @JsonProperty(value = "after", required = false)
  @JsonDeserialize(using = CommandMapDeserializer.class)
  public final List<CommandObject> after = new ArrayList<>();


  /* Global on success system commands */
  @JsonProperty(value = "success", required = false)
  @JsonDeserialize(using = CommandMapDeserializer.class)
  public final List<CommandObject> success = new ArrayList<>();

  /* Global on failure system commands */
  @JsonProperty(value = "failure", required = false)
  @JsonDeserialize(using = CommandMapDeserializer.class)
  public final List<CommandObject> failure = new ArrayList<>();

  /* Ordered sequence of targets and their spec */
  @JsonProperty(value = "targets", required = true) // Required
  public final LinkedHashMap<TargetKey, TargetSpec> targets = new LinkedHashMap<>();

  public BuildSpec() {

  }

  public static class TargetSpec {
    /* Per-target compilation command params */
    @JsonProperty(value = "params", required = true)  // Required
    @JsonDeserialize(using = ParamMapDeserializer.class)
    public final Map<ParamCmd, String> params = new HashMap<>();

    /* Per-target pre-compilation system commands */
    @JsonProperty(value = "before", required = false)
    @JsonDeserialize(using = CommandMapDeserializer.class)
    public final List<CommandObject> before = new ArrayList<>();

    /* Per-target post-compilation system commands */
    @JsonProperty(value = "after", required = false)
    @JsonDeserialize(using = CommandMapDeserializer.class)
    public final List<CommandObject> after = new ArrayList<>();

    /* Per-target on success system commands */
    @JsonProperty(value = "success", required = false)
    @JsonDeserialize(using = CommandMapDeserializer.class)
    public final List<CommandObject> success  = new ArrayList<>();

    /* Per-target on failure system commands */
    @JsonProperty(value = "failure", required = false)
    @JsonDeserialize(using = CommandMapDeserializer.class)
    public final List<CommandObject> failure  = new ArrayList<>();

    @JsonAnySetter
    public void unknown(String name, Object value) {
      throw new IllegalArgumentException(
          "Unknown parameter in target '" + name + "'. Valid parameters are the ones from ParamCmd enum.");
    }
  }
}