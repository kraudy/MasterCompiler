package com.github.kraudy.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/*
 * --download LIB/OBJ[+OBJ...][:TYPE...][,...] -o <folder>: each item (its objects' *MODULE, *PGM and *SRVPGM, or the
 * types named; OBJ may be a generic name ORD* or *ALL) goes into a save file on the IBM i (SAVOBJ, the objects' save
 * history left as it is), the save file comes down over SFTP as <folder>/<LIB>_<OBJ>.savf and is deleted there. The save file is the raw object as IBM i stores it, for offline
 * analysis or a CI artifact; it restores with RSTOBJ. The download_object tool saves the same way in MC's job, into
 * QTEMP (staging).
 */
final class ObjectDownload {
  private static final Logger logger = LoggerFactory.getLogger(ObjectDownload.class);
  private static final Pattern NAME = Pattern.compile("[A-Z0-9_#@$]{1,10}");
  /* What SAVOBJ's OBJ takes: a name, a generic name (ORD*) or *ALL */
  private static final Pattern OBJECT = Pattern.compile("\\*ALL|[A-Z0-9_#@$]{1,9}\\*|[A-Z0-9_#@$]{1,10}");
  /* LIB/OBJ[+OBJ...], then object types: :FILE:DTAARA (the * is optional); SAVOBJ refuses a type it does not know */
  private static final Pattern ITEM = Pattern.compile("([^/:]*)/([^/:]*)((?::\\*?[A-Z]{3,9})*)");
  static final int MAX_OBJECTS = 300;  // SAVOBJ's OBJ list
  private static final int MAX_NAME_PART = 60;  // of the file name: a longer object list is cut and told apart by a hash
  private static final String WORK_LIBRARY = "QGPL";
  static final String DEFAULT_TYPES = "*MODULE *PGM *SRVPGM";
  static final int SAVF_RECORD_BYTES = 528;

  static final class Item {
    final String library;
    final List<String> objects;
    final Set<String> types;  // sorted, without the *; empty: DEFAULT_TYPES

    Item(String library, List<String> objects, Set<String> types) {
      this.library = library;
      this.objects = objects;
      this.types = types;
    }

    /* LIB/OBJ, LIB/A+B */
    String label() {
      return library + "/" + String.join("+", objects);
    }

    String objectTypes() {
      if (types.isEmpty()) return DEFAULT_TYPES;
      StringBuilder list = new StringBuilder();
      for (String type : types) list.append(list.length() == 0 ? "*" : " *").append(type);
      return list.toString();
    }

    /*
     * LIB_OBJ.savf, LIB_A+B.savf, LIB_-ALL.savf / LIB_ORD-.savf (a * is - in a file name); with types
     * LIB_OBJ.DTAARA.FILE.savf: '.', '+' and '-' are in no name MC takes, so no other item gets this name
     */
    String fileName() {
      String part = String.join("+", objects).replace('*', '-');
      if (part.length() > MAX_NAME_PART) {
        part = part.substring(0, MAX_NAME_PART - 20) + "-" + objects.size() + "-" + sha256Prefix(part);
      }
      StringBuilder name = new StringBuilder(library).append('_').append(part);
      for (String type : types) name.append('.').append(type);
      return name.append(".savf").toString();
    }

    /* Into the save file, leaving the object's save history (last save date) as it is */
    String saveCommand(String saveFile) {
      return "SAVOBJ OBJ(" + String.join(" ", objects) + ") LIB(" + library + ") DEV(*SAVF) SAVF(" + saveFile + ") OBJTYPE(" + objectTypes()
          + ") UPDHST(*NO) CLEAR(*ALL)";
    }
  }

  private ObjectDownload() {}

  /* LIB/OBJ[:TYPE...] items, names checked (they end up in a command); throws IllegalArgumentException */
  static List<Item> parse(String selection) {
    List<Item> items = new ArrayList<Item>();
    for (String part : selection.split(",")) items.add(item(part, "--download"));
    return items;
  }

  static Item item(String text, String what) {
    Matcher m = ITEM.matcher(text.trim().toUpperCase(Locale.ROOT));
    List<String> objects = m.matches() ? new ArrayList<String>(new LinkedHashSet<String>(Arrays.asList(m.group(2).split("\\+", -1))))
        : Collections.<String>emptyList();
    boolean named = !objects.isEmpty() && objects.size() <= MAX_OBJECTS;
    for (String object : objects) named &= OBJECT.matcher(object).matches();
    if (!named || !NAME.matcher(m.group(1)).matches()) {
      throw new IllegalArgumentException(what + " needs LIB/OBJ (names of up to 10 letters, digits, _ # @ $; a generic "
          + "name ORD* or *ALL; up to " + MAX_OBJECTS + " joined by +), optionally with object types, e.g. "
          + "MYLIB/CUST:FILE:DTAARA or MYLIB/A+B: " + text.trim());
    }
    Set<String> types = new TreeSet<String>();
    for (String type : m.group(3).split(":")) if (!type.isEmpty()) types.add(type.replace("*", ""));
    return new Item(m.group(1), objects, types);
  }

  /*
   * download_object's 'object' (LIB/OBJ, LIB/ORD*, LIB/*ALL) or 'objects' (LIB/OBJ each, one library: they go into
   * one save file), and 'type' ("*FILE", or several: "*FILE *DTAARA")
   */
  static Item item(JsonNode args) {
    String types = args.path("type").asText("").trim().replaceAll("[\\s,:]+", ":");
    return item(objectText(args) + (types.isEmpty() ? "" : ":" + types), args.has("objects") ? "'objects'" : "'object'");
  }

