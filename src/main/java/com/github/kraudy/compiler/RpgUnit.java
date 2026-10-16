package com.github.kraudy.compiler;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;

/*
 * RPGUnit test runs in hooks (RUCALLTST): the result as JSON for the build report. RUCALLTST writes a JUnit XML
 * (XMLSTMF, Jenkins format) with every test case and its failure; MC adds XMLSTMF to a temporary stream file when
 * the hook has none, reads it back and removes it. Without the XML (an older RPGUnit, a path with variables) the
 * completion message gives the counts: "FAILURE. 3 test cases, 5 assertions, 1 failure, 0 error." (CPDA0FF when
 * all pass, CPF9897 when not); the details are then in the RPGUNIT spooled file.
 */
final class RpgUnit {

  private RpgUnit() {}

  private static final Pattern COMMAND = Pattern.compile("^\\s*(?:[A-Z0-9$#@_]+/)?RUCALLTST\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern KEYWORD = Pattern.compile("\\b(TSTPGM|XMLSTMF)\\s*\\(\\s*'*([^)']*)'*\\s*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SUMMARY = Pattern.compile("(Success|FAILURE|ERROR)\\.\\s+(\\d+) test cases?,\\s+(\\d+) assertions?,"
      + "\\s+(\\d+) failures?,\\s+(\\d+) errors?\\.");

  static boolean isTestRun(String command) {
    return command != null && COMMAND.matcher(command).find();
  }

  /* The keyword's value as written (TSTPGM, XMLSTMF), or null */
  static String keyword(String command, String name) {
    Matcher m = KEYWORD.matcher(command);
    while (m.find()) if (m.group(1).equalsIgnoreCase(name)) return m.group(2).trim();
    if (!name.equalsIgnoreCase("TSTPGM")) return null;
    /* positional: RUCALLTST LIB/TESTPGM */
    String[] words = command.trim().split("\\s+");
    return words.length > 1 && !words[1].contains("(") ? words[1].toUpperCase() : null;
  }

  /* The command with XMLSTMF('path') added; null when it has its own */
  static String withXml(String command, String path) {
    return keyword(command, "XMLSTMF") != null ? null : command.trim() + " XMLSTMF(''" + path + "'')";
  }

  /* The XML this run wrote: MC's own file, or the hook's XMLSTMF when it names a plain path (no <TSTPGM> or %Y) */
  static String xmlPath(String command, String added) {
    if (added != null) return added;
    String own = keyword(command, "XMLSTMF");
    return own != null && own.startsWith("/") && !own.contains("<") && !own.contains("%") ? own : null;
  }

  /* The run's result: from the XML when it can be read, else from RUCALLTST's completion or escape message */
  static BuildReport.TestRun result(Connection connection, String command, String xml, List<BuildReport.JoblogMessage> joblog) {
    BuildReport.TestRun run = null;
    if (xml != null) {
      try {
        String text = read(connection, xml);
        if (text != null && !text.trim().isEmpty()) run = parseXml(text);
      } catch (Exception e) {
        run = null;  // the message below still has the counts
      }
    }
    if (run == null) {
      run = new BuildReport.TestRun();
      run.source = "joblog";
      for (BuildReport.JoblogMessage m : joblog) {
        if (m.text != null && summary(m.text, run)) break;
      }
      if (run.outcome == null) run.outcome = "unknown";
      run.note = "Counts only: each failure is in the RPGUNIT spooled file of this job (spool tool, file RPGUNIT)";
    }
    run.suite = keyword(command, "TSTPGM");
    run.command = CommandStringParser.toPasteable(command);
    return run;
  }

  /* "FAILURE. 3 test cases, 5 assertions, 1 failure, 0 error." into the counts; false when the text is not that */
  static boolean summary(String text, BuildReport.TestRun run) {
    Matcher m = SUMMARY.matcher(text);
    if (!m.find()) return false;
    run.run = Integer.parseInt(m.group(2));
    run.assertions = Integer.parseInt(m.group(3));
    run.failures = Integer.parseInt(m.group(4));
    run.errors = Integer.parseInt(m.group(5));
    run.outcome = outcome(run.failures, run.errors);
    return true;
  }

  private static String outcome(int failures, int errors) {
    return errors > 0 ? "error" : failures > 0 ? "failed" : "passed";
  }

  /* RUCALLTST's JUnit XML (Jenkins 1 / 2, VS Code types): counts and each failing test case */
  static BuildReport.TestRun parseXml(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(new InputSource(new StringReader(xml.trim())));

    BuildReport.TestRun run = new BuildReport.TestRun();
    run.source = "xml";
    NodeList cases = doc.getElementsByTagName("testcase");
    int failures = 0;
    int errors = 0;
    int assertions = 0;
    List<BuildReport.TestCase> failed = new ArrayList<BuildReport.TestCase>();
    for (int i = 0; i < cases.getLength(); i++) {
      Element tc = (Element) cases.item(i);
      assertions += number(tc.getAttribute("assertions"));
      for (Node child = tc.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element)) continue;
        String kind = ((Element) child).getTagName();
        if (!kind.equals("failure") && !kind.equals("error")) continue;
        if (kind.equals("failure")) failures++;
        else errors++;
        BuildReport.TestCase t = new BuildReport.TestCase();
        t.test = tc.getAttribute("name");
        t.kind = kind;
        t.message = clip(((Element) child).getAttribute("message"), 500);
        if (kind.equals("error") && !((Element) child).getAttribute("type").isEmpty()) {
          t.message = ((Element) child).getAttribute("type") + " " + t.message;
        }
        describe((Element) child, t);
        failed.add(t);
      }
    }
    run.run = cases.getLength();
    run.assertions = assertions;
    run.failures = failures;
    run.errors = errors;
    run.outcome = outcome(failures, errors);
    run.failed = failed.isEmpty() ? null : failed;
    return run;
  }

  private static final Pattern STACK_ENTRY = Pattern.compile("^(\\S+) \\((\\S+)->(\\S+):([^)]*)\\)$");

  /*
   * Where it failed and the values compared. Jenkins types: a text body with "Callstack:" entries
   * ("test_x (TESTPGM1->TESTPGM1:31)") and, on *JENKINS2, "Expected:" / "Actual:" each followed by its value.
   * VS Code type: attributes (name, line) and child elements (callstack entries, expected, actual).
   */
  static void describe(Element failure, BuildReport.TestCase t) {
    String body = failure.getTextContent();
    String label = null;
    for (String line : body == null ? new String[0] : body.split("\\R")) {
      String text = line.trim();
      if (text.isEmpty()) continue;
      if (text.equalsIgnoreCase("Expected:") || text.equalsIgnoreCase("Actual:")) {
        label = text.substring(0, text.length() - 1).toLowerCase(java.util.Locale.ROOT);
        continue;
      }
      if (label != null) {
        if (label.equals("expected") && t.expected == null) t.expected = clip(text, 300);
        if (label.equals("actual") && t.actual == null) t.actual = clip(text, 300);
        label = null;
        continue;
      }
      if (t.at == null && STACK_ENTRY.matcher(text).matches()) t.at = text;
    }
    /* VS Code type */
    for (Node n = failure.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element)) continue;
      Element e = (Element) n;
      String name = e.getTagName().toLowerCase(java.util.Locale.ROOT);
      if (name.equals("expected") && t.expected == null) t.expected = clip(e.getTextContent(), 300);
      if (name.equals("actual") && t.actual == null) t.actual = clip(e.getTextContent(), 300);
      if (name.equals("callstack") && t.at == null) {
        for (Node c = e.getFirstChild(); c != null && t.at == null; c = c.getNextSibling()) {
          if (!(c instanceof Element) || ((Element) c).getAttribute("procedure").isEmpty()) continue;
          Element entry = (Element) c;
          t.at = entry.getAttribute("procedure") + " (" + entry.getAttribute("program") + "->" + entry.getAttribute("module")
              + ":" + entry.getAttribute("line") + ")";
        }
      }
    }
    if (t.at == null && !failure.getAttribute("line").isEmpty()) {
      t.at = failure.getAttribute("name") + " (line " + failure.getAttribute("line") + ")";
    }
  }

  private static int number(String value) {
    try {
      return value == null || value.isEmpty() ? 0 : Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String clip(String text, int max) {
    if (text == null) return null;
    String t = text.trim();
    return t.length() > max ? t.substring(0, max) + "..." : t;
  }

  /* The stream file's text: on the IBM i itself straight from the IFS, else through Db2 (QSYS2.IFS_READ) */
  private static String read(Connection connection, String path) throws Exception {
    java.io.File local = new java.io.File(path);
    if (IBMiDotEnv.isIBMi() && local.isFile()) {
      return new String(java.nio.file.Files.readAllBytes(local.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
    StringBuilder text = new StringBuilder();
    try (PreparedStatement stmt = connection.prepareStatement("SELECT LINE FROM TABLE(QSYS2.IFS_READ(PATH_NAME => ?))")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) text.append(rs.getString(1)).append('\n');
      }
    }
    return text.toString();
  }

  /* MC's temporary XML is removed after it is read */
  static void remove(Connection connection, String path) {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CALL QSYS2.QCMDEXC('RMVLNK OBJLNK(''" + path + "'')')");
    } catch (Exception ignored) { /* /tmp is cleaned anyway */ }
  }

  /* A temporary path for one run */
  static String temporaryXml() {
    return "/tmp/mc-rpgunit-" + java.util.UUID.randomUUID().toString().substring(0, 13) + ".xml";
  }
}
