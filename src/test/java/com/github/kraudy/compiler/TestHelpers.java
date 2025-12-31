package com.github.kraudy.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class TestHelpers {
 
  /**
   * Loads a resource file from src/test/resources as a String.
   *
   * @param path Path relative to src/test/resources (e.g. "yaml/basic.yaml" or "sources/hello.rpgle")
   * @return The file content as UTF-8 string
   * @throws IOException If the resource cannot be found or read
   * @throws NullPointerException If the resource is not found
   */
  public static String loadResourceAsString(String path) throws IOException {
      InputStream is = TestHelpers.class.getClassLoader().getResourceAsStream(path);
      if (is == null) {
          throw new IllegalArgumentException("Resource not found: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
  }
}
