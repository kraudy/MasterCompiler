package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Reads the EVFEVENT member a compile writes with OPTION(*EVENTF) into LIB/EVFEVENT(OBJECT)
 * (a CHAR FOR BIT DATA column, cast to the invariant CCSID to read it as text)
 * and turns its ERROR records into file/line/column errors.
 *
 * Records used (whitespace separated, message text last):
 *   PROCESSOR 0 000 1                                     new processor (e.g. SQL precompiler, then RPG)
 *   FILEID    0 001 000000 026 /path/src.rpgle 20240101120000 0
 *             version, file id, line, name length, name, timestamp, temp flag
 *   ERROR     0 001 1 000042 000042 008 000042 012 RNF7030 S 30 050 The name ...
 *             version, file id, annot class, stmt line, start line, start col,
 *             end line, end col, msg id, sev char, sev num, msg length, msg text
 * File ids restart with every PROCESSOR block.
 *
 * SQLRPGLE: the SQL precompiler's block has FILEID 999 (its output, the temporary member
 * QTEMP/QSQLTEMP1(OBJECT)) and EXPANSION records for the code it inserted:
 *   EXPANSION 0 001 000333 000333 999 000423 000431
 *             version, source file id, source start, source end, 999, temp start, temp end
 * The RPG compiler then compiles the temporary member, so its errors are mapped back:
 * a temp line inside an expansion becomes the SQL statement's source line, any other temp
 * line is shifted back by the size of the expansions before it.
 */
public final class EventFile {
  private static final Logger logger = LoggerFactory.getLogger(EventFile.class);

  private EventFile() {}

