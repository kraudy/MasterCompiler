package com.github.kraudy.compiler;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/*
 * upload_file: a file of the project folder on this PC (a built jar, a properties file, test input) to an IFS folder
 * the spec allows (uploadFolders). First call: what would happen (size, SHA-256, the file it replaces); confirm:
 * written as <name>.part, checked for its full size, renamed. Text files are tagged UTF-8 (CCSID 1208).
 * Confirmed uploads are logged in .mc/uploads.log.
 */
final class FileUpload {

  /* Where the file goes: SFTP over SSH, or the host servers' IFS access */
  interface Ifs {
    long[] stat(String path) throws Exception;  // {size, modified epoch seconds}, null when it does not exist
    boolean isFolder(String path) throws Exception;
    void put(File local, String path) throws Exception;
    void mkdirs(String path) throws Exception;
    void utf8(String path) throws Exception;
    default void utf8(List<String> paths) throws Exception {
      for (String path : paths) utf8(path);
    }
    String realPath(String path) throws Exception;  // symbolic links resolved; null when it does not exist
  }

  static final long MAX_BYTES = 512L * 1024 * 1024;
  static final int MAX_ENTRIES = 5000;      // extract: files and folders of one archive
  private static final List<String> TEXT = Arrays.asList("txt", "json", "yaml", "yml", "xml", "properties", "csv", "sql",
      "md", "html", "js", "sh", "conf", "cfg", "ini", "rpgle", "sqlrpgle", "clle", "cmd", "pnlgrp", "dspf", "pf", "lf",
      "bnd", "rpgleinc", "cbl", "cblle", "sqlcblle", "c", "h", "py", "java");
  /* Never an upload folder: the system's own, and the library file system */
  private static final List<String> SYSTEM = Arrays.asList("/", "/QSYS.LIB", "/QIBM", "/QOPENSYS", "/QOPENSYS/QIBM",
      "/QOPENSYS/USR", "/QOPENSYS/PKGS", "/QOPENSYS/ETC", "/QDLS", "/QOPT", "/QFILESVR.400", "/QNTC", "/DEV", "/ETC", "/USR", "/BIN");

  private static final ObjectMapper mapper = new ObjectMapper();

  private FileUpload() {}

  /* uploadFolders from the spec (build.yaml, else mc-base.yaml), checked */
  static List<String> folders(File project) throws Exception {
    List<String> folders = new ArrayList<String>();
    for (String f : specList(project, "uploadFolders")) folders.add(folder(f, "uploadFolders"));
    return folders;
  }

  /* Local folders files may also be uploaded from (uploadSources), besides the project: absolute or project-relative */
  static List<File> sources(File project) throws Exception {
    List<File> roots = new ArrayList<File>();
    roots.add(project.getCanonicalFile());
    for (String s : specList(project, "uploadSources")) {
      File f = new File(s.trim());
      if (!f.isAbsolute()) f = new File(project, s.trim());
      f = f.getCanonicalFile();
      if (!f.isDirectory()) throw new IllegalArgumentException("uploadSources: " + s + " is not a folder on this PC");
      if (f.getParentFile() == null) throw new IllegalArgumentException("uploadSources: " + s + " is a whole drive; name a folder");
      roots.add(f);
    }
    return roots;
  }

  /* A list from the spec (build.yaml, else mc-base.yaml) */
  private static List<String> specList(File project, String field) throws Exception {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    for (String name : new String[] { "build.yaml", "mc-base.yaml" }) {
      File spec = new File(project, name);
      if (!spec.isFile()) continue;
      JsonNode node = yaml.readTree(spec).path(field);
      if (node.isMissingNode() || node.isNull()) continue;
      List<String> values = new ArrayList<String>();
      for (JsonNode f : node.isArray() ? node : mapper.createArrayNode().add(node)) values.add(f.asText());
      return values;
    }
    return new ArrayList<String>();
  }

