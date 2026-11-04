package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.as400.access.AS400Varchar;
import org.junit.jupiter.api.Test;

/* The varchar parameter: what goes in is a length prefix plus the text; what comes back is the text the prefix says */
class ProgramCallerVarcharTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void textGoesInWithItsLengthAndComesBackExactly() {
    for (int prefix : new int[] {2, 4}) {
      AS400Varchar type = new AS400Varchar(prefix, 2000, 37);
      assertEquals(prefix + 2000, type.getByteLength());
      byte[] in = type.toBytes("{\"a\":1}  ");                 // trailing blanks are content
      assertEquals(prefix + 2000, in.length);
      assertEquals(9, in[prefix - 1] & 0xFF);                   // the prefix is the text length, not the capacity
      assertEquals("{\"a\":1}  ", ProgramCaller.varchar(type, in, null));
    }
  }

  @Test
  void emptyAndOverlongPrefixesAreSafe() {
    AS400Varchar type = new AS400Varchar(2, 10, 37);
    assertEquals("", ProgramCaller.varchar(type, type.toBytes(""), null));
    byte[] lying = type.toBytes("ABC");
    lying[0] = (byte) 0xFF;                                      // a program that left a length beyond the buffer
    lying[1] = (byte) 0xFF;
    assertEquals(10, ProgramCaller.varchar(type, lying, null).length());
  }

  @Test
  void aBufferShorterThanItsPrefixIsEmpty() {
    assertEquals("", ProgramCaller.varchar(new AS400Varchar(4, 10, 37), new byte[2], null));
  }

  @Test
  void prefixBytesCarryLengthsPastOneByte() {
    for (int[] c : new int[][] {{2, 256}, {2, 32768}, {2, 65535}, {4, 65536}}) {
      AS400Varchar type = new AS400Varchar(c[0], c[1], 37);
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < c[1]; i++) text.append((char) ('A' + i % 26));
      assertEquals(text.toString(), ProgramCaller.varchar(type, type.toBytes(text.toString()), null));
    }
  }

  @Test
  void valueTooLongForItsLengthIsRejected() {
    AS400Varchar type = new AS400Varchar(2, 3, 37);
    assertThrows(IllegalArgumentException.class, () -> type.toBytes("ABCD"));
  }

  @Test
  void parametersAreValidated() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ProgramCaller.type(MAPPER.readTree("{\"type\":\"varchar\",\"value\":\"x\"}"), null));
    assertThrows(IllegalArgumentException.class, () -> ProgramCaller.type(MAPPER.readTree("{\"type\":\"varchar\",\"length\":10,\"prefix\":3}"), null));
    assertThrows(IllegalArgumentException.class, () -> ProgramCaller.type(MAPPER.readTree("{\"type\":\"varchar\",\"length\":70000}"), null));
  }
}
