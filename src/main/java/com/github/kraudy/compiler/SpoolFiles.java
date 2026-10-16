package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * The spool tool: the signed-on user's own spooled files (a compile listing, RPGUNIT, QPJOBLOG, a program's
 * report). Without 'file' it lists the newest ones; with 'file' it reads one, a page of lines at a time.
 * Another user's spooled files are never read, whatever the profile's authority.
 */
final class SpoolFiles {

  static final int LISTED = 30;
  static final int DEFAULT_LINES = 300;
  static final int MAX_LINES = 2000;

  private static final ObjectMapper mapper = new ObjectMapper();

  private SpoolFiles() {}

  static ObjectNode handle(Connection connection, JsonNode args) throws SQLException {
    String file = upper(args.path("file").asText(""));
    String job = upper(args.path("job").asText(""));
    if (!file.isEmpty() && !file.matches("[A-Z$#@][A-Z0-9$#@_]{0,9}")) throw new IllegalArgumentException("'file' is a spooled file name, e.g. RPGUNIT");
    if (!job.isEmpty() && !job.matches("\\d{6}/[A-Z0-9$#@_]{1,10}/[A-Z0-9$#@_]{1,10}")) {
      throw new IllegalArgumentException("'job' is a qualified job name: 123456/USER/JOBNAME");
    }
    if (file.isEmpty() || args.path("list").asBoolean(false)) return list(connection, file, job);
    return read(connection, file, job, args.path("number").asInt(0), args.path("startLine").asInt(1), args.path("maxLines").asInt(0));
  }

  /* The newest spooled files of this user, optionally of one name and / or job */
  static ObjectNode list(Connection connection, String file, String job) throws SQLException {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode files = result.putArray("spooledFiles");
    String sql = "SELECT SPOOLED_FILE_NAME, SPOOLED_FILE_NUMBER, QUALIFIED_JOB_NAME, CREATION_TIMESTAMP, TOTAL_PAGES, USER_DATA, STATUS "
        + "FROM TABLE(QSYS2.SPOOLED_FILE_INFO(USER_NAME => USER)) WHERE 1 = 1"
        + (file.isEmpty() ? "" : " AND SPOOLED_FILE_NAME = ?") + (job.isEmpty() ? "" : " AND QUALIFIED_JOB_NAME = ?")
        + " ORDER BY CREATION_TIMESTAMP DESC FETCH FIRST " + LISTED + " ROWS ONLY";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      int p = 1;
      if (!file.isEmpty()) stmt.setString(p++, file);
      if (!job.isEmpty()) stmt.setString(p, job);
      stmt.setQueryTimeout(60);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          ObjectNode f = files.addObject();
          f.put("file", trim(rs.getString(1)));
          f.put("number", rs.getInt(2));
          f.put("job", trim(rs.getString(3)));
          f.put("created", String.valueOf(rs.getTimestamp(4)));
          f.put("pages", rs.getInt(5));
          f.put("userData", trim(rs.getString(6)));
          f.put("status", trim(rs.getString(7)));
        }
      }
    }
    result.put("next", files.size() == 0 ? "No spooled file of this user matches"
        : "Read one with file, job and number (the newest of that name when job is left out)");
    return result;
  }

  /* One spooled file's lines: startLine (1-based), up to maxLines */
  static ObjectNode read(Connection connection, String file, String job, int number, int startLine, int maxLines) throws SQLException {
    int lines = Math.max(1, Math.min(maxLines <= 0 ? DEFAULT_LINES : maxLines, MAX_LINES));
    int start = Math.max(1, startLine);
    /* Which one: it must be this user's (SPOOLED_FILE_INFO only lists those) */
    String sql = "SELECT QUALIFIED_JOB_NAME, SPOOLED_FILE_NUMBER, CREATION_TIMESTAMP FROM TABLE(QSYS2.SPOOLED_FILE_INFO(USER_NAME => USER)) "
        + "WHERE SPOOLED_FILE_NAME = ?" + (job.isEmpty() ? "" : " AND QUALIFIED_JOB_NAME = ?")
        + (number > 0 ? " AND SPOOLED_FILE_NUMBER = ?" : "") + " ORDER BY CREATION_TIMESTAMP DESC FETCH FIRST 1 ROW ONLY";
    String qualifiedJob;
    int fileNumber;
    String created;
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      int p = 1;
      stmt.setString(p++, file);
      if (!job.isEmpty()) stmt.setString(p++, job);
      if (number > 0) stmt.setInt(p, number);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalArgumentException("No spooled file " + file + (job.isEmpty() ? "" : " in job " + job)
              + (number > 0 ? " number " + number : "") + " of this user. Call spool without 'file' to list them");
        }
        qualifiedJob = trim(rs.getString(1));
        fileNumber = rs.getInt(2);
        created = String.valueOf(rs.getTimestamp(3));
      }
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("file", file);
    result.put("job", qualifiedJob);
    result.put("number", fileNumber);
    result.put("created", created);
    ArrayNode text = mapper.createArrayNode();
    int total = 0;
    try (PreparedStatement stmt = connection.prepareStatement("SELECT SPOOLED_DATA FROM TABLE(SYSTOOLS.SPOOLED_FILE_DATA("
        + "JOB_NAME => ?, SPOOLED_FILE_NAME => ?, SPOOLED_FILE_NUMBER => ?)) ORDER BY ORDINAL_POSITION")) {
      stmt.setString(1, qualifiedJob);
      stmt.setString(2, file);
      stmt.setString(3, String.valueOf(fileNumber));
      stmt.setQueryTimeout(120);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          total++;
          if (total >= start && text.size() < lines) {
            String line = rs.getString(1);
            text.add(line == null ? "" : line.replaceAll("\\s+$", ""));
          }
        }
      }
    }
    result.put("totalLines", total);
    result.put("startLine", start);
    result.set("lines", text);
    if (start + text.size() <= total) {
      result.put("more", "Lines " + (start + text.size()) + "-" + total + " not shown: call again with startLine "
          + (start + text.size()));
    }
    return result;
  }

  private static String upper(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
