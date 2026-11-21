package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 * A member, or a stream file wanted in another CCSID (toCcsid), is first copied into a stream file by MC's IBM i job
 * (the one run_command runs in, so QTEMP is that job's), with the command STAGE_COMMANDS has for it; that copy is
 * downloaded and deleted. It only writes below .mc/results/ (and that copy), so it needs no confirm.
 */
final class FileDownload {

  /* Where the file comes from: SFTP over SSH, or the host servers' IFS access */
  interface Source {
    long[] stat(String path) throws Exception;   // {size, modified epoch seconds}, null when no stream file is there
    void get(String path, File local) throws Exception;
  }

  static final long MAX_BYTES = 512L * 1024 * 1024;
  private static final ObjectMapper mapper = new ObjectMapper();

  /* What is copied into a stream file before the download -> the CL that copies it (from, into, CCSID) */
  static final Map<String, String> STAGE_COMMANDS = new LinkedHashMap<String, String>();
  static {
    STAGE_COMMANDS.put("member", "CPYTOSTMF FROMMBR('%s') TOSTMF('%s') STMFOPT(*REPLACE) STMFCCSID(%d) ENDLINFMT(*LF)");
    STAGE_COMMANDS.put("path", "CPY OBJ('%s') TOOBJ('%s') TOCCSID(%d) DTAFMT(*TEXT) REPLACE(*YES)");
  }
  static final int TEXT_CCSID = 1208;  // a member arrives as UTF-8 unless toCcsid says otherwise
  static final String STAGED_PREFIX = ".mc-stage-";
  private static final String NAME = "[A-Z$#@][A-Z0-9$#@_]{0,9}";
  private static final Pattern MEMBER = Pattern.compile("(?i)(" + NAME + ")/(" + NAME + ")(?:/(" + NAME + ")|\\((" + NAME + ")\\))");

  /* "member" or "path" when args ask for a copy first (a member, or toCcsid), null when the file comes as it is */
  static String stageKind(JsonNode args) {
    return args.hasNonNull("member") ? "member" : args.hasNonNull("toCcsid") ? "path" : null;
  }

  /* The CL that copies what args name into the stream file 'into'; IllegalArgumentException when args are wrong */
  static String stageCommand(JsonNode args, String into) {
    return String.format(STAGE_COMMANDS.get(stageKind(args)), clText(source(args)), clText(into), ccsid(args));
  }

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
  private static String clText(String text) {
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
    return folder + "/" + STAGED_PREFIX + Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE) + ".txt";
  }

  /* The copy made at 'staged', downloaded under 'name' (default: the member's, lower case, .txt; or the file's name) */
  static ObjectNode handleStaged(JsonNode args, String staged, File project, Source source) throws Exception {
    String from = source(args);
    String member = args.hasNonNull("member") ? DataFetch.fileName(from).replaceAll("\\.MBR$", "").toLowerCase(Locale.ROOT) + ".txt" : null;
    ObjectNode copy = mapper.createObjectNode();
    copy.put("path", staged);
    copy.put("name", args.path("name").asText(member != null ? member : DataFetch.fileName(from)));
    ObjectNode result = handle(copy, project, source);
    result.put("path", from);
    result.put("note", "Copied on the IBM i into CCSID " + ccsid(args) + " (" + (member != null ? "CPYTOSTMF" : "CPY")
        + ", as text), then downloaded byte for byte; that copy was deleted");
    return result;
  }

  private FileDownload() {}

  static ObjectNode handle(JsonNode args, File project, Source source) throws Exception {
    String path = args.path("path").asText("").trim();
    checkPath(path);
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
