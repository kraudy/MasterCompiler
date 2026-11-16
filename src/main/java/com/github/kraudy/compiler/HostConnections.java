package com.github.kraudy.compiler;

import java.sql.Connection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.ConnectionEvent;
import com.ibm.as400.access.ConnectionListener;

/*
 * Every connection MC holds to the IBM i: what it is for, its job, since when, and how often it was opened. Each new
 * host-server connection signs on again (measured on PUB400: a JDBC connection is an as-signon exchange plus a
 * database server job; a service of the AS400 object is a start-server request), and some systems refuse sign-ons
 * that come close together, so every open is counted, logged with its reason and shown by status.
 */
final class HostConnections {
  private static final Logger logger = LoggerFactory.getLogger(HostConnections.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  enum Purpose {
    BUILD, READ_ONLY, MONITOR, COPY, COMMAND, FILES;

    String label() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  /*
   * Which connection a purpose uses when not its own, and whether queries on it need SqlTools' read-only transaction
   * (the connection they share can write).
   *   SHARED (default): the job monitor reads through the read-only connection.
   *   SINGLE (--single-connection): queries, checkRows, the monitor and copy_rows on the build connection (JobWatch's
   *     stop excepted: stopOnly).
   *   READ_SERVER (--read-only, a server for another IBM i): its one connection is read only already.
   */
  enum Mode {
    SHARED(false, Purpose.MONITOR, Purpose.READ_ONLY),
    SINGLE(true, Purpose.READ_ONLY, Purpose.BUILD, Purpose.MONITOR, Purpose.BUILD, Purpose.COPY, Purpose.BUILD),
    READ_SERVER(false, Purpose.READ_ONLY, Purpose.BUILD);

    final boolean guardsQueries;
    final Map<Purpose, Purpose> routes = new EnumMap<Purpose, Purpose>(Purpose.class);

    Mode(boolean guardsQueries, Purpose... pairs) {
      this.guardsQueries = guardsQueries;
      for (int i = 0; i < pairs.length; i += 2) routes.put(pairs[i], pairs[i + 1]);
    }
  }

  /* The AS400 object's own services (not JDBC): which purpose each is, from its connection events */
  static final Map<Integer, Purpose> SERVICES = new java.util.HashMap<Integer, Purpose>();
  static {
    SERVICES.put(AS400.COMMAND, Purpose.COMMAND);
    SERVICES.put(AS400.FILE, Purpose.FILES);
  }

  static final class Entry {
    final Purpose purpose;
    boolean open;
    String job;
    String openedAt;
    int opens;
    String reason;       // why it was last opened or closed
    String nextReason;   // why the next open happens, said before it (a close MC asked for)

    Entry(Purpose purpose) {
      this.purpose = purpose;
    }
  }

  final Mode mode;
  private final Map<Purpose, Entry> entries = new EnumMap<Purpose, Entry>(Purpose.class);

  HostConnections(Mode mode) {
    this.mode = mode;
  }

  /* The connection a purpose uses */
  Purpose route(Purpose purpose) {
    Purpose to = mode.routes.get(purpose);
    return to != null ? to : purpose;
  }

  /* This purpose runs on the build connection */
  boolean onBuild(Purpose purpose) {
    return route(purpose) == Purpose.BUILD;
  }

  /* Opens a JDBC connection for a purpose and records it */
  Connection open(Purpose purpose, String reason, Callable<Connection> opener) throws Exception {
    Connection connection = opener.call();
    opened(purpose, JobWatch.jobName(connection), reason);
    return connection;
  }

  synchronized void opened(Purpose purpose, String job, String reason) {
    Entry e = entry(purpose);
    String why = e.nextReason != null ? e.nextReason : reason;
    e.nextReason = null;
    e.open = true;
    e.job = job;
    e.openedAt = java.time.LocalDateTime.now().withNano(0).toString();
    e.opens++;
    e.reason = why;
    logger.info("IBM i {} connection {}{}: {}", purpose.label(), e.opens > 1 ? "reopened" : "opened",
        job != null ? " (job " + job + ")" : "", why);
    writeTally();
  }

  /*
   * MC started by an SSH MCP server (-Dmc.opens): totalOpens in this file, rewritten at each open (a new file renamed
   * over the old: never half written), so the server on the PC still has the count after this process ends
   */
  private Path tally;

  synchronized void tallyTo(String path) {
    if (path == null || path.trim().isEmpty()) return;
    tally = Paths.get(path);
    writeTally();  // 0 now: a missing file later means unknown, not none
  }

  private void writeTally() {
    if (tally == null) return;
    try {
      Path next = tally.resolveSibling(tally.getFileName() + ".new");
      Files.write(next, String.valueOf(totalOpens()).getBytes(StandardCharsets.US_ASCII));
      Files.move(next, tally, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      logger.debug("Sign-on count not written to {}: {}", tally, e.getMessage());
    }
  }

  synchronized void closed(Purpose purpose, String reason) {
    Entry e = entry(purpose);
    if (!e.open) return;
    e.open = false;
    e.reason = reason;
    logger.info("IBM i {} connection closed: {}", purpose.label(), reason);
  }

  /* Why the next open of this purpose happens: said when MC drops it on purpose */
  synchronized void nextReason(Purpose purpose, String reason) {
    entry(purpose).nextReason = reason;
  }

  synchronized int opens(Purpose purpose) {
    Entry e = entries.get(purpose);
    return e == null ? 0 : e.opens;
  }

  synchronized int totalOpens() {
    int total = 0;
    for (Entry e : entries.values()) total += e.opens;
    return total;
  }

  private Entry entry(Purpose purpose) {
    Entry e = entries.get(purpose);
    if (e == null) {
      e = new Entry(purpose);
      entries.put(purpose, e);
    }
    return e;
  }

  /* The AS400 object's services: opened and closed as jt400 reports them */
  ConnectionListener listener() {
    return new ConnectionListener() {
      @Override public void connected(ConnectionEvent event) {
        Purpose purpose = SERVICES.get(event.getService());
        if (purpose != null) opened(purpose, serviceJob(event), "first " + purpose.label() + " request");
      }

      @Override public void disconnected(ConnectionEvent event) {
        Purpose purpose = SERVICES.get(event.getService());
        if (purpose != null) closed(purpose, "service disconnected");
      }
    };
  }

  /* The server job of a service connection, as jt400 got it at the start (no request to the IBM i); null if unknown */
  private static String serviceJob(ConnectionEvent event) {
    try {
      com.ibm.as400.access.Job[] jobs = ((AS400) event.getSource()).getJobs(event.getService());
      if (jobs.length == 0) return null;
      com.ibm.as400.access.Job job = jobs[jobs.length - 1];
      return job.getNumber() + "/" + job.getUser() + "/" + job.getName();
    } catch (Exception e) {
      return null;
    }
  }

  /* For status: each connection, open or not, and the opens since the server started */
  synchronized ObjectNode toJson() {
    ObjectNode result = mapper.createObjectNode();
    result.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    int open = 0;
    ArrayNode list = result.putArray("connections");
    for (Entry e : entries.values()) {
      if (e.open) open++;
      ObjectNode node = list.addObject();
      node.put("purpose", e.purpose.label());
      node.put("open", e.open);
      if (e.job != null) node.put("job", e.job);
      node.put("openedAt", e.openedAt);
      node.put("opens", e.opens);
      node.put("reopens", Math.max(0, e.opens - 1));
      node.put("reason", e.reason);
    }
    for (Map.Entry<Purpose, Purpose> shared : mode.routes.entrySet()) {
      result.with("sharing").put(shared.getKey().label(), shared.getValue().label());
    }
    result.put("open", open);
    result.put("totalOpens", totalOpens());
    return result;
  }

  /* One line for the command line's end (--verbose) */
  synchronized String summary() {
    java.util.StringJoiner each = new java.util.StringJoiner(", ", " (", ")");
    each.setEmptyValue("");
    for (Entry e : entries.values()) each.add(e.purpose.label() + " " + e.opens);
    return "IBM i connections opened: " + totalOpens() + each;
  }
}
