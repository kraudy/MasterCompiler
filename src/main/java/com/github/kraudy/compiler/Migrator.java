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
          key.put(ParamCmd.SRCSTMF, key.getStreamFile());
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
          key.put(ParamCmd.SRCFILE, key.getQualifiedSourceFile());
          key.put(ParamCmd.SRCMBR, key.getObjectName());
        }
        break;
    }
  }

  public void createSourcePf(TargetKey key) throws Exception {
    CommandObject cmd = new CommandObject(SysCmd.CRTSRCPF);

    cmd.put(ParamCmd.FILE, key.getQualifiedSourceFile());
    
    commandExec.executeCommand(cmd);
  }

  public void createSourceMember(TargetKey key) throws Exception {
    CommandObject cmd = new CommandObject(SysCmd.ADDPFM);

    cmd.put(ParamCmd.FILE, key.getQualifiedSourceFile());
    cmd.put(ParamCmd.MBR, key.getSourceName());
    cmd.put(ParamCmd.SRCTYPE, key.getSourceType());

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

    cmd.put(ParamCmd.FROMMBR, key.getMemberPath());
    cmd.put(ParamCmd.TOSTMF, key.getStreamFile());
    cmd.put(ParamCmd.STMFOPT, "*REPLACE");
    cmd.put(ParamCmd.STMFCCSID, MasterCompiler.UTF8_CCSID);
    cmd.put(ParamCmd.ENDLINFMT, "*LF");

    commandExec.executeCommand(cmd);
  }

  public void migrateStreamFileToMember(TargetKey key) throws Exception {
    CommandObject cmd = new CommandObject(SysCmd.CPYFRMSTMF);

    cmd.put(ParamCmd.FROMSTMF, key.getStreamFile());
    cmd.put(ParamCmd.TOMBR, key.getMemberPath());
    cmd.put(ParamCmd.MBROPT, "*REPLACE");
    cmd.put(ParamCmd.CVTDTA, "*AUTO");
    cmd.put(ParamCmd.STMFCODPAG, MasterCompiler.UTF8_CCSID);

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
