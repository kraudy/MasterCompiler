package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;

/*
 * --fetch /path/file [-o <file or folder>]: copy one IFS stream file to this PC, byte for byte, through the file host
 * server MC already uses. For a probe that writes its answer to a stream file instead of the spool.
 */
final class DataFetch {
  private DataFetch() {}

  /* An absolute path to a file; throws IllegalArgumentException */
  static void check(String path) {
    if (path == null || !path.startsWith("/") || path.endsWith("/") || path.indexOf('\0') >= 0 || path.contains("\n")) {
      throw new IllegalArgumentException("--fetch needs the absolute path of an IFS stream file, e.g. /home/USER/probe.txt: " + path);
    }
  }

  /* The file name an IFS path gets on this PC: no separator of any system, no drive, no "..", whatever it is called there */
  static String fileName(String fetch) {
    String name = fetch.substring(fetch.lastIndexOf('/') + 1).replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_");
    return name.equals(".") || name.equals("..") ? "fetched.file" : name;
  }

  /* Where the file goes: -o when it is a file name, -o/<name> when it is a folder, else the current folder */
  static File destination(String fetch, String output) {
    String name = fileName(fetch);
    if (output == null) return new File(name);
    File out = new File(output);
    return out.isDirectory() || output.endsWith("/") || output.endsWith(File.separator) ? new File(out, name) : out;
  }

  @FunctionalInterface
  interface Fetcher {
    int fetch(String path, String output) throws Exception;
  }

  static int run(AS400 system, ArgParser parser) throws Exception {
    return run(parser.getFetches(), parser.getOutputFile(), (path, output) -> fetchOne(system, path, output));
  }

  /* One path keeps the old behaviour; with several, a failed file is reported and the rest still run */
  static int run(List<String> paths, String output, Fetcher fetcher) throws Exception {
    if (paths.size() == 1) return fetcher.fetch(paths.get(0), output);
    int failed = 0;
    for (String path : paths) {
      try {
        if (fetcher.fetch(path, output) != 0) failed++;
      } catch (Exception e) {
        String why = e.getMessage();
        System.err.println("Fetch failed: " + path + (why == null || why.isEmpty() ? "" : ": " + why.split("\\R")[0]));
        failed++;
      }
    }
    return failed == 0 ? 0 : 1;
  }

  private static int fetchOne(AS400 system, String path, String output) throws Exception {
    File target = destination(path, output);
    IFSFile file = new IFSFile(system, path);
    if (!file.exists()) {
      System.err.println("Fetch failed: " + path + " does not exist on the IBM i (or this user cannot see it)");
      return 1;
    }
    if (!file.isFile()) {
      System.err.println("Fetch failed: " + path + " is not a stream file");
      return 1;
    }
    /* The source's own time and CCSID, before the copy: a stale file is visible in the answer */
    long modified = 0;
    int ccsid = 0;
    try { modified = file.lastModified(); } catch (Exception ignored) {}
    try { ccsid = file.getCCSID(); } catch (Exception ignored) {}
    File folder = target.getAbsoluteFile().getParentFile();
    if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
      System.err.println("Fetch failed: cannot create " + folder);
      return 1;
    }
    long copied = 0;
    /* Into a new, uniquely named file next to the target (created exclusively, never an existing file or link), then moved over
       it: fetching onto the file itself (MC on the IBM i) cannot empty it first */
    File partial = java.nio.file.Files.createTempFile(target.getAbsoluteFile().getParentFile().toPath(), ".mc-fetch-", ".part").toFile();
    try (InputStream in = new IFSFileInputStream(system, path, IFSFileInputStream.SHARE_ALL);
         OutputStream out = new FileOutputStream(partial)) {
      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = in.read(buffer)) > 0) {
        out.write(buffer, 0, n);
        copied += n;
      }
    } catch (Exception e) {
      partial.delete();
      throw e;
    }
    java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    System.out.println(fetched(path, copied, target, modified, ccsid));
    return 0;
  }

  /* The line the fetch prints: source, size, when it was last changed, and its CCSID when known */
  static String fetched(String path, long bytes, File target, long modified, int ccsid) {
    StringBuilder line = new StringBuilder("Fetched ").append(path).append(" (").append(bytes).append(" bytes");
    if (modified > 0) {
      line.append(", modified ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(modified)));
    }
    if (ccsid > 0) line.append(", CCSID ").append(ccsid);
    return line.append(") to ").append(target).toString();
  }
}