  /* 'object' as it is, or 'objects' as LIB/A+B */
  private static String objectText(JsonNode args) {
    if (!args.has("objects")) return args.path("object").asText("");
    if (args.has("object") || !args.get("objects").isArray() || args.get("objects").size() == 0) {
      throw new IllegalArgumentException("Give 'object' (LIB/OBJ) or 'objects' (a list of LIB/OBJ in one library), not both");
    }
    String library = null;
    List<String> names = new ArrayList<String>();
    for (JsonNode entry : args.get("objects")) {
      String[] parts = entry.asText("").trim().toUpperCase(Locale.ROOT).split("/", -1);
      if (parts.length != 2) throw new IllegalArgumentException("'objects' holds LIB/OBJ names: " + entry.asText(""));
      if (library != null && !library.equals(parts[0])) {
        throw new IllegalArgumentException("'objects' are saved from one library into one save file: " + library + " and " + parts[0]);
      }
      library = parts[0];
      names.add(parts[1]);
    }
    return library + "/" + String.join("+", names);
  }

  /*
   * download_object, in MC's job: saved into a save file in QTEMP (no library of the user's is written; it goes with
   * the job), sized so that it stops at the download limit, then copied byte for byte into the stream file 'into'
   */
  static FileDownload.Staging staging(JsonNode args, String into) {
    Item item = item(args);
    String saveFile = saveFileName();
    String qualified = "QTEMP/" + saveFile;
    return new FileDownload.Staging(Arrays.asList(
        "CRTSAVF FILE(" + qualified + ") MAXRCDS(" + FileDownload.MAX_BYTES / SAVF_RECORD_BYTES + ")",
        item.saveCommand(qualified),
        "CPYTOSTMF FROMMBR('/QSYS.LIB/QTEMP.LIB/" + saveFile + ".FILE') TOSTMF('" + FileDownload.clText(into) + "') STMFOPT(*REPLACE)"),
        "DLTF FILE(" + qualified + ")",
        "object", item.label() + " " + item.objectTypes(), item.fileName(),
        "Saved on the IBM i with SAVOBJ ... UPDHST(*NO) (the object's save history is unchanged) into a save file in "
        + "QTEMP, copied into a stream file and downloaded byte for byte; both copies were deleted. RSTOBJ restores it "
        + "(on the IBM i: CPYFRMSTMF into a save file first)");
  }

  /* The first 12 hex digits of the text's SHA-256: tells two long object lists apart in a file name */
  static String sha256Prefix(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /* MC plus 8 random hex digits: a save file name no one else uses */
  static String saveFileName() {
    byte[] random = new byte[4];
    new SecureRandom().nextBytes(random);
    StringBuilder hex = new StringBuilder("MC");
    for (byte b : random) hex.append(String.format("%02X", b));
    return hex.toString();
  }

  /* Exit code: 0 when every object came down */
  static int run(ArgParser parser) throws Exception {
    List<Item> items = parse(parser.getDownload());
    SignOnGuard.Target target = MasterCompiler.signOnTarget(parser, new File("."));
    if (target == null) throw new IllegalArgumentException("--download brings objects to this PC over SSH; on the IBM i itself use SAVOBJ");
    Code4iConfig code4i = MasterCompiler.code4i(parser);
    SshTarget.Port port = SshTarget.Port.of(parser, code4i);
    String key = code4i != null ? code4i.privateKeyPath : null;
    String workLibrary = parser.getCurlib() != null ? parser.getCurlib().trim().toUpperCase(Locale.ROOT)
        : code4i != null && code4i.currentLibrary != null ? code4i.currentLibrary.toUpperCase(Locale.ROOT) : WORK_LIBRARY;
    if (!NAME.matcher(workLibrary).matches()) throw new IllegalArgumentException("--curlib is not a library name: " + workLibrary);
    File folder = new File(parser.getOutputFile() != null ? parser.getOutputFile() : ".");

    SshTarget ssh;
    try {
      ssh = SignOnGuard.signOn(target, () -> SshTarget.connect(target.host, port.number, target.user, target.password, key));
    } catch (SignOnGuard.Blocked blocked) {
      logger.error(blocked.getMessage());
      return 1;
    }
    int failed = 0;
    try {
      for (Item item : items) {
        try {
          File saved = download(ssh, item, workLibrary, folder);
          logger.info("{} saved in {} ({} bytes)", item.label(), saved.getPath(), saved.length());
        } catch (Exception e) {
          failed++;
          logger.error("{} was not downloaded: {}", item.label(), oneLine(e.getMessage()));
        }
      }
    } finally {
      ssh.close();
    }
    return failed == 0 ? 0 : 1;
  }

  private static File download(SshTarget ssh, Item item, String workLibrary, File folder) throws Exception {
    String savf = saveFileName();
    String qualified = workLibrary + "/" + savf;
    File local = new File(folder, item.fileName());
    ssh.exec("system " + SshTarget.quote("CRTSAVF FILE(" + qualified + ")"));
    try {
      ssh.exec("system " + SshTarget.quote(item.saveCommand(qualified)));
      ssh.download("/QSYS.LIB/" + workLibrary + ".LIB/" + savf + ".FILE", local);
    } finally {
      ssh.execQuiet("system " + SshTarget.quote("DLTF FILE(" + qualified + ")"));
    }
    return local;
  }

  private static String oneLine(String message) {
    if (message == null) return "no message";
    int end = message.indexOf('\n');
    return end < 0 ? message : message.substring(0, end) + " | " + message.substring(end + 1).trim().replace('\n', ' ');
  }
}
