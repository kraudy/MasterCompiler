package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * A service program's signature changes when its exports change (a new procedure, with EXPORT(*ALL) or a binder source
 * whose current signature is generated). A program bound to the old signature fails at activation (MCH4431) until it is
 * bound again. After a build, the callers in the service program's library whose bound signature is none of its current
 * ones are listed with the command that binds them again. Callers the same build rebuilt are bound already.
 */
final class SignatureCheck {
  static final int SIGNATURE_BYTES = 16;

  /* How each kind of caller is bound again to the service programs' current signatures */
  static final Map<String, String> REBIND = new LinkedHashMap<String, String>();
  static {
    REBIND.put("*PGM", "UPDPGM PGM(%s/%s) MODULE(*NONE)");
    REBIND.put("*SRVPGM", "UPDSRVPGM SRVPGM(%s/%s) MODULE(*NONE)");
  }

  static final String NOTE = "Its exports changed, so its signature did: these callers are bound to an old one and fail "
      + "with MCH4431 (signature violation) when called, until bound again. Run each rebind (run_command), or build them.";

  private static final String CALLERS = "SELECT TRIM(B.PROGRAM_LIBRARY), TRIM(B.PROGRAM_NAME), TRIM(B.OBJECT_TYPE), "
      + "B.BOUND_SERVICE_PROGRAM_SIGNATURE, P.EXPORT_SIGNATURES, TRIM(P.PROGRAM_LIBRARY), P.SIGNATURES "
      + "FROM QSYS2.PROGRAM_INFO P JOIN QSYS2.BOUND_SRVPGM_INFO B ON B.PROGRAM_LIBRARY = P.PROGRAM_LIBRARY "
      + "AND B.BOUND_SERVICE_PROGRAM = P.PROGRAM_NAME AND B.BOUND_SERVICE_PROGRAM_LIBRARY IN (P.PROGRAM_LIBRARY, '*LIBL') "
      + "WHERE P.PROGRAM_LIBRARY = COALESCE(?, (SELECT SYSTEM_SCHEMA_NAME FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT')) "
      + "AND P.PROGRAM_NAME = ? AND P.OBJECT_TYPE = '*SRVPGM'";

  /* The callers of library/name bound to a signature it no longer has (library null or CURLIB: the current library) */
  static BuildReport.SignatureChange staleCallers(Connection connection, String library, String name) throws SQLException {
    BuildReport.SignatureChange change = null;
    String lib = library == null || library.equalsIgnoreCase("CURLIB") || library.equalsIgnoreCase("*CURLIB") ? null : library;
    try (PreparedStatement stmt = connection.prepareStatement(CALLERS)) {
      stmt.setString(1, lib);
      stmt.setString(2, name);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          if (bound(rs.getBytes(5), rs.getInt(7), rs.getBytes(4))) continue;
          if (change == null) {
            change = new BuildReport.SignatureChange();
            change.serviceProgram = rs.getString(6) + "/" + name;
            change.note = NOTE;
          }
          change.callers.add(rs.getString(1) + "/" + rs.getString(2) + " " + rs.getString(3));
          String rebind = REBIND.get(rs.getString(3));
          if (rebind != null) change.rebind.add(String.format(rebind, rs.getString(1), rs.getString(2)));
        }
      }
    }
    return change;
  }

  /* The caller's signature is one of the service program's 'count' (EXPORT_SIGNATURES: 16 bytes each, with or without a
     separator byte between them: the length tells) */
  static boolean bound(byte[] exportSignatures, int count, byte[] signature) {
    if (exportSignatures == null || signature == null) return true;  // nothing to compare: not reported
    int stride = count > 1 && exportSignatures.length >= count * (SIGNATURE_BYTES + 1) - 1 ? SIGNATURE_BYTES + 1 : SIGNATURE_BYTES;
    for (int at = 0; at + SIGNATURE_BYTES <= exportSignatures.length; at += stride) {
      if (Arrays.equals(Arrays.copyOfRange(exportSignatures, at, at + SIGNATURE_BYTES),
          Arrays.copyOf(signature, SIGNATURE_BYTES))) return true;
    }
    return false;
  }

  /* After a build: each service program it built whose callers need binding again */
  static List<BuildReport.SignatureChange> check(Connection connection, List<BuildReport.TargetResult> targets) {
    List<BuildReport.SignatureChange> changes = new ArrayList<BuildReport.SignatureChange>();
    for (BuildReport.TargetResult result : targets) {
      String[] parts = result.target.split("\\.");
      if (!BuildReport.BUILT.equals(result.status) || parts.length < 3 || !"SRVPGM".equals(parts[2])) continue;
      try {
        BuildReport.SignatureChange change = staleCallers(connection, parts[0], parts[1]);
        if (change != null) changes.add(change);
      } catch (SQLException e) {
        BuildReport.SignatureChange unknown = new BuildReport.SignatureChange();
        unknown.serviceProgram = parts[0] + "/" + parts[1];
        unknown.note = "Its callers' signatures could not be checked: " + e.getMessage();
        changes.add(unknown);
      }
    }
    return changes;
  }
}
