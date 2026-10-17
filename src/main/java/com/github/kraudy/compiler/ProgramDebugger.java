package com.github.kraudy.compiler;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.CommandCall;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileOutputStream;
import com.ibm.as400.access.ProgramCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.QSYSObjectPathName;

/*
 * debug_program: call_program with breakpoints. MCDBG (src/main/resources/debug/MCDBG.c, compiled once in the current
 * library) starts a source debug session in the program's job, sets the breakpoints and, as the program-stop handler,
 * evaluates the variables at each stop and lets the program go on. Everything runs in the one host server job of
 * call_program: MCDBG 'S', the program, MCDBG 'E'.
 */
final class ProgramDebugger {
  static final String HELPER = "MCDBG";
  static final String VERSION = "MasterCompiler debug helper v3";
  private static final String LINES = "[\\n\\u0085\\r]+";  // ILE C writes its newline as NEL
  static final int MAX_BREAKS = 20;
  static final int MAX_VARS = 20;
  static final int RESULT_SIZE = 64000;
  private static final Pattern VARIABLE = Pattern.compile("[A-Z$#@][A-Z0-9_$#@]*(\\.[A-Z$#@][A-Z0-9_$#@]*)*(\\(\\d{1,5}\\))?",
      Pattern.CASE_INSENSITIVE);
  private static final ObjectMapper mapper = new ObjectMapper();

  private ProgramDebugger() {}

  /* breakpoints: [10, 25] or [{"line": 10, "module": "MOD1"}]; "MOD1:10,25;MOD2:5" for MCDBG */
  static String lines(JsonNode breakpoints) {
    if (!breakpoints.isArray() || breakpoints.size() == 0) throw new IllegalArgumentException("Give 'breakpoints': source lines, e.g. [12, 30]");
    if (breakpoints.size() > MAX_BREAKS) throw new IllegalArgumentException("Up to " + MAX_BREAKS + " breakpoints");
    java.util.Map<String, List<Integer>> byModule = new java.util.LinkedHashMap<String, List<Integer>>();
    for (JsonNode b : breakpoints) {
      int line = b.isObject() ? b.path("line").asInt(0) : b.asInt(0);
      String module = b.isObject() ? b.path("module").asText("").trim().toUpperCase(Locale.ROOT) : "";
      if (line < 1) throw new IllegalArgumentException("A breakpoint is a source line number from 1: " + b);
      if (!module.isEmpty() && !module.matches("[A-Z$#@][A-Z0-9_$#@]{0,9}")) throw new IllegalArgumentException("Not a module name: " + module);
      byModule.computeIfAbsent(module, k -> new ArrayList<Integer>()).add(line);
    }
    StringBuilder spec = new StringBuilder();
    for (java.util.Map.Entry<String, List<Integer>> e : byModule.entrySet()) {
      if (spec.length() > 0) spec.append(';');
      if (!e.getKey().isEmpty()) spec.append(e.getKey()).append(':');
      for (int i = 0; i < e.getValue().size(); i++) spec.append(i > 0 ? "," : "").append(e.getValue().get(i));
    }
    return spec.toString();
  }

  /* Variable names only (DS.SUBFIELD and ARR(3) too): EVAL takes debug language, so nothing else goes through */
  static List<String> variables(JsonNode list) {
    List<String> names = new ArrayList<String>();
    if (!list.isArray() || list.size() == 0) throw new IllegalArgumentException("Give 'variables': the names to show at each stop");
    if (list.size() > MAX_VARS) throw new IllegalArgumentException("Up to " + MAX_VARS + " variables");
    for (JsonNode v : list) {
      String name = v.asText("").trim();
      if (!VARIABLE.matcher(name).matches()) throw new IllegalArgumentException("Not a variable name: " + name);
      names.add(name.toUpperCase(Locale.ROOT));
    }
    return names;
  }

