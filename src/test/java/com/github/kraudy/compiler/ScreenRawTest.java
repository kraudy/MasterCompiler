package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/* Item 27, offline: a local socket plays the IBM i and sends one screen with an output and an input field */
class ScreenRawTest {

  @TempDir
  Path tempDir;

  private static final int IAC = 255, EOR = 239;

  /* GDS header (put/get) + data: what the IBM i sends before the telnet framing */
  static byte[] record(int... data) {
    byte[] r = new byte[10 + data.length];
    r[0] = (byte) (r.length >> 8);
    r[1] = (byte) r.length;
    r[2] = 0x12; r[3] = (byte) 0xA0; r[6] = 0x04; r[9] = 0x03;
    for (int i = 0; i < data.length; i++) r[10 + i] = (byte) data[i];
    return r;
  }

  /* Clear Unit; Write To Display: "HELLO" in an output field (white), a 10-position hidden input field (nondisplay);
     Read MDT Fields */
  static final byte[] SCREEN = record(
      0x04, 0x40,
      0x04, 0x11, 0x00, 0x00,
      0x11, 1, 1, 0x1D, 0x22, 0x00, 0x05, 0xC8, 0xC5, 0xD3, 0xD3, 0xD6,
      0x11, 2, 1, 0x1D, 0x40, 0x00, 0x27, 0x00, 0x0A,
      0x04, 0x52, 0x00, 0x00);

  static byte[] framed(byte[] r) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte b : r) {
      out.write(b);
      if ((b & 0xFF) == IAC) out.write(IAC);
    }
    out.write(IAC);
    out.write(EOR);
    return out.toByteArray();
  }

  @Test
  void screenKeepsOutputFieldsHostWritesAndRecords() throws Exception {
    try (ServerSocket host = new ServerSocket(0)) {
      CompletableFuture<byte[]> keySent = CompletableFuture.supplyAsync(() -> {
        try (Socket s = host.accept()) {
          OutputStream out = s.getOutputStream();
          out.write(framed(SCREEN));
          out.flush();
          InputStream in = s.getInputStream();
          ByteArrayOutputStream got = new ByteArrayOutputStream();
          int b, last = 0;
          while ((b = in.read()) >= 0) {  // the key's record, up to IAC EOR
            got.write(b);
            if (last == IAC && b == EOR) break;
            last = b;
          }
          out.write(framed(SCREEN));  // the answer: the same screen again
          out.flush();
          Thread.sleep(500);
          return got.toByteArray();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });

      try (Screen5250 screen = Screen5250.open("localhost", host.getLocalPort(), false, 37, null, 10)) {
        List<Screen5250.Field> all = screen.allFields();
        assertEquals(2, all.size(), "the output field too");
        assertFalse(all.get(0).input);
        assertEquals(0x22, all.get(0).attr);
        assertEquals("HELLO", screen.value(all.get(0)));
        assertTrue(all.get(1).input);
        assertTrue(all.get(1).hidden());
        assertEquals(1, screen.inputFields().size(), "typing still sees input fields only");

        List<byte[]> writes = screen.hostWrites();
        assertEquals(1, writes.size());
        assertArrayEquals(SCREEN, writes.get(0), "the record as it came, without the telnet framing");

        ScreenRecording recording = ScreenRecording.start(tempDir.toFile(), "localhost", host.getLocalPort(), false, 37, false);
        screen.record(recording);
        screen.fill(screen.inputFields().get(0), "SECRET1");
        screen.press(Screen5250.AID_ENTER, "Enter");
        assertTrue(screen.waitForInput(10));  // before the host closes the connection
        keySent.get(10, TimeUnit.SECONDS);
        recording.close();

        List<String> lines = Files.readAllLines(recording.file.toPath(), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("meta", mapper.readTree(lines.get(0)).path("kind").asText());
        JsonNode out = mapper.readTree(lines.get(1));
        assertEquals("out", out.path("kind").asText());
        assertEquals("Enter", out.path("aid").asText());
        assertEquals("***", out.path("fields").path(0).path("value").asText(), "a hidden value never reaches the file");
        assertFalse(String.join("\n", lines).contains("SECRET1"));
        JsonNode in = mapper.readTree(lines.get(2));
        assertEquals("in", in.path("kind").asText());
        assertEquals(SqlTools.hex(SCREEN).toLowerCase(), in.path("hex").asText());
        assertEquals(1, screen.hostWrites().size(), "only the records since the last key");
      }
    }
  }

  @Test
  void everyDisplayAttributeHasAMeaning() {
    for (int a = 0x20; a <= 0x3F; a++) assertNotNull(ScreenTools.DISPLAY.get(a), Integer.toHexString(a));
    assertEquals("nondisplay", ScreenTools.DISPLAY.get(0x27));
    assertEquals(8, ScreenTools.SHIFT.length);
  }
}
