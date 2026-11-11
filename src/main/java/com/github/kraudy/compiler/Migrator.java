package com.github.kraudy.compiler;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  static final String MEMBER_TEXT = "MasterCompiler: copy of the stream file";  // marks the members MC writes
  private String replacedMember;

  public Migrator(Connection connection, boolean debug, boolean verbose, User currentUser, CommandExecutor commandExec) {
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
    this.currentUser = currentUser;
    this.commandExec = commandExec;
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
          String foreign = foreignMember(key);
          boolean created = false;
          if (foreign != null) {
            replacedMember = (commandExec.isDryRun() ? "Would replace" : "Replaced") + " source member " + foreign
                + " with " + key.getStreamFile() + ": MasterCompiler did not create it (or an MC before v0.3.31 did; "
                + "its members are marked from then on)";
            logger.warn(replacedMember);
          } else if (!sourceMemberExists(key)) {
            createSourceMember(key);
            created = true;
          }
          migrateStreamFileToMember(key);
          /* MC's members carry its text: a replaced one is reported once, not at every build */
          if (foreign != null || created) {
            commandExec.executeCommand("CHGPFM FILE(" + key.getQualifiedSourceFile() + ") MBR(" + key.getSourceName()
                + ") TEXT(''" + MEMBER_TEXT + "'')", commandExec.getCurrentTime());  // inside QCMDEXC('...'): quotes doubled
          }
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

  /*
   * LIB/FILE(MEMBER), changed ..., text '...' when the member exists and its text is not MC's: someone else's source
   * (a member from an older MC, with no text, counts as someone else's once)
   */
  String foreignMember(TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
         ResultSet rs = stmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
            "SELECT CAST(SYSTEM_TABLE_SCHEMA AS VARCHAR(10) CCSID " + MasterCompiler.INVARIANT_CCSID + ") AS Lib, " +
            "COALESCE(CAST(PARTITION_TEXT AS VARCHAR(50) CCSID " + MasterCompiler.INVARIANT_CCSID + "), '') AS Text, " +
            "LAST_CHANGE_TIMESTAMP AS Changed " +
            "FROM QSYS2.SYSPARTITIONSTAT " +
            "INNER JOIN Libs " +
            "ON (SYSTEM_TABLE_SCHEMA = Libs.Libraries) " +
            "WHERE SYSTEM_TABLE_NAME = '" + key.getSourceFile() + "' " +
            "AND SYSTEM_TABLE_MEMBER = '" + key.getSourceName() + "' " +
            sourceLibraryFilter(key) + "LIMIT 1")) {
      if (!rs.next()) return null;
      String text = rs.getString("Text").trim();
      if (text.equals(MEMBER_TEXT)) return null;
      return rs.getString("Lib").trim() + "/" + key.getSourceFile() + "(" + key.getSourceName() + "), changed "
          + rs.getTimestamp("Changed") + (text.isEmpty() ? "" : ", text '" + text + "'");
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
