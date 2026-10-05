package com.github.kraudy.compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/*
 * Minimal POSIX ustar archive, enough for PASE tar to unpack the project's sources in one go
 * (one upload instead of a round trip per file). Regular files only; keeps their modification time.
 */
final class TarWriter {
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  /* Path with "/" separators; ustar holds up to 100 + 155 characters split on a "/" */
  void add(String path, File file) throws Exception {
    byte[] data = Files.readAllBytes(file.toPath());
    byte[] header = new byte[512];
    String name = path;
    String prefix = "";
    if (bytes(path).length > 100) {
      int split = -1;
      for (int i = path.indexOf('/'); i > 0 && split < 0; i = path.indexOf('/', i + 1)) {
        if (bytes(path.substring(0, i)).length <= 155 && bytes(path.substring(i + 1)).length <= 100) split = i;
      }
      if (split < 0) throw new IllegalArgumentException("Path too long for tar: " + path);
      prefix = path.substring(0, split);
      name = path.substring(split + 1);
    }
    put(header, 0, 100, bytes(name));
    put(header, 100, 8, octal(0644, 8));
    put(header, 108, 8, octal(0, 8));
    put(header, 116, 8, octal(0, 8));
    put(header, 124, 12, octal(data.length, 12));
    put(header, 136, 12, octal(file.lastModified() / 1000, 12));
    for (int i = 148; i < 156; i++) header[i] = ' ';  // checksum counts as spaces
    header[156] = '0';
    put(header, 257, 6, "ustar\0".getBytes(StandardCharsets.US_ASCII));
    put(header, 263, 2, "00".getBytes(StandardCharsets.US_ASCII));
    put(header, 345, 155, bytes(prefix));
    long sum = 0;
    for (byte b : header) sum += b & 0xff;
    put(header, 148, 8, octal(sum, 7));

    out.write(header);
    out.write(data);
    out.write(new byte[(512 - data.length % 512) % 512]);
  }

  /* The archive, closed with two empty blocks */
  byte[] finish() throws Exception {
    out.write(new byte[1024]);
    return out.toByteArray();
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /* Zero-padded octal, NUL terminated, in a field of the given width */
  private static byte[] octal(long value, int width) {
    String digits = Long.toOctalString(value);
    StringBuilder field = new StringBuilder();
    for (int i = digits.length(); i < width - 1; i++) field.append('0');
    return field.append(digits).append('\0').toString().getBytes(StandardCharsets.US_ASCII);
  }

  private static void put(byte[] header, int offset, int length, byte[] value) {
    System.arraycopy(value, 0, header, offset, Math.min(length, value.length));
  }
}
