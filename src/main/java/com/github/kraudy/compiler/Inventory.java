package com.github.kraudy.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;

/*
 * inventory: what a library holds (objects with their creation time and owner, source members) and what an IFS folder
 * holds, saved by name in .mc/inventory/. clean since: that name deletes only what is new (or created again) since:
 * what hooks (CRTDUPOBJ, CRTDTAARA), called programs and dumps left, not only what MC's builds made. Folders are
 * listed where MC runs on the IBM i (over SSH), without following symbolic links.
 */
final class Inventory {
  static final String FOLDER = ".mc/inventory";
  static final int MAX_FILES = 5000;
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /* object type -> the command that deletes it (%s: LIB/NAME); a type without one is listed, not deleted */
  static final Map<String, String> DELETE = new HashMap<String, String>();
  static {
    String[][] commands = {
      { "*PGM", "DLTPGM PGM(%s)" }, { "*SRVPGM", "DLTSRVPGM SRVPGM(%s)" }, { "*MODULE", "DLTMOD MODULE(%s)" },
      { "*FILE", "DLTF FILE(%s)" }, { "*DTAARA", "DLTDTAARA DTAARA(%s)" }, { "*DTAQ", "DLTDTAQ DTAQ(%s)" },
      { "*USRSPC", "DLTUSRSPC USRSPC(%s)" }, { "*USRIDX", "DLTUSRIDX USRIDX(%s)" }, { "*USRQ", "DLTUSRQ USRQ(%s)" },
      { "*BNDDIR", "DLTBNDDIR BNDDIR(%s)" }, { "*CMD", "DLTCMD CMD(%s)" }, { "*MSGF", "DLTMSGF MSGF(%s)" },
      { "*PNLGRP", "DLTPNLGRP PNLGRP(%s)" }, { "*MENU", "DLTMNU MENU(%s)" }, { "*SQLPKG", "DLTSQLPKG SQLPKG(%s)" },
      { "*JOBD", "DLTJOBD JOBD(%s)" }, { "*OUTQ", "DLTOUTQ OUTQ(%s)" }, { "*DTADCT", "DLTDTADCT DTADCT(%s)" },
      { "*SRCF", "DLTF FILE(%s)" }, { "*QRYDFN", "DLTQRY QRY(%s)" }, { "*CRQD", "DLTCRQD CRQD(%s)" },
    };
    for (String[] c : commands) DELETE.put(c[0], c[1]);
  }

  private Inventory() {}

