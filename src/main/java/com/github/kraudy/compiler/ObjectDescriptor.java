package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;

/* Core struct for capturing compilation params */
public class ObjectDescriptor {
  private static final Logger logger = LoggerFactory.getLogger(ObjectDescriptor.class);

  private final Connection connection;
  private final boolean debug;
  private final boolean verbose;

  public ObjectDescriptor(Connection connection, boolean debug, boolean verbose) {
    this.connection = connection;
    this.debug = debug;
    this.verbose = verbose;
  }

  public void getObjectInfo(TargetKey key) throws SQLException {

    /* Check if object exists */
    objectExists(key);

    /* If no object, return */
    if (!key.objectExists()) return;

    switch (key.getCompilationCommand()) {
      /* PGM info */
      case CRTBNDRPG:
      case CRTBNDCL:
      case CRTRPGPGM:
      case CRTCLPGM:
        getPgmInfo(key);
        break;

      /* Sql Rpg info */
      case CRTSQLRPGI:
        getSqlRpgInfo(key);
        break;

      /* SrvPgm info */
      case CRTSRVPGM:
        getSrvpgmInfo(key);
        break;
     
      /* Module info */
      case CRTRPGMOD :
      case CRTCLMOD :
        getModuleInfo(key); 
        break;

      /* Sql info */
      case RUNSQLSTM :
        getSqlInfo(key);
        break;

      /* Dds info */
      case CRTDSPF: 
      case CRTPF:
      case CRTLF:
      case CRTPRTF:
        getDdsInfo(key);
        break;

      /* Cmd info */
      case CRTCMD :
        getCmdInfo(key);
        break;
    
      default:
        break;
    }

    return;
  }

  public void objectExists(TargetKey key) throws SQLException {
    if (key.getObjectTypeEnum() == ObjectType.FUNCTION) {
      try (Statement stmt = connection.createStatement();
          ResultSet rs = stmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
            "SELECT 1 " + 
            "FROM QSYS2.SYSFUNCS " +
            "INNER JOIN Libs " +
            "ON (SPECIFIC_SCHEMA = Libs.Libraries) " +
            "WHERE SPECIFIC_NAME = '" + key.getObjectName() + "' " +
            "LIMIT 1")) {
        if (!rs.next()) {
          if (verbose) logger.info("Function object not found: " + key.asString());
          return;  
        }

        if (verbose) logger.info("Function object found: " + key.asString());
        key.setObjectExists(true);
        return;
      }
    }

    if (key.getObjectTypeEnum() == ObjectType.INDEX) {
      try (Statement stmt = connection.createStatement();
          ResultSet rs = stmt.executeQuery(
            "With " +
            Utilities.CteLibraryList +
            "SELECT 1 " +
            "FROM QSYS2.SYSINDEXES " +
            "INNER JOIN Libs " +
            "ON (SYSTEM_INDEX_SCHEMA = Libs.Libraries) " +
            "WHERE SYSTEM_INDEX_NAME = '" + key.getObjectName() + "' " +
            "LIMIT 1")) {
        if (!rs.next()) {
          if (verbose) logger.info("Function object not found: " + key.asString());
          return;  
        }

        if (verbose) logger.info("Function object found: " + key.asString());
        key.setObjectExists(true);
        return;
      }
    }

    if (key.getObjectTypeEnum() == ObjectType.TRIGGER) {
      try (Statement stmt = connection.createStatement();
          ResultSet rs = stmt.executeQuery(
            "WITH " + 
            Utilities.CteLibraryList +
            " SELECT 1 " +
            " FROM QSYS2.SYSTRIGGERS " +
            " INNER JOIN Libs " +
            "   ON TRIGGER_SCHEMA = Libs.Libraries " +
            " WHERE TRIGGER_NAME = '" + key.getObjectName() + "' " +
            " LIMIT 1")) {
        if (!rs.next()) {
          if (verbose) logger.info("Trigger object not found: " + key.asString());
          return;  
        }

        if (verbose) logger.info("Trigger object found: " + key.asString());
        key.setObjectExists(true);
        return;
      }
    }

