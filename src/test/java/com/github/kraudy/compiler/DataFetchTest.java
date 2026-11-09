package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The --fetch answer: the source's size, modification time and CCSID. No IBM i. */
public class DataFetchTest {

  @Test
  void test_Fetched_Line_Shows_Modification_Time_And_Ccsid() throws Exception {
    long modified = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-11-03 03:53:33").getTime();

    String line = DataFetch.fetched("/home/ROBKRAUDY/artdmp1.txt", 37448, new File(".work/task6/dump1.txt"), modified, 37);

    assertTrue(line.contains("Fetched /home/ROBKRAUDY/artdmp1.txt"), line);
    assertTrue(line.contains("37448 bytes"), line);
    assertTrue(line.contains("modified 2026-11-03 03:53:33"), line);
    assertTrue(line.contains("CCSID 37"), line);
    assertTrue(line.endsWith("to .work/task6/dump1.txt"), line);
  }

  @Test
  void test_Fetched_Line_Leaves_Out_Unknown_Attributes() {
    String line = DataFetch.fetched("/x.txt", 1, new File("x.txt"), 0, 0);

    assertFalse(line.contains("modified"), line);
    assertFalse(line.contains("CCSID"), line);
    assertEquals("Fetched /x.txt (1 bytes) to x.txt", line);
  }

  @Test
  void test_FetchManyContinuesAfterAFileFails() throws Exception {
    List<String> seen = new ArrayList<String>();
    int status = DataFetch.run(Arrays.asList("/one/a.txt", "/two/b.txt", "/three/c.txt"), "out/", (path, output) -> {
      seen.add(path);
      return path.contains("two") ? 1 : 0;
    });

    assertEquals(Arrays.asList("/one/a.txt", "/two/b.txt", "/three/c.txt"), seen);
    assertEquals(1, status);
  }

  @Test
  void test_FetchOnePathKeepsItsStatus() throws Exception {
    assertEquals(7, DataFetch.run(Arrays.asList("/one/a.txt"), "out/", (path, output) -> 7));
  }
}
