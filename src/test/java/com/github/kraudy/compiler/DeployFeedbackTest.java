package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

/* PASE deploy feedback: CL literals, dependency cycles, CRTCMD PGM library, global hooks, ignored messages,
   RUNSQLSTM listings, upload_file extract. No IBM i. */
class DeployFeedbackTest {
  private static final ObjectMapper mapper = new ObjectMapper();

  @TempDir
  Path dir;

  private void write(String name, String text) throws Exception {
    Path file = dir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.write(file, text.getBytes(StandardCharsets.UTF_8));
  }

  /* Each program names the other's command inside a message: words, not uses. Before, a cycle */
  @Test
  void aCommandNamedInsideAMessageIsNoDependency() throws Exception {
    write("strapp.cmd", "CMD PROMPT('Start')\n");
    write("endapp.cmd", "CMD PROMPT('End')\n");
    write("strapp.pgm.clle", "PGM\n  SNDPGMMSG MSG('Started: to stop run +\n    ENDAPP when done') MSGTYPE(*COMP)\n"
        + "  SNDPGMMSG MSG('or CALL ENDAPP')\nENDPGM\n");
    write("endapp.pgm.clle", "PGM\n  SNDPGMMSG MSG('Ended: start again with +\n    STRAPP') MSGTYPE(*COMP)\n"
        + "  STRAPP\nENDPGM\n");
    BuildSpec spec = new SpecGenerator(null, false, false).generate(dir.toString(), "curlib");
    List<String> order = new ArrayList<String>();
    for (TargetKey key : spec.targets.keySet()) order.add(key.asString());
    /* the real use (STRAPP as a statement in endapp) still orders the command first */
    assertTrue(order.indexOf("CURLIB.STRAPP.CMD.CMD") < order.indexOf("CURLIB.ENDAPP.PGM.CLLE"), order.toString());
    for (TargetKey key : spec.targets.keySet()) {
      if (key.asString().equals("CURLIB.STRAPP.PGM.CLLE")) {
        for (TargetKey child : key.getChildsList()) assertFalse(child.asString().contains("ENDAPP"), child.asString());
      }
    }
    BitSet text = DependencyAwareness.quoted("A('x''y') B");
    assertTrue(text.get(3) && text.get(6), "x and y: '' inside a literal keeps it open");
    assertFalse(text.get(10), "B is after it");
  }

  @Test
  void aCycleIsAReportWithTheCircleOnly() throws Exception {
    BuildSpec spec = new BuildSpec();
    TargetKey a = new TargetKey("CURLIB.A.PGM.CLLE");
    TargetKey b = new TargetKey("CURLIB.B.PGM.CLLE");
    TargetKey d = new TargetKey("CURLIB.D.PGM.CLLE");
    for (TargetKey key : new TargetKey[] { d, a, b }) spec.targets.put(key, new BuildSpec.TargetSpec());
    d.addChild(a);
    a.addChild(b);
    b.addChild(a);
    BuildTopoSort.DependencyCycle cycle = assertThrows(BuildTopoSort.DependencyCycle.class,
        () -> new BuildTopoSort(false, false).topologicalSort(spec));
    JsonNode report = mapper.readTree(cycle.getMessage());
    assertEquals("dependencyCycle", report.path("error").asText());
    List<String> circle = new ArrayList<String>();
    for (JsonNode n : report.path("cycle")) circle.add(n.asText());
    assertEquals(3, circle.size(), circle.toString());
    assertEquals(circle.get(0), circle.get(2));
    assertFalse(circle.contains("CURLIB.D.PGM.CLLE"), "D only reaches the cycle: " + circle);
    assertEquals(3, report.path("unresolved").size());
    assertEquals(cycle.getMessage(), cycle.toString(), "the build thread reports toString()");
    assertTrue(cycle instanceof IllegalArgumentException, "every tool answers its message as it is");
  }

