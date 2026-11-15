package com.github.kraudy.compiler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * The MCP server's read connection (sql, checkRows, the job monitor): opened once and kept. A change of the build job's
 * library list is applied on it with CHGLIBL instead of a new connection, since every new connection signs on again.
 * It is opened with access "read call" (CHGLIBL is a CALL of QSYS2.QCMDEXC) and setReadOnly(true): jt400 refuses
 * every statement but SELECT and CALL, and what the sql tool runs is checked by SqlTools first.
 */
final class ReadConnection implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(ReadConnection.class);

  private final Callable<Connection> opener;
  private final HostConnections connections;
  private Connection connection;
  private String applied;  // the CHGLIBL last run on this connection: null after an open

  ReadConnection(Callable<Connection> opener, HostConnections connections) {
    this.opener = opener;
    this.connections = connections;
  }

  /* As it is (the monitor qualifies every name): opened when there is none */
  synchronized Connection get() throws Exception {
    if (connection != null && !connection.isClosed()) return connection;
    String reason = connection == null ? "first use" : "the connection was closed";
    connection = connections.open(HostConnections.Purpose.READ_ONLY, reason, opener);
    applied = null;
    return connection;
  }

  /* With this library list (a CHGLIBL command), applied in place when it changed */
  synchronized Connection get(String chglibl) throws Exception {
    Connection c = get();
    if (chglibl.equals(applied)) return c;
    try (Statement stmt = c.createStatement()) {
      stmt.execute("CALL QSYS2.QCMDEXC('" + chglibl.replace("'", "''") + "')");
    }
    if (applied != null) logger.info("IBM i read-only connection kept, library list changed in place: {}", chglibl);
    applied = chglibl;
    return c;
  }

  @Override
  public synchronized void close() {
    try {
      if (connection != null && !connection.isClosed()) connection.close();
    } catch (SQLException ignored) { /* closing anyway */ }
    if (connection != null) connections.closed(HostConnections.Purpose.READ_ONLY, "server disconnected");
    connection = null;
    applied = null;
  }
}