  /* An absolute IFS folder, without '.', '..' or a trailing '/', outside the system folders */
  static String folder(String value, String where) {
    String f = value == null ? "" : value.trim().replaceAll("/+$", "");
    if (!f.startsWith("/") || f.contains("//") || Arrays.asList(f.split("/")).contains("..")
        || Arrays.asList(f.split("/")).contains(".") || f.contains("\\")) {
      throw new IllegalArgumentException(where + ": '" + value + "' is not an absolute IFS folder (/home/USER1/app)");
    }
    String upper = f.toUpperCase(Locale.ROOT);
    if (f.isEmpty() || SYSTEM.contains(upper) || upper.startsWith("/QSYS.LIB/") || upper.startsWith("/QIBM/")
        || upper.startsWith("/QOPENSYS/QIBM/") || upper.startsWith("/QOPENSYS/USR/") || upper.startsWith("/QOPENSYS/PKGS/")) {
      throw new IllegalArgumentException(where + ": " + (f.isEmpty() ? "/" : f) + " is a system folder; uploadFolders are "
          + "the application's own folders (/home/USER1/app)");
    }
    return f;
  }

  /* path is folder or below it. /QOpenSys tells upper from lower case; the rest of the IFS (/home, ...) does not */
  static boolean within(String path, String folder) {
    boolean exact = exactCase(folder);
    String p = exact ? path : path.toUpperCase(Locale.ROOT);
    String f = exact ? folder : folder.toUpperCase(Locale.ROOT);
    return p.equals(f) || p.startsWith(f + "/");
  }

  /* The path with links resolved: the deepest part that exists, resolved, plus the rest */
  static String resolved(Ifs ifs, String path) throws Exception {
    String rest = "";
    String at = path;
    while (!at.isEmpty()) {
      String real = ifs.realPath(at);
      if (real != null) return real.replaceAll("/+$", "") + rest;
      int slash = at.lastIndexOf('/');
      rest = at.substring(slash) + rest;
      at = at.substring(0, slash);
    }
    return path;
  }

