package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * A member, a stream file wanted in another CCSID (toCcsid), or an object (download_object: a save file of it), is
 * first copied into a stream file by MC's IBM i job (the one run_command runs in, so QTEMP is that job's), with the
 * commands STAGES has for it; that copy is downloaded and deleted. It only writes below .mc/results/ (and that copy),
 * so it needs no confirm.
 */
final class FileDownload {

  /* Where the file comes from: SFTP over SSH, or the host servers' IFS access */
  interface Source {
    long[] stat(String path) throws Exception;   // {size, modified epoch seconds}, null when no stream file is there
    void get(String path, File local) throws Exception;
  }

  static final long MAX_BYTES = 512L * 1024 * 1024;
  private static final ObjectMapper mapper = new ObjectMapper();

  /*
   * How a copy is made in MC's job: its commands, in order; the command that removes what the first one made, run
   * afterwards whatever happened but only once that first one succeeded (never something that was there before); and
   * for the answer what was copied (under fromKey), the default file name and a note
   */
  static final class Staging {
    final List<String> commands;
    final String cleanup;
    final String fromKey;
    final String from;
    final String name;
    final String note;

    Staging(List<String> commands, String cleanup, String fromKey, String from, String name, String note) {
      this.commands = commands;
      this.cleanup = cleanup;
      this.fromKey = fromKey;
      this.from = from;
      this.name = name;
      this.note = note;
    }
  }

  /* What is copied into a stream file before the download -> how, from args and the copy's path ('into') */
  static final Map<String, BiFunction<JsonNode, String, Staging>> STAGES = new LinkedHashMap<String, BiFunction<JsonNode, String, Staging>>();
  static {
    STAGES.put("object", ObjectDownload::staging);
    STAGES.put("member", (args, into) -> asText(args, "CPYTOSTMF FROMMBR('%s') TOSTMF('%s') STMFOPT(*REPLACE) STMFCCSID(%d) ENDLINFMT(*LF)",
        into, DataFetch.fileName(source(args)).replaceAll("\\.MBR$", "").toLowerCase(Locale.ROOT) + ".txt", "CPYTOSTMF"));
    STAGES.put("path", (args, into) -> asText(args, "CPY OBJ('%s') TOOBJ('%s') TOCCSID(%d) DTAFMT(*TEXT) REPLACE(*YES)",
        into, DataFetch.fileName(source(args)), "CPY"));
  }
  static final int TEXT_CCSID = 1208;  // a member arrives as UTF-8 unless toCcsid says otherwise
  static final String STAGED_PREFIX = ".mc-stage-";
  static final long STALE_STAGED_MILLIS = 3600 * 1000L;  // a copy whose download was given up (the proxy's wait ran out)
  private static final String NAME = "[A-Z$#@][A-Z0-9$#@_]{0,9}";
  private static final Pattern MEMBER = Pattern.compile("(?i)(" + NAME + ")/(" + NAME + ")(?:/(" + NAME + ")|\\((" + NAME + ")\\))");

  /* A member or a stream file copied as text in a CCSID, by one command (from, into, CCSID) */
  private static Staging asText(JsonNode args, String command, String into, String name, String how) {
    String from = source(args);
    return new Staging(Collections.singletonList(String.format(command, clText(from), clText(into), ccsid(args))), null,
        "path", from, name, "Copied on the IBM i into CCSID " + ccsid(args) + " (" + how + ", as text), then downloaded "
        + "byte for byte; that copy was deleted");
  }

  /* The STAGES key when args ask for a copy first (an object, a member, or toCcsid), null when the file comes as it is */
  static String stageKind(JsonNode args) {
    return args.hasNonNull("object") ? "object" : args.hasNonNull("member") ? "member" : args.hasNonNull("toCcsid") ? "path" : null;
  }

  /* How what args name is copied into the stream file 'into'; IllegalArgumentException when args are wrong */
  static Staging staging(JsonNode args, String into) {
    Staging how = STAGES.get(stageKind(args)).apply(args, into);
    checkName(args.path("name").asText(how.name));  // a wrong name is refused before the copy, not after it
    return how;
  }

