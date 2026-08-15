package com.github.kraudy.compiler;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;

public class SourceDescriptor {
  private static final Logger logger = LoggerFactory.getLogger(SourceDescriptor.class);

  private final AS400 system;
  private final Connection connection;
  private final String baseDirectory;
  private final boolean debug;
  private final boolean verbose;

  public SourceDescriptor(AS400 system, Connection connection, String baseDirectory,
      boolean debug, boolean verbose) {
    this.system = system;
    this.connection = connection;
    this.baseDirectory = baseDirectory;
    this.debug = debug;
    this.verbose = verbose;
  }

  /* Get Pgm and SrvPgm objects creation timestamp */
  public void getPgmSrvPgmCreation (TargetKey key) throws SQLException {
    if (connection == null) return;
    try (Statement stmt = connection.createStatement();
        ResultSet rsObjCreationInfo = stmt.executeQuery(
          "With " +
          Utilities.CteLibraryList +
          "SELECT " +
              "CREATE_TIMESTAMP " +
            "FROM QSYS2.PROGRAM_INFO " +
            "INNER JOIN Libs " +
            "ON (PROGRAM_LIBRARY = Libs.Libraries) " +
            "WHERE " +
                "PROGRAM_NAME = '" + key.getObjectName() + "' " +
                "AND OBJECT_TYPE = '" + key.getObjectType() + "' "
          )) {
      if (!rsObjCreationInfo.next()) {
        if (verbose) logger.info(("Could not extract object creation time '" + key.asString() ));
        return;
      }

      if (verbose) logger.info("Found object creation data '" + key.asString());

      key.setLastBuild(rsObjCreationInfo.getTimestamp("CREATE_TIMESTAMP"));
    }
  }

  public void getSqlCreation (TargetKey key) throws SQLException {
    if (connection == null) return;
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
        if (verbose) logger.info(("Could not extract sql object creation time '" + key.asString() ));
        return;
      }

      if (verbose) logger.info("Found sql object creation data '" + key.asString());

      key.setLastBuild(rsSql.getTimestamp("LAST_ALTERED_TIMESTAMP"));
    }
  }

  /** *MODULE, *FILE, *BNDDIR, *CMD, … via OBJECT_STATISTICS. */
  public void getObjectCreation(TargetKey key) throws SQLException {
    if (connection == null) return;
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(
          "Select OBJCREATED " +
          "From TABLE( " +
            "QSYS2.OBJECT_STATISTICS( " +
              "OBJECT_SCHEMA => '" + ValCmd.LIBL.toString() + "', " +
              "OBJTYPELIST => '" + key.getObjectType() + "', " +
              "OBJECT_NAME => '" + key.getObjectName() + "' " +
            ") " +
          ") " +
          "LIMIT 1")) {
      if (!rs.next()) {
        if (verbose) logger.info("Could not extract object creation time '" + key.asString());
        return;
      }
      if (verbose) logger.info("Found object creation data '" + key.asString());
      key.setLastBuild(rs.getTimestamp("OBJCREATED"));
    }
  }

  public void getObjectTimestamps(TargetKey key) throws SQLException {
    if (connection != null) {
      if (key.isProgram() || key.isServiceProgram()) {
        getPgmSrvPgmCreation(key);
      } else if (key.isSql()) {
        getSqlCreation(key);
      } else {
        getObjectCreation(key);
      }
    }

    if (key.containsStreamFile()) {
      getSourceStreamFileLastChange(key);
      return;
    }
    if (connection != null) {
      getSourceMemberLastChange(key);
    }
  }

  public void getSourceMemberLastChange(TargetKey key) throws SQLException {
    if (connection == null) return;
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
          if (verbose) logger.info("Could not get source member last change: " + key.getSourceName());
          return;
        }

        if (verbose) logger.info("Found source member last change: " + key.getSourceName());
        key.setLastEdit(rs.getTimestamp("LAST_SOURCE_UPDATE_TIMESTAMP"));
    }
  }

  /**
   * Stream-file mtime: local {@link File} if present, otherwise {@link IFSFile}.
   * Same split as {@link DependencyAwareness} source reads. No DB2.
   */
  public void getSourceStreamFileLastChange(TargetKey key) {
    Timestamp edit = latestSourceEdit(key);
    if (edit == null) {
      if (verbose) logger.info("Could not get source stream file last change: " + key.getStreamFile());
      return;
    }
    if (verbose) logger.info("Found source last change: " + key.getStreamFile());
    key.setLastEdit(edit);
  }

  /**
   * Newest mtime of the target's own SRCSTMF and any attached /copy|/include files.
   */
  public Timestamp latestSourceEdit(TargetKey key) {
    if (key == null) return null;
    Timestamp best = streamFileLastEdit(key);
    for (String inc : key.getIncludeFiles()) {
      Timestamp t = pathLastEdit(inc);
      if (t != null && (best == null || t.after(best))) {
        best = t;
      }
    }
    return best;
  }

  public Timestamp streamFileLastEdit(TargetKey key) {
    if (key == null || !key.containsStreamFile()) return null;
    return pathLastEdit(resolveFullPath(baseDirectory, key.getStreamFile()));
  }

  public Timestamp pathLastEdit(String fullPath) {
    if (fullPath == null || fullPath.isEmpty()) return null;

    File local = new File(fullPath);
    if (local.isFile()) {
      long ms = local.lastModified();
      return ms > 0 ? new Timestamp(ms) : null;
    }

    if (system == null) return null;
    try {
      IFSFile remote = new IFSFile(system, fullPath);
      if (!remote.exists()) return null;
      long ms = remote.lastModified();
      return ms > 0 ? new Timestamp(ms) : null;
    } catch (Exception e) {
      if (debug) logger.info("IFS lastModified failed for {}: {}", fullPath, e.toString());
      return null;
    }
  }

  static String resolveFullPath(String baseDir, String streamFile) {
    if (streamFile == null) return baseDir;
    String rel = streamFile.replace("'", "").trim();
    if (rel.startsWith("/") || (rel.length() > 2 && rel.charAt(1) == ':')) {
      return rel;
    }
    if (baseDir == null || baseDir.isEmpty()) return rel;
    if (baseDir.endsWith("/") || baseDir.endsWith("\\")) {
      return baseDir + rel;
    }
    return baseDir + "/" + rel;
  }
}