    /* This also finds procedures which are of type PGM */
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(
          "Select 1  " +
          "From TABLE( " +
            "QSYS2.OBJECT_STATISTICS( " +
              "OBJECT_SCHEMA => '" + ValCmd.LIBL.toString() + "', " +
              "OBJTYPELIST => '" + key.getObjectType() + "', " +
              "OBJECT_NAME => '" + key.getObjectName() + "' " +
            ") " +
          ") " + 
          "LIMIT 1")) {
      if (!rs.next()) {
        if (verbose) logger.info("Object not found: " + key.asString());
        return;  
      }

      if (verbose) logger.info("Found object: " + key.asString());
      key.setObjectExists(true);
      return;
    }
  }

  /* *PGM */
  private void getPgmInfo(TargetKey key) throws SQLException {
    
    try (Statement stmt = connection.createStatement();
        ResultSet rsObj = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT PROGRAM_LIBRARY, " + // programLibrary
              "PROGRAM_NAME, " + // programName
              "COALESCE(PROGRAM_TYPE,'') As PROGRAM_TYPE, " +  // [ILE, OPM] 
              "OBJECT_TYPE, " +   // typeOfProgram
              "COALESCE(TEXT_DESCRIPTION, '') As TEXT, " + // textDescription
              "PROGRAM_OWNER, " + // owner
              "PROGRAM_ATTRIBUTE, " + // attribute
              "USER_PROFILE As USRPRF, " +
              "USE_ADOPTED_AUTHORITY, " +
              "RELEASE_CREATED_ON, " +
              "COALESCE(TARGET_RELEASE, '') As TGTRLS, " +
              "COALESCE(ALLOW_RTVCLSRC, '') As ALWRTVSRC, " + // allowRTVCLSRC
              "CONVERSION_REQUIRED, " +
              "CONVERSION_DETAIL, " +
              //-- These seem to be for ILE objects
              "COALESCE(ACTIVATION_GROUP, '') AS ACTGRP, " + // activationGroupAttribute
              "SHARED_ACTIVATION_GROUP, " +
              "ALLOW_UPDATE, " +
              "ALLOW_BOUND_SRVPGM_LIBRARY_UPDATE, " +
              "ALL_CREATION_DATA, " +
              "COALESCE(PROFILING_DATA, '') As PRFDTA, " +
              "COALESCE(STORAGE_MODEL , '') As STGMDL, " +
              "ARGUMENT_OPTIMIZATION, " +
              "NUMBER_OF_UNRESOLVED_REFERENCES, " +
              // Source file related data
              "(TRIM(SOURCE_FILE_LIBRARY) || '/' || TRIM(SOURCE_FILE)) As SRCFILE, " +
              "SOURCE_FILE_MEMBER As SRCMBR, " +
              "COALESCE((TRIM(SQL_SORT_SEQUENCE_LIBRARY) || '/' || TRIM(SQL_SORT_SEQUENCE)), '') As SRTSEQ, " +
              "COALESCE(LANGUAGE_ID, '') As LANGID, " +
              "OBSERVABLE, " + // observable
              "COALESCE(OPTIMIZATION, '') As OPTIMIZE, " +
              "COALESCE(LOG_COMMANDS, '' ) As LOG, " +
              "COALESCE(FIX_DECIMAL_DATA, '') As FIXNBR, " + // fixDecimalData
              "TERASPACE_STORAGE_ENABLED_PROGRAM " + // teraspaceEnabled
            "FROM QSYS2.PROGRAM_INFO " +
            "INNER JOIN Libs " +
            "ON (PROGRAM_LIBRARY = Libs.Libraries) " +
            "WHERE " + 
                "PROGRAM_NAME = '" + key.getObjectName() + "' " +
                "AND OBJECT_TYPE = '" + key.getObjectType() + "' "
          )) {
      if (!rsObj.next()) {
        if(verbose) logger.info(("Could not retrieve pgm object compilation info " + key.asString() ));
        return;
      }

      if (verbose) logger.info("Found pgm object compilation info '" + key.asString());

      switch (key.getCompilationCommand()) {
        case CRTBNDRPG:
          String fixNbr = rsObj.getString("FIXNBR").trim();
          if (!fixNbr.isEmpty()){
            key.put(ParamCmd.FIXNBR, fixNbr.equals("1") ? ValCmd.YES : ValCmd.NO);
          }

          String prfDta = rsObj.getString("PRFDTA").trim();
          if (!prfDta.isEmpty()) key.put(ParamCmd.PRFDTA, prfDta);

        case CRTBNDCL:
          String actgrp = rsObj.getString("ACTGRP").trim();
          if (!actgrp.isEmpty()) key.put(ParamCmd.ACTGRP, actgrp);
          if ("QILE".equals(actgrp)) key.put(ParamCmd.DFTACTGRP, ValCmd.NO);

          String stgMdl = rsObj.getString("STGMDL").trim();
          if (!stgMdl.isEmpty()) key.put(ParamCmd.STGMDL, stgMdl);

          String optimize = rsObj.getString("OPTIMIZE").trim();
          if (!optimize.isEmpty())  {
            try {
              key.put(ParamCmd.OPTIMIZE, optimize);
            } catch (Exception ignore) {}
          }

        case CRTRPGPGM:
        case CRTCLPGM:
          String tgtRls = rsObj.getString("TGTRLS").trim();
          key.put(ParamCmd.TGTRLS, ValCmd.CURRENT);
          if (!tgtRls.isEmpty()) key.put(ParamCmd.TGTRLS, tgtRls);

          String text = rsObj.getString("TEXT").trim();
          if (!text.isEmpty()) key.put(ParamCmd.TEXT, text);

          String usrPrf = rsObj.getString("USRPRF").trim();
          if (!usrPrf.isEmpty()) key.put(ParamCmd.USRPRF, usrPrf);

          String srtSeq = rsObj.getString("SRTSEQ").trim();
          if (!srtSeq.isEmpty()) key.put(ParamCmd.SRTSEQ, srtSeq);          

          String langId = rsObj.getString("LANGID").trim();
          if (!langId.isEmpty()) key.put(ParamCmd.LANGID, langId);

          break;
      }

      switch (key.getCompilationCommand()) {
        case CRTBNDCL:
        case CRTCLPGM:
          String logCmds = rsObj.getString("LOG").trim();
          if (!logCmds.isEmpty()) key.put(ParamCmd.LOG, logCmds.equals("1") ? ValCmd.YES : ValCmd.NO);

          String alwRtvSrc = rsObj.getString("ALWRTVSRC").trim();
          if (!alwRtvSrc.isEmpty()) key.put(ParamCmd.ALWRTVSRC, alwRtvSrc.equals("1") ? ValCmd.YES : ValCmd.NO);
          break;
      }

    }
  }

  /* Query BOUND_MODULE_INFO for module-specific fields */
  private void getModuleInfo(TargetKey key) throws SQLException {
    if (!key.isModule()) {
      if(verbose) logger.info("" + key.asString() + " Is not a module");
      return;
    }
    try (Statement stmt = connection.createStatement();
        ResultSet rsMod = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT " +
                "MODULE_CREATE_TIMESTAMP, " +
                "SOURCE_CHANGE_TIMESTAMP, " +
                "MODULE_CCSID, " +
                "COALESCE((TRIM(SQL_SORT_SEQUENCE_LIBRARY) || '/' || TRIM(SQL_SORT_SEQUENCE)), '') As SRTSEQ, " +
                "LANGUAGE_ID As LANGID, " +
                "DEBUG_DATA, " +
                "COALESCE(OPTIMIZATION_LEVEL, '') As OPTIMIZE, " +
                "MAX_OPTIMIZATION_LEVEL, " +
                "OBJECT_CONTROL_LEVEL, " +
                "RELEASE_CREATED_ON, " +
                "TARGET_RELEASE AS TGTRLS, " +
                "CREATION_DATA, " +
                "TERASPACE_STORAGE_ENABLED, " +
                "STORAGE_MODEL As STGMDL, " +
                "NUMBER_PROCEDURES, " +
                "PROFILING_DATA As PRFDTA, " +
                "ALLOW_RTVCLSRC As ALWRTVSRC, " +
                "USER_MODIFIED, " +
                "COALESCE(LIC_OPTIONS, '') As LICOPT " +
          /*  QSYS2.PROGRAM_INFO does not shows module objects */
          "FROM QSYS2.BOUND_MODULE_INFO " +
          "INNER JOIN Libs " +
          /* Here we need to also use the PROGRAM_LIBRARY, otherwise, the query becomes slow */
            "ON (PROGRAM_LIBRARY = Libs.Libraries AND BOUND_MODULE_LIBRARY = Libs.Libraries) " +
          "WHERE " +
            "BOUND_MODULE = '" + key.getObjectName() + "' " +
            "AND MODULE_ATTRIBUTE = '" + key.getSourceType() + "' "
        )) {
      if (!rsMod.next()) {
        if(verbose) logger.info("Could not retrieve module compilation info " + key.asString());
        return;
      }

      if (verbose) logger.info("Found module compilation info " + key.asString());

      String modOptimize = rsMod.getString("OPTIMIZE").trim();
      if (!modOptimize.isEmpty()) {
        switch (modOptimize) {
          case "10": key.put(ParamCmd.OPTIMIZE, ValCmd.NONE);   break;
          case "20": key.put(ParamCmd.OPTIMIZE, ValCmd.BASIC);  break;
          case "30": key.put(ParamCmd.OPTIMIZE, ValCmd.BASIC);  break;
          case "40": key.put(ParamCmd.OPTIMIZE, ValCmd.FULL);   break;
        }
      }

      String srtSeq = rsMod.getString("SRTSEQ").trim();
      if (!srtSeq.isEmpty()) key.put(ParamCmd.SRTSEQ, srtSeq); 

      key.put(ParamCmd.LANGID, rsMod.getString("LANGID").trim()) 
        .put(ParamCmd.TGTRLS, rsMod.getString("TGTRLS").trim()) 
        .put(ParamCmd.STGMDL, rsMod.getString("STGMDL").trim()) 
        .put(ParamCmd.PRFDTA, rsMod.getString("PRFDTA").trim()); 

      switch (key.getCompilationCommand()) {
        case CRTCLMOD:
          key.put(ParamCmd.ALWRTVSRC, rsMod.getString("ALWRTVSRC").trim()); 
          break;
      }

      String licopt = rsMod.getString("LICOPT").trim();
      if(!licopt.isEmpty()) key.put(ParamCmd.LICOPT, licopt); 
    }
  }

  private void getCmdInfo(TargetKey key) throws SQLException {
    
    try (Statement stmt = connection.createStatement();
        ResultSet rsCmdInfo = stmt.executeQuery(
          "With " + 
          Utilities.CteLibraryList +
          "SELECT " +
            "(TRIM(COMMAND_LIBRARY) || '/' || TRIM(COMMAND_NAME)) As CMD, " +
            "TEXT_DESCRIPTION As TEXT, " +
            "(TRIM(COMMAND_PROCESSING_PROGRAM_LIBRARY) || '/' || TRIM(COMMAND_PROCESSING_PROGRAM)) As PGM, " +
            "(TRIM(SOURCE_FILE_LIBRARY) || '/' || TRIM(SOURCE_FILE)) As SRCFILE, " +
            "SOURCE_FILE_MEMBER As SRCMBR, " +
            "THREADSAFE As THDSAFE " +
            "FROM QSYS2.COMMAND_INFO " +
            "INNER JOIN Libs " +
            "ON (COMMAND_LIBRARY = Libs.Libraries) " +
            "WHERE " + 
                "COMMAND_NAME = '" + key.getObjectName() + "' "
          )) {
      if (!rsCmdInfo.next()) {
        if(verbose) logger.info(("Could not retrieve command compilation info " + key.asString()));
        return;
      }

      if (verbose) logger.info("Found command compilation info" + key.asString());
      
      String cmd = rsCmdInfo.getString("CMD").trim();
      if(!cmd.isEmpty()) key.put(ParamCmd.CMD, cmd); 

      String pgm = rsCmdInfo.getString("PGM").trim();
      if(!pgm.isEmpty()) key.put(ParamCmd.PGM, pgm); 

      String srcfile = rsCmdInfo.getString("SRCFILE").trim();
      if(!srcfile.isEmpty()) key.put(ParamCmd.SRCFILE, srcfile); 

      String srcmbr = rsCmdInfo.getString("SRCMBR").trim();
      if(!srcmbr.isEmpty()) key.put(ParamCmd.SRCMBR, srcmbr); 

      ValCmd threadsafe = ValCmd.fromString(rsCmdInfo.getString("THDSAFE").trim());
      key.put(ParamCmd.THDSAFE, threadsafe); 

      }
  }

  private void getSqlRpgInfo(TargetKey key)throws SQLException{
    try (Statement stmt = connection.createStatement();
        ResultSet rsSqlRpgInfo = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT PROGRAM_LIBRARY, " + // programLibrary
              "PROGRAM_NAME, " + // programName
              "OBJECT_TYPE, " +   // typeOfProgram
              "COALESCE(TEXT_DESCRIPTION, '') As TEXT, " + // textDescription
              "PROGRAM_OWNER, " + // owner
              "PROGRAM_ATTRIBUTE, " + // attribute
              "USER_PROFILE As USRPRF, " +
              "USE_ADOPTED_AUTHORITY, " +
              "COALESCE(TARGET_RELEASE, '') As TGTRLS, " +
              // Module related data
              "COPYRIGHTS, " +
              "COPYRIGHT_STRINGS, " +
              // Source file related data
              "(TRIM(SOURCE_FILE_LIBRARY) || '/' || TRIM(SOURCE_FILE)) As SRCFILE, " +
              "SOURCE_FILE_MEMBER As SRCMBR, " +
              //-- Sql related info
              "SQL_RELATIONAL_DATABASE, " +
              "COALESCE(SQL_COMMITMENT_CONTROL, '') As COMMIT, " +
              "COALESCE(SQL_NAMING, '') As NAMING, " +
              "SQL_DATE_FORMAT As DATFMT, " +
              "SQL_DATE_SEPARATOR As DATSEP, " +
              "SQL_TIME_FORMAT As TIMFMT, " +
              "SQL_TIME_SEPARATOR As TIMSEP, " +
              "(TRIM(SQL_SORT_SEQUENCE_LIBRARY) || '/' || TRIM(SQL_SORT_SEQUENCE)) As SRTSEQ, " +
              "SQL_LANGUAGE_ID As LANGID, " +
              "SQL_DEFAULT_SCHEMA, " +
              "SQL_PATH, " +
              "SQL_DYNAMIC_USER_PROFILE As DYNUSRPRF, " +
              "SQL_ALLOW_COPY_DATA As ALWCPYDTA, " +
              "SQL_CLOSE_SQL_CURSOR As CLOSQLCSR, " +
              "SQL_DELAY_PREPARE As DLYPRP, " +
              "SQL_ALLOW_BLOCK As ALWBLK " +
            "FROM QSYS2.PROGRAM_INFO " +
            "INNER JOIN Libs " +
            "ON (PROGRAM_LIBRARY = Libs.Libraries) " +
            "WHERE " + 
                "PROGRAM_NAME = '" + key.getObjectName() + "' " +
                "AND OBJECT_TYPE = '" + key.getObjectType() + "' "
          )) {
      if (!rsSqlRpgInfo.next()) {
        if(verbose) logger.info(("Could not retrieve sql object compilation info " + key.asString() ));
        return;
      }

      if (verbose) logger.info("Found sql object compilation info " + key.asString());

      key.put(ParamCmd.TEXT, rsSqlRpgInfo.getString("TEXT").trim())
        .put(ParamCmd.USRPRF, rsSqlRpgInfo.getString("USRPRF").trim()); 

      String tgtrls = rsSqlRpgInfo.getString("TGTRLS").trim();
      if(!tgtrls.isEmpty()) key.put(ParamCmd.TGTRLS, tgtrls); 

      //TODO: The service does not returns data for these filed.

      //key.put(ParamCmd.COMMIT, ValCmd.fromString(rsSqlRpgInfo.getString("COMMIT"))); 
      //key.put(ParamCmd.NAMING, ValCmd.fromString(rsSqlRpgInfo.getString("NAMING"))); 

      //key.put(ParamCmd.DATFMT, ValCmd.fromString(rsSqlRpgInfo.getString("DATFMT"))); 
      //key.put(ParamCmd.DATSEP, ValCmd.fromString(rsSqlRpgInfo.getString("DATSEP"))); 

      //key.put(ParamCmd.TIMFMT, ValCmd.fromString(rsSqlRpgInfo.getString("TIMFMT"))); 
      //key.put(ParamCmd.TIMSEP, ValCmd.fromString(rsSqlRpgInfo.getString("TIMSEP"))); 

      //key.put(ParamCmd.SRTSEQ, rsSqlRpgInfo.getString("SRTSEQ").trim()); 
      //key.put(ParamCmd.LANGID, rsSqlRpgInfo.getString("LANGID").trim()); 

      //key.put(ParamCmd.DYNUSRPRF, ValCmd.fromString(rsSqlRpgInfo.getString("DYNUSRPRF"))); 
      //key.put(ParamCmd.ALWCPYDTA, ValCmd.fromString(rsSqlRpgInfo.getString("ALWCPYDTA"))); 
      //key.put(ParamCmd.CLOSQLCSR, ValCmd.fromString(rsSqlRpgInfo.getString("CLOSQLCSR"))); 
      //key.put(ParamCmd.DLYPRP, ValCmd.fromString(rsSqlRpgInfo.getString("DLYPRP"))); 
      //key.put(ParamCmd.ALWBLK, ValCmd.fromString(rsSqlRpgInfo.getString("ALWBLK"))); 
    }
  }

  private void getSrvpgmInfo(TargetKey key) throws SQLException {
    try (Statement stmt = connection.createStatement();
        ResultSet rsSrvPgm = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT PROGRAM_LIBRARY, " + // programLibrary
              "PROGRAM_NAME, " + // programName
              "COALESCE(PROGRAM_TYPE,'') As PROGRAM_TYPE, " +  // [ILE, OPM] 
              "OBJECT_TYPE, " +   // typeOfProgram
              "COALESCE(TEXT_DESCRIPTION, '') As TEXT, " + // textDescription
              "PROGRAM_OWNER, " + // owner
              "PROGRAM_ATTRIBUTE, " + // attribute
              "USER_PROFILE As USRPRF, " +
              "USE_ADOPTED_AUTHORITY, " +
              "RELEASE_CREATED_ON, " +
              "COALESCE(TARGET_RELEASE, '') As TGTRLS, " +
              "COALESCE(ALLOW_RTVCLSRC, '') As ALWRTVSRC, " + // allowRTVCLSRC
              "CONVERSION_REQUIRED, " +
              "CONVERSION_DETAIL, " +
              //-- These seem to be for ILE objects
              "COALESCE(ACTIVATION_GROUP, '') AS ACTGRP, " + // activationGroupAttribute
              "SHARED_ACTIVATION_GROUP, " +
              "ALLOW_UPDATE, " +
              "ALLOW_BOUND_SRVPGM_LIBRARY_UPDATE, " +
              "ALL_CREATION_DATA, " +
              "COALESCE(PROFILING_DATA, '') As PRFDTA, " +
              "COALESCE(STORAGE_MODEL , '') As STGMDL, " +
              "ARGUMENT_OPTIMIZATION, " +
              "NUMBER_OF_UNRESOLVED_REFERENCES, " +
              // Source file related data
              "(TRIM(SOURCE_FILE_LIBRARY) || '/' || TRIM(SOURCE_FILE)) As SRCFILE, " +
              "SOURCE_FILE_MEMBER As SRCMBR, " +
              "COALESCE((TRIM(SQL_SORT_SEQUENCE_LIBRARY) || '/' || TRIM(SQL_SORT_SEQUENCE)), '') As SRTSEQ, " +
              "COALESCE(LANGUAGE_ID, '') As LANGID, " +
              "OBSERVABLE, " + // observable
              "COALESCE(OPTIMIZATION, '') As OPTIMIZE, " +
              "COALESCE(LOG_COMMANDS, '' ) As LOG, " +
              "COALESCE(FIX_DECIMAL_DATA, '') As FIXNBR, " + // fixDecimalData
              "TERASPACE_STORAGE_ENABLED_PROGRAM " + // teraspaceEnabled
            "FROM QSYS2.PROGRAM_INFO " +
            "INNER JOIN Libs " +
            "ON (PROGRAM_LIBRARY = Libs.Libraries) " +
            "WHERE " + 
                "PROGRAM_NAME = '" + key.getObjectName() + "' " +
                "AND OBJECT_TYPE = '" + key.getObjectType() + "' "
          )) {
      if (!rsSrvPgm.next()) {
        if (verbose) logger.info(("Could not retrieve srvpgm object compilation info " + key.asString() ));
        return;
      }

      if (verbose) logger.info("Found srvpgm object compilation info " + key.asString());

      String actgrp = rsSrvPgm.getString("ACTGRP").trim();
      if (!actgrp.isEmpty()) key.put(ParamCmd.ACTGRP, actgrp);
      if ("QILE".equals(actgrp)) key.put(ParamCmd.DFTACTGRP, ValCmd.NO);

      String stgMdl = rsSrvPgm.getString("STGMDL").trim();
      if (!stgMdl.isEmpty()) key.put(ParamCmd.STGMDL, stgMdl);

      String tgtRls = rsSrvPgm.getString("TGTRLS").trim();
      key.put(ParamCmd.TGTRLS, ValCmd.CURRENT);
      if (!tgtRls.isEmpty()) key.put(ParamCmd.TGTRLS, tgtRls);

      String text = rsSrvPgm.getString("TEXT").trim();
      if (!text.isEmpty()) key.put(ParamCmd.TEXT, text);

      String usrPrf = rsSrvPgm.getString("USRPRF").trim();
      if (!usrPrf.isEmpty()) key.put(ParamCmd.USRPRF, usrPrf);
    }
  }
  
  private void getSqlInfo(TargetKey key) throws SQLException{
    //TODO: I tried using QSYS2.SYSFILES but it does not shows coompilation params
    return;
  }

  private void getDdsInfo(TargetKey key){
    //TODO: What view should we use here?
    return;
  }

  

}