  /*
   * Copies left in folder by downloads given up over an hour ago, deleted (a local folder: MC on the IBM i); looked
   * for at most once an hour, the folder's other files are not read
   */
  static void deleteStale(File folder) {
    long now = System.currentTimeMillis();
    if (now - lastStaleSweep < STALE_STAGED_MILLIS) return;
    lastStaleSweep = now;
    try (DirectoryStream<Path> copies = Files.newDirectoryStream(folder.toPath(), STAGED_PREFIX + "*")) {
      for (Path copy : copies) if (now - Files.getLastModifiedTime(copy).toMillis() > STALE_STAGED_MILLIS) Files.deleteIfExists(copy);
    } catch (IOException e) {
      // the next sweep tries again
    }
  }
  private static volatile long lastStaleSweep;

  /* The IFS path of what is copied: the member's /QSYS.LIB path (QTEMP: the job's own), or 'path' */
  static String source(JsonNode args) {
    if (!args.hasNonNull("member")) {
      String path = args.path("path").asText("").trim();
      checkPath(path);
      return path;
    }
    if (args.hasNonNull("path")) throw new IllegalArgumentException("Give 'member' or 'path', not both");
    Matcher m = MEMBER.matcher(args.get("member").asText("").trim());
    if (!m.matches()) {
      throw new IllegalArgumentException("'member' is LIB/FILE/MEMBER or LIB/FILE(MEMBER), e.g. QTEMP/QSQLSRC/ART801");
    }
    return ("/QSYS.LIB/" + m.group(1) + ".LIB/" + m.group(2) + ".FILE/" + (m.group(3) != null ? m.group(3) : m.group(4))
        + ".MBR").toUpperCase(Locale.ROOT);
  }

  /* toCcsid, else UTF-8 */
  static int ccsid(JsonNode args) {
    JsonNode given = args.get("toCcsid");
    if (given == null || given.isNull()) return TEXT_CCSID;
    if (!given.canConvertToInt() || given.asInt() < 1 || given.asInt() > 65533) {
      throw new IllegalArgumentException("'toCcsid' is a CCSID number, e.g. 1208 (UTF-8) or 819 (ISO 8859-1)");
    }
    return given.asInt();
  }

  /* A CL quoted literal's text: apostrophes doubled (the command goes to QCMDEXC as a parameter, not inside SQL) */
  static String clText(String text) {
    return text.replace("'", "''");
  }

  private static void checkPath(String path) {
    try {
      DataFetch.check(path);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("'path' is the absolute path of an IFS stream file, e.g. /home/USER/probe.bin");
    }
  }

  /* The copy's path in folder on the IBM i: a name of MC's own, deleted after the download */
  static String stagedPath(String folder) {
    return folder + "/" + STAGED_PREFIX + Long.toHexString(new SecureRandom().nextLong() & Long.MAX_VALUE) + ".txt";
  }

  /* The copy made at 'staged', downloaded under 'name' (default: the Staging's) */
  static ObjectNode handleStaged(JsonNode args, String staged, File project, Source source) throws Exception {
    Staging how = staging(args, staged);
    ObjectNode copy = mapper.createObjectNode();
    copy.put("path", staged);
    copy.put("name", args.path("name").asText(how.name));
    ObjectNode result = handle(copy, project, source);
    if (!how.fromKey.equals("path")) result.remove("path");  // the copy's path: what was copied replaces it
    result.put(how.fromKey, how.from);
    result.put("note", how.note);
    return result;
  }

  private FileDownload() {}

  /* The local file's name, trimmed; IllegalArgumentException when it is not a plain file name */
  static String checkName(String given) {
    String name = given.trim();
    if (name.isEmpty() || !name.equals(DataFetch.fileName(name)) || name.startsWith(".")) {
      throw new IllegalArgumentException("'name' is a file name, without folders");
    }
    return name;
  }

  static ObjectNode handle(JsonNode args, File project, Source source) throws Exception {
    String path = args.path("path").asText("").trim();
    checkPath(path);
    String name = checkName(args.path("name").asText(DataFetch.fileName(path)));
    long[] remote = source.stat(path);
    if (remote == null) throw new IllegalArgumentException(path + " is not a stream file on the IBM i (or this user cannot see it)");
    if (remote[0] > MAX_BYTES) throw new IllegalArgumentException(path + " is larger than 512 MB");

    File results = resultsFolder(project);
    File target = new File(results, name);
    /* into a new file next to the target, then moved over it: a failed copy never leaves half a file under its name */
    File partial = DataFetch.partialFile(results, ".mc-download-");
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
