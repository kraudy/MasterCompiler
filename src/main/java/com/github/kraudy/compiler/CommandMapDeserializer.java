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

  private void processObjectNode(ObjectNode objectNode, List<CommandObject> paramList) {
    /* Get before or after commands hooks */
    Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();

      SysCmd sysCmd = SysCmd.fromString(entry.getKey());
      CommandObject commandObject = new CommandObject(sysCmd);

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

}