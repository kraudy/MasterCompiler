package com.github.kraudy.compiler;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/*
 *  Extracts commands and its param:value pairs from spec (Yaml file)
 */
public class CommandMapDeserializer extends JsonDeserializer<List<CommandObject>> {
  @Override
  public List<CommandObject> deserialize(JsonParser parser, DeserializationContext ctxt)
          throws IOException {

    /* Stores list of system commands to be executed. Mapped from hooks */
    List<CommandObject> paramList = new ArrayList<>();

    // Read as generic JsonNode first to avoid premature cast
    JsonNode rootNode = parser.getCodec().readTree(parser);

    // Handle both ObjectNode (map) and ArrayNode (list)
    if (rootNode.isObject()) {
      processObjectNode((ObjectNode) rootNode, paramList);
    } else if (rootNode.isArray()) {
      for (JsonNode element : rootNode) {
        if (!element.isObject()) throw new IllegalArgumentException("Each list element must be a command object");

        processObjectNode((ObjectNode) element, paramList);
      }
    } else {
      throw new IllegalArgumentException("Expected object or array for commands");
    }

    return paramList;
  }

  /*
   * One hook element: commands MC knows (param: value, validated), "Cmd: <any CL command>" for the rest,
   * an unknown command with param: value (sent as NAME PARAM(value) ...), and "ignore: [CPF2105, ...]"
   * for the element's commands: those escape messages are treated like MONMSG.
   */
  private void processObjectNode(ObjectNode objectNode, List<CommandObject> paramList) {
    List<String> ignore = new ArrayList<String>();
    JsonNode ignoreNode = objectNode.get("ignore");
    if (ignoreNode == null) ignoreNode = objectNode.get("Ignore");
    if (ignoreNode != null) {
      if (ignoreNode.isArray()) for (JsonNode id : ignoreNode) ignore.add(id.asText());
      else for (String id : ignoreNode.asText().split("[,\\s]+")) if (!id.isEmpty()) ignore.add(id);
    }

    /* Get before or after commands hooks */
    Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (entry.getKey().equalsIgnoreCase("ignore")) continue;

      if (entry.getKey().equalsIgnoreCase("cmd")) {
        if (!entry.getValue().isTextual()) throw new IllegalArgumentException("Cmd takes the CL command as text");
        paramList.add(CommandObject.raw(entry.getValue().asText()).ignore(ignore));
        continue;
      }

      SysCmd sysCmd;
      try {
        sysCmd = SysCmd.fromString(entry.getKey());
      } catch (IllegalArgumentException unknown) {
        paramList.add(CommandObject.raw(rawCommand(entry.getKey(), entry.getValue())).ignore(ignore));
        continue;
      }
      CommandObject commandObject = new CommandObject(sysCmd).ignore(ignore);

      JsonNode paramsNode = entry.getValue();
      if (!paramsNode.isObject()) {
        throw new IllegalArgumentException("Parameters for " + sysCmd.name() + " must be param: value");
      }

      Iterator<Map.Entry<String, JsonNode>> paramFields = paramsNode.fields();
      while (paramFields.hasNext()) {
        Map.Entry<String, JsonNode> paramEntry = paramFields.next();
        ParamCmd paramCmd = ParamCmd.fromString(paramEntry.getKey());
        String valueNode = Utilities.nodeToString(paramEntry.getValue());
        commandObject.put(paramCmd, valueNode);
      }

      paramList.add(commandObject);
    }
  }

  /* A command MC does not model, written as param: value: NAME PARAM(value) ... (values as given) */
  private static String rawCommand(String name, JsonNode params) {
    StringBuilder cmd = new StringBuilder(name.toUpperCase());
    if (params.isTextual()) return cmd.append(' ').append(params.asText()).toString();
    if (!params.isObject()) throw new IllegalArgumentException("Parameters for " + name + " must be param: value");
    Iterator<Map.Entry<String, JsonNode>> it = params.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> p = it.next();
      cmd.append(' ').append(p.getKey().toUpperCase()).append('(').append(Utilities.nodeToString(p.getValue())).append(')');
    }
    return cmd.toString();
  }

}