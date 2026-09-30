package com.github.kraudy.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Public IBM i repositories used as test input, cloned at one pinned commit so upstream
 * changes cannot break MC's tests (move a pin on purpose, together with its assertions).
 * Only MC's output is checked against them; their code is never copied into MC.
 */
final class PinnedRepo {

  static final PinnedRepo TOBI_EXAMPLE = new PinnedRepo(
      "https://github.com/IBM/tobi-example.git", "89e49d5c812e3df76c5be9edd9a9439ce7da8373");
  static final PinnedRepo COMPANY_SYSTEM = new PinnedRepo(
      "https://github.com/IBM/ibmi-company_system.git", "37bb9128f379af40c08549b43ab8764b65fddec8");
  static final PinnedRepo NICK_LITTEN = new PinnedRepo(
      "https://github.com/NickLitten/nick.litten.public.git", "acdc3c95378dfac13043fa0ad3c484cd09e18290");
  static final PinnedRepo PUB400_TOPICS = new PinnedRepo(
      "https://github.com/MarcoDeSenas/IBMi-topics-thanks-to-pub400.git", "f358e6beffecdebb755fddff42fb48c81eccf503");

  final String url;
  final String commit;

  private PinnedRepo(String url, String commit) {
    this.url = url;
    this.commit = commit;
  }

  /* Fresh checkout of the pinned commit in a temp directory; delete it with {@link #delete} */
  Path checkout() throws Exception {
    assumeTrue(gitAvailable(), "git is not on PATH");
    Path dir = Files.createTempDirectory("mc-pinned-");
    run(dir, "git", "init", "-q");
    run(dir, "git", "fetch", "-q", "--depth", "1", url, commit);
    run(dir, "git", "checkout", "-q", "FETCH_HEAD");
    return dir;
  }

  /* Best effort: git makes its object files read-only, which Windows refuses to delete as they are */
  static void delete(Path root) {
    if (root == null || !Files.exists(root)) return;
    try {
      walkDelete(root);
    } catch (IOException ignored) {
      /* a leftover temp directory must not fail the test */
    }
  }

  private static void walkDelete(Path root) throws IOException {
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        file.toFile().setWritable(true);
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void run(Path dir, String... command) throws Exception {
    Process p = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
    assertEquals(0, p.waitFor(), "failed: " + String.join(" ", command));
  }

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
