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
 *                 { "type": "int", "bytes": 4, "value": 3 } ]
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
      case "packed": return new AS400PackedDecimal(p.path("digits").asInt(15), p.path("decimals").asInt(0));
      case "zoned":  return new AS400ZonedDecimal(p.path("digits").asInt(15), p.path("decimals").asInt(0));
      case "int":
        int bytes = p.path("bytes").asInt(4);
        return bytes == 2 ? new AS400Bin2() : bytes == 8 ? new AS400Bin8() : new AS400Bin4();
      default: throw new IllegalArgumentException("Parameter type must be char, packed, zoned or int, not " + type);
    }
  }

  static Object value(JsonNode p, AS400DataType type) {
    JsonNode v = p.get("value");
    if (type instanceof AS400Text) return v == null || v.isNull() ? "" : v.asText();
    if (type instanceof AS400PackedDecimal || type instanceof AS400ZonedDecimal) {
      return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asText()).setScale(p.path("decimals").asInt(0));
    }
    long n = v == null || v.isNull() ? 0 : v.asLong();
    if (type instanceof AS400Bin2) return (short) n;
    if (type instanceof AS400Bin8) return n;
    return (int) n;
  }

  static ObjectNode call(AS400 system, String library, String program, JsonNode parameters, List<String> libraryList,
      String currentLibrary) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AS400DataType[] types = new AS400DataType[parameters.size()];
    ProgramParameter[] params = new ProgramParameter[parameters.size()];
    for (int i = 0; i < parameters.size(); i++) {
      types[i] = type(parameters.get(i), system);
      params[i] = new ProgramParameter(types[i].toBytes(value(parameters.get(i), types[i])), types[i].getByteLength());
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

    ProgramCall call = new ProgramCall(system, QSYSObjectPathName.toPath(library, program, "PGM"), params);
    ExecutorService runner = Executors.newSingleThreadExecutor();
    ObjectNode result = mapper.createObjectNode();
    result.put("program", library + "/" + program);
    long start = System.currentTimeMillis();
    try {
      Future<Boolean> ran = runner.submit((java.util.concurrent.Callable<Boolean>) call::run);
      boolean ok;
      try {
        ok = ran.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        system.disconnectService(AS400.COMMAND);  // ends the job the program hangs in
        throw new IllegalStateException("The call did not end in " + TIMEOUT_SECONDS + " s: its job was ended "
            + "(waiting for a record lock, a message reply or an endless loop?)");
      }
      result.put("success", ok);
      result.put("milliseconds", System.currentTimeMillis() - start);
      ArrayNode out = result.putArray("parameters");
      for (int i = 0; i < params.length; i++) {
        Object back = types[i].toObject(params[i].getOutputData());
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
    }
    return result;
  }

  private static String messages(AS400Message[] list) {
    StringBuilder text = new StringBuilder();
    for (AS400Message m : list) text.append(m.getID()).append(' ').append(m.getText()).append("; ");
    return text.toString();
  }
}
