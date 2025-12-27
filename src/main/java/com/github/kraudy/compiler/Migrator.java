package com.github.kraudy.compiler;

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

  public void migrateSource(TargetKey key) throws SQLException{
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
          if(verbose) logger.info("\nMigrating source member to stream file");
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
          if(verbose) logger.info("\nMigrating stream file to source member");
          if (!sourcePfExists(key)) createSourcePf(key);
          if (!sourceMemberExists(key)) createSourceMember(key);
          migrateStreamFileToMember(key);
          key.put(ParamCmd.SRCFILE, key.getQualifiedSourceFile());
          key.put(ParamCmd.SRCMBR, key.getObjectName());
        }
        break;
    }
  }

  public void createSourcePf(TargetKey key){
    ParamMap map = new ParamMap();
    map.put(SysCmd.CRTSRCPF, ParamCmd.FILE, key.getQualifiedSourceFile());
    
    commandExec.executeCommand(map.getCommandString(SysCmd.CRTSRCPF));
  }

  public void createSourceMember(TargetKey key){

    ParamMap map = new ParamMap();

    map.put(SysCmd.ADDPFM, ParamCmd.FILE, key.getQualifiedSourceFile());
    map.put(SysCmd.ADDPFM, ParamCmd.MBR, key.getSourceName());
    map.put(SysCmd.ADDPFM, ParamCmd.SRCTYPE, key.getSourceType());

    commandExec.executeCommand(map.getCommandString(SysCmd.ADDPFM));

  }

  public void migrateMemberToStreamFile(TargetKey key){
    ParamMap map = new ParamMap();

    if(!key.containsStreamFile()) key.setStreamSourceFile(currentUser.getHomeDirectory() + "/" + "sources" + "/" + key.asString());

    map.put(SysCmd.CPYTOSTMF, ParamCmd.FROMMBR, key.getMemberPath());
    map.put(SysCmd.CPYTOSTMF, ParamCmd.TOSTMF, key.getStreamFile());
    map.put(SysCmd.CPYTOSTMF, ParamCmd.STMFOPT, "*REPLACE");
    map.put(SysCmd.CPYTOSTMF, ParamCmd.STMFCCSID, MasterCompiler.UTF8_CCSID);
    map.put(SysCmd.CPYTOSTMF, ParamCmd.ENDLINFMT, "*LF");

    commandExec.executeCommand(map.getCommandString(SysCmd.CPYTOSTMF));
  }

  public void migrateStreamFileToMember(TargetKey key){
    ParamMap map = new ParamMap();

    map.put(SysCmd.CPYFRMSTMF, ParamCmd.FROMSTMF, key.getStreamFile());
    map.put(SysCmd.CPYFRMSTMF, ParamCmd.TOMBR, key.getMemberPath());
    map.put(SysCmd.CPYFRMSTMF, ParamCmd.MBROPT, "*REPLACE");
    map.put(SysCmd.CPYFRMSTMF, ParamCmd.CVTDTA, "*AUTO");
    map.put(SysCmd.CPYFRMSTMF, ParamCmd.STMFCODPAG, MasterCompiler.UTF8_CCSID);

    commandExec.executeCommand(map.getCommandString(SysCmd.CPYFRMSTMF));
  }

  /* Validate if Source PF exists */
  public boolean sourcePfExists(TargetKey key) throws SQLException{
    //TODO: Change this for library list
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
        if (verbose) logger.info("\nSource PF " + key.getSourceFile() + " already exist in library " + key.getLibrary());
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
        if (verbose) logger.info("\nMember " + key.getSourceName() + " already exist in library " + key.getLibrary());
        return true;
      }
      return false;
    }
  }

}
