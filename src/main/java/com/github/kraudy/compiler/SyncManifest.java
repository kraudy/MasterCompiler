package com.github.kraudy.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/*
 * .mc/sync-manifest.json in the project: what the last sync uploaded to one IBM i folder (size, time and SHA-256 of
 * each file). Size and time alone miss an edit of the same size within the same second, and re-upload files when the
 * IBM i tar did not keep their time; the hash settles both. Files over HASH_UP_TO bytes are not hashed.
 */
final class SyncManifest {
  private static final Logger logger = LoggerFactory.getLogger(SyncManifest.class);
  private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  static final long HASH_UP_TO = 1024 * 1024;

  private final File file;
  private final String key;       // host and remote folder: one project may sync to several
  private final ObjectNode root;
  private boolean dirty;

  private SyncManifest(File file, String key, ObjectNode root) {
    this.file = file;
    this.key = key;
    this.root = root;
  }

  static SyncManifest load(File project, String key) {
    File file = new File(project, ".mc/sync-manifest.json");
    ObjectNode root = mapper.createObjectNode();
    try {
      if (file.isFile()) {
        JsonNode all = mapper.readTree(file);
        if (all.isObject()) root = (ObjectNode) all;
      }
    } catch (Exception e) {
      logger.warn("Could not read {}: {}", file, e.getMessage());
    }
    return new SyncManifest(file, key, root);
  }

  private ObjectNode files() {
    JsonNode node = root.get(key);
    return node != null && node.isObject() ? (ObjectNode) node : root.putObject(key);
  }

  /* The entry for a file, or null */
  JsonNode get(String rel) {
    JsonNode entry = files().get(rel);
    return entry != null && entry.isObject() ? entry : null;
  }

  void put(String rel, long size, long mtime, String sha256) {
    ObjectNode entry = files().putObject(rel);
    entry.put("size", size);
    entry.put("mtime", mtime);
    entry.put("sha256", sha256);
    dirty = true;
  }

  void remove(String rel) {
    if (files().remove(rel) != null) dirty = true;
  }

  void save() {
    if (!dirty) return;
    try {
      file.getParentFile().mkdirs();
      Files.write(file.toPath(), mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
      dirty = false;
    } catch (Exception e) {
      logger.warn("Could not write {}: {}", file, e.getMessage());
    }
  }
}
