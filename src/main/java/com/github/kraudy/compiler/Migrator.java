package com.github.kraudy.compiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.ibm.as400.access.User;

/*
 * Source files migrator
 */
public class Migrator {
  private static final Logger logger = LoggerFactory.getLogger(Migrator.class);

  private final Connection connection;
  private final boolean debug;
  private final boolean verbose;
  private final User currentUser;
  private CommandExecutor commandExec;
  static final String MEMBER_TEXT = "MasterCompiler: copy of the stream file";  // for people: MC wrote this member
  static final String MEMBER_REFUSAL = "Did not replace";
  /* Members this project's builds wrote, with their timestamps then: any other existing member is someone else's */
  static final String MEMBERS_FILE = ".mc/members.json";
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private String replacedMember;
  private File project;            // where MEMBERS_FILE is kept; null: none, every existing member counts as foreign
  private Boolean replaceMembers;  // null: replace and warn (CLI); false: refuse (MCP); true: replace (MCP, user agreed)

  public Migrator(Connection connection, boolean debug, boolean verbose, User currentUser, CommandExecutor commandExec) {
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
    this.currentUser = currentUser;
    this.commandExec = commandExec;
  }

  public void setProject(File project) {
    this.project = project;
  }

  public void setReplaceMembers(Boolean replaceMembers) {
    this.replaceMembers = replaceMembers;
  }

  public void migrateSource(TargetKey key) throws Exception, SQLException{
    switch (key.getCompilationCommand()){
      case CRTSRVPGM:
        /* If Export and not Stream file, ignore migration */
        if (key.containsKey(ParamCmd.EXPORT) && !key.containsStreamFile()) break;
      case CRTCLMOD:
      case CRTCMOD:
      case CRTBNDC:
      case CRTRPGMOD:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTSQLRPGI:
      case RUNSQLSTM:
      case CRTCMD:
        /* 
         * Migrate from source member to stream file
         */
        if (!key.containsKey(ParamCmd.SRCSTMF) && 
          key.containsKey(ParamCmd.SRCFILE)) {
          if(verbose) logger.info("Migrating source member to stream file");
          migrateMemberToStreamFile(key);

          key.put(ParamCmd.SRCSTMF, key.getStreamFile())
            .removeSourceFile()
            .removeMember();
        }
        break;

      case CRTCLPGM:
      case CRTRPGPGM:
      case CRTDSPF:
      case CRTPF:
      case CRTLF:
      case CRTPRTF:
      case CRTMNU:
      case CRTQMQRY:
        /* 
         * Migrate from stream file to source member
         */
        if (key.containsStreamFile()) {
          //TODO: Should this be migrated to QTEMP?
          if(verbose) logger.info("Migrating stream file to source member");
          if (!sourcePfExists(key)) createSourcePf(key);
          Member before = member(key);
          String foreign = before != null && !written(before) ? before.describe() : null;
          if (foreign != null) {
            boolean refuse = Boolean.FALSE.equals(replaceMembers);
            String what = " source member " + foreign + " with " + key.getStreamFile() + ": this project's builds did "
                + "not write it, or it changed since";
            if (refuse && !commandExec.isDryRun()) {
              throw new CompilerException(MEMBER_REFUSAL + what + ". Show the user; build again with replaceMembers: "
                  + "true only if they agree (it replaces every such member of that build)");
            }
            replacedMember = (commandExec.isDryRun() ? "Would replace" : "Replaced") + what
                + (refuse ? " (build replaces it only with replaceMembers: true, after the user agreed)" : "");
            logger.warn(replacedMember);
          } else if (before == null && !sourceMemberExists(key)) {
            createSourceMember(key);
          }
          migrateStreamFileToMember(key);
          if (before == null || !MEMBER_TEXT.equals(before.text)) {
            commandExec.executeCommand("CHGPFM FILE(" + key.getQualifiedSourceFile() + ") MBR(" + key.getSourceName()
                + ") TEXT(''" + MEMBER_TEXT + "'')", commandExec.getCurrentTime());  // inside QCMDEXC('...'): quotes doubled
          }
          if (!commandExec.isDryRun()) record(member(key));
          key.put(ParamCmd.SRCFILE, key.getQualifiedSourceFile())
            .put(ParamCmd.SRCMBR, key.getObjectName())
            .removeStreamFile();
        }
        break;
    }
  }

  public void createSourcePf(TargetKey key) throws Exception {
    /* 
     * If this creation returns an error because the source pf already exits
     * that means that it has no data and can be ommited
     * SYSPARTITIONSTAT does not catch empty source pf.
     */
    try {
      CommandObject cmd = new CommandObject(SysCmd.CRTSRCPF)
        .put(ParamCmd.FILE, key.getQualifiedSourceFile());
      
      commandExec.executeCommand(cmd);
    } catch (Exception ignore) {
      if(verbose) logger.info("Omitting source pf creation: " + key.getQualifiedSourceFile() + " .Already exists");
    }
    
  }