  @Test
  void aCommandKeepsTheLibraryOfItsProgram() throws Exception {
    write("runapp.cmd", "CMD PROMPT('Run')\n");
    write("runapp.pgm.clle", "PGM\nENDPGM\n");
    write("other.cmd", "CMD PROMPT('Other')\n");
    write("mc-base.yaml", "targets:\n"
        + "  curlib.runapp.cmd.cmd:\n    params:\n      PGM: DEVLIB/RUNAPP\n"
        + "  curlib.other.cmd.cmd:\n    params:\n      PGM: RUNAPP\n");
    BuildSpec spec = new SpecGenerator(null, false, false).generate(dir.toString(), "curlib");
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> e : spec.targets.entrySet()) {
      if (e.getKey().asString().equals("CURLIB.RUNAPP.CMD.CMD")) {
        assertEquals("DEVLIB/RUNAPP", e.getValue().params.get(ParamCmd.PGM));
        assertTrue(e.getKey().getCommandString().contains("PGM(DEVLIB/RUNAPP)"), e.getKey().getCommandString());
        assertFalse(e.getKey().getChildsList().isEmpty(), "a curlib program target may be that library's: still built first");
      }
      if (e.getKey().asString().equals("CURLIB.OTHER.CMD.CMD")) assertEquals("RUNAPP", e.getValue().params.get(ParamCmd.PGM));
    }
  }

  @Test
  void globalHooksLeftOutKeepWhereTheJobBuilds() {
    BuildSpec spec = new BuildSpec();
    spec.before.add(new CommandObject(SysCmd.CHGLIBL).put(ParamCmd.LIBL, "DEVLIB"));
    spec.before.add(new CommandObject(SysCmd.CHGCURDIR).put(ParamCmd.DIR, "/home/u/p"));
    spec.before.add(CommandObject.raw("CPYOBJ OBJ(F) FROMLIB(PROD) OBJTYPE(*FILE) TOLIB(DEVLIB)"));
    spec.before.add(CommandObject.raw("STRJRNPF FILE(DEVLIB/F) JRN(DEVLIB/J)"));
    spec.before.add(CommandObject.raw("QSYS/CHGCURLIB CURLIB(DEVLIB)"));
    spec.after.add(CommandObject.raw("SNDMSG MSG(done) TOUSR(*REQUESTER)"));
    assertEquals(3, spec.leaveOutGlobalHooks());
    assertEquals(3, spec.before.size(), "a library change written as Cmd stays too");
    assertEquals(SysCmd.CHGLIBL, spec.before.get(0).getSystemCommand());
    assertTrue(spec.after.isEmpty());
  }

  @Test
  void ignoredMessagesAreMessageIds() throws Exception {
    CommandExecutor executor = new CommandExecutor(null, false, false, true);
    assertSame(executor, executor.ignoreMessages(Arrays.asList("cpf9898", "CPF9897")));
    assertThrows(IllegalArgumentException.class, () -> executor.ignoreMessages(Collections.singletonList("X') OR ('1")));
    write("build.yaml", "ignoreMessages: [CPF9898, CPF9897]\ntargets: {}\n");
    assertEquals(Arrays.asList("CPF9898", "CPF9897"), ProtectedLibs.list(dir.resolve("build.yaml").toFile(), "ignoreMessages"));
  }

  @Test
  void aRunSqlStmListingIsNamedAfterItsSource() {
    assertEquals("MC_DEPLOY_", CommandFailure.spoolName("mc-deploy_tables-48.sql"));
    assertEquals("MCTB48", CommandFailure.spoolName("/home/u/mc48/mctb48.table.sql"));
    assertEquals("MC_X", CommandFailure.listingName("RUNSQLSTM SRCSTMF('/home/u/mc-x.sql') COMMIT(*NONE)"));
    assertEquals("DDL", CommandFailure.listingName("RUNSQLSTM SRCFILE(LIB/QSQLSRC) SRCMBR(DDL)"));
    assertEquals("SQLHELLO", CommandFailure.listingName("CRTSQLRPGI OBJ(LIB/SQLHELLO) SRCSTMF('x.sqlrpgle')"));
    assertNull(CommandFailure.listingName("CHKOBJ OBJ(QSYS/QCMD) OBJTYPE(*PGM)"));
  }

  /* An IFS in memory: what upload_file writes and tags */
  static final class FakeIfs implements FileUpload.Ifs {
    final Map<String, Long> files = new LinkedHashMap<String, Long>();
    final Set<String> folders = new HashSet<String>();
    final List<String> utf8 = new ArrayList<String>();
    final Map<String, String> links = new HashMap<String, String>();

    @Override public long[] stat(String path) {
      Long size = files.get(path);
      return size == null ? null : new long[] { size, 0 };
    }
    @Override public boolean isFolder(String path) {
      return folders.contains(path);
    }
    @Override public void put(File local, String path) {
      files.put(path, local.length());
    }
    @Override public void mkdirs(String path) {
      for (String p = path; p.length() > 1; p = p.substring(0, p.lastIndexOf('/'))) folders.add(p);
    }
    @Override public void utf8(String path) {
      utf8.add(path);
    }
    @Override public String realPath(String path) {
      for (Map.Entry<String, String> link : links.entrySet()) {
        if (path.equals(link.getKey()) || path.startsWith(link.getKey() + "/")) return link.getValue() + path.substring(link.getKey().length());
      }
      return files.containsKey(path) || folders.contains(path) ? path : null;
    }
  }

  private File zip(String name, String... entries) throws Exception {
    File file = dir.resolve(name).toFile();
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
      for (String entry : entries) {
        zip.putNextEntry(new ZipEntry(entry));
        if (!entry.endsWith("/")) zip.write(("-- " + entry + "\n").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }

  /* A ustar header (the reader does not check the checksum) */
  private static byte[] header(String name, long size, char type) {
    byte[] h = new byte[512];
    byte[] n = name.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(n, 0, h, 0, n.length);
    byte[] s = String.format("%011o", size).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(s, 0, h, 124, s.length);
    h[156] = (byte) type;
    System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 5);
    return h;
  }

  private File tgz(String name, Object... entries) throws Exception {
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    for (int i = 0; i < entries.length; i += 2) {
      String entry = (String) entries[i];
      byte[] data = ((String) entries[i + 1]).getBytes(StandardCharsets.UTF_8);
      char type = entry.endsWith("/") ? '5' : entry.startsWith("link:") ? '2' : '0';
      tar.write(header(entry.replace("link:", ""), type == '0' ? data.length : 0, type));
      if (type == '0') {
        tar.write(data);
        tar.write(new byte[(512 - data.length % 512) % 512]);
      }
    }
    tar.write(new byte[1024]);
    File file = dir.resolve(name).toFile();
    try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(file))) {
      out.write(tar.toByteArray());
    }
    return file;
  }

  private ObjectNode args(String file, boolean confirm) {
    return mapper.createObjectNode().put("file", file).put("to", "/home/u/app").put("extract", true).put("confirm", confirm);
  }

  @Test
  void anArchiveIsUnpackedFileByFile() throws Exception {
    write("build.yaml", "uploadFolders: [/home/u/app]\ntargets: {}\n");
    zip("bundle.zip", "sql/", "sql/tables.sql", "sql/views/v1.sql", "run.sh", "lib/app.jar");
    FakeIfs ifs = new FakeIfs();
    ifs.files.put("/home/u/app/run.sh", 3L);
    ObjectNode preview = FileUpload.handle(args("bundle.zip", false), dir.toFile(), ifs);
    assertEquals(4, preview.path("files").asInt());
    assertEquals(3, preview.path("folders").asInt(), "sql, sql/views, lib");
    assertTrue(preview.path("replaces").asText().contains("run.sh"), preview.toString());
    assertEquals(1, ifs.files.size(), "a preview writes nothing");

    ObjectNode done = FileUpload.handle(args("bundle.zip", true), dir.toFile(), ifs);
    assertTrue(done.path("uploaded").asBoolean(), done.toString());
    assertTrue(ifs.files.containsKey("/home/u/app/sql/views/v1.sql") && ifs.files.containsKey("/home/u/app/lib/app.jar"));
    assertTrue(ifs.utf8.contains("/home/u/app/sql/tables.sql") && ifs.utf8.contains("/home/u/app/run.sh"));
    assertFalse(ifs.utf8.contains("/home/u/app/lib/app.jar"), "a jar's bytes are not text");

    tgz("deploy.tar.gz", "./ddl/", "", "./ddl/a.sql", "create table a (x int);", "./b.sql", "select 1;");
    FakeIfs other = new FakeIfs();
    ObjectNode tar = FileUpload.handle(args("deploy.tar.gz", true), dir.toFile(), other);
    assertEquals(2, tar.path("files").asInt(), tar.toString());
    assertEquals(Long.valueOf(23), other.files.get("/home/u/app/ddl/a.sql"));
  }

  @Test
  void anArchiveThatWouldLeaveTheFolderIsRefusedWhole() throws Exception {
    write("build.yaml", "uploadFolders: [/home/u/app]\ntargets: {}\n");
    FakeIfs ifs = new FakeIfs();
    zip("up.zip", "ok.sql", "../etc/x");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("up.zip", true), dir.toFile(), ifs));
    zip("abs.zip", "/etc/x");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("abs.zip", true), dir.toFile(), ifs));
    zip("part.zip", "a.sql.part", "a.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("part.zip", true), dir.toFile(), ifs));
    tgz("link.tgz", "a.sql", "x", "link:evil", "");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("link.tgz", true), dir.toFile(), ifs));
    /* a folder there that is a link out of uploadFolders */
    ifs.links.put("/home/u/app/sql", "/QSYS.LIB/PROD.LIB");
    zip("in.zip", "sql/tables.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("in.zip", true), dir.toFile(), ifs));
    assertTrue(ifs.files.isEmpty(), "nothing written for a refused archive: " + ifs.files);
    /* /home does not tell A.sql from a.sql; a temporary name in any case is MC's own */
    zip("case.zip", "A.sql", "a.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("case.zip", true), dir.toFile(), ifs));
    zip("upper.zip", "a.sql.PART");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("upper.zip", true), dir.toFile(), ifs));
    zip("partdir.zip", "a", "a.part/x.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("partdir.zip", true), dir.toFile(), ifs));
    /* headers without files cost too: a small archive of many pax globals is not read to its end */
    ByteArrayOutputStream globals = new ByteArrayOutputStream();
    for (int i = 0; i < 20000; i++) globals.write(header("pax", 0, 'g'));
    globals.write(new byte[1024]);
    File globalsFile = dir.resolve("globals.tgz").toFile();
    try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(globalsFile))) {
      out.write(globals.toByteArray());
    }
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("globals.tgz", false), dir.toFile(), ifs));
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 40; i++) deep.append("d").append(i).append('/');
    zip("deep.zip", deep + "x.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("deep.zip", false), dir.toFile(), ifs));
    zip("clash.zip", "Sql", "sql/x.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("clash.zip", true), dir.toFile(), ifs));
    /* a file of the archive where a folder is there, past the first files too */
    String[] many = new String[300];
    for (int i = 0; i < many.length; i++) many[i] = "f" + i + ".sql";
    zip("many.zip", many);
    FakeIfs withFolder = new FakeIfs();
    withFolder.folders.add("/home/u/app/f250.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(args("many.zip", true), dir.toFile(), withFolder));
    assertTrue(withFolder.files.isEmpty());
    assertTrue(ifs.files.isEmpty(), "nothing written for a refused archive: " + ifs.files);
    zip("plain.zip", "a.sql");
    ObjectNode named = args("plain.zip", false).put("name", "x.sql");
    assertThrows(IllegalArgumentException.class, () -> FileUpload.handle(named, dir.toFile(), ifs));
  }
}
