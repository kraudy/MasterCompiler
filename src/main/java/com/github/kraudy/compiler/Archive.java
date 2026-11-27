package com.github.kraudy.compiler;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/*
 * upload_file extract: the entries of a .zip, .tar, .tar.gz or .tgz, one at a time (a stream each: nothing is held in
 * memory). Only files and folders: a link, device or fifo stops the reading, as does a name that would leave the folder
 * the archive goes to (absolute, '..'). Tar: ustar names (prefix + name), GNU long names (L) and pax paths (x).
 */
final class Archive {
  static final List<String> KINDS = Arrays.asList(".zip", ".tar", ".tar.gz", ".tgz");
  private static final int BLOCK = 512;
  /* Whatever the names: the headers read (a file may take a long-name or pax header too) and the bytes of the ones
     without file data, for the whole archive, so a small archive cannot make MC read on and on */
  private static final int MAX_HEADERS = 2 * FileUpload.MAX_ENTRIES + 16;
  private static final long MAX_METADATA = 16L * 1024 * 1024;
  static final int MAX_PATH = 1024;  // characters of a path in the archive
  static final int MAX_DEPTH = 32;   // its folders

  interface Entries {
    /* path: relative, '/'-separated, checked; data: the file's bytes (null for a folder), read or not */
    void entry(String path, boolean folder, long size, InputStream data) throws Exception;
  }

  private Archive() {}

  static boolean isArchive(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    for (String kind : KINDS) if (lower.endsWith(kind)) return true;
    return false;
  }

