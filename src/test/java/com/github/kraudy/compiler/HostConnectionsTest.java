package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/* Offline (no IBM i): connections are counted, a library-list change keeps the read connection, single-connection
   routes everything to the build connection, and its queries run in a read-only transaction */
class HostConnectionsTest {

  /* A JDBC object that does nothing: every call is recorded, interfaces answer with more of the same, result sets are empty */
  static final class FakeJdbc {
    final List<String> calls = new ArrayList<String>();
    boolean closed;

    Connection connection() {
      return (Connection) fake(Connection.class);
    }

    private Object fake(Class<?> type) {
      return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
        String name = method.getName();
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return proxy == args[0];
        if (name.equals("toString")) return "fake " + type.getSimpleName();
        if (type == Connection.class && name.equals("close")) closed = true;
        if (type == Connection.class && name.equals("isClosed")) return closed;
        if (type == Connection.class && name.equals("getAutoCommit")) return true;
        if (type == Connection.class && name.equals("getTransactionIsolation")) return Connection.TRANSACTION_NONE;
        calls.add(name + (args != null && args.length > 0 && args[0] != null ? " " + args[0] : ""));
        Class<?> r = method.getReturnType();
        if (r.isInterface()) return fake(r);
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
      });
    }

    long count(String prefix) {
      return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }
  }

  @Test
  void libraryListChangeKeepsTheReadConnection() throws Exception {
    HostConnections connections = new HostConnections(HostConnections.Mode.SHARED);
    FakeJdbc jdbc = new FakeJdbc();
    int[] opens = { 0 };
    ReadConnection read = new ReadConnection(() -> { opens[0]++; return jdbc.connection(); }, connections);

    Connection first = read.get("CHGLIBL LIBL(QGPL) CURLIB(LIB1)");
    Connection same = read.get("CHGLIBL LIBL(QGPL) CURLIB(LIB1)");
    Connection changed = read.get("CHGLIBL LIBL(QGPL QIWS) CURLIB(LIB1)");

    assertEquals(1, opens[0], "one connection for three calls and a library-list change");
    assertSame(first, same);
    assertSame(first, changed);
    assertFalse(jdbc.closed, "a library-list change must not close it");
    assertEquals(2, jdbc.count("execute CALL QSYS2.QCMDEXC('CHGLIBL"), "CHGLIBL once per distinct list: " + jdbc.calls);
    assertTrue(jdbc.calls.contains("execute CALL QSYS2.QCMDEXC('CHGLIBL LIBL(QGPL QIWS) CURLIB(LIB1)')"), jdbc.calls.toString());
    assertEquals(1, connections.opens(HostConnections.Purpose.READ_ONLY));
  }

  @Test
  void aClosedReadConnectionIsReopenedAndItsListAppliedAgain() throws Exception {
    HostConnections connections = new HostConnections(HostConnections.Mode.SHARED);
    List<FakeJdbc> opened = new ArrayList<FakeJdbc>();
    ReadConnection read = new ReadConnection(() -> { FakeJdbc j = new FakeJdbc(); opened.add(j); return j.connection(); }, connections);

    read.get("CHGLIBL LIBL(QGPL) CURLIB(LIB1)");
    opened.get(0).closed = true;  // dropped
    read.get("CHGLIBL LIBL(QGPL) CURLIB(LIB1)");

    assertEquals(2, opened.size());
    assertEquals(1, opened.get(1).count("execute CALL QSYS2.QCMDEXC('CHGLIBL"), "the new job gets the list too");
    JsonNode entry = connections.toJson().path("connections").get(0);
    assertEquals(2, entry.path("opens").asInt());
    assertEquals(1, entry.path("reopens").asInt());
    assertEquals("the connection was closed", entry.path("reason").asText());
  }

  /* The scripted sequence of a session: status reports exactly the opens made */
  @Test
  void statusCountsMatchTheOpensOfAScriptedSequence() throws Exception {
    HostConnections connections = new HostConnections(HostConnections.Mode.SHARED);
    int[] opens = { 0 };
    connections.open(HostConnections.Purpose.BUILD, "first call", () -> { opens[0]++; return new FakeJdbc().connection(); });
    ReadConnection read = new ReadConnection(() -> { opens[0]++; return new FakeJdbc().connection(); }, connections);
    JobWatch watch = JobWatch.shared(null, connections, read::get);

    read.get("CHGLIBL LIBL(QGPL) CURLIB(LIB1)");          // sql
    watch.inspect("123456/QUSER/QZDASOINIT");             // whats_running during a build: the monitor reads through it
    watch.joblogTail("123456/QUSER/QZDASOINIT", 5);
    read.get("CHGLIBL LIBL(QGPL QIWS) CURLIB(LIB1)");     // run_command ADDLIBLE, then sql
    read.get("CHGLIBL LIBL(QGPL QIWS) CURLIB(LIB1)");     // checkRows before
    read.get("CHGLIBL LIBL(QGPL QIWS) CURLIB(LIB1)");     // checkRows after

    JsonNode status = connections.toJson();
    assertEquals(opens[0], status.path("totalOpens").asInt(), status.toString());
    assertEquals(2, status.path("totalOpens").asInt(), "build and read-only, no monitor of its own: " + status);
    assertEquals(2, status.path("open").asInt());
    for (JsonNode c : status.path("connections")) assertEquals(0, c.path("reopens").asInt(), status.toString());
    assertEquals("read-only", status.path("sharing").path("monitor").asText());
  }

  @Test
  void singleConnectionRoutesEveryReadToTheBuildConnection() {
    HostConnections single = new HostConnections(HostConnections.Mode.SINGLE);
    for (HostConnections.Purpose p : new HostConnections.Purpose[] {
        HostConnections.Purpose.BUILD, HostConnections.Purpose.READ_ONLY, HostConnections.Purpose.MONITOR, HostConnections.Purpose.COPY }) {
      assertTrue(single.onBuild(p), p + " must run on the build connection");
    }
    assertTrue(HostConnections.Mode.SINGLE.guardsQueries, "queries on a connection that can write need the guard");
    assertFalse(HostConnections.Mode.SHARED.guardsQueries);
    assertFalse(HostConnections.Mode.READ_SERVER.guardsQueries, "a read-only server's connection is read only already");

    HostConnections shared = new HostConnections(HostConnections.Mode.SHARED);
    assertFalse(shared.onBuild(HostConnections.Purpose.READ_ONLY));
    assertEquals(HostConnections.Purpose.READ_ONLY, shared.route(HostConnections.Purpose.MONITOR));
  }

  @Test
  void singleConnectionMonitorOpensNothingToLook() {
    HostConnections connections = new HostConnections(HostConnections.Mode.SINGLE);
    JobWatch watch = JobWatch.stopOnly(null, connections);  // null system: opening a connection would fail here
    assertEquals(JobWatch.NOT_LOOKED, watch.inspect("123456/QUSER/QZDASOINIT").path("note").asText());
    assertEquals(JobWatch.NOT_LOOKED, watch.joblogTail("123456/QUSER/QZDASOINIT", 5).get(0).asText());
    assertEquals(0, connections.totalOpens());
  }

  @Test
  void guardedQueriesRunInAReadOnlyTransaction() throws Exception {
    FakeJdbc jdbc = new FakeJdbc();
    new SqlTools().guarded(true).query(jdbc.connection(), "SELECT * FROM MCDITEM", 10, 10);

    int readOnly = jdbc.calls.indexOf("execute " + SqlTools.READ_ONLY_TRANSACTION);
    int query = jdbc.calls.indexOf("executeQuery SELECT * FROM MCDITEM");
    int rollback = jdbc.calls.indexOf("rollback");
    assertTrue(jdbc.calls.contains("setAutoCommit false"), jdbc.calls.toString());
    assertTrue(jdbc.calls.contains("setTransactionIsolation " + Connection.TRANSACTION_READ_COMMITTED),
        "READ ONLY needs commitment control (ignored under NO COMMIT): " + jdbc.calls);
    assertTrue(readOnly >= 0 && readOnly < query && query < rollback, "read-only transaction, query, rollback: " + jdbc.calls);
    assertTrue(jdbc.calls.indexOf("setAutoCommit true") > rollback, "the connection's own settings come back: " + jdbc.calls);
    assertTrue(jdbc.calls.indexOf("setTransactionIsolation " + Connection.TRANSACTION_NONE) > rollback, jdbc.calls.toString());
  }

  @Test
  void unguardedQueriesLeaveTheTransactionAlone() throws Exception {
    FakeJdbc jdbc = new FakeJdbc();
    new SqlTools().query(jdbc.connection(), "SELECT * FROM MCDITEM", 10, 10);
    assertEquals(0, jdbc.count("setAutoCommit") + jdbc.count("rollback") + jdbc.count("execute SET"), jdbc.calls.toString());
  }

  @Test
  void writesAreRefusedBeforeTheyReachTheBuildConnection() {
    for (String write : new String[] { "INSERT INTO MCDITEM VALUES ('X', 'Y', 1)", "DELETE FROM MCDITEM",
        "CALL QSYS2.QCMDEXC('DLTLIB X')", "SELECT * FROM FINAL TABLE (INSERT INTO MCDITEM VALUES ('X', 'Y', 1))",
        "VALUES QSYS2.QCMDEXC('DLTLIB X')" }) {
      FakeJdbc jdbc = new FakeJdbc();
      assertThrows(IllegalArgumentException.class,
          () -> new SqlTools().guarded(true).query(jdbc.connection(), write, 10, 10), write);
      assertTrue(jdbc.calls.stream().noneMatch(c -> c.startsWith("execute") && c.contains(write)), write + " ran: " + jdbc.calls);
    }
  }

  @Test
  void libraryListCommandShape() {
    assertEquals("CHGLIBL LIBL(*NONE) CURLIB(*CRTDFT)", chglibl());
  }

  private static String chglibl() {
    try {
      return SqlTools.libraryListCommand(new FakeJdbc().connection());  // no rows: no current library, no user libraries
    } catch (java.sql.SQLException e) {
      throw new AssertionError(e);
    }
  }
}
