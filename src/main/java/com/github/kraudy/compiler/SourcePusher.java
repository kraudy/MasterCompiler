package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileOutputStream;

/*
 * --push <ifs-dir>: copies the local git repository's sources to the IFS over the
 * existing JT400 connection, keeping the repository layout, so a remote build
 * (laptop, CI) compiles what is on disk locally. With --since only changed files go.
 * Files are written as UTF-8 stream files (CCSID 1208).
 */
public final class SourcePusher {
  private static final Logger logger = LoggerFactory.getLogger(SourcePusher.class);
  private static final int UTF8_CCSID = Integer.parseInt(MasterCompiler.UTF8_CCSID);

  private SourcePusher() {}

  /**
   * Uploads the files ({@code onlyFiles} when given, else changed since {@code since},
   * else the whole repository) and returns the IFS directory that mirrors the spec's base
   * directory (the job's current directory for relative SRCSTMF).
   */
  public static String push(AS400 system, String baseDir, String ifsDir, String since,
      Set<String> onlyFiles, boolean dryRun, boolean verbose) throws Exception {
    String root = GitChanges.root(baseDir);
    Set<String> files = onlyFiles != null ? onlyFiles
        : since != null ? GitChanges.changedFiles(baseDir, since)
        : GitChanges.allFiles(baseDir);

    int pushed = 0;
    for (String path : files) {
      File local = new File(path);
      if (!local.isFile()) continue;  // deleted since the ref
      String remote = toRemote(root, path, ifsDir);
      if (remote == null) continue;   // outside the repository
      if (verbose) logger.info("Push {} -> {}", path, remote);
      if (!dryRun) upload(system, local, remote);
      pushed++;
    }
    logger.info("{} {} files to {}", dryRun ? "Would push" : "Pushed", pushed, ifsDir);

    String remoteBase = toRemote(root, GitChanges.canonical(baseDir), ifsDir);
    return remoteBase != null ? remoteBase : trimSlash(ifsDir);
  }

  private static void upload(AS400 system, File local, String remote) throws Exception {
    IFSFile target = new IFSFile(system, remote);
    IFSFile parent = target.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    if (target.exists()) target.delete();  // so the new file gets the UTF-8 CCSID tag

    byte[] buffer = new byte[8192];
    try (InputStream in = new FileInputStream(local);
         OutputStream out = new IFSFileOutputStream(system, remote, IFSFileOutputStream.SHARE_ALL, false, UTF8_CCSID)) {
      int read;
      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }
  }

  /* Local path under the repository root -> same relative path under ifsDir */
  private static String toRemote(String root, String path, String ifsDir) {
    String base = trimSlash(ifsDir);
    if (path.equals(root)) return base;
    String prefix = root.endsWith(File.separator) ? root : root + File.separator;
    if (!path.startsWith(prefix)) return null;
    return base + "/" + path.substring(prefix.length()).replace(File.separatorChar, '/');
  }

  private static String trimSlash(String dir) {
    return dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
  }
}
