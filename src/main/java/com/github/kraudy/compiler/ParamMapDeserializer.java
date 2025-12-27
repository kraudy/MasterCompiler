package com.github.kraudy.compiler;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/*
 * Extracts compilation command's param:value pairs from spec (Yaml file)
 */
public class ParamMapDeserializer extends JsonDeserializer<Map<ParamCmd, String>> {
  @Override
  public Map<ParamCmd, String> deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JacksonException {

    /* Get tree */
    ObjectNode node = p.getCodec().readTree(p);

    /* Params map */
    Map<ParamCmd, String> result = new HashMap<>();

    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      /* Get param */
      ParamCmd param = ParamCmd.fromString(field.getKey());

      /* Get param value */
      String valueNode = Utilities.nodeToString(field.getValue());

      result.put(param, valueNode);
    }
    
    return result;
  }
}