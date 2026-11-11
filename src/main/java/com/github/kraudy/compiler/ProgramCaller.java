package com.github.kraudy.compiler;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Bin2;
import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400Bin8;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400PackedDecimal;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.AS400Varchar;
import com.ibm.as400.access.AS400ZonedDecimal;
import com.ibm.as400.access.CommandCall;
import com.ibm.as400.access.ProgramCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.QSYSObjectPathName;

/*
 * call_program: run a program the project builds, with typed parameters, and return what it gives back.
 *
 *   parameters: [ { "type": "char", "length": 512, "value": "..." },        a whole fixed-length buffer works too
 *                 { "type": "packed", "digits": 7, "decimals": 2, "value": 12.5 },
 *                 { "type": "zoned", "digits": 5, "decimals": 0 },          no value: output only (blanks / zero in)
 *                 { "type": "int", "bytes": 4, "value": 3 },
 *                 { "type": "varchar", "length": 2000, "value": "{\"a\":1}" } ]   2-byte length + text (RPG VARCHAR(2000)), length required;
 *                                                                              "prefix": 4 for a 4-byte length (VARCHAR(n:4))
 *
 * It runs in a host server job of its own, given the spec's library list first, so a crash or a hang
 * does not touch MC's job; a call that does not end in the time limit has that job ended.
 */
final class ProgramCaller {
  private static final int TIMEOUT_SECONDS = 120;

  private ProgramCaller() {}

  static AS400DataType type(JsonNode p, AS400 system) {
    String type = p.path("type").asText("char").toLowerCase(Locale.ROOT);
    switch (type) {
      case "char":   return new AS400Text(p.path("length").asInt(Math.max(1, p.path("value").asText("").length())), system);
      case "varchar": {
        /* the declared size of the RPG variable, in bytes of the job's CCSID: required, so the buffer is never guessed */
        if (!p.has("length")) throw new IllegalArgumentException("varchar needs \"length\": the size the program declares, e.g. VARCHAR(2000)");
        int max = p.path("length").asInt(0);
        int prefix = p.path("prefix").asInt(2);
        if (prefix != 2 && prefix != 4) throw new IllegalArgumentException("varchar \"prefix\" is 2 or 4 (bytes of the length), not " + prefix);
        if (max < 1 || (prefix == 2 && max > 65535)) throw new IllegalArgumentException("varchar \"length\" is 1 to 65535 with a 2-byte prefix, not " + max);
        return new AS400Varchar(prefix, max, 65535, system);  // 65535 + system: the system's CCSID, as AS400Text(length, system) does for char
      }
      case "packed": return new AS400PackedDecimal(p.path("digits").asInt(15), p.path("decimals").asInt(0));
      case "zoned":  return new AS400ZonedDecimal(p.path("digits").asInt(15), p.path("decimals").asInt(0));
      case "int":
        int bytes = p.path("bytes").asInt(4);
        return bytes == 2 ? new AS400Bin2() : bytes == 8 ? new AS400Bin8() : new AS400Bin4();
      default: throw new IllegalArgumentException("Parameter type must be char, varchar, packed, zoned or int, not " + type);
    }
  }

