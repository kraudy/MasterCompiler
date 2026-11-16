package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;
import com.jcraft.jsch.SftpATTRS;

/*
 * download_file: one IFS stream file to this PC byte for byte (no CCSID conversion), into the project's .mc/results/.
 * Over SSH through the session's SFTP channel (no new sign-on); otherwise through the file host server, as --fetch.
 * It only reads on the IBM i and only writes below .mc/results/, so it needs no confirm.
 */
final class FileDownload {

  /* Where the file comes from: SFTP over SSH, or the host servers' IFS access */
  interface Source {
    long[] stat(String path) throws Exception;   // {size, modified epoch seconds}, null when no stream file is there
    void get(String path, File local) throws Exception;
  }

  static final long MAX_BYTES = 512L * 1024 * 1024;
  private static final ObjectMapper mapper = new ObjectMapper();

  private FileDownload() {}

  static ObjectNode handle(JsonNode args, File project, Source source) throws Exception {
    String path = args.path("path").asText("").trim();
    try {
      DataFetch.check(path);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("'path' is the absolute path of an IFS stream file, e.g. /home/USER/probe.bin");
    }
    String name = args.path("name").asText(DataFetch.fileName(path)).trim();
    if (name.isEmpty() || !name.equals(DataFetch.fileName(name)) || name.startsWith(".")) {
      throw new IllegalArgumentException("'name' is a file name, without folders");
    }
    long[] remote = source.stat(path);
    if (remote == null) throw new IllegalArgumentException(path + " is not a stream file on the IBM i (or this user cannot see it)");
    if (remote[0] > MAX_BYTES) throw new IllegalArgumentException(path + " is larger than 512 MB");

    File results = resultsFolder(project);
    File target = new File(results, name);
    /* into a new file next to the target, then moved over it: a failed copy never leaves half a file under its name */
    File partial = Files.createTempFile(results.toPath(), ".mc-download-", ".part").toFile();
    try {
      source.get(path, partial);
      if (partial.length() != remote[0]) {
        throw new IllegalStateException("The download of " + path + " did not complete: " + partial.length() + " of "
            + remote[0] + " bytes");
      }
      Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } finally {
      partial.delete();
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("path", path);
    result.put("bytes", target.length());
    result.put("sha256", FileUpload.sha256(target));
    result.put("modified", new Timestamp(remote[1] * 1000L).toString().replaceAll("\\.0$", ""));
    result.put("file", project.getCanonicalFile().toPath().relativize(target.toPath()).toString().replace('\\', '/'));
    result.put("localPath", target.getPath());
    result.put("note", "Byte for byte: no CCSID conversion (an EBCDIC file stays EBCDIC)");
    return result;
  }

  /* <project>/.mc/results, created if needed; refused when a symbolic link leads it out of the project */
  static File resultsFolder(File project) throws IOException {
    File results = new File(project, ".mc/results");
    if (!results.isDirectory() && !results.mkdirs()) throw new IOException("Cannot create " + results);
    File real = results.getCanonicalFile();
    if (!real.toPath().startsWith(project.getCanonicalFile().toPath())) {
      throw new IOException(results + " leads out of the project (to " + real + "): nothing is written there");
    }
    return real;
  }

  /* Over SSH: the session's SFTP channel */
  static Source over(SshTarget ssh) {
    return new Source() {
      @Override public long[] stat(String path) throws Exception {
        SftpATTRS a = ssh.stat(path);
        return a == null || a.isDir() ? null : new long[] { a.getSize(), a.getMTime() };
      }
      @Override public void get(String path, File local) throws Exception {
        ssh.download(path, local);
      }
    };
  }

  /* Over the host servers (JT400): the file server, read without conversion */
  static Source over(AS400 system) {
    return new Source() {
      @Override public long[] stat(String path) throws Exception {
        IFSFile f = new IFSFile(system, path);
        return f.exists() && f.isFile() ? new long[] { f.length(), f.lastModified() / 1000 } : null;
      }
      @Override public void get(String path, File local) throws Exception {
        try (InputStream in = new IFSFileInputStream(system, path, IFSFileInputStream.SHARE_ALL);
             OutputStream out = new FileOutputStream(local)) {
          byte[] buffer = new byte[64 * 1024];
          int n;
          while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
      }
    };
  }
}
