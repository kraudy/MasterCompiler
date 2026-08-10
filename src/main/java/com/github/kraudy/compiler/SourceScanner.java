package com.github.kraudy.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;

/**
 * Discovers compileable source files under a root directory.
 * Prefers the local filesystem when the path exists locally; otherwise walks IFS.
 */
public class SourceScanner {
  private static final Logger logger = LoggerFactory.getLogger(SourceScanner.class);

  private final AS400 system; // may be null when scanning local only
  private final boolean verbose;

  public SourceScanner(AS400 system, boolean verbose) {
    this.system = system;
    this.verbose = verbose;
  }

  public static final class CandidateSource {
    /** Absolute path usable for reading (local or IFS). */
    public final String absolutePath;
    /** Path relative to scan root, using forward slashes (for SRCSTMF). */
    public final String relativePath;
    public final String fileName;

    public CandidateSource(String absolutePath, String relativePath, String fileName) {
      this.absolutePath = absolutePath;
      this.relativePath = relativePath;
      this.fileName = fileName;
    }
  }

  /**
   * Walk {@code root} and return candidates whose basename parses as an MC source.
   */
  public List<CandidateSource> scan(String root) throws Exception {
    if (root == null || root.trim().isEmpty()) {
      throw new IllegalArgumentException("Scan root path is required");
    }

    String normalizedRoot = root.trim();
    // Strip trailing slashes except root "/"
    while (normalizedRoot.length() > 1
        && (normalizedRoot.endsWith("/") || normalizedRoot.endsWith("\\"))) {
      normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
    }

    File localRoot = new File(normalizedRoot);
    if (localRoot.isDirectory()) {
      if (verbose) logger.info("Scanning local directory: {}", localRoot.getAbsolutePath());
      return scanLocal(localRoot.toPath());
    }

    if (system == null) {
      throw new IllegalArgumentException(
          "Scan root not found locally and no AS400 connection for IFS: " + normalizedRoot);
    }

    IFSFile ifsRoot = new IFSFile(system, normalizedRoot);
    if (!ifsRoot.exists() || !ifsRoot.isDirectory()) {
      throw new IllegalArgumentException("Scan root not found (local or IFS): " + normalizedRoot);
    }
    if (verbose) logger.info("Scanning IFS directory: {}", normalizedRoot);
    return scanIfs(ifsRoot, normalizedRoot);
  }

  private List<CandidateSource> scanLocal(Path root) throws IOException {
    List<CandidateSource> result = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> files = walk
          .filter(Files::isRegularFile)
          .filter(p -> !shouldSkipPath(p.toString()))
          .sorted()
          .collect(Collectors.toList());

      for (Path file : files) {
        String fileName = file.getFileName().toString();
        if (!SourceNaming.parseFileName(fileName).isPresent()) {
          if (verbose) logger.info("Skipping unrecognized source name: {}", fileName);
          continue;
        }
        Path rel = root.relativize(file);
        String relativePath = rel.toString().replace('\\', '/');
        result.add(new CandidateSource(file.toAbsolutePath().toString(), relativePath, fileName));
      }
    }
    if (verbose) logger.info("Found {} source candidates under {}", result.size(), root);
    return result;
  }

  private List<CandidateSource> scanIfs(IFSFile dir, String rootPath) throws Exception {
    List<CandidateSource> result = new ArrayList<>();
    scanIfsRecursive(dir, rootPath, result);
    Collections.sort(result, (a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
    if (verbose) logger.info("Found {} source candidates under {}", result.size(), rootPath);
    return result;
  }

  private void scanIfsRecursive(IFSFile dir, String rootPath, List<CandidateSource> out)
      throws Exception {
    IFSFile[] children = dir.listFiles();
    if (children == null) return;

    for (IFSFile child : children) {
      String path = child.getPath();
      if (shouldSkipPath(path)) continue;

      if (child.isDirectory()) {
        scanIfsRecursive(child, rootPath, out);
        continue;
      }
      if (!child.isFile()) continue;

      String fileName = child.getName();
      if (!SourceNaming.parseFileName(fileName).isPresent()) {
        if (verbose) logger.info("Skipping unrecognized source name: {}", fileName);
        continue;
      }

      String relativePath = toRelative(rootPath, path);
      out.add(new CandidateSource(path, relativePath, fileName));
    }
  }

  private static String toRelative(String root, String absolute) {
    String r = root.endsWith("/") ? root : root + "/";
    if (absolute.startsWith(r)) {
      return absolute.substring(r.length());
    }
    if (absolute.startsWith(root) && absolute.length() > root.length()
        && absolute.charAt(root.length()) == '/') {
      return absolute.substring(root.length() + 1);
    }
    return absolute;
  }

  private static boolean shouldSkipPath(String path) {
    String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (lower.contains("/.git/") || lower.endsWith("/.git")) return true;
    if (lower.contains("/target/") || lower.contains("/node_modules/")) return true;
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return true;
    if (lower.endsWith(".md") || lower.endsWith(".txt")) return true;
    if (lower.endsWith(".class") || lower.endsWith(".jar")) return true;
    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".gif")) return true;
    return false;
  }
}