  /* Now: the library's objects and source members, and the folder's files when one is given */
  static ObjectNode take(Connection c, String library, String folder) throws Exception {
    ObjectNode inv = mapper.createObjectNode();
    inv.put("taken", LocalDateTime.now().withNano(0).toString());
    inv.put("library", library);
    ArrayNode objects = inv.putArray("objects");
    try (PreparedStatement ps = c.prepareStatement("SELECT OBJNAME, OBJTYPE, COALESCE(OBJATTRIBUTE, ''), OBJOWNER, "
        + "VARCHAR(OBJCREATED) FROM TABLE(QSYS2.OBJECT_STATISTICS(?, '*ALL')) X ORDER BY OBJTYPE, OBJNAME")) {
      ps.setString(1, library);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          objects.addObject().put("name", rs.getString(1).trim()).put("type", rs.getString(2).trim())
              .put("attribute", rs.getString(3).trim()).put("owner", rs.getString(4).trim()).put("created", rs.getString(5));
        }
      }
    }
    ArrayNode members = inv.putArray("members");
    try (PreparedStatement ps = c.prepareStatement("SELECT P.SYSTEM_TABLE_NAME, P.SYSTEM_TABLE_MEMBER, "
        + "VARCHAR(P.CREATE_TIMESTAMP) FROM QSYS2.SYSPARTITIONSTAT P JOIN QSYS2.SYSTABLES T ON T.SYSTEM_TABLE_SCHEMA = "
        + "P.SYSTEM_TABLE_SCHEMA AND T.SYSTEM_TABLE_NAME = P.SYSTEM_TABLE_NAME WHERE P.SYSTEM_TABLE_SCHEMA = ? AND "
        + "T.FILE_TYPE = 'S' ORDER BY 1, 2")) {
      ps.setString(1, library);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          members.addObject().put("file", rs.getString(1).trim()).put("member", rs.getString(2).trim()).put("created", rs.getString(3));
        }
      }
    }
    if (folder != null) inv.set("ifs", files(folder));
    return inv;
  }

  /* The folder's files (not following symbolic links), at most MAX_FILES; only where MC runs on the IBM i */
  static ObjectNode files(String folder) throws IOException {
    if (!IBMiDotEnv.isIBMi()) {
      throw new IllegalArgumentException("'ifs' is listed by MasterCompiler on the IBM i (the server with --ssh): this "
          + "one runs on the PC");
    }
    if (!folder.startsWith("/") || folder.trim().equals("/")) throw new IllegalArgumentException("'ifs' is an absolute folder, not /");
    Path root = Paths.get(folder).toRealPath();
    ObjectNode ifs = mapper.createObjectNode();
    ifs.put("folder", root.toString());
    ArrayNode files = ifs.putArray("files");
    try (Stream<Path> walk = Files.walk(root)) {  // walk does not follow links unless asked
      for (Path p : (Iterable<Path>) walk::iterator) {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isDirectory()) continue;
        if (files.size() >= MAX_FILES) {
          ifs.put("truncated", true);
          break;
        }
        ObjectNode f = files.addObject().put("path", p.toString()).put("size", a.size())
            .put("modified", a.lastModifiedTime().toInstant().getEpochSecond()).put("link", a.isSymbolicLink());
        try {
          f.put("owner", Files.getOwner(p, LinkOption.NOFOLLOW_LINKS).getName());
        } catch (Exception unknown) { /* no owner on this file system */ }
      }
    }
    return ifs;
  }

  /* What is in 'now' and not in 'before' (or there again with another creation time) */
  static ObjectNode diff(JsonNode before, JsonNode now) {
    ObjectNode d = mapper.createObjectNode();
    d.set("objects", added(before.path("objects"), now.path("objects"), "name", "type"));
    d.set("members", added(before.path("members"), now.path("members"), "file", "member"));
    d.set("files", added(before.path("ifs").path("files"), now.path("ifs").path("files"), "path", null));
    return d;
  }

  private static ArrayNode added(JsonNode before, JsonNode now, String key, String key2) {
    Map<String, String> created = new HashMap<String, String>();
    for (JsonNode e : before) created.put(id(e, key, key2), e.path("created").asText(""));
    ArrayNode added = mapper.createArrayNode();
    for (JsonNode e : now) {
      String was = created.get(id(e, key, key2));
      if (was == null || !was.equals(e.path("created").asText(""))) added.add(e);
    }
    return added;
  }

  private static String id(JsonNode e, String key, String key2) {
    return e.path(key).asText() + (key2 != null ? " " + e.path(key2).asText() : "");
  }

  /* The deleting command for a new object; null when its type has none */
  static String deleteCommand(String library, JsonNode object) {
    String command = DELETE.get(object.path("type").asText().toUpperCase(Locale.ROOT));
    return command == null ? null : String.format(command, library + "/" + object.path("name").asText());
  }

  /* Logical files (views, indexes) before the physical ones they are over */
  static List<JsonNode> deleteOrder(JsonNode objects) {
    List<JsonNode> first = new ArrayList<JsonNode>();
    List<JsonNode> then = new ArrayList<JsonNode>();
    for (JsonNode o : objects) ("LF".equals(o.path("attribute").asText()) ? first : then).add(o);
    first.addAll(then);
    return first;
  }

  static File file(File project, String name) {
    if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("'save' / 'since' is a name: letters, digits, - and _");
    return new File(project, FOLDER + "/" + name + ".json");
  }

  static void save(File project, String name, ObjectNode inventory) throws IOException {
    File f = file(project, name);
    f.getParentFile().mkdirs();
    mapper.writeValue(f, inventory);
  }

  static JsonNode load(File project, String name) throws IOException {
    File f = file(project, name);
    if (!f.isFile()) throw new IllegalArgumentException("No inventory named " + name + " (inventory with save: \"" + name + "\" takes one)");
    return mapper.readTree(f);
  }

  /* A file the clean may delete: in a folder whose real path is inside the inventory's, not a folder, owned by this
     user. A symbolic link is deleted itself, never what it points to (Files.delete does not follow it). */
  static boolean deletable(JsonNode file, String folder, String user) {
    try {
      Path p = Paths.get(file.path("path").asText());
      Path parent = p.getParent();
      if (parent == null || !parent.toRealPath().startsWith(Paths.get(folder).toRealPath())) return false;
      BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return !a.isDirectory() && Files.getOwner(p, LinkOption.NOFOLLOW_LINKS).getName().equalsIgnoreCase(user);
    } catch (IOException | SecurityException e) {
      return false;
    }
  }
}