  public void createSourceMember(TargetKey key) throws Exception {
    /* DDS members are typed by what they describe (PF, LF, DSPF, PRTF), as IBM tools expect */
    String sourceType = key.getSourceType().equals("DDS") ? key.getObjectTypeEnum().name() : key.getSourceType();
    CommandObject cmd = new CommandObject(SysCmd.ADDPFM)
      .put(ParamCmd.FILE, key.getQualifiedSourceFile())
      .put(ParamCmd.MBR, key.getSourceName())
      .put(ParamCmd.SRCTYPE, sourceType);

    commandExec.executeCommand(cmd);

  }

  public void migrateMemberToStreamFile(TargetKey key) throws Exception {
    CommandObject cmd = new CommandObject(SysCmd.CPYTOSTMF);

    if(!key.containsStreamFile()){
      String migrationPath = currentUser.getHomeDirectory() + "/" + "sources";
      File migrationDir = new File(migrationPath);
      if (!migrationDir.exists()) migrationDir.mkdirs(); // Create dir if it does not exists
      key.setStreamSourceFile(migrationPath + "/" + key.asString());
    }

    cmd.put(ParamCmd.FROMMBR, key.getMemberPath())
      .put(ParamCmd.TOSTMF, key.getStreamFile())
      .put(ParamCmd.STMFOPT, "*REPLACE")
      .put(ParamCmd.STMFCCSID, MasterCompiler.UTF8_CCSID)
      .put(ParamCmd.ENDLINFMT, "*LF");

    commandExec.executeCommand(cmd);
  }

  public void migrateStreamFileToMember(TargetKey key) throws Exception {
    CommandObject cmd = new CommandObject(SysCmd.CPYFRMSTMF)
      .put(ParamCmd.FROMSTMF, key.getStreamFile())
      .put(ParamCmd.TOMBR, key.getMemberPath())
      .put(ParamCmd.MBROPT, "*REPLACE")
      .put(ParamCmd.CVTDTA, "*AUTO")
      .put(ParamCmd.STMFCODPAG, MasterCompiler.UTF8_CCSID);

    commandExec.executeCommand(cmd);
  }

