package com.github.kraudy.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class ParamValueTest {
  @Test
  void testEmptyConstructor() {
    ParamValue pv = new ParamValue();

    assertNull(pv.get());
    assertEquals(0, pv.getCount()); // No history
    assertTrue(pv.getHistory().isEmpty());
    assertNull(pv.getLastChange());
    assertNull(pv.getPrevious()); // count < 1
  }

  @Test
  void testPutUpdatesCurrentAndHistory() {
    ParamValue pv = new ParamValue("first");

    String previous = pv.put("second");

    assertEquals("first", previous); // Returns previous value
    assertEquals("second", pv.get());
    assertEquals(List.of("[INIT]", "first", "second"), pv.getHistory());
    assertEquals("second", pv.getLastChange());
    assertEquals("first", pv.getPrevious());
  }

  @Test
  void testMultiplePuts() {
    ParamValue pv = new ParamValue("one");

    pv.put("two");
    String prev3 = pv.put("three");
    pv.put("four");

    assertEquals("two", prev3);
    assertEquals("four", pv.get());
    assertEquals(List.of("[INIT]", "one", "two", "three", "four"), pv.getHistory());
    assertEquals("three", pv.getPrevious());
    assertEquals("one", pv.getFirst());
  }

  @Test
  void testRemove() {
    ParamValue pv = new ParamValue("value");

    pv.put("newvalue");
    String previous = pv.remove();

    assertEquals("newvalue", previous);
    assertNull(pv.get());
    assertEquals(List.of("[INIT]", "value", "newvalue", "[REMOVED]"), pv.getHistory());
    assertEquals("[REMOVED]", pv.getLastChange());
    assertEquals("newvalue", pv.getPrevious());
    assertTrue(pv.wasRemoved());
    assertTrue(pv.wasInit());
  }

  @Test
  void testPutAfterRemove() {
    ParamValue pv = new ParamValue("initial");

    pv.remove(); 
    assertNull(pv.get()); // Current must be null
    assertTrue(pv.wasRemoved()); // Last is now the new value

    String previous = pv.put("after-remove"); // put after remove

    assertEquals("[REMOVED]", previous); // Check previous [REMOVED]
    assertEquals("after-remove", pv.get()); // Assert new value
    assertEquals(List.of("[INIT]", "initial", "[REMOVED]", "after-remove"), pv.getHistory()); // Check history list
  }

  @Test
  void testEdgeCases() {
    ParamValue pv = new ParamValue();

    // Put on empty
    String prev1 = pv.put("first");
    assertNull(prev1); // No previous

    pv.put("second");
    pv.remove();

    assertNull(pv.get());
    assertTrue(pv.wasRemoved());
  }

}
