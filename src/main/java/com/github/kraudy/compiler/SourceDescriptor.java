package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SourceDescriptor {
  private static final Logger logger = LoggerFactory.getLogger(SourceDescriptor.class);

  private final Connection connection;
  private final boolean debug;
  private final boolean verbose;

  public SourceDescriptor(Connection connection, boolean debug, boolean verbose) {
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
  }

  /* Get Pgm and SrvPgm objects creation timestamp */
  public void getPgmSrvPgmCreation (TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
        ResultSet rsObjCreationInfo = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT " +
              "CREATE_TIMESTAMP, " + // creationDateTime
              "SOURCE_FILE_CHANGE_TIMESTAMP " + // sourceUpdatedDateTime
            "FROM QSYS2.PROGRAM_INFO " +
            "INNER JOIN Libs " +
            "ON (PROGRAM_LIBRARY = Libs.Libraries) " +
            "WHERE " + 
                "PROGRAM_NAME = '" + key.getObjectName() + "' " +
                "AND OBJECT_TYPE = '" + key.getObjectType() + "' "
          )) {
      if (!rsObjCreationInfo.next()) {
        if (verbose) logger.info(("\nCould not extract object creation time '" + key.asString() ));
        return;
      }

      if (verbose) logger.info("\nFound object creation data '" + key.asString());

      key.setLastBuild(rsObjCreationInfo.getTimestamp("CREATE_TIMESTAMP"));
      key.setLastEdit(rsObjCreationInfo.getTimestamp("SOURCE_FILE_CHANGE_TIMESTAMP"));

    }
  }

  public void getModCreation (TargetKey key) throws SQLException {

  }

  public void getSqlCreation (TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
        ResultSet rsSql = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT " +
              "LAST_ALTERED_TIMESTAMP " +
            "FROM QSYS2.SYSFILES " + 
            "INNER JOIN Libs " +
            "ON (TABLE_SCHEMA = Libs.Libraries) " +
            "WHERE " + 
                "TABLE_NAME = '" + key.getObjectName() + "' " +
                "AND SQL_OBJECT_TYPE = '" + key.getObjectTypeName() + "' "
          )) {
      if (!rsSql.next()) {
        if (verbose) logger.info(("\nCould not extract sql object creation time '" + key.asString() ));
        return;
      }

      if (verbose) logger.info("\nFound sql object creation data '" + key.asString());

      key.setLastBuild(rsSql.getTimestamp("LAST_ALTERED_TIMESTAMP"));
      
    }
  }

  public void getDdsCreation (TargetKey key) throws SQLException {

  }

  public void getObjectTimestamps(TargetKey key) throws SQLException {
    /* Get object creation timestamp */
    if (key.isProgram() || key.isServiceProgram()) {
      getPgmSrvPgmCreation(key);
    } else if (key.isModule()) {
      getModCreation(key);
    } else if (key.isSql()) {
      getSqlCreation(key);
    } else if (key.isDds()) {
      getDdsCreation(key);
    } 
    

    /* Get source stream file last change */
    if (key.containsStreamFile()){
      //TODO: Add git diff
      getSourceStreamFileLastChange(key);
      return;
    }
    /* Get source member last change */
    getSourceMemberLastChange(key);
    return;
  }

  public void getSourceMemberLastChange(TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
          ResultSet rs = stmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
              "SELECT LAST_SOURCE_UPDATE_TIMESTAMP FROM QSYS2.SYSPARTITIONSTAT " +
              "INNER JOIN Libs " +
              "ON (TABLE_SCHEMA = Libs.Libraries) " +
              "WHERE TABLE_NAME = '" + key.getSourceFile() + "' " +
              "AND TABLE_PARTITION = '" + key.getSourceName() + "'" +
              "AND SOURCE_TYPE = '" + key.getSourceType() + "'")) {
        if (!rs.next()) {
          if (verbose) logger.info("\nCould not get source member last change: " + key.getSourceName());
          key.setLastEdit(null);  // File not found
          return;  
        }

        if (verbose) logger.info("\nFound source member last change: " + key.getSourceName());
        key.setLastEdit(rs.getTimestamp("LAST_SOURCE_UPDATE_TIMESTAMP"));
        return;
    }
  }

  public void getSourceStreamFileLastChange(TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
          ResultSet rs = stmt.executeQuery(
            "SELECT DATA_CHANGE_TIMESTAMP " + 
            "FROM TABLE (QSYS2.IFS_OBJECT_STATISTICS( " +
                    "START_PATH_NAME => '" + key.getStreamFile() +  "', " +
                    "SUBTREE_DIRECTORIES => 'NO' " +
                ") " +
            ")")) {
        if (!rs.next()) {
            if (verbose) logger.info("\nCould not get source stream file last change: " + key.getStreamFile());
          key.setLastEdit(null);  // File not found
          return;
        }
        
        if (verbose) logger.info("\nFound source stream file last change: " + key.getStreamFile());
        key.setLastEdit(rs.getTimestamp("DATA_CHANGE_TIMESTAMP"));
        return;
    }
  }
}