  /* MCDBG in the library, created (or replaced when its version text differs) from the jar's source */
  static String ensureHelper(AS400 system, Connection connection, String library) throws Exception {
    try (PreparedStatement stmt = connection.prepareStatement("SELECT OBJTEXT FROM TABLE(QSYS2.OBJECT_STATISTICS(?, '*PGM', ?))")) {
      stmt.setString(1, library);
      stmt.setString(2, HELPER);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next() && VERSION.equals(String.valueOf(rs.getString(1)).trim())) return "present";
      }
    }
    /* The source goes in a new folder only this user can open: nobody else can swap it before CRTCMOD reads it */
    String folder = "/tmp/mcdbg-" + Long.toHexString(new SecureRandom().nextLong() & Long.MAX_VALUE);
    IFSFile dir = new IFSFile(system, folder);
    if (!dir.mkdir()) throw new IllegalStateException("Could not create " + folder + " for the debug helper's source");
    String source = folder + "/MCDBG.c";
    try {
      run(system, "CHGAUT OBJ('" + folder + "') USER(*PUBLIC) DTAAUT(*EXCLUDE) OBJAUT(*NONE)");
      String[] inside = dir.list();
      if (inside == null || inside.length > 0) throw new IllegalStateException(folder + " is not empty: the debug helper was not created");
      writeSource(system, source);
      run(system, "CRTCMOD MODULE(QTEMP/" + HELPER + ") SRCSTMF('" + source + "') DBGVIEW(*NONE) OPTIMIZE(10)");
      run(system, "CRTPGM PGM(" + library + "/" + HELPER + ") MODULE(QTEMP/" + HELPER + ") ACTGRP(QTEDBGAG) "
          + "BNDSRVPGM((QSYS/QTEDBGS)) REPLACE(*YES) TEXT('" + VERSION + "')");
    } finally {
      try { new IFSFile(system, source).delete(); dir.delete(); } catch (Exception ignored) { /* /tmp is cleaned anyway */ }
    }
    return "created in " + library;
  }

  private static void writeSource(AS400 system, String source) throws Exception {
    try (InputStream in = ProgramDebugger.class.getResourceAsStream("/debug/MCDBG.c");
         OutputStream out = new IFSFileOutputStream(system, source, IFSFileOutputStream.SHARE_NONE, false, 1208)) {
      byte[] bytes = new byte[8192];
      for (int n; (n = in.read(bytes)) > 0;) out.write(bytes, 0, n);
    }
  }

  private static void run(AS400 system, String command) throws Exception {
    CommandCall call = new CommandCall(system);
    if (!call.run(command)) {
      StringBuilder why = new StringBuilder();
      for (AS400Message m : call.getMessageList()) why.append(m.getID()).append(' ').append(m.getText()).append("; ");
      throw new IllegalStateException("Could not create the debug helper (" + command.split(" ")[0] + "): " + why);
    }
  }

  /* The steps around the program: start debug and set the breakpoints before, end it and read the stops after */
  static ProgramCaller.Around around(AS400 system, String library, String program, String lines, List<String> variables,
      int maxHits) {
    return new ProgramCaller.Around() {
      @Override
      public void before() throws Exception {
        /* an unmonitored exception gets its default reply instead of waiting in MSGW; after() puts the job's back */
        CommandCall chgjob = new CommandCall(system);
        inquiryReply = chgjob.getServerJob().getInquiryMessageReply();
        chgjob.run("CHGJOB INQMSGRPY(*DFT)");
        /* The job in debug mode first (the APIs need it). No source display: this job has no screen. UPDPROD(*YES):
           files in *PROD libraries (development ones too) update as they do in call_program; protectedLibs is the fence */
        CommandCall strdbg = new CommandCall(system);
        if (!strdbg.run("STRDBG UPDPROD(*YES) DSPMODSRC(*NO)")) {
          new CommandCall(system).run("ENDDBG");  // still in debug from an earlier run: once more
          if (!new CommandCall(system).run("STRDBG UPDPROD(*YES) DSPMODSRC(*NO)")) {
            StringBuilder why = new StringBuilder();
            for (AS400Message m : strdbg.getMessageList()) why.append(m.getID()).append(' ').append(m.getText()).append("; ");
            throw new IllegalStateException("Could not start debug (STRDBG): " + why);
          }
        }
        String qualified = String.format("%-10s%-10s", program, library);
        ProgramParameter[] params = {
            new ProgramParameter(text(1, "S", system)),
            new ProgramParameter(text(20, qualified, system)),
            new ProgramParameter(text(2000, lines, system)),
            new ProgramParameter(text(1000, String.join(" ", variables), system)),
            new ProgramParameter(text(5, String.valueOf(maxHits), system)),
            new ProgramParameter(2000) };
        ProgramCall start = new ProgramCall(system, QSYSObjectPathName.toPath(library, HELPER, "PGM"), params);
        if (!start.run()) throw new IllegalStateException("The debug helper did not start: " + messages(start));
        String set = (String) new AS400Text(2000, system).toObject(params[5].getOutputData());
        startResult = set.trim();
      }

      private String startResult = "";
      private String inquiryReply;

      /* The job as call_program finds it: out of debug, its own inquiry reply */
      private void restore() throws Exception {
        new CommandCall(system).run("ENDDBG");
        if (inquiryReply != null) new CommandCall(system).run("CHGJOB INQMSGRPY(" + inquiryReply.trim() + ")");
      }

      @Override
      public void after(ObjectNode result) throws Exception {
        ArrayNode breaks = result.putArray("breakpoints");
        ArrayNode notes = mapper.createArrayNode();
        for (String line : startResult.split(LINES)) {
          String l = line.trim();
          if (l.startsWith("BREAK ")) {
            String[] p = l.split("\\s+");
            breaks.addObject().put("module", p[1]).put("line", Integer.parseInt(p[2]));
          } else if (!l.isEmpty() && !result.has("success")) {
            notes.add(l.replaceFirst("^(ERR|NOTE) ", ""));  // otherwise the end's text has them too
          }
        }
        if (result.has(ProgramCaller.JOB_ENDED)) return;  // the debug session and its stops ended with the job

        ProgramParameter[] params = { new ProgramParameter(text(1, "E", system)), new ProgramParameter(RESULT_SIZE) };
        ProgramCall end = new ProgramCall(system, QSYSObjectPathName.toPath(library, HELPER, "PGM"), params);
        if (!end.run()) {
          notes.add("The debug session did not end cleanly: " + messages(end));
        }
        restore();
        String text = (String) new AS400Text(RESULT_SIZE, system).toObject(params[1].getOutputData());
        ArrayNode stops = result.putArray("stops");
        ObjectNode stop = null;
        for (String line : text.split(LINES)) {
          String l = line.replaceAll("\\s+$", "");
          if (l.startsWith("STOP ")) {
            String[] p = l.split("\\s+");
            stop = stops.addObject();
            stop.put("module", p[1]);
            stop.put("line", Integer.parseInt(p[2]));
            stop.put("hit", Integer.parseInt(p[3]));
            stop.putObject("variables");
          } else if (l.startsWith("VAR ") && stop != null) {
            String nv = l.substring(4);
            int eq = nv.indexOf('=');
            if (eq > 0) ((ObjectNode) stop.get("variables")).put(nv.substring(0, eq).trim(), nv.substring(eq + 1));
          } else if (l.startsWith("NOTE ") || l.startsWith("ERR ")) {
            notes.add(l.substring(l.indexOf(' ') + 1).replaceAll("\\s{2,}", " "));
          }
        }
        if (notes.size() > 0) result.set("debugNotes", notes);
      }
    };
  }

  private static byte[] text(int length, String value, AS400 system) {
    return new AS400Text(length, system).toBytes(value);
  }

  private static String messages(ProgramCall call) {
    StringBuilder text = new StringBuilder();
    for (AS400Message m : call.getMessageList()) text.append(m.getID()).append(' ').append(m.getText()).append("; ");
    return text.toString();
  }

  /* Display programs wait for a key at a READ: no stop handler runs there. Those go through screen_open */
  static boolean usesDisplay(List<String> sourceLines) {
    Pattern display = Pattern.compile("\\bWORKSTN\\b|^\\s*(\\w+:\\s*)?DCLF\\b|\\bSNDRCVF\\b|\\bEXFMT\\b", Pattern.CASE_INSENSITIVE);
    for (String line : sourceLines) {
      String code = line.length() > 6 && line.charAt(6) == '*' ? "" : line.replaceFirst("//.*", "");  // not comments
      if (display.matcher(code).find()) return true;
    }
    return false;
  }

  static String note() {
    return "Variables show the program's memory at each stop: data it read, production data too. Nothing masks them.";
  }
}