  static Object value(JsonNode p, AS400DataType type) {
    JsonNode v = p.get("value");
    if (type instanceof AS400Text || type instanceof AS400Varchar) return v == null || v.isNull() ? "" : v.asText();
    if (type instanceof AS400PackedDecimal || type instanceof AS400ZonedDecimal) {
      return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asText()).setScale(p.path("decimals").asInt(0));
    }
    long n = v == null || v.isNull() ? 0 : v.asLong();
    if (type instanceof AS400Bin2) return (short) n;
    if (type instanceof AS400Bin8) return n;
    return (int) n;
  }

  /* Steps around the program, in its job (debug_program: start debug before, collect the stops after) */
  /* in the result after() gets when the call timed out and its job was ended */
  static final String JOB_ENDED = "jobEnded";

  /* before() runs in the call's job before it, after() always runs once it is over (failed and timed out too) */
  interface Around {
    void before() throws Exception;
    void after(ObjectNode result) throws Exception;

    /* The call did not end in time: what is known about the job, before it is ended (null: nothing to add) */
    default String hung(String job) {
      return null;
    }
  }

  static ObjectNode call(AS400 system, String library, String program, JsonNode parameters, List<String> libraryList,
      String currentLibrary) throws Exception {
    return call(system, library, program, parameters, libraryList, currentLibrary, null);
  }

  static ObjectNode call(AS400 system, String library, String program, JsonNode parameters, List<String> libraryList,
      String currentLibrary, Around around) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AS400DataType[] types = new AS400DataType[parameters.size()];
    ProgramParameter[] params = new ProgramParameter[parameters.size()];
    for (int i = 0; i < parameters.size(); i++) {
      types[i] = type(parameters.get(i), system);
      byte[] in;
      try {
        in = types[i].toBytes(value(parameters.get(i), types[i]));
      } catch (IllegalArgumentException e) {
        if (!(types[i] instanceof AS400Varchar) || !(e instanceof com.ibm.as400.access.ExtendedIllegalArgumentException)
            || ((com.ibm.as400.access.ExtendedIllegalArgumentException) e).getReturnCode()
                != com.ibm.as400.access.ExtendedIllegalArgumentException.LENGTH_NOT_VALID) throw e;
        throw new IllegalArgumentException("Parameter " + (i + 1) + ": the varchar value does not fit in its length of "
            + parameters.get(i).path("length").asInt() + " bytes (" + e.getMessage() + ")");
      }
      params[i] = new ProgramParameter(in, types[i].getByteLength());
    }

    /* The host server job gets the spec's library list, then the program runs there */
    CommandCall setup = new CommandCall(system);
    StringBuilder libs = new StringBuilder();
    for (String lib : libraryList) if (!lib.equalsIgnoreCase(currentLibrary)) libs.append(lib).append(' ');
    String chglibl = "CHGLIBL LIBL(" + (libs.length() == 0 ? "*NONE" : libs.toString().trim()) + ")"
        + (currentLibrary != null ? " CURLIB(" + currentLibrary + ")" : "");
    if (!setup.run(chglibl)) {
      throw new IllegalStateException("Could not set the library list for the call: " + messages(setup.getMessageList()));
    }
    /* A host server job's printer output goes to the user's QPRTJOB by default: kept in this job, it is read back.
       If the override fails the call still runs; the answer then says the job owns no spooled file */
    setup.run("OVRPRTF FILE(*PRTF) SPLFOWN(*JOB) OVRSCOPE(*JOB)");

    ProgramCall call = new ProgramCall(system, QSYSObjectPathName.toPath(library, program, "PGM"), params);
    ExecutorService runner = Executors.newSingleThreadExecutor();
    ObjectNode result = mapper.createObjectNode();
    result.put("program", library + "/" + program);
    /* The job the program runs in (its spooled output and job log are found by this name); not worth failing the call for */
    try {
      com.ibm.as400.access.Job job = setup.getServerJob();
      result.put("job", job.getNumber() + "/" + job.getUser() + "/" + job.getName());
    } catch (Exception ignored) { /* the answer just has no job */ }
    long start = System.currentTimeMillis();
    try {
      if (around != null) {
        around.before();
        start = System.currentTimeMillis();
      }
      Future<Boolean> ran = runner.submit((java.util.concurrent.Callable<Boolean>) call::run);
      boolean ok;
      try {
        ok = ran.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        String why = null;
        try {
          if (around != null && result.has("job")) why = around.hung(result.path("job").asText());
        } catch (Exception ignored) { /* the diagnosis is a bonus */ }
        system.disconnectService(AS400.COMMAND);  // ends the job the program hangs in
        result.put(JOB_ENDED, true);
        throw new IllegalStateException("The call did not end in " + TIMEOUT_SECONDS + " s: its job"
            + (result.has("job") ? " " + result.path("job").asText() : "") + " was ended "
            + "(waiting for a record lock, a message reply or an endless loop?)" + (why == null ? "" : "\n" + why));
      }
      result.put("success", ok);
      result.put("milliseconds", System.currentTimeMillis() - start);
      ArrayNode out = result.putArray("parameters");
      for (int i = 0; i < params.length; i++) {
        byte[] data = params[i].getOutputData();
        if (data == null) {  // a failed call returns no parameters; its messages say why
          out.addNull();
          continue;
        }
        if (types[i] instanceof AS400Varchar) {  // the text the program left in it, exactly (no trailing-blank trim)
          out.add(varchar((AS400Varchar) types[i], data, system));
          continue;
        }
        Object back = types[i].toObject(data);
        if (back instanceof String) out.add(((String) back).replaceAll("\\s+$", ""));
        else if (back instanceof BigDecimal) out.add((BigDecimal) back);
        else out.add(String.valueOf(back));
      }
      ArrayNode msgs = result.putArray("messages");
      for (AS400Message m : call.getMessageList()) {
        msgs.add((m.getID() != null ? m.getID() + " " : "") + "(sev " + m.getSeverity() + ") " + m.getText());
      }
    } finally {
      runner.shutdownNow();
      if (around != null) around.after(result);
    }
    return result;
  }

  /* A varchar as the program left it: the length prefix says how much of the buffer is text. JTOpen's toObject(byte[])
     refuses the whole capacity-plus-prefix buffer, so the prefix is read here. */
  static String varchar(AS400Varchar type, byte[] data, AS400 system) {
    int prefix = type.getVarLengthSize();
    if (data.length < prefix) return "";
    long n = 0;
    for (int i = 0; i < prefix; i++) n = (n << 8) | (data[i] & 0xFF);
    int length = (int) Math.min(n, data.length - prefix);
    if (length <= 0) return "";
    AS400Text text = system != null ? new AS400Text(length, 65535, system) : new AS400Text(length, type.getCcsid());
    return (String) text.toObject(data, prefix);
  }

  private static String messages(AS400Message[] list) {
    StringBuilder text = new StringBuilder();
    for (AS400Message m : list) text.append(m.getID()).append(' ').append(m.getText()).append("; ");
    return text.toString();
  }
}