  /* Validate if Source PF exists */
  public boolean sourcePfExists(TargetKey key) throws SQLException{
    try (Statement validateStmt = connection.createStatement();
        ResultSet validateRs = validateStmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
            "SELECT 1 AS Exist " +
            "FROM QSYS2. SYSPARTITIONSTAT " +
            "INNER JOIN Libs " +
            "ON (SYSTEM_TABLE_SCHEMA = Libs.Libraries) " +
                "WHERE SYSTEM_TABLE_NAME = '" + key.getSourceFile() + "' " +
                sourceLibraryFilter(key) +
                "AND TRIM(SOURCE_TYPE) <> '' LIMIT 1")) {
      if (validateRs.next()) {
        if (verbose) logger.info("Source PF " + key.getSourceFile() + " already exist in library " + key.getLibrary());
        return true;
      }
      if (verbose) logger.info("Source PF " + key.getSourceFile() + " does not exist in library " + key.getLibrary());
      return false;
    }
  }

  /*
   * The source file is created and copied into key.getQualifiedSourceFile(): look there, not anywhere on the
   * library list (a QRPGSRC in another library on the list made MC skip creating ROBKRAUDY2/QRPGSRC, and the
   * copy into it then failed with CPFA0A9).  *LIBL / *CURLIB keep the library-list search.
   */
  static String sourceLibraryFilter(TargetKey key) {
    String qualified = key.getQualifiedSourceFile();
    int slash = qualified == null ? -1 : qualified.indexOf('/');
    if (slash <= 0) return "";
    String library = qualified.substring(0, slash).trim().toUpperCase();
    if (library.startsWith("*")) return "";
    return "AND SYSTEM_TABLE_SCHEMA = '" + library.replace("'", "''") + "' ";
  }

  /* The source member this build replaced (or would replace) although MC did not create it; null when none */
  public String takeReplacedMember() {
    String replaced = replacedMember;
    replacedMember = null;
    return replaced;
  }

  /* A source member as SYSPARTITIONSTAT has it */
  static final class Member {
    String server;         // CURRENT SERVER: the same library names on another IBM i are other members
    String name;           // LIB/FILE(MBR), the library it resolved to
    String text;
    String changed;        // LAST_CHANGE_TIMESTAMP
    String sourceUpdated;  // LAST_SOURCE_UPDATE_TIMESTAMP: an edit of its lines

    String id() {
      return server + " " + name;
    }

    String describe() {
      return name + ", changed " + changed + (text.isEmpty() || text.equals(MEMBER_TEXT) ? "" : ", text '" + text + "'");
    }
  }

  /* The member the stream file goes into, null when it does not exist yet */
  Member member(TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
            "SELECT CAST(SYSTEM_TABLE_SCHEMA AS VARCHAR(10) CCSID " + MasterCompiler.INVARIANT_CCSID + ") AS Lib, " +
            "COALESCE(CAST(PARTITION_TEXT AS VARCHAR(50) CCSID " + MasterCompiler.INVARIANT_CCSID + "), '') AS Text, " +
            "VARCHAR(LAST_CHANGE_TIMESTAMP) AS Changed, VARCHAR(LAST_SOURCE_UPDATE_TIMESTAMP) AS SourceUpdated, " +
            "CAST(CURRENT SERVER AS VARCHAR(18) CCSID " + MasterCompiler.INVARIANT_CCSID + ") AS Server " +
            "FROM QSYS2.SYSPARTITIONSTAT " +
            "INNER JOIN Libs " +
            "ON (SYSTEM_TABLE_SCHEMA = Libs.Libraries) " +
            "WHERE SYSTEM_TABLE_NAME = '" + key.getSourceFile() + "' " +
            "AND SYSTEM_TABLE_MEMBER = '" + key.getSourceName() + "' " +
            sourceLibraryFilter(key) + "LIMIT 1")) {
      if (!rs.next()) return null;
      Member m = new Member();
      m.server = rs.getString("Server").trim();
      m.name = rs.getString("Lib").trim() + "/" + key.getSourceFile() + "(" + key.getSourceName() + ")";
      m.text = rs.getString("Text").trim();
      m.changed = rs.getString("Changed");
      m.sourceUpdated = rs.getString("SourceUpdated");
      return m;
    }
  }

  /* This project's builds wrote it, and nobody changed it since: both timestamps as recorded (unknown: not ours) */
  boolean written(Member m) {
    JsonNode recorded = readMembers().path(m.id());
    return m.changed != null && m.sourceUpdated != null
        && m.changed.equals(recorded.path("changed").asText(null))
        && m.sourceUpdated.equals(recorded.path("sourceUpdated").asText(null));
  }

  private ObjectNode readMembers() {
    if (project == null) return mapper.createObjectNode();
    File file = new File(project, MEMBERS_FILE);
    try {
      if (file.isFile()) {
        JsonNode all = mapper.readTree(file);
        if (all.isObject()) return (ObjectNode) all;
      }
    } catch (Exception e) {
      logger.warn("Could not read {}: {}", MEMBERS_FILE, e.getMessage());
    }
    return mapper.createObjectNode();
  }

  /* After MC wrote the member: its timestamps now, written to a temporary file first so a crash leaves the old record */
  private void record(Member m) {
    if (project == null || m == null) return;
    ObjectNode all = readMembers();
    ObjectNode entry = all.putObject(m.id());
    entry.put("changed", m.changed);
    entry.put("sourceUpdated", m.sourceUpdated);
    File file = new File(project, MEMBERS_FILE);
    try {
      file.getParentFile().mkdirs();
      File temp = File.createTempFile("members", ".tmp", file.getParentFile());  // its own: another build's is not touched
      mapper.writeValue(temp, all);
      try {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      logger.warn("Could not write {}: {}", MEMBERS_FILE, e.getMessage());
    }
  }

  /* Validate if Source Member exists */
  public boolean sourceMemberExists(TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
            "SELECT CAST(SYSTEM_TABLE_MEMBER AS VARCHAR(10) CCSID " + MasterCompiler.INVARIANT_CCSID + ") AS Member " +
            "FROM QSYS2.SYSPARTITIONSTAT " +
            "INNER JOIN Libs " +
            "ON (SYSTEM_TABLE_SCHEMA = Libs.Libraries) " +
            "WHERE SYSTEM_TABLE_NAME = '" + key.getSourceFile() + "' " +
            "AND SYSTEM_TABLE_MEMBER = '" + key.getSourceName() + "' " +
            /* no SOURCE_TYPE test here: the file is already known to be a source file, and the member that
               CPYFRMSTMF MBROPT(*REPLACE) filled has a null SOURCE_TYPE, which made every rebuild try ADDPFM
               again (CPF5812 member already exists) */
            sourceLibraryFilter(key))) { 
      if (rs.next()) {
        if (verbose) logger.info("Member " + key.getSourceName() + " already exist in library " + key.getLibrary());
        return true;
      }
      return false;
    }
  }

}