  static ObjectNode handle(JsonNode args, File project, Ifs ifs) throws Exception {
    List<String> allowed = folders(project);
    if (allowed.isEmpty()) {
      throw new IllegalArgumentException("The spec allows no upload folder. Ask the user which IFS folder files may go to "
          + "and add uploadFolders: [/home/USER1/app] to build.yaml (or mc-base.yaml)");
    }
    String requested = args.path("file").asText("").trim();
    if (requested.isEmpty()) throw new IllegalArgumentException("Give 'file': a file in the project folder");
    File root = project.getCanonicalFile();
    File local = new File(requested).isAbsolute() ? new File(requested) : new File(root, requested);
    local = local.getCanonicalFile();
    List<File> roots = sources(project);
    boolean fromAllowed = false;
    for (File r : roots) fromAllowed |= local.toPath().startsWith(r.toPath());
    if (!fromAllowed) {
      throw new IllegalArgumentException(requested + " is outside the project folder and the spec's uploadSources: only "
          + "those files are uploaded. Ask the user before adding a folder to uploadSources");
    }
    if (!local.isFile()) throw new IllegalArgumentException(requested + " is not a file");
    if (local.length() > MAX_BYTES) throw new IllegalArgumentException(requested + " is larger than 512 MB");

    String to = folder(args.path("to").asText(""), "'to'");
    boolean inside = false;
    for (String f : allowed) inside |= within(to, f);
    if (!inside) {
      throw new IllegalArgumentException(to + " is not in the spec's uploadFolders " + allowed + ". Ask the user before "
          + "adding a folder there");
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("file", local.toPath().startsWith(root.toPath())
        ? root.toPath().relativize(local.toPath()).toString().replace('\\', '/') : local.getPath());
    result.put("localPath", local.getPath());
    result.put("bytes", local.length());
    result.put("sha256", sha256(local));
    if (args.path("extract").asBoolean(false)) return extract(args, project, ifs, local, to, allowed, result);

    String name = args.path("name").asText(local.getName()).trim();
    if (!isFileName(name)) throw new IllegalArgumentException("'name' is a file name, without folders");
    String path = to + "/" + name;
    /* A symbolic link on the way (the folder or the file) must not lead out of the allowed folders */
    checkInside(ifs, path, allowed);
    boolean text = isText(name);
    result.put("to", path);
    result.put("ccsid", text ? "1208 (text, UTF-8)" : "bytes copied as they are; the CCSID tag is the system's default for a new file");
    if (ifs.isFolder(path)) throw new IllegalArgumentException(path + " is a folder");
    long[] existing = ifs.stat(path);
    if (existing != null) {
      result.put("replaces", existing[0] + " bytes, changed " + new java.sql.Timestamp(existing[1] * 1000L).toString().replaceAll("\\.0$", ""));
    }
    if (!ifs.isFolder(to)) result.put("createsFolder", to);
    if (!args.path("confirm").asBoolean(false)) {
      result.put("next", "Nothing was uploaded. Show this to the user; if they agree, call again with confirm: true.");
      return result;
    }
    ifs.put(local, path);
    long[] arrived = ifs.stat(path);
    if (arrived == null || arrived[0] != local.length()) {
      throw new IllegalStateException("The upload of " + path + " did not complete: " + (arrived == null ? 0 : arrived[0]) + " of "
          + local.length() + " bytes");
    }
    if (text) ifs.utf8(path);
    result.put("uploaded", true);
    result.remove("next");
    log(project, result);
    return result;
  }

  /* A file name MC writes: no folder in it, not one of the upload's own temporary names (.part, .old, any case) */
  private static boolean isFileName(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return !(name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
        || lower.endsWith(".part") || lower.endsWith(".old"));
  }

  private static boolean isText(String name) {
    return TEXT.contains(name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "");
  }

  /* path, its links resolved, is still in one of the allowed folders */
  private static void checkInside(Ifs ifs, String path, List<String> allowed) throws Exception {
    String real = resolved(ifs, path);
    for (String f : allowed) if (within(real, resolved(ifs, f))) return;
    throw new IllegalArgumentException(path + " leads to " + real + " (a symbolic link), outside the spec's uploadFolders");
  }

  /* The archive read once: its checked files (each with its private copy, when unpacked) and folders */
  private static final class Unpacked {
    final Map<String, Path> files = new LinkedHashMap<String, Path>();  // path in the archive -> its copy (null: counted)
    final Set<String> folders = new TreeSet<String>();
    long bytes;  // the bytes read, not the sizes the archive declares
  }

  /*
   * Names as the folder there tells them apart: /QOpenSys by case, the rest of the IFS (/home) not, so A.sql and a.sql
   * would be one file. into: where the files' bytes go (null: only counted, for the preview).
   */
  private static Unpacked unpack(File archive, Path into, boolean exactCase) throws Exception {
    Unpacked unpacked = new Unpacked();
    Set<String> fileNames = new HashSet<String>();
    Archive.read(archive, (path, folder, size, data) -> {
      if (unpacked.files.size() + unpacked.folders.size() >= MAX_ENTRIES) {
        throw new IllegalArgumentException(archive.getName() + " has more than " + MAX_ENTRIES + " files and folders");
      }
      for (String part : path.split("/")) {  // a folder too: the upload writes a.part beside a
        if (!isFileName(part)) throw new IllegalArgumentException(path + ": a name MC's upload uses for its own temporary files (.part, .old)");
      }
      if (folder) {
        unpacked.folders.add(path);
        return;
      }
      if (!fileNames.add(exactCase ? path : path.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(path + " is twice in " + archive.getName() + (exactCase ? "" : " (the IFS there "
            + "does not tell upper from lower case)"));
      }
      Path copy = into == null ? null : into.resolve(Integer.toString(unpacked.files.size()));
      unpacked.bytes += copy(data, copy, MAX_BYTES - unpacked.bytes, archive.getName());
      unpacked.files.put(path, copy);
      if (path.contains("/")) unpacked.folders.add(path.substring(0, path.lastIndexOf('/')));
    });
    for (String f : new ArrayList<String>(unpacked.folders)) {  // every folder on the way: a link there must not lead out
      for (int slash = f.indexOf('/'); slash > 0; slash = f.indexOf('/', slash + 1)) unpacked.folders.add(f.substring(0, slash));
      if (unpacked.files.size() + unpacked.folders.size() > MAX_ENTRIES) {
        throw new IllegalArgumentException(archive.getName() + " has more than " + MAX_ENTRIES + " files and folders");
      }
    }
    for (String f : unpacked.folders) {
      if (fileNames.contains(exactCase ? f : f.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException(f + " is a file and a folder");
    }
    return unpacked;
  }

  /* The entry's bytes into its copy (or only counted), at most budget of them */
  private static long copy(InputStream in, Path copy, long budget, String archive) throws Exception {
    byte[] buffer = new byte[65536];
    long count = 0;
    try (OutputStream out = copy == null ? null : Files.newOutputStream(copy)) {
      int n;
      while ((n = in.read(buffer)) > 0) {
        count += n;
        if (count > budget) throw new IllegalArgumentException(archive + " unpacks to more than 512 MB");
        if (out != null) out.write(buffer, 0, n);
      }
    }
    return count;
  }

  /*
   * extract: the archive's files and folders into 'to', each file as a single upload is (written as .part, checked,
   * renamed, the old one kept until then; text tagged UTF-8). Read once into a private folder of this PC, everything
   * checked (names, types, sizes read, where each folder leads, what each path is there) before anything is written:
   * nothing is written for an archive that would fail, and what is uploaded is what was checked.
   */
  private static ObjectNode extract(JsonNode args, File project, Ifs ifs, File local, String to, List<String> allowed,
      ObjectNode result) throws Exception {
    if (!Archive.isArchive(local.getName())) {
      throw new IllegalArgumentException("extract takes a " + String.join(", ", Archive.KINDS) + " file: " + local.getName() + " is not one");
    }
    if (args.hasNonNull("name")) throw new IllegalArgumentException("extract keeps the archive's own names: leave out 'name'");
    boolean confirm = args.path("confirm").asBoolean(false);
    Path work = confirm ? Files.createTempDirectory("mc-extract") : null;  // owner only
    try {
      Unpacked unpacked = unpack(local, work, exactCase(to));
      checkInside(ifs, to, allowed);
      for (String f : unpacked.folders) {
        checkInside(ifs, to + "/" + f, allowed);
        if (ifs.stat(to + "/" + f) != null) throw new IllegalArgumentException(to + "/" + f + " is a file there, a folder in the archive");
      }
      List<String> replaced = new ArrayList<String>();
      for (String file : unpacked.files.keySet()) {
        String remote = to + "/" + file;
        if (ifs.stat(remote) != null) replaced.add(file);
        else if (ifs.isFolder(remote)) throw new IllegalArgumentException(remote + " is a folder there, a file in the archive");
      }
      result.put("to", to);
      result.put("extract", true);
      result.put("files", unpacked.files.size());
      result.put("folders", unpacked.folders.size());
      result.put("unpackedBytes", unpacked.bytes);
      ArrayNode sample = result.putArray("firstFiles");
      for (String file : unpacked.files.keySet()) {
        if (sample.size() >= 20) break;
        sample.add(file);
      }
      long text = unpacked.files.keySet().stream().filter(FileUpload::isText).count();
      result.put("textFiles", text + " tagged UTF-8 (CCSID 1208); the others' bytes are copied as they are");
      if (!replaced.isEmpty()) {
        result.put("replaces", replaced.size() + " existing file(s): " + String.join(", ", replaced.subList(0, Math.min(20, replaced.size())))
            + (replaced.size() > 20 ? ", ..." : ""));
      }
      if (!ifs.isFolder(to)) result.put("createsFolder", to);
      if (!confirm) {
        result.put("next", "Nothing was uploaded. Show this to the user; if they agree, call again with confirm: true.");
        return result;
      }
      ifs.mkdirs(to);
      for (String f : unpacked.folders) ifs.mkdirs(to + "/" + f);
      List<String> utf8 = new ArrayList<String>();
      for (Map.Entry<String, Path> file : unpacked.files.entrySet()) {
        String remote = to + "/" + file.getKey();
        File copy = file.getValue().toFile();
        ifs.put(copy, remote);
        long[] arrived = ifs.stat(remote);
        if (arrived == null || arrived[0] != copy.length()) {
          throw new IllegalStateException("The upload of " + remote + " did not complete: " + (arrived == null ? 0 : arrived[0])
              + " of " + copy.length() + " bytes; the files before it are in place");
        }
        if (isText(file.getKey())) utf8.add(remote);
      }
      if (!utf8.isEmpty()) ifs.utf8(utf8);
    } finally {
      if (work != null) {
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(work)) {
          for (Path copy : copies) Files.deleteIfExists(copy);
        }
        Files.deleteIfExists(work);
      }
    }
    result.put("uploaded", true);
    result.remove("next");
    log(project, result);
    return result;
  }

  /* /QOpenSys tells upper from lower case; the rest of the IFS (/home, ...) does not */
  private static boolean exactCase(String folder) {
    return folder.toUpperCase(Locale.ROOT).startsWith("/QOPENSYS/") || folder.equalsIgnoreCase("/QOpenSys");
  }

  private static void log(File project, ObjectNode result) {
    try {
      File log = new File(project, ".mc/uploads.log");
      log.getParentFile().mkdirs();
      String line = java.time.OffsetDateTime.now().withNano(0) + " " + result.path("file").asText() + " -> "
          + result.path("to").asText() + (result.path("extract").asBoolean(false) ? " extracted " + result.path("files").asText()
          + " files" : "") + " " + result.path("bytes").asText() + " bytes sha256 " + result.path("sha256").asText()
          + System.lineSeparator();
      java.nio.file.Files.write(log.toPath(), line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception ignored) { /* the upload itself went fine */ }
  }

  static String sha256(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (java.io.InputStream in = new java.io.FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
    }
    return SqlTools.hex(digest.digest()).toLowerCase(Locale.ROOT);
  }

  /* Over SSH: SFTP (SshTarget writes <name>.part, checks the size and renames) */
  static Ifs over(SshTarget ssh) {
    return new Ifs() {
      @Override public long[] stat(String path) throws Exception {
        com.jcraft.jsch.SftpATTRS a = ssh.stat(path);
        return a == null || a.isDir() ? null : new long[] { a.getSize(), a.getMTime() };
      }
      @Override public boolean isFolder(String path) throws Exception {
        com.jcraft.jsch.SftpATTRS a = ssh.stat(path);
        return a != null && a.isDir();
      }
      @Override public void put(File local, String path) throws Exception {
        ssh.upload(local, path);
      }
      @Override public void mkdirs(String path) throws Exception {
        ssh.mkdirs(path);
      }
      @Override public void utf8(String path) throws Exception {
        ssh.setUtf8(Collections.singletonList(path));
      }
      @Override public void utf8(List<String> paths) throws Exception {
        ssh.setUtf8(paths);  // 50 files per setccsid
      }
      @Override public String realPath(String path) throws Exception {
        return ssh.realPath(path);
      }
    };
  }

  /* Over the host servers (JT400): the IFS through the file server */
  static Ifs over(com.ibm.as400.access.AS400 system) {
    return new Ifs() {
      @Override public long[] stat(String path) throws Exception {
        com.ibm.as400.access.IFSFile f = new com.ibm.as400.access.IFSFile(system, path);
        return f.exists() && f.isFile() ? new long[] { f.length(), f.lastModified() / 1000 } : null;
      }
      @Override public boolean isFolder(String path) throws Exception {
        return new com.ibm.as400.access.IFSFile(system, path).isDirectory();
      }
      @Override public void put(File local, String path) throws Exception {
        com.ibm.as400.access.IFSFile folder = new com.ibm.as400.access.IFSFile(system, path.substring(0, path.lastIndexOf('/')));
        if (!folder.exists()) folder.mkdirs();
        com.ibm.as400.access.IFSFile part = new com.ibm.as400.access.IFSFile(system, path + ".part");
        try (java.io.InputStream in = new java.io.FileInputStream(local);
             java.io.OutputStream out = new com.ibm.as400.access.IFSFileOutputStream(part)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (part.length() != local.length()) {
          part.delete();
          throw new java.io.IOException("The upload is incomplete: " + part.length() + " of " + local.length() + " bytes");
        }
        /* The old file is set aside until the new one is in place: a failed rename puts it back */
        com.ibm.as400.access.IFSFile target = new com.ibm.as400.access.IFSFile(system, path);
        com.ibm.as400.access.IFSFile old = new com.ibm.as400.access.IFSFile(system, path + ".old");
        boolean replacing = target.exists();
        if (replacing) {
          if (old.exists()) old.delete();
          if (!target.renameTo(old)) throw new java.io.IOException("Could not set " + path + " aside to replace it");
        }
        if (!part.renameTo(new com.ibm.as400.access.IFSFile(system, path))) {
          if (replacing) old.renameTo(new com.ibm.as400.access.IFSFile(system, path));
          throw new java.io.IOException("Could not rename " + part.getPath() + " to " + path);
        }
        if (replacing) old.delete();
      }
      @Override public void mkdirs(String path) throws Exception {
        com.ibm.as400.access.IFSFile folder = new com.ibm.as400.access.IFSFile(system, path);
        if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Could not create the folder " + path);
      }
      @Override public void utf8(String path) throws Exception {
        new com.ibm.as400.access.IFSFile(system, path).setCCSID(1208);
      }
      @Override public String realPath(String path) throws Exception {
        com.ibm.as400.access.IFSFile f = new com.ibm.as400.access.IFSFile(system, path);
        return f.exists() ? f.getCanonicalPath() : null;
      }
    };
  }
}