  static void read(File archive, Entries entries) throws Exception {
    String lower = archive.getName().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".zip")) {
      zip(archive, entries);
      return;
    }
    if (!isArchive(lower)) throw new IllegalArgumentException(archive.getName() + " is not one of " + KINDS);
    try (InputStream file = new BufferedInputStream(new FileInputStream(archive), 65536);
         InputStream in = lower.endsWith(".tar") ? file : new GZIPInputStream(file, 65536)) {
      tar(in, entries);
    }
  }

  private static void zip(File archive, Entries entries) throws Exception {
    try (ZipFile zip = new ZipFile(archive)) {
      int read = 0;
      for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements();) {
        ZipEntry e = all.nextElement();
        if (++read > MAX_HEADERS) throw new IllegalArgumentException(archive.getName() + " has too many entries");
        String path = path(e.getName());
        if (path == null) continue;  // the archive's own folder ("./")
        if (e.isDirectory()) {
          entries.entry(path, true, 0, null);
          continue;
        }
        try (InputStream data = zip.getInputStream(e)) {
          entries.entry(path, false, e.getSize(), data);
        }
      }
    }
  }

  private static void tar(InputStream in, Entries entries) throws Exception {
    byte[] header = new byte[BLOCK];
    String longName = null;
    int headers = 0;
    long[] metadata = { 0 };
    while (true) {
      if (!fill(in, header)) return;  // the archive ends without its two empty blocks: what was read stands
      if (empty(header)) return;
      if (++headers > MAX_HEADERS) throw new IllegalArgumentException("The archive has too many entries");
      char type = (char) header[156];
      long size = octal(header, 124, 12);
      String name = longName != null ? longName : ustarName(header);
      longName = null;
      if (type == 'L' || type == 'x') {  // the next entry's name: GNU long name, or a pax header's path
        byte[] meta = new byte[(int) metadata(size, metadata)];
        readFully(in, meta);
        skip(in, padding(size));
        longName = type == 'L' ? cString(meta) : paxPath(meta);
        continue;
      }
      if (type == 'g') {  // pax global header: nothing MC uses
        skip(in, metadata(size, metadata) + padding(size));
        continue;
      }
      boolean folder = type == '5';
      if (!folder && type != '0' && type != '\0' && type != '7') {
        throw new IllegalArgumentException(name + " is " + (type == '1' || type == '2' ? "a link" : "not a file or a folder")
            + " (tar type " + type + "): MC extracts only files and folders");
      }
      String path = path(name);
      if (path == null) {
        skip(in, metadata(size, metadata) + padding(size));
        continue;
      }
      if (folder) {
        entries.entry(path, true, 0, null);
        skip(in, metadata(size, metadata) + padding(size));
        continue;
      }
      Bounded data = new Bounded(in, size);
      entries.entry(path, false, size, data);
      skip(in, data.left + padding(size));
    }
  }

  /*
   * The entry's path inside the folder: '\' as '/', a leading "./" and a trailing '/' dropped; null for the archive's
   * own folder. Absolute, a drive, '..', '.', empty parts or control characters: refused.
   */
  static String path(String name) {
    String p = name.replace('\\', '/');
    while (p.startsWith("./")) p = p.substring(2);
    if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    if (p.isEmpty() || p.equals(".")) return null;
    if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException(name + ": an absolute path in the archive");
    String[] parts = p.split("/", -1);
    if (p.length() > MAX_PATH || parts.length > MAX_DEPTH) {
      throw new IllegalArgumentException("A path in the archive longer than " + MAX_PATH + " characters or " + MAX_DEPTH
          + " folders deep: " + p.substring(0, Math.min(80, p.length())) + "...");
    }
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) {
        throw new IllegalArgumentException(name + ": a path in the archive that leaves its folder or is not plain");
      }
    }
    for (char c : p.toCharArray()) {
      if (c < 0x20 || c == 0x7f) throw new IllegalArgumentException("A path in the archive with a control character: " + name);
    }
    return p;
  }

  private static String ustarName(byte[] header) {
    String name = cString(Arrays.copyOfRange(header, 0, 100));
    boolean ustar = new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
    String prefix = ustar ? cString(Arrays.copyOfRange(header, 345, 500)) : "";
    return prefix.isEmpty() ? name : prefix + "/" + name;
  }

  /* "len path=value\n" records */
  private static String paxPath(byte[] meta) {
    String text = new String(meta, StandardCharsets.UTF_8);
    for (String record : text.split("\n")) {
      int space = record.indexOf(' ');
      if (space > 0 && record.startsWith("path=", space + 1)) return record.substring(space + 6);
    }
    return null;  // no path: the entry's own name is used
  }

  private static String cString(byte[] bytes) {
    int end = 0;
    while (end < bytes.length && bytes[end] != 0) end++;
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }

  private static long octal(byte[] header, int at, int length) {
    long value = 0;
    boolean digits = false;
    for (int i = at; i < at + length; i++) {
      byte b = header[i];
      if (b == 0 || b == ' ') {
        if (digits) break;
        continue;
      }
      if (b < '0' || b > '7') throw new IllegalArgumentException("Not a tar archive (or a size MC does not read)");
      value = value * 8 + (b - '0');
      digits = true;
    }
    return value;
  }

  /* Bytes of an entry without file data (a header's, a folder's): never much in a real archive, nor all of them */
  private static long metadata(long size, long[] total) {
    if (size > 1 << 20) throw new IllegalArgumentException("A tar entry without file data of " + size + " bytes: not read");
    total[0] += size;
    if (total[0] > MAX_METADATA) throw new IllegalArgumentException("The archive's headers take more than 16 MB: not read");
    return size;
  }

  private static boolean empty(byte[] block) {
    for (byte b : block) if (b != 0) return false;
    return true;
  }

  private static long padding(long size) {
    return (BLOCK - size % BLOCK) % BLOCK;
  }

  /* A whole block, false at the end of the stream before any byte of it */
  private static boolean fill(InputStream in, byte[] block) throws IOException {
    int read = 0;
    while (read < block.length) {
      int n = in.read(block, read, block.length - read);
      if (n < 0) {
        if (read == 0) return false;
        throw new EOFException("The archive ends inside a tar header");
      }
      read += n;
    }
    return true;
  }

  private static void readFully(InputStream in, byte[] buffer) throws IOException {
    int read = 0;
    while (read < buffer.length) {
      int n = in.read(buffer, read, buffer.length - read);
      if (n < 0) throw new EOFException("The archive ends inside an entry");
      read += n;
    }
  }

  private static void skip(InputStream in, long bytes) throws IOException {
    byte[] buffer = new byte[8192];
    while (bytes > 0) {
      int n = in.read(buffer, 0, (int) Math.min(buffer.length, bytes));
      if (n < 0) throw new EOFException("The archive ends inside an entry");
      bytes -= n;
    }
  }

  /* One tar entry's bytes; closing it leaves the archive open */
  private static final class Bounded extends FilterInputStream {
    long left;

    Bounded(InputStream in, long size) {
      super(in);
      this.left = size;
    }

    @Override public int read() throws IOException {
      if (left <= 0) return -1;
      int b = super.read();
      if (b >= 0) left--;
      return b;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) return -1;
      int n = super.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      return n;
    }

    @Override public long skip(long n) throws IOException {
      return 0;  // the reader skips what is left itself
    }

    @Override public void close() {
    }
  }
}
