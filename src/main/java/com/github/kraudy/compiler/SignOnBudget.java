package com.github.kraudy.compiler;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * --max-sign-ons N: at most N sign-ons to the build system over the server's life. Every place that signs on takes one
 * first (take): with none left the call is refused before anything reaches the IBM i. One count, kept where the server
 * runs: MC that an SSH MCP server started on the IBM i (-Dmc.signOnsFromPc) asks that server for each of its own
 * (a JSON-RPC request on its stdout, answered on its stdin), so SSH, 5250 and MC's connections there share it, also
 * across sessions. Read-only connections to other systems are not in it.
 */
final class SignOnBudget {
  static final String METHOD = "mc/signOn";
  static final String FROM_PC = "mc.signOnsFromPc";
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final int ASK_SECONDS = 60;

  private static int max = -1;  // -1: no limit
  private static final List<String> spent = new ArrayList<String>();  // what each sign-on was for, in order
  private static volatile Consumer<String> pc;  // MC on the IBM i: sends the request to the server on the PC
  private static final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
  private static final AtomicInteger ids = new AtomicInteger();
  private static final String ID_PREFIX = "mc-signon-";
  private static final SecureRandom random = new SecureRandom();

  /* A sign-on the budget does not allow: nothing was sent to the IBM i */
  static final class Exhausted extends IllegalStateException {
    Exhausted(String message) {
      super(message);
    }
  }

  private SignOnBudget() {}

  static synchronized void limit(int n) {
    max = n;
  }

  /* MC on the IBM i started by an SSH MCP server with a budget: that server counts */
  static void askPc(Consumer<String> sender) {
    pc = sender;
  }

  static boolean fromPc() {
    return Boolean.getBoolean(FROM_PC);
  }

  static synchronized boolean limited() {
    return max >= 0;
  }

  /* One sign-on for 'what', or Exhausted */
  static void take(String what) {
    Consumer<String> sender = pc;
    if (sender != null) {
      askThePc(sender, what);
      return;
    }
    synchronized (SignOnBudget.class) {
      if (max >= 0 && spent.size() >= max) {
        throw new Exhausted("Refused: --max-sign-ons " + max + " allows no more sign-ons to the IBM i (used: "
            + String.join("; ", spent) + "). This needs one more (" + what + "), so MasterCompiler did not sign on. A new "
            + "budget needs a new server (restart it)");
      }
      spent.add(what);
    }
  }

  private static void askThePc(Consumer<String> sender, String what) {
    String id = ID_PREFIX + ids.incrementAndGet() + "-" + Long.toHexString(random.nextLong());  // not guessable
    CompletableFuture<JsonNode> answer = new CompletableFuture<JsonNode>();
    pending.put(id, answer);
    try {
      ObjectNode request = mapper.createObjectNode();
      request.put("jsonrpc", "2.0");
      request.put("id", id);
      request.put("method", METHOD);
      request.putObject("params").put("what", what);
      sender.accept(mapper.writeValueAsString(request));
      JsonNode result = answer.get(ASK_SECONDS, TimeUnit.SECONDS).path("result");
      if (!result.path("allowed").asBoolean(false)) {
        throw new Exhausted(result.path("message").asText("Refused by the server on the PC (--max-sign-ons)"));
      }
    } catch (Exhausted e) {
      throw e;
    } catch (Exception e) {
      throw new Exhausted("Refused: the server on the PC keeps the sign-on budget (--max-sign-ons) and did not answer ("
          + e.getClass().getSimpleName() + "), so " + what + " did not sign on");
    } finally {
      pending.remove(id);
    }
  }

  /* The PC's answer to askThePc: true when it was one (then it is not handled as a request) */
  static boolean answer(JsonNode message) {
    if (message.has("method") || !message.has("id")) return false;
    CompletableFuture<JsonNode> waiting = pending.remove(message.path("id").asText());
    if (waiting == null) return false;
    waiting.complete(message);
    return true;
  }

  /* On the PC: a client's message that looks like an answer to MC's sign-on request (never passed on to MC) */
  static boolean isAnswerId(JsonNode message) {
    return !message.has("method") && message.path("id").asText("").startsWith(ID_PREFIX);
  }

  /* On the PC: the answer to MC's request from the IBM i */
  static ObjectNode decide(JsonNode request) {
    ObjectNode response = mapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", request.get("id"));
    ObjectNode result = response.putObject("result");
    try {
      take("MC on the IBM i: " + request.path("params").path("what").asText("a connection"));
      result.put("allowed", true);
    } catch (Exhausted e) {
      result.put("allowed", false);
      result.put("message", e.getMessage());
    }
    return response;
  }

  /* For status, when there is a limit; null otherwise */
  static synchronized ObjectNode status() {
    if (max < 0) return null;
    ObjectNode node = mapper.createObjectNode();
    node.put("max", max);
    node.put("used", spent.size());
    node.put("left", Math.max(0, max - spent.size()));
    spent.forEach(node.putArray("spentOn")::add);
    return node;
  }

  /* Tests */
  static synchronized void reset() {
    max = -1;
    spent.clear();
    pc = null;
  }
}
