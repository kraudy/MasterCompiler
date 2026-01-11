package com.github.kraudy.compiler;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.Command;
import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SourceType;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.ReturnCodeException;

/*
 * Simple POJO for compilation targets
 * A new object is created per each target defined in the spec (Yaml file)
 */
public class TargetKey {
  private static final Logger logger = LoggerFactory.getLogger(TargetKey.class);

  private String library;              // Target object library
  private String objectName;           // Target object name
  private ObjectType objectType;       // Target object type
  private SourceType sourceType;       // Target source type
  private String sourceFile;           // Target Source Phisical File name.
  private String sourceName;           // Target Source member name. Set to object name by default
  private String sourceStmf;           // Target Ifs source stream file
  private CompCmd compilationCommand;  // Compilation command
  private ParamMap ParamCmdSequence;   // Compilation command's Param:Value 

  private Timestamp lastSourceEdit;    // Last time the source was edited
  private Timestamp lastBuild;         // Last time the object was compiled

  private boolean isOpm;               // Is this key opm?
  private boolean objectExists = false;        // Does the compiled object exists?

  private final List<TargetKey> childs = new ArrayList<>(); // List of child targets
  private final List<TargetKey> fathers = new ArrayList<>(); // List of fathers targets

  public TargetKey(String key) {
    String[] parts = key.split("\\.");
    if (parts.length != 4) {
      throw new IllegalArgumentException("Invalid key: " + key + ". Expected: library.objectName.objectType.sourceType");
    }

    this.library = parts[0].toUpperCase();
    if (this.library.isEmpty()) throw new IllegalArgumentException("Library name is required.");

    this.objectName = parts[1].toUpperCase();
    if (this.objectName.isEmpty()) throw new IllegalArgumentException("Object name is required.");

    try {
      this.objectType = ObjectType.valueOf(parts[2].toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid objectType : " + parts[2]);
    }

    try {
      this.sourceType = SourceType.valueOf(parts[3].toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid sourceType : " + parts[3]);
    }

    /* Set default source file */
    try {
      this.sourceFile = SourceType.defaultSourcePf(this.sourceType, this.objectType);
    } catch (IllegalArgumentException e){
      throw e;
    }

    /* Set default source name to object name */
    this.sourceName = this.objectName;

    /* Get target key compilation command */
    this.compilationCommand = CompilationPattern.getCompilationCommand(this.sourceType, this.objectType);

    /* Check if target is opm */
    this.isOpm = CompilationPattern.isOpm(this.sourceType);

    /* Init param map for this target */
    this.ParamCmdSequence = new ParamMap();

    /* Set default compilation params */
    Utilities.SetDefaultParams(this);
  }

  public void setLibrary(String library){
    this.library = library;
  }

  //TODO: Refacto methods to be like this one. It will make the code cooler.
  public TargetKey setStreamSourceFile(String sourcePath){
    this.sourceStmf = sourcePath;
    /* Try to set  SRCSTMF, if not valid just ignore*/
    try {
      put(ParamCmd.SRCSTMF, this.sourceStmf);  
    } catch (Exception ignore) {}

    return this;
  }

  public TargetKey setLastEdit(Timestamp lastSourceEdit){
    this.lastSourceEdit = lastSourceEdit;
    return this;
  }

  public TargetKey setLastBuild(Timestamp lastBuild){
    this.lastBuild = lastBuild;
    return this;
  }

  public String getQualifiedObject(){
    return this.library + "/" + this.objectName;
  }

  public String getQualifiedObject(ValCmd valcmd){
    return valcmd.toString() + "/" + this.objectName;
  }

  public String getQualifiedSourceFile(){
    return this.library + "/" + getSourceFile();
  }

  public String getQualifiedLiblSourceFile(){
    return ValCmd.LIBL.toString() + "/" + getSourceFile();
  }

  public String getQualifiedTemporarySourceFile(){
    return "QTEMP" + "/" + getSourceFile();
  }

  public boolean containsKey(ParamCmd param) {
    return this.ParamCmdSequence.containsKey(param);
  }

  public boolean containsStreamFile() {
    String sourceStreamFile = get(ParamCmd.SRCSTMF);
    if (!sourceStreamFile.isEmpty()){
      this.sourceStmf = sourceStreamFile;
    }
    return this.sourceStmf != null;
  }

  /* Using curlib as library? */
  public boolean isCurLib() {
    try {
      return (ValCmd.CURLIB == ValCmd.fromString(this.library));
    } catch (Exception ignore) {}
    return false;
  }

  public boolean isSql() {
    return this.sourceType == SourceType.SQL;
  }

  public boolean isDds() {
    return this.sourceType == SourceType.DDS;
  }

  public boolean isFile() {
    switch (this.objectType) {
      case PF:
      case LF:
      case PRTF:
      case DSPF:
      case TABLE:
        return true;
    }
    return false;
  }

  public boolean isBndDir() {
    return this.objectType == ObjectType.BNDDIR;
  }

  public boolean isModule() {
    return this.objectType == ObjectType.MODULE;
  }

  public boolean isProgram() {
    return this.objectType == ObjectType.PGM;
  }

  public boolean isServiceProgram() {
    return this.objectType == ObjectType.SRVPGM;
  }

  /* Used for diff build */
  public boolean needsRebuild() {
    /* If no timestamp, rebuild */
    if (this.lastSourceEdit == null || this.lastBuild == null) {
        return true;
    }
    return this.lastSourceEdit.after(this.lastBuild);
  }

  public String getTimestmaps() {
    if (this.lastSourceEdit == null) return " (source has no previous edit)";
    if (this.lastBuild == null) return " (object has no previous edit)";

    return " (source last edit: " + this.lastSourceEdit + ", object last build: " + this.lastBuild + ")";
  }

  public TargetKey putAll(Map<ParamCmd, String> params) {
    if (params == null) return this;
    /* 
     * If the object is OPM, extract the SRCSTMF param before it is rejected and store its value.
     * This will help us later to do the migration from stream file to source member since OPM commands don't support SRCSTMF.
     */
    if(params.containsKey(ParamCmd.SRCSTMF)){
      setStreamSourceFile(params.get(ParamCmd.SRCSTMF));
    }

    params.forEach((param, value) -> {
      try {
        put(param, value);
      } catch (Exception ignore) {
        /* Invalid params are just ignored. This is useful because this map has not been previously validated */
        logger.info("Rejected: Parameter " + param.name() + " not valid for command " + getCompilationCommandName());
      }
    });

    return this;
  }

  public String get(ParamCmd param) {
    return this.ParamCmdSequence.get(param);
  }

  public String getCommandString(){
    ResolveConflicts();
    getChangesSummary();
    return this.ParamCmdSequence.getCommandString(this.compilationCommand);
  }

  public TargetKey ResolveConflicts() {
    Utilities.ResolveConflicts(this);
    return this;
  }

  public String getCommandStringWithoutSummary(){
    return this.ParamCmdSequence.getCommandStringWithoutSummary(this.compilationCommand);
  }

  public TargetKey remove(ParamCmd param) {
    this.ParamCmdSequence.remove(param);
    return this;
  }

  public TargetKey removeSourceFile() {
    String source = get(ParamCmd.SRCFILE);
    if (!source.isEmpty()){
      String[] sourceList = source.split("/");
      if(sourceList.length < 2){
        this.sourceFile = source;
      } else {
        this.sourceFile = sourceList[1]; // Remove lib and only get source name
      }
    }

    this.ParamCmdSequence.remove(ParamCmd.SRCFILE);

    return this;
  }

  public TargetKey removeStreamFile() {
    String sourceStreamFile = get(ParamCmd.SRCSTMF);
    if (!sourceStreamFile.isEmpty()){
      this.sourceStmf = sourceStreamFile;
    }

    this.ParamCmdSequence.remove(ParamCmd.SRCSTMF);

    return this;
  }

  public TargetKey removeMember() {
    String member = get(ParamCmd.SRCMBR);
    if (!member.isEmpty()){
      this.sourceName = member;
    }
    this.ParamCmdSequence.remove(ParamCmd.SRCMBR);

    return this;
  }

  public TargetKey put(ParamCmd param, String value) {
    /* At this point there should be no invalid command params. If present, an exception is thrown */
    if (!Utilities.validateCommandParam(this.compilationCommand, param)) {
      throw new IllegalArgumentException("Parameters " + param.name() + " not valid for command " + getCompilationCommandName());
    }

    this.ParamCmdSequence.put(param, value);

    return this;
  }

  public TargetKey put(ParamCmd param, ValCmd value) {
    return put(param, value.toString());
  }

  public void getChangesSummary() {
    List<ParamCmd> compilationPattern = CompilationPattern.getCommandPattern(this.compilationCommand);

    this.ParamCmdSequence.getChangesSummary(compilationPattern, getCompilationCommandName());
  }

  public String asString() {
    return library + "." + objectName + "." + objectType.name() + "." + sourceType.name();
  }

  public String asMapKey() {
    /* We use  getObjectType to handle different file types*/
    return objectName + "." + getObjectType(); // + "." + sourceType.name(); // Should add the source type
  }

  public String asFileName() {
    return getObjectName() + "." + getSourceType();
  }

  public String getStreamFile() {
    String sourceStreamFile = get(ParamCmd.SRCSTMF);
    if (sourceStreamFile.isEmpty()) return this.sourceStmf;
    this.sourceStmf = sourceStreamFile.replace("'", "").trim(); // Remove scaping
    return this.sourceStmf;
  }

  public String getObjectName() {
    return this.objectName;
  }

  public boolean objectExists() {
    return this.objectExists;
  }

  public void setObjectExists(boolean exists) {
    this.objectExists = exists;
  }

  public ObjectType getObjectTypeEnum() {
    return this.objectType;
  }

  public String getObjectType() {
    switch (this.objectType) {
      case PF:
      case LF:
      case DSPF:
      case PRTF:
      case TABLE:
      case VIEW:
        return ParamCmd.FILE.toString();

      case PROCEDURE:
        return ObjectType.PGM.toString();

      case SEQUENCE:
        return ObjectType.DTAARA.toString();

      default:
        break;
    }
    return this.objectType.toParam();
  }

  public String getObjectTypeName() {
    switch (this.objectType) {
      case PF:
      case LF:
      case DSPF:
        return ParamCmd.FILE.name();
    
      default:
        break;
    }
    return this.objectType.name();
  }

  public Integer getChildsCount() {
    return getChildsList().size();
  }

  public List<TargetKey> getChildsList() {
    return Collections.unmodifiableList(childs);
  }

  public Integer getFathersCount() {
    return getFathersList().size();
  }

  public List<TargetKey> getFathersList() {
    return Collections.unmodifiableList(fathers);
  }

  public boolean isChild() {
    return !this.fathers.isEmpty();
  }

  public boolean isFather() {
    return !this.childs.isEmpty();
  }

  public void addChild(TargetKey child) {
    if (child != null && !childs.contains(child)) {
      childs.add(child);
    }
  }

  public void addFather(TargetKey father) {
    if (father != null && !fathers.contains(father)) {
      fathers.add(father);
    }
  }

  public List<String> getModulesNameList() {
    if (getCompilationCommand() != CompCmd.CRTSRVPGM) throw new IllegalArgumentException("Method not valid for command " + getCompilationCommandName());
  
    List<String> modList = new ArrayList<>();

    String modules = get(ParamCmd.MODULE);
    if (modules.isEmpty()) return modList;

    for (String mod : modules.split("\\s+")) {
      if (mod.isEmpty()) continue;

      String modName = mod.replaceAll(".*/", "").trim(); // strip lib if present
      if (modName.isEmpty()) continue;

      if (modName.isEmpty()) continue;

      modList.add(modName);
    }

    return modList;
  }

  public String getSourceType() {
    return this.sourceType.name();
  }

  public String getSourceFile() {
    String source = get(ParamCmd.SRCFILE);
    if (source.isEmpty()) return this.sourceFile;

    source = source.replace("'", "").trim(); // Remove scaping
    String[] sourceList = source.split("/");
    if(sourceList.length < 2){
      this.sourceFile = source;
    } else {
      this.sourceFile = sourceList[1]; // Remove lib and only get source name
    }
    return this.sourceFile;
  }

  public String getSourceName() {
    String member = get(ParamCmd.SRCMBR);
    if (member.isEmpty()) return this.sourceName;
    this.sourceName = member;
    return this.sourceName;
  }

  public String getLibrary() {
    return this.library;
  }

  public String getMemberPath() {
    return "/QSYS.lib/" + getLibrary() + ".lib/" + getSourceFile() + ".file/" + getSourceName()+ ".mbr";
  }

  public CompCmd getCompilationCommand() {
    return this.compilationCommand;
  }

  public String getCompilationCommandName() {
    return this.compilationCommand.name();
  }

  public ParamMap getParamMap() {
    return this.ParamCmdSequence;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;

    // Allow comparison with String directly
    if (obj instanceof String) {
      String str = (String) obj;
      /* Performs validation for map key. If false just ignore*/
      if (this.asMapKey().equals(str)) return true;
      return this.asString().toUpperCase().equals(str);
    }

    /* If the other object is not a TargetKey, return false */
    if (!(obj instanceof TargetKey)) return false;

    /* Key string comparison between two TargetKey objects */
    TargetKey targetKey = (TargetKey) obj;
    return this.asString().toUpperCase().equals(targetKey.asString().toUpperCase());
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.asString().toUpperCase());
  }

}