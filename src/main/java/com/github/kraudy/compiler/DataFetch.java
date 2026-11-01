package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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

  /* Where the file goes: -o when it is a file name, -o/<name> when it is a folder, else the current folder */
  static File destination(String fetch, String output) {
    /* The IFS file's own name decides a file name here: no separator of any system, no drive, no "..", whatever it is called there */
    String name = fetch.substring(fetch.lastIndexOf('/') + 1).replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_");
    if (name.equals(".") || name.equals("..")) name = "fetched.file";
    if (output == null) return new File(name);
    File out = new File(output);
    return out.isDirectory() || output.endsWith("/") || output.endsWith(File.separator) ? new File(out, name) : out;
  }

  static int run(AS400 system, ArgParser parser) throws Exception {
    String path = parser.getFetch();
    File target = destination(path, parser.getOutputFile());
    IFSFile file = new IFSFile(system, path);
    if (!file.exists()) {
      System.err.println("Fetch failed: " + path + " does not exist on the IBM i (or this user cannot see it)");
      return 1;
    }
    if (!file.isFile()) {
      System.err.println("Fetch failed: " + path + " is not a stream file");
      return 1;
    }
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
    System.out.println("Fetched " + path + " (" + copied + " bytes) to " + target);
    return 0;
  }
}
