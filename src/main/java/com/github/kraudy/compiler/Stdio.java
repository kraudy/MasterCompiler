package com.github.kraudy.compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/*
 * "-" in place of a file: --json -, -o - (--fetch) write to stdout, --sql -, --call-params -, --args-file -, --script -
 * read stdin. Once a run writes data to stdout, everything else it prints (logs, the table, "Fetched ...") goes to
 * stderr, so stdout carries the data alone.
 */
final class Stdio {
  static final String DASH = "-";
  private static volatile PrintStream data;  // the real stdout, once taken for data

  private Stdio() {}

  static boolean dash(String value) {
    return DASH.equals(value);
  }

  /* stdout for data from here on; System.out (logback's console, every println) becomes stderr */
  static synchronized PrintStream takeStdout() {
    if (data != null) return data;
    try {
      data = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
    System.setOut(System.err);
    return data;
  }

  /* Where a "-"-able output goes: the file, or stdout (closing it only flushes) */
  static OutputStream open(String path) throws IOException {
    if (!dash(path)) return new FileOutputStream(new File(path));
    return new FilterOutputStream(takeStdout()) {
      @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
      @Override public void close() throws IOException {  // one document: a line of its own
        out.write('\n');
        out.flush();
      }
    };
  }

  /* The value itself, or all of stdin for "-" (read once: only one option may take stdin) */
  static String text(String value) {
    if (!dash(value)) return value;
    try {
      return new String(readAll(System.in), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("Could not read stdin: " + e.getMessage());
    }
  }

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream all = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int n; (n = in.read(buffer)) > 0;) all.write(buffer, 0, n);
    return all.toByteArray();
  }
}
