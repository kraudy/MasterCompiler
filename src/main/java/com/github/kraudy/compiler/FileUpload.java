package com.github.kraudy.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void utf8(String path) throws Exception;
    String realPath(String path) throws Exception;  // symbolic links resolved; null when it does not exist
  }

  static final long MAX_BYTES = 512L * 1024 * 1024;
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
    boolean exact = folder.toUpperCase(Locale.ROOT).startsWith("/QOPENSYS/") || folder.equalsIgnoreCase("/QOpenSys");
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
    String name = args.path("name").asText(local.getName()).trim();
    if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..") || name.endsWith(".part")
        || name.endsWith(".old")) {
      throw new IllegalArgumentException("'name' is a file name, without folders");
    }
    String path = to + "/" + name;
    /* A symbolic link on the way (the folder or the file) must not lead out of the allowed folders */
    String real = resolved(ifs, path);
    boolean stillInside = false;
    for (String f : allowed) stillInside |= within(real, resolved(ifs, f));
    if (!stillInside) {
      throw new IllegalArgumentException(path + " leads to " + real + " (a symbolic link), outside the spec's uploadFolders");
    }
    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
    boolean text = TEXT.contains(ext);

    ObjectNode result = mapper.createObjectNode();
    result.put("file", local.toPath().startsWith(root.toPath())
        ? root.toPath().relativize(local.toPath()).toString().replace('\\', '/') : local.getPath());
    result.put("localPath", local.getPath());
    result.put("bytes", local.length());
    result.put("sha256", sha256(local));
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

  private static void log(File project, ObjectNode result) {
    try {
      File log = new File(project, ".mc/uploads.log");
      log.getParentFile().mkdirs();
      String line = java.time.OffsetDateTime.now().withNano(0) + " " + result.path("file").asText() + " -> "
          + result.path("to").asText() + " " + result.path("bytes").asText() + " bytes sha256 " + result.path("sha256").asText()
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
      @Override public void utf8(String path) throws Exception {
        ssh.setUtf8(java.util.Collections.singletonList(path));
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
