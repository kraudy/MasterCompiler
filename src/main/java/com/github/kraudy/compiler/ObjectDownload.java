package com.github.kraudy.compiler;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * --download LIB/OBJ[,LIB/OBJ...] -o <folder>: each object (its *MODULE, *PGM and *SRVPGM) goes into a save file on
 * the IBM i (SAVOBJ), the save file comes down over SFTP as <folder>/<LIB>_<OBJ>.savf and is deleted there. The save
 * file is the raw object as IBM i stores it, for offline analysis or a CI artifact; it restores with RSTOBJ.
 */
final class ObjectDownload {
  private static final Logger logger = LoggerFactory.getLogger(ObjectDownload.class);
  private static final Pattern NAME = Pattern.compile("[A-Z0-9_#@$]{1,10}");
  private static final String WORK_LIBRARY = "QGPL";

  static final class Item {
    final String library;
    final String object;

    Item(String library, String object) {
      this.library = library;
      this.object = object;
    }
  }

  private ObjectDownload() {}

  /* LIB/OBJ items, names checked (they end up in a command); throws IllegalArgumentException */
  static List<Item> parse(String selection) {
    List<Item> items = new ArrayList<Item>();
    for (String part : selection.split(",")) {
      String[] names = part.trim().toUpperCase(Locale.ROOT).split("/", -1);
      if (names.length != 2 || !NAME.matcher(names[0]).matches() || !NAME.matcher(names[1]).matches()) {
        throw new IllegalArgumentException("--download needs LIB/OBJ (names of up to 10 letters, digits, _ # @ $): " + part.trim());
      }
      items.add(new Item(names[0], names[1]));
    }
    return items;
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
          logger.info("{}/{} saved in {} ({} bytes)", item.library, item.object, saved.getPath(), saved.length());
        } catch (Exception e) {
          failed++;
          logger.error("{}/{} was not downloaded: {}", item.library, item.object, oneLine(e.getMessage()));
        }
      }
    } finally {
      ssh.close();
    }
    return failed == 0 ? 0 : 1;
  }

  private static File download(SshTarget ssh, Item item, String workLibrary, File folder) throws Exception {
    byte[] random = new byte[4];
    new SecureRandom().nextBytes(random);
    StringBuilder hex = new StringBuilder("MC");
    for (byte b : random) hex.append(String.format("%02X", b));
    String savf = hex.toString();
    String qualified = workLibrary + "/" + savf;
    File local = new File(folder, item.library + "_" + item.object + ".savf");
    ssh.exec("system " + SshTarget.quote("CRTSAVF FILE(" + qualified + ")"));
    try {
      ssh.exec("system " + SshTarget.quote("SAVOBJ OBJ(" + item.object + ") LIB(" + item.library + ") DEV(*SAVF) SAVF(" + qualified
          + ") OBJTYPE(*MODULE *PGM *SRVPGM) CLEAR(*ALL)"));
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
