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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.as400.access.CharConverter;

/* Item 35, offline: a CL command longer than a menu's command line goes on the Command Entry display (CALL QCMD) */
class ScreenCommandTest {

  private static final int IAC = 255, EOR = 239;

  /* A screen with a footer/label line, "===>" on a row and the command line after it (Read MDT Fields) */
  static byte[] commandScreen(int row, int length, String legend) throws Exception {
    CharConverter ebcdic = new CharConverter(37);
    byte[] text = ebcdic.stringToByteArray(legend);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] { 0x04, 0x40, 0x04, 0x11, 0x00, 0x00 });
    out.write(new byte[] { 0x11, (byte) row, 2, 0x7E, 0x7E, 0x7E, 0x6E });  // ===>
    out.write(new byte[] { 0x11, (byte) row, 6, 0x1D, 0x40, 0x00, 0x20, (byte) (length >> 8), (byte) length });
    out.write(new byte[] { 0x11, 22, 2 });
    out.write(text);
    out.write(new byte[] { 0x04, 0x52, 0x00, 0x00 });
    return ScreenRawTest.record(unsigned(out.toByteArray()));
  }

  private static int[] unsigned(byte[] bytes) {
    int[] ints = new int[bytes.length];
    for (int i = 0; i < bytes.length; i++) ints[i] = bytes[i] & 0xFF;
    return ints;
  }

  static final byte[] MENU;
  static final byte[] COMMAND_ENTRY;
  static final byte[] QSH;
  static {
    try {
      MENU = commandScreen(20, 153, "F3=Exit   F4=Prompt");
      COMMAND_ENTRY = commandScreen(18, 313, "F3=Exit   F4=Prompt");
      QSH = commandScreen(20, 153, "F3=Exit   F4=End of File");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /* Plays the IBM i: an initial screen, then Command Entry after every key; keeps each key's record */
  static final class Host implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final List<byte[]> keys = Collections.synchronizedList(new ArrayList<byte[]>());
    final byte[] first;
    final Thread thread = new Thread(this::serve);

    Host() throws Exception {
      this(MENU);
    }

    Host(byte[] first) throws Exception {
      this.first = first;
      thread.setDaemon(true);
      thread.start();
    }

    private void serve() {
      try (Socket s = socket.accept()) {
        OutputStream out = s.getOutputStream();
        out.write(ScreenRawTest.framed(first));
        out.flush();
        InputStream in = s.getInputStream();
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        int b, last = 0;
        while ((b = in.read()) >= 0) {
          if (last == IAC && b == EOR) {
            keys.add(key.toByteArray());
            key.reset();
            out.write(ScreenRawTest.framed(COMMAND_ENTRY));
            out.flush();
          } else if (b != IAC || last == IAC) {
            key.write(b);
          }
          last = (last == IAC && b == IAC) ? 0 : b;
        }
      } catch (Exception ignored) {
        // the test closed the session
      }
    }

    int port() {
      return socket.getLocalPort();
    }

    @Override
    public void close() throws Exception {
      socket.close();
    }
  }

  private static boolean contains(byte[] record, String text) throws Exception {
    byte[] want = new CharConverter(37).stringToByteArray(text);
    outer:
    for (int i = 0; i + want.length <= record.length; i++) {
      for (int j = 0; j < want.length; j++) if (record[i + j] != want[j]) continue outer;
      return true;
    }
    return false;
  }

  private static String command(int length) {
    StringBuilder s = new StringBuilder("QSH CMD('echo ");
    while (s.length() < length - 2) s.append('x');
    return s.append("')").toString();
  }

  @Test
  void aCommandThatFitsIsTypedOnTheMenu() throws Exception {
    try (Host host = new Host(); Screen5250 screen = Screen5250.open("localhost", host.port(), false, 37, null, 10)) {
      assertNull(ScreenTools.typeCommand(screen, "DSPLIBL", Screen5250.AID_ENTER, "Enter", 10));
      assertTrue(screen.waitForInput(10));
      assertEquals(1, host.keys.size(), "one key: the command itself");
      assertTrue(contains(host.keys.get(0), "DSPLIBL"));
    }
  }

  @Test
  void aLongerCommandGoesOnCommandEntry() throws Exception {
    String longer = command(200);
    try (Host host = new Host(); Screen5250 screen = Screen5250.open("localhost", host.port(), false, 37, null, 10)) {
      String how = ScreenTools.typeCommand(screen, longer, Screen5250.AID_ENTER, "Enter", 10);
      assertNotNull(how);
      assertTrue(how.contains("153") && how.contains("313"), how);
      assertTrue(screen.waitForInput(10));
      assertEquals(2, host.keys.size(), "CALL QCMD, then the command");
      assertTrue(contains(host.keys.get(0), ScreenTools.COMMAND_ENTRY));
      assertFalse(contains(host.keys.get(0), longer));
      assertTrue(contains(host.keys.get(1), longer));
    }
  }

  @Test
  void qshAndSqlScreensDoNotReceiveCallQcmd() throws Exception {
    try (Host host = new Host(QSH); Screen5250 screen = Screen5250.open("localhost", host.port(), false, 37, null, 10)) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> ScreenTools.typeCommand(screen, command(200), Screen5250.AID_ENTER, "Enter", 10));
      assertTrue(e.getMessage().contains("CL command line"), e.getMessage());
      assertTrue(host.keys.isEmpty(), "CALL QCMD was never sent to QSH");
    }
    assertFalse(ScreenTools.isClCommandScreen(Arrays.asList("Enter SQL statements", "===>", "F4=Prompt")));
    assertFalse(ScreenTools.isClCommandScreen(Arrays.asList("===>", "F3=Exit F4=End of File")));
    assertTrue(ScreenTools.isClCommandScreen(Arrays.asList("Selection or command", "===>", "F24=More keys")));
    assertTrue(ScreenTools.isClCommandScreen(Arrays.asList("Parameters or command", "===>")));
  }

  @Test
  void tooLongForCommandEntryTypesNothing() throws Exception {
    try (Host host = new Host(); Screen5250 screen = Screen5250.open("localhost", host.port(), false, 37, null, 10)) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> ScreenTools.typeCommand(screen, command(ScreenTools.COMMAND_ENTRY_ROOM + 1), Screen5250.AID_ENTER, "Enter", 10));
      assertTrue(e.getMessage().contains("run_command"), e.getMessage());
      assertTrue(screen.waitingForInput(), "no key went: the menu still waits");
      assertEquals("", screen.value(screen.inputFields().get(0)).trim(), "nothing typed");
      assertTrue(host.keys.isEmpty());
    }
  }

  @Test
  void commandGoesWithoutThisScreensFields() {
    assertTrue(ScreenTools.WITH_COMMAND.contains("key"));
    assertFalse(ScreenTools.WITH_COMMAND.contains("fields"));
    assertFalse(ScreenTools.WITH_COMMAND.contains("cursor"));
    assertFalse(ScreenTools.WITH_COMMAND.contains("ask"));
  }

  @Test
  void protectedLibsReadsBaseOverlayWithoutSources(@TempDir Path dir) throws Exception {
    Path base = dir.resolve("mc-base.yaml");
    Files.write(base, "protectedLibs: [PRODLIB]\nbefore:\n  - Cmd: ADDLIBLE LIB(DEVLIB)\n".getBytes(StandardCharsets.UTF_8));
    assertEquals(Collections.singletonList("PRODLIB"), ProtectedLibs.read(ProtectedLibs.file(dir.toFile(), null)));
    BuildSpec overlay = Utilities.deserializeOverlay(base.toString());
    assertEquals(Collections.singletonList("PRODLIB"), overlay.protectedLibs);
    assertEquals(1, overlay.before.size());

    Path broken = dir.resolve("broken-base.yaml");
    Files.write(broken, "protectedLibs: [PRODLIB]\nbefore:\n  - NOT_A_COMMAND\n".getBytes(StandardCharsets.UTF_8));
    assertThrows(RuntimeException.class, () -> Utilities.deserializeOverlay(broken.toString()));
  }

  @Test
  void longLibraryListUsesCommandEntryAndReturnsWithF3() throws Exception {
    StringBuilder libs = new StringBuilder("CHGLIBL LIBL(");
    while (libs.length() < 170) libs.append("MYLIB1 ");
    String chglibl = libs.append(") CURLIB(MYLIB1)").toString();
    try (Host host = new Host(); Screen5250 screen = Screen5250.open("localhost", host.port(), false, 37, null, 10)) {
      String answer = ScreenTools.applyLibraryList(screen, chglibl, 10);
      assertTrue(answer.startsWith(chglibl), answer);
      assertEquals(3, host.keys.size(), "CALL QCMD, CHGLIBL, then F3 back");
      assertTrue(contains(host.keys.get(0), ScreenTools.COMMAND_ENTRY));
      assertTrue(contains(host.keys.get(1), chglibl));
      assertEquals(0x33, host.keys.get(2)[12] & 0xFF, "F3 AID after Command Entry");
    }
  }
}
