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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*  
 *  Commmands patterns to be extracted from the build spec (YAML file )
 */
public class BuildSpec {
  private String baseDirectory;  // Absolute path to the directory containing the YAML
  private List<TargetKey> targetsList = new ArrayList<>();
  private ConcurrentHashMap<String, TargetKey> exportedProcToModule = new ConcurrentHashMap<>();

  public String getBaseDirectory() { return baseDirectory; }

  /* Problems found while reading the sources (e.g. two files building the same object), shown in the build report */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public final java.util.List<String> warnings = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

  /* Set once the dependency graph is built, so a build does not scan the sources a second time */
  private boolean dependenciesDetected;
  public boolean isDependenciesDetected() { return dependenciesDetected; }
  public void setDependenciesDetected(boolean detected) { this.dependenciesDetected = detected; }
  public void setBaseDirectory(String baseDirectory) { this.baseDirectory = baseDirectory; }

  public void setExportedProcedures(ConcurrentHashMap<String, TargetKey> exportedProcToModule) { 
    this.exportedProcToModule = exportedProcToModule; 
  }

  public boolean containsExport(String procedure, TargetKey target){
    TargetKey procTarget = this.exportedProcToModule.getOrDefault(procedure, null);
    if (procTarget == null) return false;
    /* Validate target */
    return procTarget.equals(target);
  }

  //TODO: Add getTargetByMap()
  public void setTargetsList(Set<TargetKey> targetsSet) { this.targetsList.addAll(targetsSet);}
  public List<TargetKey> getTargetsList() { return this.targetsList;}

  public TargetKey getTargetKey(TargetKey key){
    if (!contains(key)) return null;
    int index = targetsList.indexOf(key);
    
    if (index == -1) return null;
    
    return targetsList.get(index);
  }

  public boolean contains(TargetKey key){
    if (this.targetsList.contains(key)) return true;
    return false;
  }

  /* Libraries no project program may write to (files it opens for update / output, SQL INSERT / UPDATE / DELETE
     resolving there): such a target fails before it is compiled */
  @JsonProperty(value = "protectedLibs", required = false)
  public final List<String> protectedLibs = new ArrayList<>();

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
    /* Optional full compile command (array or CL string); folded into params on load */
    @JsonProperty(value = "command", required = false)
    @JsonDeserialize(using = CommandStringParser.CommandFormDeserializer.class)
    public CommandStringParser.CommandForm command;

    /* Per-target compilation command params */
    @JsonProperty(value = "params", required = false)
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
          "Unknown field in target '" + name + "'. Valid fields: params, command, before, after, success, failure.");
    }
  }
}