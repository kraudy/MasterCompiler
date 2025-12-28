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

    ObjectNode node = parser.getCodec().readTree(parser);

    /* Get before or after commands hooks */
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      /* Get SysCmd entry */
      Map.Entry<String, JsonNode> entry = fields.next();

      //TODO: For now, only SysCmd commands, but could be expanded to ExecCmd like CALL, etc

      /* Get command */
      SysCmd sysCmd = SysCmd.fromString(entry.getKey());
      CommandObject commandObject = new CommandObject(sysCmd);

      JsonNode paramsNode = entry.getValue();

      if (!paramsNode.isObject()) throw new IllegalArgumentException("Parameters for " + sysCmd.name() + " must be param: value");

      /* Gets list of params and values per command */
      Iterator<Map.Entry<String, JsonNode>> paramFields = paramsNode.fields();
      while (paramFields.hasNext()) {
          /* Get next entry */
          Map.Entry<String, JsonNode> paramEntry = paramFields.next();
          /* Get ParamCmd from key */
          ParamCmd paramCmd = ParamCmd.fromString(paramEntry.getKey());
          /* Get string value */
          String valueNode = Utilities.nodeToString(paramEntry.getValue());

          commandObject.put(paramCmd, valueNode);
      }

      /* Store command's strings to be later executed */
      paramList.add(commandObject);
    }

    return paramList;
  }
}