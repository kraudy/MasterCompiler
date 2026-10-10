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

  /* Library settings for the build job, so a spec (or mc-base.yaml) need not spell out the hooks:
     curlib: DEVLIB / libl: [DEVLIB, APPLIB] become CHGLIBL / CHGCURLIB before every other hook */
  @JsonProperty(value = "curlib", required = false)
  public String curlib;

  @JsonProperty(value = "libl", required = false)
  @JsonDeserialize(using = LibraryListDeserializer.class)
  public String libl;

  public void applyLibrarySettings() {
    if (libl != null && !libl.trim().isEmpty()) {
      CommandObject chglibl = new CommandObject(CompilationPattern.SysCmd.CHGLIBL).put(ParamCmd.LIBL, libl.trim().toUpperCase());
      if (curlib != null && !curlib.trim().isEmpty()) chglibl.put(ParamCmd.CURLIB, curlib.trim().toUpperCase());
      before.add(0, chglibl);
    } else if (curlib != null && !curlib.trim().isEmpty()) {
      before.add(0, new CommandObject(CompilationPattern.SysCmd.CHGCURLIB).put(ParamCmd.CURLIB, curlib.trim().toUpperCase()));
    }
    curlib = null;  // applied once
    libl = null;
  }

  /* libl as a YAML list or a space-separated string */
  public static final class LibraryListDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<String> {
    @Override
    public String deserialize(com.fasterxml.jackson.core.JsonParser p, com.fasterxml.jackson.databind.DeserializationContext c)
        throws java.io.IOException {
      com.fasterxml.jackson.databind.JsonNode node = p.getCodec().readTree(p);
      if (!node.isArray()) return node.asText();
      StringBuilder libs = new StringBuilder();
      for (com.fasterxml.jackson.databind.JsonNode lib : node) libs.append(libs.length() > 0 ? " " : "").append(lib.asText());
      return libs.toString();
    }
  }

  /* Libraries no project program may write to (files it opens for update / output, SQL INSERT / UPDATE / DELETE
     resolving there): such a target fails before it is compiled */
  @JsonProperty(value = "protectedLibs", required = false)
  public final List<String> protectedLibs = new ArrayList<>();

  /* Which Code for IBM i connection builds, and other IBM i systems the tools may read (ReadOnlyConnections) */
  @JsonProperty(value = "connections", required = false)
  public com.fasterxml.jackson.databind.JsonNode connections;

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
  @JsonProperty(value = "targets", required = false) // required in a spec, optional in an mc-base.yaml overlay
  public final LinkedHashMap<TargetKey, TargetSpec> targets = new LinkedHashMap<>();

  public BuildSpec() {

  }

  public static class TargetSpec {
    /* Optional full compile command (array or CL string); folded into params on load */
    @JsonProperty(value = "command", required = false)
    @JsonDeserialize(using = CommandStringParser.CommandFormDeserializer.class)
    public CommandStringParser.CommandForm command;

    /* Binding directories: true deletes an existing one and creates it again (only the spec's entries remain) */
    @JsonProperty(value = "recreate", required = false)
    public Boolean recreate;

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
          "Unknown field in target '" + name + "'. Valid fields: params, command, recreate, before, after, success, failure.");
    }
  }
}