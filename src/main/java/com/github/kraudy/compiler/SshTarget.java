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
    session.setServerAliveInterval(30_000);
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

  /* Run a PASE command, return its stdout; non-zero exit fails with its stderr */
  public String exec(String command) throws Exception {
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    channel.setCommand(command);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    channel.setOutputStream(out);
    channel.setErrStream(err);
    channel.connect(30_000);
    while (!channel.isClosed()) Thread.sleep(50);
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
  public SftpATTRS stat(String remote) throws Exception {
    try {
      return sftp().stat(remote);
    } catch (SftpException e) {
      if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
      throw e;
    }
  }

  /* Upload, creating parent directories, and keep the local modification time (to skip it next time) */
  public void upload(File local, String remote) throws Exception {
    mkdirs(remote.substring(0, remote.lastIndexOf('/')));
    sftp().put(local.getPath(), remote, ChannelSftp.OVERWRITE);
    sftp().setMtime(remote, (int) (local.lastModified() / 1000));
  }

  public void upload(byte[] data, String remote) throws Exception {
    mkdirs(remote.substring(0, remote.lastIndexOf('/')));
    sftp().put(new java.io.ByteArrayInputStream(data), remote, ChannelSftp.OVERWRITE);
  }

  public void mkdirs(String dir) throws Exception {
    if (dir.isEmpty() || knownDirs.contains(dir)) return;
    if (stat(dir) == null) {
      mkdirs(dir.substring(0, Math.max(0, dir.lastIndexOf('/'))));
      sftp().mkdir(dir);
    }
    knownDirs.add(dir);
  }

  /*
   * Every file under a remote directory as relative path -> {size, mtime}: one listing per directory
   * instead of one round trip per file (that matters at 150+ ms per round trip). Empty when it does not exist.
   */
  public Map<String, long[]> listTree(String dir) throws Exception {
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
