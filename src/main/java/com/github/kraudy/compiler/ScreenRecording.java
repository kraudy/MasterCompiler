package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * screen record: true. One JSON line per event, in tn5250-rs's recording format (its replay and codec tools read it):
 * a "meta" line, then "in" (each record the IBM i sent, hex, exactly as it came without the telnet framing) and "out"
 * (each key: aid, cursor, the fields it sent with hidden values as ***), each with t_ms since the start. The bytes
 * MasterCompiler sends are never written: a Read Input Fields answer carries every field, a hidden one too. The "in"
 * records are exact, so a nondisplay field the host itself filled is in them, as on the wire.
 */
final class ScreenRecording implements Screen5250.Recorder {
  private static final Logger logger = LoggerFactory.getLogger(ScreenRecording.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  final File file;
  private final OutputStream out;
  private final long start = System.currentTimeMillis();

  private ScreenRecording(File file, OutputStream out) {
    this.file = file;
    this.out = out;
  }

  /* A new file in <project>/.mc/results, private to the user where the file system allows it */
  static ScreenRecording start(File project, String host, int port, boolean tls, int ccsid, boolean wide) throws IOException {
    File folder = FileDownload.resultsFolder(project);
    File file = new File(folder, "screen-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl");
    try {
      Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException windows) {
      Files.createFile(file.toPath());
    }
    ScreenRecording recording = new ScreenRecording(file, new FileOutputStream(file, true));
    ObjectNode meta = mapper.createObjectNode();
    meta.put("v", 1);
    meta.put("kind", "meta");
    meta.put("host", host);
    meta.put("port", port);
    meta.put("tls", tls);
    meta.put("device", "");
    meta.put("wide", wide);
    meta.put("ccsid", ccsid);
    meta.put("started", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    meta.put("app", "MasterCompiler " + MasterCompiler.version());
    recording.line(meta);
    return recording;
  }

  @Override
  public synchronized void incoming(byte[] record) {
    ObjectNode in = mapper.createObjectNode();
    in.put("kind", "in");
    in.put("hex", SqlTools.hex(record).toLowerCase(Locale.ROOT));
    line(in);
  }

  @Override
  public synchronized void outgoing(String key, int row, int col, List<Object[]> fields) {
    ObjectNode o = mapper.createObjectNode();
    o.put("kind", "out");
    o.put("aid", key);
    o.putArray("cursor").add(row).add(col);
    ArrayNode sent = o.putArray("fields");
    for (Object[] f : fields) sent.addObject().put("row", (Integer) f[0]).put("col", (Integer) f[1]).put("value", (String) f[2]);
    line(o);
  }

  synchronized void close() {
    try {
      out.close();
    } catch (IOException ignored) { /* the lines are flushed one by one */ }
  }

  private void line(ObjectNode event) {
    event.put("t_ms", System.currentTimeMillis() - start);
    try {
      out.write((mapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      logger.warn("Could not write the screen recording {}: {}", file, e.getMessage());
    }
  }
}
