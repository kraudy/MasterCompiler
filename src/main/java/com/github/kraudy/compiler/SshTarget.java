package com.github.kraudy.compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/*
 * --ssh: the IBM i over SSH, the way Code for IBM i reaches it (port 22 instead of the host servers).
 * Authentication: the connection's private key, else ~/.ssh/id_ed25519 / id_ecdsa / id_rsa, else IBMI_PASSWORD.
 * Host keys follow OpenSSH's accept-new: an unknown host is trusted and recorded in ~/.ssh/known_hosts,
 * a changed key is refused.
 */
/* The SFTP methods are synchronized: the proxy's call thread syncs while its response thread downloads */
public final class SshTarget implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(SshTarget.class);

  private final Session session;
  private ChannelSftp sftp;
  private final Set<String> knownDirs = new HashSet<String>();  // created or seen: no need to check again

  private SshTarget(Session session) {
    this.session = session;
  }

  public static SshTarget connect(String host, int port, String user, String password, String privateKeyPath) throws Exception {
    JSch jsch = new JSch();
    File sshDir = new File(System.getProperty("user.home"), ".ssh");
    File knownHosts = new File(sshDir, "known_hosts");
    if (!knownHosts.isFile()) {
      sshDir.mkdirs();
      knownHosts.createNewFile();
    }
    jsch.setKnownHosts(knownHosts.getPath());

    int keys = 0;
    for (String key : keyCandidates(sshDir, privateKeyPath)) {
      try {
        jsch.addIdentity(key);
        keys++;
      } catch (Exception e) {
        logger.info("SSH key not usable, skipped: {} ({})", key, e.getMessage());
      }
    }
    boolean hasPassword = password != null && !password.isEmpty();
    if (keys == 0 && !hasPassword) {
      throw new IllegalArgumentException("No SSH key found and IBMI_PASSWORD is not set: provide one of them for --ssh");
    }

    Session session = jsch.getSession(user, host, port);
    if (hasPassword) session.setPassword(password);
    session.setConfig("StrictHostKeyChecking", "ask");
    session.setConfig("FingerprintHash", "SHA256");
    session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
    session.setUserInfo(new AcceptNewHosts(password));
    /* A link that stops answering (VPN drop, stuck firewall) ends the session after ~60 s instead of hanging */
    session.setServerAliveInterval(15_000);
    session.setServerAliveCountMax(3);
    session.connect(30_000);
    logger.info("SSH connected to {}@{}:{}", user, host, port);
    return new SshTarget(session);
  }

  private static List<String> keyCandidates(File sshDir, String explicit) {
    List<String> keys = new ArrayList<String>();
    if (explicit != null && new File(explicit).isFile()) keys.add(explicit);
    for (String name : new String[] { "id_ed25519", "id_ecdsa", "id_rsa" }) {
      File key = new File(sshDir, name);
      if (key.isFile() && !keys.contains(key.getPath())) keys.add(key.getPath());
    }
    return keys;
  }

  /* Run a PASE command, return its stdout; non-zero exit fails with its stderr; a hung command fails too */
  public String exec(String command) throws Exception {
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    channel.setCommand(command);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    channel.setOutputStream(out);
    channel.setErrStream(err);
    channel.connect(30_000);
    long limit = seconds("MC_REMOTE_TIMEOUT", 600) * 1000L;
    long start = System.currentTimeMillis();
    while (!channel.isClosed()) {
      if (System.currentTimeMillis() - start > limit) {
        channel.disconnect();
        throw new IllegalStateException("Remote command did not finish in " + limit / 1000 + " s (MC_REMOTE_TIMEOUT): "
            + command);
      }
      Thread.sleep(50);
    }
    int status = channel.getExitStatus();
    channel.disconnect();
    if (status != 0) {
      throw new IllegalStateException("Remote command failed (" + status + "): " + command + "\n"
          + new String(err.toByteArray(), StandardCharsets.UTF_8).trim());
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  /* A long-running remote process whose stdin/stdout the caller drives (the remote MCP server) */
  public ChannelExec start(String command) throws Exception {
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    channel.setCommand(command);
    return channel;
  }

  /* Size and modification time of a remote file, or null when it does not exist */
  public synchronized SftpATTRS stat(String remote) throws Exception {
    try {
      return sftp().stat(remote);
    } catch (SftpException e) {
      if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
      throw e;
    }
  }

  /* Upload, creating parent directories, and keep the local modification time (to skip it next time) */
  public synchronized void upload(File local, String remote) throws Exception {
    watchedPut(new java.io.FileInputStream(local), local.length(), remote, "Upload of " + local.getName());
    sftp().setMtime(remote, (int) (local.lastModified() / 1000));
  }

  public synchronized void upload(byte[] data, String remote) throws Exception {
    watchedPut(new java.io.ByteArrayInputStream(data), data.length, remote,
        "Upload of " + remote.substring(remote.lastIndexOf('/') + 1));
  }

  /*
   * Every upload: progress in the log (large files), aborted when no byte moves for MC_STALL_SECONDS
   * (default 60), written to <name>.part, checked for its full size, then renamed. A cut or stuck
   * transfer never leaves a truncated file under the real name, and its error says how far it got.
   */
  private void watchedPut(java.io.InputStream in, long size, String remote, String what) throws Exception {
    mkdirs(remote.substring(0, remote.lastIndexOf('/')));
    String part = remote + ".part";
    long stallMs = seconds("MC_STALL_SECONDS", 60) * 1000L;
    Transfer transfer = new Transfer(what, size);
    ChannelSftp channel = sftp();
    java.util.concurrent.ScheduledFuture<?> watch = WATCHDOG.scheduleAtFixedRate(() -> {
      if (System.currentTimeMillis() - transfer.lastMove > stallMs) {
        transfer.stalled = true;
        channel.disconnect();  // makes the blocked put() fail
      }
    }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    try {
      channel.put(in, part, transfer, ChannelSftp.OVERWRITE);
    } catch (Exception e) {
      if (transfer.stalled) {
        throw new java.io.IOException(what + " stalled: no data moved for " + stallMs / 1000 + " s after "
            + mb(transfer.done) + " of " + mb(size) + ". The connection to the IBM i is stuck (network, VPN or "
            + "firewall); start the server again. MC_STALL_SECONDS changes the limit.");
      }
      throw new java.io.IOException(what + " failed after " + mb(transfer.done) + " of " + mb(size) + ": " + e.getMessage(), e);
    } finally {
      watch.cancel(false);
      in.close();
    }
    SftpATTRS arrived = sftp().stat(part);
    if (arrived.getSize() != size) {
      try { sftp().rm(part); } catch (SftpException ignored) { /* already gone */ }
      throw new java.io.IOException(what + " is incomplete on the IBM i: " + arrived.getSize() + " of " + size
          + " bytes arrived. Start the server again to retry.");
    }
    try { sftp().rm(remote); } catch (SftpException missing) { /* first upload */ }
    sftp().rename(part, remote);
    if (size >= LOG_FROM) logger.info("{}: done, {} in {} s", what, mb(size), (System.currentTimeMillis() - transfer.start) / 1000);
  }

  private static final long LOG_FROM = 1024 * 1024;  // progress lines for files of 1 MB and more
  private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mc-ssh-watchdog");
        t.setDaemon(true);
        return t;
      });

  /* Bytes moved so far; a progress line every 5 s for large files */
  private static final class Transfer implements com.jcraft.jsch.SftpProgressMonitor {
    final String what;
    final long size;
    final long start = System.currentTimeMillis();
    volatile long done;
    volatile long lastMove = start;
    volatile boolean stalled;
    private long lastLog = start;

    Transfer(String what, long size) {
      this.what = what;
      this.size = size;
    }

    @Override public void init(int op, String src, String dest, long max) {
      if (size >= LOG_FROM) logger.info("{}: starting, {}", what, mb(size));
    }

    @Override public boolean count(long bytes) {
      done += bytes;
      long now = System.currentTimeMillis();
      lastMove = now;
      if (size >= LOG_FROM && now - lastLog >= 5000) {
        lastLog = now;
        long kbPerSecond = done / 1024 / Math.max(1, (now - start) / 1000);
        logger.info("{}: {} of {} ({}%, {} KB/s)", what, mb(done), mb(size), done * 100 / Math.max(1, size), kbPerSecond);
      }
      return !stalled;
    }

    @Override public void end() { }
  }

  static String mb(long bytes) {
    return bytes < 1048576 ? (bytes + 1023) / 1024 + " KB"
        : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
  }

  private static int seconds(String env, int fallback) {
    try {
      return Integer.parseInt(System.getenv(env).trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  public synchronized void mkdirs(String dir) throws Exception {
    if (dir.isEmpty() || knownDirs.contains(dir)) return;
    if (stat(dir) == null) {
      mkdirs(dir.substring(0, Math.max(0, dir.lastIndexOf('/'))));
      sftp().mkdir(dir);
    }
    knownDirs.add(dir);
  }

  /* Download, keeping the remote modification time (so the next sync does not upload it back) */
  public synchronized void download(String remote, File local) throws Exception {
    if (local.getParentFile() != null) local.getParentFile().mkdirs();
    sftp().get(remote, local.getPath());
    SftpATTRS attrs = sftp().stat(remote);
    local.setLastModified(attrs.getMTime() * 1000L);
  }

  /*
   * Every file under a remote directory as relative path -> {size, mtime}: one listing per directory
   * instead of one round trip per file (that matters at 150+ ms per round trip). Empty when it does not exist.
   */
  public synchronized Map<String, long[]> listTree(String dir) throws Exception {
    Map<String, long[]> files = new HashMap<String, long[]>();
    if (stat(dir) == null) return files;
    listInto(dir, "", files);
    return files;
  }

  @SuppressWarnings("unchecked")
  private void listInto(String dir, String prefix, Map<String, long[]> files) throws Exception {
    knownDirs.add(dir);
    for (ChannelSftp.LsEntry entry : (Vector<ChannelSftp.LsEntry>) sftp().ls(dir)) {
      String name = entry.getFilename();
      if (name.equals(".") || name.equals("..")) continue;
      SftpATTRS attrs = entry.getAttrs();
      if (attrs.isDir()) listInto(dir + "/" + name, prefix + name + "/", files);
      else files.put(prefix + name, new long[] { attrs.getSize(), attrs.getMTime() });
    }
  }

  /* Tag stream files as UTF-8 so the compilers read them right (SFTP leaves the default CCSID) */
  public void setUtf8(List<String> remotePaths) throws Exception {
    for (int i = 0; i < remotePaths.size(); i += 50) {
      String setccsid = System.getenv("MC_REMOTE_SETCCSID");  // other PASE layouts
      StringBuilder command = new StringBuilder(setccsid != null && !setccsid.isEmpty() ? setccsid : "/QOpenSys/usr/bin/setccsid")
          .append(" 1208");
      for (String path : remotePaths.subList(i, Math.min(remotePaths.size(), i + 50))) {
        command.append(' ').append(quote(path));
      }
      exec(command.toString());
    }
  }

  /* Single-quoted for the PASE shell */
  public static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private ChannelSftp sftp() throws Exception {
    if (sftp == null || sftp.isClosed()) {
      sftp = (ChannelSftp) session.openChannel("sftp");
      sftp.connect(30_000);
    }
    return sftp;
  }

  public boolean isConnected() {
    return session.isConnected();
  }

  @Override
  public void close() {
    if (sftp != null) sftp.disconnect();
    session.disconnect();
  }

  /*
   * accept-new: yes for an unknown host (recorded in known_hosts), no when a known host's key changed.
   * Keyboard-interactive logins (many IBM i systems ask "Enter your password") get IBMI_PASSWORD.
   */
  private static final class AcceptNewHosts implements UserInfo, UIKeyboardInteractive {
    private final String password;

    AcceptNewHosts(String password) {
      this.password = password;
    }

    @Override public boolean promptYesNo(String message) {
      boolean changed = message.toUpperCase().contains("CHANGED");
      if (changed) logger.error("SSH host key changed, refusing to connect: {}", message);
      /* JSch's message names the host and the key fingerprint: show it so the user can check it */
      else logger.warn("Trusting a new SSH host key, added to known_hosts. Check the fingerprint with your administrator: {}",
          message.replace('\n', ' '));
      return !changed;
    }
    @Override public String getPassphrase() { return null; }
    @Override public String getPassword() { return password; }
    @Override public boolean promptPassword(String message) { return password != null && !password.isEmpty(); }
    @Override public boolean promptPassphrase(String message) { return false; }
    @Override public void showMessage(String message) { logger.info("SSH: {}", message); }

    @Override
    public String[] promptKeyboardInteractive(String destination, String name, String instruction,
        String[] prompts, boolean[] echo) {
      if (password == null || password.isEmpty()) return null;  // cancel: no password to give
      String[] answers = new String[prompts.length];
      for (int i = 0; i < prompts.length; i++) answers[i] = password;
      return answers;
    }
  }
}
