package com.github.kraudy.compiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.CharConverter;

/*
 * A 5250 display session over telnet (RFC 1205 / 2877, TLS on port 992), as a 3179-2 display (24 x 80): enough
 * of the 5250 data stream to show screens, fill input fields and press keys, so an agent can run and check
 * display file programs. The screen is kept as the IBM i wrote it (EBCDIC bytes, attribute bytes in place);
 * non-display fields (passwords) are never shown or typed into by the tools.
 */
final class Screen5250 implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(Screen5250.class);

  /* telnet */
  private static final int IAC = 255, DONT = 254, DO = 253, WONT = 252, WILL = 251, SB = 250, SE = 240, EOR = 239;
  private static final int BINARY = 0, TIMING_MARK = 6, TERMINAL_TYPE = 24, END_OF_RECORD = 25;
  private static final String TERMINAL = "IBM-3179-2";

  /* 5250 header opcodes and commands */
  private static final int OP_PUT_GET = 3, OP_SAVE = 4;
  private static final int ESC = 0x04;

  /* AID codes */
  static final int AID_ENTER = 0xF1, AID_HELP = 0xF3, AID_ROLL_DOWN = 0xF4, AID_ROLL_UP = 0xF5, AID_PRINT = 0xF6,
      AID_CLEAR = 0xBD, AID_QUERY = 0x88;

  /* An input field of the format table */
  static final class Field {
    int attrPos;  // the attribute byte before the field
    int start;
    int length;
    int ffw1;
    int ffw2;
    int attr;
    boolean mdt;

    boolean bypass()  { return (ffw1 & 0x20) != 0; }
    boolean hidden()  { return (attr & 0x07) == 0x07; }
    int shift()       { return ffw1 & 0x07; }  // 3 numeric only, 5 digits only, 7 signed numeric
    boolean numeric() { return shift() == 3 || shift() == 5 || shift() == 7; }
    boolean signed()  { return shift() == 7; }
    int adjust()      { return ffw2 & 0x07; }  // 5 right adjust zero fill, 6 blank fill, 7 mandatory fill
  }

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final String[] toChar = new String[256];
  private final java.util.Map<Character, Byte> toByte = new java.util.HashMap<Character, Byte>();
  private final Thread reader;
  private final Runnable onScreen;  // after each screen that waits for input: the watch file

  private int rows = 24;
  private int cols = 80;
  private byte[] buffer = new byte[rows * cols];
  private final TreeMap<Integer, Field> fields = new TreeMap<Integer, Field>();  // by start
  private int address;
  private int cursor;
  private int pendingRead;      // the read command the IBM i waits on (0: none, it is busy)
  private boolean inputError;   // Write Error Code: the error line shows until the next key
  private byte[] errorLineSaved;
  private volatile String closedBy;
  private long lastChange;

  private Screen5250(Socket socket, int ccsid, Runnable onScreen) throws IOException {
    this.socket = socket;
    this.in = socket.getInputStream();
    this.out = socket.getOutputStream();
    this.onScreen = onScreen;
    CharConverter converter = new CharConverter(ccsid);
    for (int b = 0x40; b < 0xFF; b++) {
      String c = converter.byteArrayToString(new byte[] { (byte) b });
      toChar[b] = c.length() == 1 && !Character.isISOControl(c.charAt(0)) ? c : " ";
      if (c.length() == 1 && !toByte.containsKey(c.charAt(0))) toByte.put(c.charAt(0), (byte) b);
    }
    reader = new Thread(this::readLoop, "mc-5250");
    reader.setDaemon(true);
  }

  /* Connect (TLS unless told otherwise) and wait for the first screen: usually the sign-on display */
  static Screen5250 open(String host, int port, boolean tls, int ccsid, Runnable onScreen, int waitSeconds)
      throws Exception {
    Socket plain = new Socket();
    plain.connect(new InetSocketAddress(host, port), 15_000);
    Socket socket = plain;
    if (tls) {
      SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
      javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");  // the certificate must name the host
      ssl.setSSLParameters(params);
      ssl.startHandshake();
      socket = ssl;
    }
    Screen5250 screen = new Screen5250(socket, ccsid, onScreen);
    screen.reader.start();
    if (!screen.waitForInput(waitSeconds)) {
      String why = screen.closedBy;
      screen.close();
      throw new IOException(why != null ? why : "No screen from " + host + ":" + port + " in " + waitSeconds + " s");
    }
    return screen;
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (IOException ignored) {}
  }

  synchronized boolean closed() {
    return closedBy != null;
  }

  synchronized String closedBy() {
    return closedBy;
  }

  /* ---- what the tools see ---- */

  synchronized int rows() { return rows; }
  synchronized int cols() { return cols; }
  synchronized int cursor() { return cursor; }
  synchronized boolean waitingForInput() { return pendingRead != 0; }
  synchronized boolean inputError() { return inputError; }

  /* The screen as text: attribute bytes and non-display fields are blanks */
  synchronized List<String> lines() {
    List<String> lines = new ArrayList<String>();
    boolean hidden = false;
    for (int r = 0; r < rows; r++) {
      StringBuilder line = new StringBuilder(cols);
      for (int c = 0; c < cols; c++) {
        int b = buffer[r * cols + c] & 0xFF;
        if (b >= 0x20 && b <= 0x3F) {
          hidden = (b & 0x07) == 0x07;
          line.append(' ');
        } else if (hidden || b < 0x40 || b == 0xFF) {
          line.append(' ');
        } else {
          line.append(toChar[b]);
        }
      }
      lines.add(line.toString());
    }
    return lines;
  }

  synchronized List<Field> inputFields() {
    List<Field> list = new ArrayList<Field>();
    for (Field f : fields.values()) if (!f.bypass()) list.add(f);
    return list;
  }

  synchronized String value(Field f) {
    StringBuilder s = new StringBuilder();
    for (int i = f.start; i < f.start + f.length && i < buffer.length; i++) {
      int b = buffer[i] & 0xFF;
      s.append(b < 0x40 || b == 0xFF ? " " : toChar[b]);
    }
    return s.toString();
  }

  /* ---- typing ---- */

  /* Field exit with 'text': the rest nulled, numeric and right-adjust fields right-aligned; sets MDT */
  synchronized void fill(Field f, String text) {
    if (f.bypass()) throw new IllegalArgumentException("That field is protected (output only)");
    int room = f.signed() ? f.length - 1 : f.length;
    boolean negative = false;
    if (f.signed() && text.trim().startsWith("-")) {
      negative = true;
      text = text.trim().substring(1);
    }
    if (f.numeric() || f.adjust() >= 5) text = text.trim();
    if (text.length() > room) {
      throw new IllegalArgumentException("'" + text + "' is longer than the field (" + room + " positions)");
    }
    byte[] bytes = encode(text);
    if (f.numeric()) {
      for (byte b : bytes) {
        int v = b & 0xFF;
        if (!(v >= 0xF0 && v <= 0xF9) && v != 0x40 && v != 0x4B && v != 0x6B && v != 0x60 && v != 0x4E) {
          throw new IllegalArgumentException("The field takes digits only, not '" + text + "'");
        }
      }
    }
    byte[] content = new byte[f.length];
    boolean right = f.numeric() || f.adjust() >= 5;
    byte pad = f.adjust() == 5 ? (byte) 0xF0 : f.adjust() == 6 ? (byte) 0x40 : (byte) 0x00;
    if (right) {
      java.util.Arrays.fill(content, 0, room, f.numeric() && pad == 0 ? (byte) 0x40 : pad);
      System.arraycopy(bytes, 0, content, room - bytes.length, bytes.length);
    } else {
      System.arraycopy(bytes, 0, content, 0, bytes.length);
    }
    if (f.signed()) content[f.length - 1] = negative ? (byte) 0x60 : (byte) 0x40;
    System.arraycopy(content, 0, buffer, f.start, f.length);
    f.mdt = true;
  }

  synchronized void moveCursor(int row, int col) {
    if (row < 1 || row > rows || col < 1 || col > cols) {
      throw new IllegalArgumentException("Cursor " + row + "," + col + " is outside the " + rows + "x" + cols + " screen");
    }
    cursor = (row - 1) * cols + (col - 1);
  }

  /* Reset: clears an input error so keys are taken again */
  synchronized void reset() {
    if (inputError && errorLineSaved != null) {
      System.arraycopy(errorLineSaved, 0, buffer, (rows - 1) * cols, cols);
    }
    inputError = false;
    errorLineSaved = null;
  }

  /* Press an AID key: the fields go to the IBM i as the pending read asked for */
  void press(int aid) throws IOException {
    byte[] record;
    synchronized (this) {
      if (closedBy != null) throw new IOException("The session ended: " + closedBy);
      reset();
      if (pendingRead == 0) {
        throw new IllegalStateException("The IBM i is still working on the previous key (keyboard locked): call screen to wait for it");
      }
      ByteArrayOutputStream data = new ByteArrayOutputStream();
      data.write(cursor / cols + 1);
      data.write(cursor % cols + 1);
      data.write(aid);
      boolean withData = aid != AID_HELP && aid != AID_PRINT && aid != AID_CLEAR;
      if (withData) {
        boolean all = pendingRead == 0x42;  // Read Input Fields: every field, else the changed ones with their address
        for (Field f : fields.values()) {
          if (f.bypass() && !all) continue;
          if (!all && !f.mdt) continue;
          if (!all) {
            data.write(0x11);
            data.write(f.start / cols + 1);
            data.write(f.start % cols + 1);
          }
          byte[] content = fieldData(f);
          data.write(content, 0, content.length);
        }
      }
      pendingRead = 0;
      lastChange = System.currentTimeMillis();
      record = gds(0, OP_PUT_GET, data.toByteArray());
    }
    send(record);
  }

  /* Attention (the ATTN key): a header flag, no data */
  void attention() throws IOException {
    synchronized (this) {
      pendingRead = 0;
    }
    send(gds(0x40, 0, new byte[0]));
  }

  /* Wait until the IBM i asks for input again (and nothing more arrives for a moment); false on timeout */
  synchronized boolean waitForInput(int seconds) {
    long end = System.currentTimeMillis() + seconds * 1000L;
    while (closedBy == null) {
      long now = System.currentTimeMillis();
      if (pendingRead != 0 && now - lastChange >= 250) return true;
      if (now >= end) return false;
      try {
        wait(Math.min(end - now, 100));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /* What a field sends: signed numeric without its sign position, the last digit's zone D when negative */
  private byte[] fieldData(Field f) {
    byte[] content = java.util.Arrays.copyOfRange(buffer, f.start, f.start + f.length);
    for (int i = 0; i < content.length; i++) if ((content[i] & 0xFF) < 0x40) content[i] = 0x40;
    if (!f.signed()) return content;
    byte[] digits = java.util.Arrays.copyOf(content, f.length - 1);
    if ((content[f.length - 1] & 0xFF) == 0x60 && digits.length > 0) {
      int last = digits[digits.length - 1] & 0xFF;
      if (last >= 0xF0 && last <= 0xF9) digits[digits.length - 1] = (byte) (0xD0 | (last & 0x0F));
    }
    for (int i = 0; i < digits.length; i++) if (digits[i] == 0x40) digits[i] = (byte) 0xF0;
    return digits;
  }

  private byte[] encode(String text) {
    byte[] bytes = new byte[text.length()];
    for (int i = 0; i < text.length(); i++) {
      Byte b = toByte.get(text.charAt(i));
      if (b == null) throw new IllegalArgumentException("'" + text.charAt(i) + "' has no EBCDIC code in this session's CCSID");
      bytes[i] = b;
    }
    return bytes;
  }

  /* ---- reading the IBM i ---- */

  private void readLoop() {
    ByteArrayOutputStream record = new ByteArrayOutputStream();
    String why = "the IBM i closed the connection";
    try {
      int b;
      while ((b = in.read()) >= 0) {
        if (b != IAC) {
          record.write(b);
          continue;
        }
        int cmd = in.read();
        if (cmd == IAC) {
          record.write(IAC);
        } else if (cmd == EOR) {
          byte[] r = record.toByteArray();
          record.reset();
          try {
            processRecord(r);
          } catch (Exception e) {
            logger.warn("5250: could not process a record ({} bytes): {}", r.length, e.toString());
          }
        } else if (cmd == DO || cmd == DONT || cmd == WILL || cmd == WONT) {
          negotiate(cmd, in.read());
        } else if (cmd == SB) {
          subnegotiation();
        }
      }
    } catch (IOException e) {
      why = "the connection ended: " + e.getMessage();
    }
    synchronized (this) {
      closedBy = why;
      notifyAll();
    }
  }

  private void negotiate(int cmd, int option) throws IOException {
    boolean wanted = option == BINARY || option == END_OF_RECORD || option == TERMINAL_TYPE || option == TIMING_MARK;
    if (cmd == DO) send(new byte[] { (byte) IAC, (byte) (wanted ? WILL : WONT), (byte) option });
    else if (cmd == WILL) send(new byte[] { (byte) IAC, (byte) (wanted ? DO : DONT), (byte) option });
  }

  private void subnegotiation() throws IOException {
    ByteArrayOutputStream sb = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) >= 0) {
      if (b == IAC) {
        int next = in.read();
        if (next == SE) break;
        sb.write(next);
      } else {
        sb.write(b);
      }
    }
    byte[] data = sb.toByteArray();
    if (data.length >= 2 && data[0] == TERMINAL_TYPE && data[1] == 1) {  // SEND
      ByteArrayOutputStream reply = new ByteArrayOutputStream();
      reply.write(new byte[] { (byte) IAC, (byte) SB, (byte) TERMINAL_TYPE, 0 });
      reply.write(TERMINAL.getBytes(StandardCharsets.US_ASCII));
      reply.write(new byte[] { (byte) IAC, (byte) SE });
      send(reply.toByteArray());
    }
    /* NEW-ENVIRON is refused (WONT), so the IBM i shows its sign-on display */
  }

  private synchronized void processRecord(byte[] r) throws IOException {
    if (r.length < 10) return;
    int dataStart = 6 + (r[6] & 0xFF);
    int opcode = r[9] & 0xFF;
    lastChange = System.currentTimeMillis();
    if (opcode == OP_SAVE && dataStart >= r.length) {
      sendLater(gds(0, OP_PUT_GET, saveScreen()));
      return;
    }
    int i = dataStart;
    while (i < r.length) {
      if ((r[i] & 0xFF) != ESC) {
        i++;
        continue;
      }
      if (i + 1 >= r.length) break;
      int command = r[i + 1] & 0xFF;
      i += 2;
      switch (command) {
        case 0x40:  // Clear Unit
          clear(24, 80);
          break;
        case 0x20:  // Clear Unit Alternate
          if (i < r.length) {
            int size = r[i++] & 0xFF;
            if (size == 0x00) clear(27, 132); else clear(24, 80);
          }
          break;
        case 0x50:  // Clear Format Table
          fields.clear();
          break;
        case 0x11:  // Write To Display
          i = writeToDisplay(r, i);
          break;
        case 0x21:  // Write Error Code
          i = writeErrorCode(r, i);
          break;
        case 0x22:  // Write Error Code To Window
          i = writeErrorCode(r, i + 2);
          break;
        case 0x42: case 0x52: case 0x82:  // Read Input Fields / MDT Fields / MDT Alternate
          i += 2;
          pendingRead = command == 0x82 ? 0x52 : command;
          notifyAll();
          if (onScreen != null) sendLaterRun(onScreen);
          break;
        case 0x72: case 0x83:  // Read Immediate (Alternate): the fields at once, no AID
          pendingRead = 0x52;
          sendLater(readImmediate());
          pendingRead = 0;
          break;
        case 0x62: case 0x66:  // Read Screen
          sendLater(gds(0, OP_PUT_GET, java.util.Arrays.copyOf(buffer, buffer.length)));
          break;
        case 0x02:  // Save Screen
          sendLater(gds(0, OP_PUT_GET, saveScreen()));
          break;
        case 0x12:  // Restore Screen: the saved stream follows as commands
          break;
        case 0x23:  // Roll
          i = roll(r, i);
          break;
        case 0xF3:  // Write Structured Field
          i = structuredField(r, i);
          break;
        default:
          logger.info("5250: command {} not handled, rest of the record skipped", Integer.toHexString(command));
          i = r.length;
      }
    }
    notifyAll();
  }

  private void clear(int newRows, int newCols) {
    rows = newRows;
    cols = newCols;
    buffer = new byte[rows * cols];
    fields.clear();
    address = 0;
    cursor = 0;
    inputError = false;
    errorLineSaved = null;
  }

  private int writeToDisplay(byte[] r, int i) {
    if (i + 1 >= r.length) return r.length;
    int cc1 = r[i] & 0xFF;
    int cc2 = r[i + 1] & 0xFF;
    i += 2;
    int reset = cc1 & 0xE0;
    for (Field f : fields.values()) {
      if (reset == 0x40 && !f.bypass()) f.mdt = false;
      if (reset == 0x60 || reset == 0xA0 || reset == 0xE0) f.mdt = false;
      boolean nullIt = (reset == 0x80 || reset == 0xC0) ? (!f.bypass() && f.mdt) : (reset == 0xA0 || reset == 0xE0) && !f.bypass();
      if (nullIt) java.util.Arrays.fill(buffer, f.start, Math.min(f.start + f.length, buffer.length), (byte) 0);
      if (reset == 0xC0 && !f.bypass()) f.mdt = false;
    }
    if ((cc2 & 0x08) != 0) inputError = false;
    boolean cursorSet = false;
    while (i < r.length && (r[i] & 0xFF) != ESC) {
      int b = r[i] & 0xFF;
      switch (b) {
        case 0x01: {  // Start Of Header: a new format
          int len = i + 1 < r.length ? r[i + 1] & 0xFF : 0;
          i += 2 + len;
          fields.clear();
          break;
        }
        case 0x02: {  // Repeat To Address
          int to = at(r[i + 1], r[i + 2]);
          byte c = r[i + 3];
          while (address <= to && address < buffer.length) buffer[address++] = c;
          i += 4;
          break;
        }
        case 0x03: {  // Erase To Address
          int to = at(r[i + 1], r[i + 2]);
          int len = r[i + 3] & 0xFF;
          while (address <= to && address < buffer.length) buffer[address++] = 0;
          i += 3 + len;
          break;
        }
        case 0x10: {  // Transparent Data
          int len = ((r[i + 1] & 0xFF) << 8) | (r[i + 2] & 0xFF);
          for (int k = 0; k < len && i + 3 + k < r.length; k++) put(r[i + 3 + k]);
          i += 3 + len;
          break;
        }
        case 0x11:  // Set Buffer Address
          address = at(r[i + 1], r[i + 2]);
          i += 3;
          break;
        case 0x12:  // Write Extended Attribute
          i += 3;
          break;
        case 0x13:  // Insert Cursor
          cursor = at(r[i + 1], r[i + 2]);
          cursorSet = true;
          i += 3;
          break;
        case 0x14:  // Move Cursor
          cursor = at(r[i + 1], r[i + 2]);
          cursorSet = true;
          i += 3;
          break;
        case 0x15: {  // Write To Display Structured Field (windows, scroll bars): skipped
          int len = ((r[i + 1] & 0xFF) << 8) | (r[i + 2] & 0xFF);
          i += 1 + Math.max(len, 2);
          break;
        }
        case 0x1D:  // Start Field
          i = startField(r, i + 1);
          break;
        default:
          put((byte) b);
          i++;
      }
    }
    if (!cursorSet && (cc2 & 0x40) == 0 && !fields.isEmpty()) {
      Field first = null;
      for (Field f : fields.values()) if (!f.bypass()) { first = f; break; }
      if (first != null && cursor == 0) cursor = first.start;
    }
    return i;
  }

  /* SF: [FFW 2 bytes [FCW 2 bytes ...]] attribute length(2); the address moves to the field's first position */
  private int startField(byte[] r, int i) {
    int attrPos = address;
    Field f = null;
    if (((r[i] & 0xFF) & 0xC0) == 0x40) {
      f = new Field();
      f.ffw1 = r[i] & 0xFF;
      f.ffw2 = r[i + 1] & 0xFF;
      f.mdt = (f.ffw1 & 0x08) != 0;
      i += 2;
      while (((r[i] & 0xFF) & 0xE0) != 0x20) i += 2;  // FCWs
    }
    int attr = r[i] & 0xFF;
    int length = ((r[i + 1] & 0xFF) << 8) | (r[i + 2] & 0xFF);
    i += 3;
    put((byte) attr);
    if (f != null) {
      f.attrPos = attrPos;
      f.start = address;
      f.length = length;
      f.attr = attr;
      fields.put(f.start, f);
    }
    return i;
  }

  private int writeErrorCode(byte[] r, int i) {
    int line = (rows - 1) * cols;
    if (!inputError) errorLineSaved = java.util.Arrays.copyOfRange(buffer, line, line + cols);
    int saved = address;
    address = line;
    while (i < r.length && (r[i] & 0xFF) != ESC) {
      int b = r[i] & 0xFF;
      if (b == 0x13 || b == 0x11) {
        if (b == 0x13) cursor = at(r[i + 1], r[i + 2]); else address = at(r[i + 1], r[i + 2]);
        i += 3;
      } else {
        put((byte) b);
        i++;
      }
    }
    address = saved;
    inputError = true;
    if (pendingRead == 0) pendingRead = 0x52;  // the read before it still waits
    return i;
  }

  private int roll(byte[] r, int i) {
    int dir = r[i] & 0xFF;
    int top = (r[i + 1] & 0xFF) - 1;
    int bottom = (r[i + 2] & 0xFF) - 1;
    int lines = dir & 0x1F;
    boolean down = (dir & 0x80) != 0;
    for (int n = 0; n < lines; n++) {
      if (down) {
        for (int row = bottom; row > top; row--) System.arraycopy(buffer, (row - 1) * cols, buffer, row * cols, cols);
        java.util.Arrays.fill(buffer, top * cols, top * cols + cols, (byte) 0);
      } else {
        for (int row = top; row < bottom; row++) System.arraycopy(buffer, (row + 1) * cols, buffer, row * cols, cols);
        java.util.Arrays.fill(buffer, bottom * cols, bottom * cols + cols, (byte) 0);
      }
    }
    return i + 3;
  }

  private int structuredField(byte[] r, int i) throws IOException {
    int len = ((r[i] & 0xFF) << 8) | (r[i + 1] & 0xFF);
    int cls = i + 2 < r.length ? r[i + 2] & 0xFF : 0;
    int type = i + 3 < r.length ? r[i + 3] & 0xFF : 0;
    if (cls == 0xD9 && type == 0x70) sendLater(gds(0, 0, queryReply()));
    return i + Math.max(len, 2);
  }

  /* 5250 Query reply: a 3179-2 display on a controller that supports input fields everywhere */
  private byte[] queryReply() {
    byte[] q = new byte[61];
    q[0] = 0; q[1] = 0; q[2] = (byte) AID_QUERY;
    q[3] = 0; q[4] = 58;                        // length of what follows, with these two bytes
    q[5] = (byte) 0xD9; q[6] = 0x70; q[7] = (byte) 0x80;
    q[8] = 0x06; q[9] = 0x00;                   // controller: another 5250 emulator
    q[10] = 0x01; q[11] = 0x01; q[12] = 0x00;   // code level
    q[29] = 0x01;                               // a display
    byte[] type = { (byte) 0xF3, (byte) 0xF1, (byte) 0xF7, (byte) 0xF9 };  // "3179"
    System.arraycopy(type, 0, q, 30, 4);
    q[34] = (byte) 0xF0; q[35] = (byte) 0xF0; q[36] = (byte) 0xF2;      // model "002"
    q[37] = 0x02;                               // keyboard
    q[40] = 0x00; q[41] = 0x61; q[42] = 0x50; q[43] = 0x00;  // serial number
    q[44] = (byte) 0xFF; q[45] = (byte) 0xFF;   // input fields
    q[49] = 0x01; q[50] = 0x10;                 // 24 x 80, no extended attributes
    return q;
  }

  private byte[] readImmediate() {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    data.write(cursor / cols + 1);
    data.write(cursor % cols + 1);
    data.write(0);
    for (Field f : fields.values()) {
      if (!f.mdt || f.bypass()) continue;
      data.write(0x11);
      data.write(f.start / cols + 1);
      data.write(f.start % cols + 1);
      byte[] content = fieldData(f);
      data.write(content, 0, content.length);
    }
    return gds(0, OP_PUT_GET, data.toByteArray());
  }

  /* What Restore Screen gives back later: the screen and its fields rebuilt as a Write To Display */
  private byte[] saveScreen() {
    ByteArrayOutputStream s = new ByteArrayOutputStream();
    s.write(ESC); s.write(0x12);
    s.write(ESC); s.write(rows == 27 ? 0x20 : 0x40);
    if (rows == 27) s.write(0x00);
    s.write(ESC); s.write(0x11); s.write(0x00); s.write(0x08);
    s.write(0x11); s.write(1); s.write(1);
    for (int pos = 0; pos < buffer.length; pos++) {
      Field f = null;
      for (Field candidate : fields.values()) if (candidate.attrPos == pos) { f = candidate; break; }
      if (f != null) {
        s.write(0x11); s.write(pos / cols + 1); s.write(pos % cols + 1);
        s.write(0x1D); s.write(f.ffw1 & ~0x08 | (f.mdt ? 0x08 : 0)); s.write(f.ffw2);
        s.write(f.attr); s.write(f.length >> 8); s.write(f.length & 0xFF);
        continue;
      }
      int b = buffer[pos] & 0xFF;
      s.write(b == 0x04 || (b > 0 && b < 0x20) ? 0x40 : b);
    }
    s.write(0x13); s.write(cursor / cols + 1); s.write(cursor % cols + 1);
    if (pendingRead != 0) { s.write(ESC); s.write(pendingRead); s.write(0); s.write(0); }
    return s.toByteArray();
  }

  private int at(byte row, byte col) {
    int a = ((row & 0xFF) - 1) * cols + ((col & 0xFF) - 1);
    return Math.max(0, Math.min(a, buffer.length - 1));
  }

  private void put(byte b) {
    if (address < buffer.length) buffer[address] = b;
    address = (address + 1) % buffer.length;
  }

  /* ---- sending ---- */

  /* A 5250 record: header (length, 12A0, flags, opcode), data, IAC EOR */
  private static byte[] gds(int flags, int opcode, byte[] data) {
    ByteArrayOutputStream rec = new ByteArrayOutputStream();
    int len = data.length + 10;
    rec.write(len >> 8); rec.write(len & 0xFF);
    rec.write(0x12); rec.write(0xA0);
    rec.write(0); rec.write(0);
    rec.write(0x04);
    rec.write(flags); rec.write(0);
    rec.write(opcode);
    rec.write(data, 0, data.length);
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    for (byte b : rec.toByteArray()) {
      wire.write(b);
      if ((b & 0xFF) == IAC) wire.write(IAC);
    }
    wire.write(IAC);
    wire.write(EOR);
    return wire.toByteArray();
  }

  /* Answers made while processing a record: sent outside the lock, in order */
  private void sendLater(byte[] bytes) {
    try {
      send(bytes);
    } catch (IOException e) {
      logger.warn("5250: could not answer the IBM i: {}", e.getMessage());
    }
  }

  private void sendLaterRun(Runnable r) {
    Thread t = new Thread(() -> {
      try {
        Thread.sleep(300);
        r.run();
      } catch (Exception ignored) {}
    }, "mc-5250-watch");
    t.setDaemon(true);
    t.start();
  }

  private void send(byte[] bytes) throws IOException {
    synchronized (out) {
      out.write(bytes);
      out.flush();
    }
  }

  /* AID for a key name: Enter, F1-F24, PageUp, PageDown, Help, Print, Clear; -1 when unknown */
  static int aid(String key) {
    String k = key.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    switch (k) {
      case "ENTER":    return AID_ENTER;
      case "PAGEUP": case "ROLLDOWN": return AID_ROLL_DOWN;
      case "PAGEDOWN": case "ROLLUP": return AID_ROLL_UP;
      case "HELP":     return AID_HELP;
      case "PRINT":    return AID_PRINT;
      case "CLEAR":    return AID_CLEAR;
      default: break;
    }
    if (k.matches("F([1-9]|1[0-9]|2[0-4])")) {
      int n = Integer.parseInt(k.substring(1));
      return n <= 12 ? 0x30 + n : 0xB0 + (n - 12);
    }
    return -1;
  }
}