  /**
   * Errors from LIB/EVFEVENT(member), only when its TIMESTAMP record is at or after {@code since}
   * (a stale member from an earlier compile is ignored). Empty when there is no member.
   * Both times come from the IBM i clock (CURRENT_TIMESTAMP and the compiler), so they compare safely.
   */
  public static List<BuildReport.CompileError> read(Connection connection, String library, String member,
      Timestamp since, String baseDir) {
    List<BuildReport.CompileError> errors = new ArrayList<BuildReport.CompileError>();
    if (connection == null || library == null || member == null) return errors;

    String lib = library.trim().toUpperCase();
    String mbr = member.trim().toUpperCase();

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE OR REPLACE ALIAS QTEMP.MCEVFEVENT FOR " + lib + ".EVFEVENT (" + mbr + ")");

      List<String> records = new ArrayList<String>();
      try (ResultSet rs = stmt.executeQuery("SELECT CAST(E.EVFEVENT AS VARCHAR(400) CCSID " + MasterCompiler.INVARIANT_CCSID + ") " +
          "FROM QTEMP.MCEVFEVENT E ORDER BY RRN(E)")) {
        while (rs.next()) records.add(rs.getString(1));
      }
      if (!isFresh(records, since)) return errors;
      return parse(records, baseDir);

    } catch (SQLException e) {
      logger.info("Could not read event file {}/EVFEVENT({}): {}", lib, mbr, e.getMessage());
      return errors;
    }
  }

  public static List<BuildReport.CompileError> parse(List<String> records, String baseDir) {
    List<BuildReport.CompileError> errors = new ArrayList<BuildReport.CompileError>();
    Map<String, String> files = new HashMap<String, String>();                  // file id -> name, per processor
    Map<String, SqlExpansion> expansions = new HashMap<String, SqlExpansion>(); // temp member -> original source
    SqlExpansion precompile = null;  // set while reading the SQL precompiler's block

    for (String record : records) {
      if (record == null) continue;
      String line = record.trim();

      if (line.startsWith("PROCESSOR")) {
        files.clear();
        precompile = null;
        continue;
      }

      if (line.startsWith("FILEID")) {
        String[] t = line.split("\\s+", 6);  // FILEID ver id line len rest
        if (t.length < 6) continue;
        int length = toInt(t[4]);
        String name = (t[5].length() >= length ? t[5].substring(0, length) : t[5]).trim();
        if (name.isEmpty()) continue;  // no name: summary records
        files.put(t[2], name);
        if (t[2].equals(SqlExpansion.TEMP_FILE_ID)) {
          precompile = new SqlExpansion();
          expansions.put(memberName(name), precompile);
        }
        continue;
      }

      if (line.startsWith("EXPANSION")) {
        String[] t = line.split("\\s+");
        if (precompile == null || t.length < 8) continue;
        if (precompile.source == null) precompile.source = files.get(t[2]);
        precompile.add(toInt(t[3]), toInt(t[6]), toInt(t[7]));
        continue;
      }

      if (line.startsWith("ERROR")) {
        String[] t = line.split("\\s+", 14);
        if (t.length < 14) continue;
        BuildReport.CompileError error = new BuildReport.CompileError();
        String file = files.get(t[2]);
        error.line = toInt(t[5]);
        error.column = toInt(t[6]);
        error.endLine = toInt(t[7]);
        error.endColumn = toInt(t[8]);
        error.id = t[9];
        error.severity = toInt(t[11]);
        error.message = t[13].trim();

        SqlExpansion temp = file != null ? expansions.get(memberName(file)) : null;
        if (temp != null && temp.source != null) {  // RPG error in the precompiler's output
          file = temp.source;
          error.line = temp.toSource(error.line);
          error.endLine = temp.toSource(error.endLine);
        }
        error.file = file != null ? relative(file, baseDir) : null;
        errors.add(error);
      }
    }
    return errors;
  }

  /* Maps lines of the SQL precompiler's temporary member back to the original source */
  private static final class SqlExpansion {
    static final String TEMP_FILE_ID = "999";

    String source;                                     // original source path
    final List<int[]> ranges = new ArrayList<int[]>(); // {source line, temp start, temp end}, in temp order

    void add(int sourceLine, int tempStart, int tempEnd) {
      if (tempEnd >= tempStart) ranges.add(new int[] { sourceLine, tempStart, tempEnd });
    }

    int toSource(int tempLine) {
      if (tempLine <= 0) return tempLine;
      int inserted = 0;
      for (int[] range : ranges) {
        if (tempLine < range[1]) break;
        if (tempLine <= range[2]) return range[0];  // generated code: the SQL statement's line
        inserted += range[2] - range[1] + 1;
      }
      return tempLine - inserted;
    }
  }

  /*
   * Same member in both spellings the compilers use:
   * QTEMP/QSQLTEMP1(ART200) and /QSYS.LIB/QTEMP.LIB/QSQLTEMP1.FILE/ART200.MBR
   */
  private static String memberName(String name) {
    Matcher qsys = QSYS_MEMBER.matcher(name.trim());
    if (qsys.matches()) return (qsys.group(1) + "/" + qsys.group(2) + "(" + qsys.group(3) + ")").toUpperCase();
    return name.trim().toUpperCase();
  }

  private static final Pattern QSYS_MEMBER = Pattern.compile(
      "(?i)/QSYS\\.LIB/([^/]+)\\.LIB/([^/]+)\\.FILE/([^/]+)\\.MBR");

  /*
   * RUNSQLSTM has no event file: its errors are in the listing it spools (OPTION(*LIST)), named
   * after the source, e.g. EMPLOYEE for employee.table. Message lines look like
   *   SQL0104  30      15  Position 27 Token ( was not valid. Valid tokens: ) ,.
   *   msg id, severity, record (= line of the stream file), text
   */
  public static List<BuildReport.CompileError> readSqlListing(Connection connection, String spoolName,
      Timestamp since, String sourceFile) {
    return readListing(connection, spoolName, "SQL", since, sourceFile);
  }

  /*
   * The SQL messages of the user's listings named spoolName with that user data (RUNSQLSTM: SQL; the SQL precompilers:
   * the command, e.g. CRTSQLCI), spooled since then. They go to the user's QPRTJOB, not the compiling job.
   */
  public static List<BuildReport.CompileError> readListing(Connection connection, String spoolName, String userData,
      Timestamp since, String sourceFile) {
    if (connection == null || spoolName == null || spoolName.isEmpty()) return new ArrayList<BuildReport.CompileError>();
    String name = spoolName.toUpperCase();
    if (name.length() > 10) name = name.substring(0, 10);
    List<String> lines = new ArrayList<String>();
    try (PreparedStatement stmt = connection.prepareStatement(
           "SELECT S.SPOOLED_FILE_NUMBER, D.SPOOLED_DATA " +
           "FROM TABLE(QSYS2.SPOOLED_FILE_INFO(USER_NAME => USER, STARTING_TIMESTAMP => ?)) S " +
           "INNER JOIN TABLE(SYSTOOLS.SPOOLED_FILE_DATA(JOB_NAME => S.QUALIFIED_JOB_NAME, " +
             "SPOOLED_FILE_NAME => S.SPOOLED_FILE_NAME, SPOOLED_FILE_NUMBER => S.SPOOLED_FILE_NUMBER)) D ON 1 = 1 " +
           "WHERE S.SPOOLED_FILE_NAME = ? AND S.USER_DATA = ? ORDER BY S.SPOOLED_FILE_NUMBER, D.ORDINAL_POSITION")) {
      stmt.setTimestamp(1, since);
      stmt.setString(2, name);
      stmt.setString(3, userData);
      try (ResultSet rs = stmt.executeQuery()) {
        Integer first = null;  // the first listing since then only: never two merged (same name, another library or session)
        while (rs.next()) {
          if (first == null) first = rs.getInt(1);
          if (rs.getInt(1) != first) break;
          lines.add(rs.getString(2) == null ? "" : rs.getString(2));
        }
      }
    } catch (SQLException e) {
      logger.info("Could not read listing {}: {}", name, e.getMessage());
    }
    return parseListing(lines, sourceFile);
  }

  /* A listing's message lines (id, severity, record, text) with the text lines indented under them */
  static List<BuildReport.CompileError> parseListing(List<String> lines, String sourceFile) {
    List<BuildReport.CompileError> errors = new ArrayList<BuildReport.CompileError>();
    BuildReport.CompileError last = null;
    for (String line : lines) {
      Matcher m = SQL_LISTING_MESSAGE.matcher(line.trim());
      Matcher more = LISTING_CONTINUED.matcher(line);
      if (m.matches()) {
        last = new BuildReport.CompileError();
        last.file = sourceFile;
        last.id = m.group(1);
        last.severity = toInt(m.group(2));
        last.line = toInt(m.group(3));
        last.endLine = last.line;
        last.message = m.group(4).trim();
        Matcher position = SQL_POSITION.matcher(last.message);
        if (position.find()) last.column = toInt(position.group(1));
        errors.add(last);
      } else if (last != null && more.matches()) {
        last.message += " " + more.group(1).trim();
      } else {
        last = null;
      }
    }
    return errors;
  }

  private static final Pattern SQL_LISTING_MESSAGE = Pattern.compile("^(SQ[A-Z0-9]{5})\\s+(\\d{1,2})\\s+(\\d+)\\s+(.*)$");
  private static final Pattern LISTING_CONTINUED = Pattern.compile("^ {21}(\\S.*)$");  // the TEXT column (21), under the message; "Message Summary" is at 20
  private static final Pattern SQL_POSITION = Pattern.compile("Position (\\d+)");

  /* First record is "TIMESTAMP 0 yyyyMMddHHmmss"; the member must not predate the target build */
  private static boolean isFresh(List<String> records, Timestamp since) {
    if (since == null) return true;
    for (String record : records) {
      if (record == null || !record.trim().startsWith("TIMESTAMP")) continue;
      String[] t = record.trim().split("\\s+");
      if (t.length < 3) return false;
      try {
        long written = new SimpleDateFormat("yyyyMMddHHmmss").parse(t[2]).getTime();
        return written >= since.getTime() - 1000;  // record has second precision
      } catch (ParseException e) {
        return false;
      }
    }
    return false;
  }

  private static String relative(String path, String baseDir) {
    if (baseDir == null || baseDir.isEmpty()) return path;
    String base = baseDir.endsWith("/") ? baseDir : baseDir + "/";
    return path.startsWith(base) ? path.substring(base.length()) : path;
  }

  private static int toInt(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
