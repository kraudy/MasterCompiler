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

  public Migrator(Connection connection, boolean debug, boolean verbose, User currentUser, CommandExecutor commandExec) {
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
    this.currentUser = currentUser;
    this.commandExec = commandExec;
  }

  public void migrateSource(TargetKey key) throws Exception, SQLException{
    switch (key.getCompilationCommand()){
      case CRTCLMOD:
      case CRTRPGMOD:
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTSQLRPGI:
      case CRTSRVPGM:
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
          if (!sourceMemberExists(key)) createSourceMember(key);
          migrateStreamFileToMember(key);
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
    CommandObject cmd = new CommandObject(SysCmd.ADDPFM)
      .put(ParamCmd.FILE, key.getQualifiedSourceFile())
      .put(ParamCmd.MBR, key.getSourceName())
      .put(ParamCmd.SRCTYPE, key.getSourceType());

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
                "AND TRIM(SOURCE_TYPE) <> '' LIMIT 1")) {
      if (validateRs.next()) {
        if (verbose) logger.info("Source PF " + key.getSourceFile() + " already exist in library " + key.getLibrary());
        return true;
      }
      if (verbose) logger.info("Source PF " + key.getSourceFile() + " does not exist in library " + key.getLibrary());
      return false;
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
            "AND TRIM(SOURCE_TYPE) <> '' ")) { 
      if (rs.next()) {
        if (verbose) logger.info("Member " + key.getSourceName() + " already exist in library " + key.getLibrary());
        return true;
      }
      return false;
    }
  }

}
