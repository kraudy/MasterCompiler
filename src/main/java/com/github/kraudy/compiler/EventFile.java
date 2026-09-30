package com.github.kraudy.compiler;

import java.sql.Connection;
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
    Map<String, String> files = new HashMap<String, String>();

    for (String record : records) {
      if (record == null) continue;
      String line = record.trim();

      if (line.startsWith("PROCESSOR")) {
        files.clear();
        continue;
      }

      if (line.startsWith("FILEID")) {
        String[] t = line.split("\\s+", 6);  // FILEID ver id line len rest
        if (t.length < 6) continue;
        int length = toInt(t[4]);
        String name = t[5].length() >= length ? t[5].substring(0, length) : t[5];
        if (!name.trim().isEmpty()) files.put(t[2], relative(name.trim(), baseDir));  // no name: summary records
        continue;
      }

      if (line.startsWith("ERROR")) {
        String[] t = line.split("\\s+", 14);
        if (t.length < 14) continue;
        BuildReport.CompileError error = new BuildReport.CompileError();
        error.file = files.get(t[2]);
        error.line = toInt(t[5]);
        error.column = toInt(t[6]);
        error.endLine = toInt(t[7]);
        error.endColumn = toInt(t[8]);
        error.id = t[9];
        error.severity = toInt(t[11]);
        error.message = t[13].trim();
        errors.add(error);
      }
    }
    return errors;
  }

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
