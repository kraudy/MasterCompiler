package com.github.kraudy.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ProtocolFamily;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

/*
 * Unix domain sockets for --socket (Java 16 and newer; MC is built for Java 8, so the calls go by reflection).
 * The server's socket is made in a private folder, made rw for its owner only, then moved to the path: nobody else
 * can connect in between. It removes only the socket it made.
 */
final class UnixSockets {
  private UnixSockets() {}

  private static ProtocolFamily unix() {
    try {
      return StandardProtocolFamily.valueOf("UNIX");
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("--socket needs Java 16 or newer (this is Java " + System.getProperty("java.version") + ")");
    }
  }

  private static SocketAddress address(Path path) throws IOException {
    try {
      return (SocketAddress) Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", Path.class).invoke(null, path);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("--socket needs Java 16 or newer");
    }
  }

  private static <T> T open(Class<T> type) throws IOException {
    try {
      return type.cast(type.getMethod("open", ProtocolFamily.class).invoke(null, unix()));
    } catch (InvocationTargetException e) {
      throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("--socket needs Java 16 or newer");
    }
  }

  /* A listening socket and the file it published; close() removes that file only while it is still this one */
  static final class Listener {
    final ServerSocketChannel channel;
    private final Path path;
    private final Object key;  // the socket file's identity (inode)

    Listener(ServerSocketChannel channel, Path path) throws IOException {
      this.channel = channel;
      this.path = path;
      this.key = key(path);
    }

    synchronized void close() {
      try {
        channel.close();
        if (key != null && key.equals(key(path))) Files.delete(path);
      } catch (IOException gone) { /* removed already */ }
    }

    private static Object key(Path path) {
      try {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
      } catch (IOException e) {
        return null;
      }
    }
  }

  /* Listening at path; a socket file left there by a server that ended is replaced, a live one is refused */
  static Listener listen(Path path) throws IOException {
    path = path.toAbsolutePath();
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(path)) {
        throw new IllegalArgumentException("--socket " + path + " exists and is not a socket: nothing was changed");
      }
      Object stale = Listener.key(path);
      try (SocketChannel live = connect(path)) {
        throw new IllegalArgumentException("--socket " + path + ": another server listens there");
      } catch (ConnectException refused) {  // nobody listens: left by a server that ended (still that file: not one just published)
        if (stale != null && stale.equals(Listener.key(path))) Files.delete(path);
      } catch (IOException other) {
        throw new IllegalArgumentException("--socket " + path + " exists and cannot be checked (" + other.getMessage()
            + "): nothing was changed");
      }
    }
    Path folder = privateFolder(path.getParent());
    Path made = folder.resolve("s");
    ServerSocketChannel server = open(ServerSocketChannel.class);
    try {
      try {
        server.bind(address(made));
      } catch (SocketException e) {  // e.g. longer than a socket path may be (about 100 bytes)
        throw new IllegalArgumentException("--socket " + path + ": " + e.getMessage() + "; use a shorter path, e.g. /tmp/mc-<name>.sock");
      }
      try {
        Files.setPosixFilePermissions(made, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException notPosix) { /* Windows: the folder's ACL */ }
      publish(made, path);
      return new Listener(server, path);
    } catch (IOException | RuntimeException e) {
      server.close();
      throw e;
    } finally {
      Files.deleteIfExists(made);
      Files.deleteIfExists(folder);
    }
  }

  /* A link at path, never over a file there (two servers starting at once: the second is refused) */
  private static void publish(Path made, Path path) throws IOException {
    try {
      Files.createLink(path, made);
    } catch (FileAlreadyExistsException e) {
      throw new IllegalArgumentException("--socket " + path + ": another server took it meanwhile");
    } catch (UnsupportedOperationException | IOException noLinks) {  // no hard links here (e.g. Windows): a move that does not replace
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("--socket " + path + ": another server took it meanwhile");
      Files.move(made, path);
    }
  }

  /* <parent>/.mc<6 hex>, rwx for the owner only (short: a socket path is limited to about 100 bytes) */
  private static Path privateFolder(Path parent) throws IOException {
    SecureRandom random = new SecureRandom();
    for (int tries = 0; ; tries++) {
      Path folder = parent.resolve(String.format(".mc%06x", random.nextInt(1 << 24)));
      try {
        try {
          return Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
          return Files.createDirectory(folder);
        }
      } catch (FileAlreadyExistsException taken) {
        if (tries > 20) throw taken;
      }
    }
  }

  static SocketChannel connect(Path path) throws IOException {
    SocketChannel channel = open(SocketChannel.class);
    try {
      channel.connect(address(path.toAbsolutePath()));
      return channel;
    } catch (IOException e) {
      channel.close();
      throw e;
    }
  }

  /* Streams over the channel's own read and write (separate locks: a write never waits for a blocked read) */
  static InputStream in(SocketChannel channel) {
    return new InputStream() {
      @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
      }

      @Override public int read(byte[] b, int off, int len) throws IOException {
        return len == 0 ? 0 : channel.read(ByteBuffer.wrap(b, off, len));
      }
    };
  }

  static OutputStream out(SocketChannel channel) {
    return new OutputStream() {
      @Override public void write(int b) throws IOException {
        write(new byte[] { (byte) b }, 0, 1);
      }

      @Override public void write(byte[] b, int off, int len) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
        while (buffer.hasRemaining()) channel.write(buffer);
      }
    };
  }
}
