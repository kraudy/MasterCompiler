package com.github.kraudy.compiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * The certificates a TLS connection from MC (the 5250 session) trusts: Java's own (cacerts), on Windows also the
 * Windows trusted roots (Windows-ROOT, where a company's own CA is usually installed), and MC_TRUSTSTORE: a CA
 * certificate (.pem / .crt / .cer, one or more) or a key store (.jks / .p12 / .pfx, MC_TRUSTSTORE_PASSWORD if it
 * has one). The certificate must still name the host.
 */
final class TrustStores {
  private static final Logger logger = LoggerFactory.getLogger(TrustStores.class);

  private static SSLSocketFactory factory;
  private static final List<String> sources = new ArrayList<String>();

  private TrustStores() {}

  static synchronized SSLSocketFactory socketFactory() throws Exception {
    if (factory != null) return factory;
    KeyStore trusted = KeyStore.getInstance("PKCS12");
    trusted.load(null, null);
    int[] count = { 0 };

    TrustManagerFactory java = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    java.init((KeyStore) null);
    for (javax.net.ssl.TrustManager tm : java.getTrustManagers()) {
      if (tm instanceof X509TrustManager) for (X509Certificate c : ((X509TrustManager) tm).getAcceptedIssuers()) add(trusted, "java", c, count);
    }
    sources.add("Java (" + count[0] + ")");

    if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
      int before = count[0];
      try {
        KeyStore windows = KeyStore.getInstance("Windows-ROOT");
        windows.load(null, null);
        for (Enumeration<String> aliases = windows.aliases(); aliases.hasMoreElements();) {
          Certificate c = windows.getCertificate(aliases.nextElement());
          if (c != null) add(trusted, "windows", c, count);
        }
        sources.add("Windows-ROOT (" + (count[0] - before) + ")");
      } catch (Exception e) {
        logger.warn("Could not read the Windows certificate store (Windows-ROOT): {}", e.toString());
        sources.add("Windows-ROOT unavailable: " + e.getMessage());
      }
    }

    String file = System.getenv("MC_TRUSTSTORE");
    if (file != null && !file.trim().isEmpty()) {
      int before = count[0];
      load(trusted, new File(file.trim()), count);
      sources.add("MC_TRUSTSTORE " + file.trim() + " (" + (count[0] - before) + ")");
    }

    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trusted);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), null);
    logger.info("TLS trusts: {}", String.join(", ", sources));
    factory = context.getSocketFactory();
    return factory;
  }

  /* Where the trusted certificates came from, for an error message */
  static synchronized String describe() {
    return sources.isEmpty() ? "Java's" : String.join(", ", sources);
  }

  private static void load(KeyStore into, File file, int[] count) throws Exception {
    if (!file.isFile()) throw new IllegalArgumentException("MC_TRUSTSTORE " + file + " is not a file");
    String name = file.getName().toLowerCase();
    if (name.endsWith(".jks") || name.endsWith(".p12") || name.endsWith(".pfx")) {
      String password = System.getenv("MC_TRUSTSTORE_PASSWORD");
      KeyStore store = KeyStore.getInstance(name.endsWith(".jks") ? "JKS" : "PKCS12");
      try (InputStream in = new FileInputStream(file)) {
        store.load(in, password != null ? password.toCharArray() : null);
      }
      for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements();) {
        Certificate c = store.getCertificate(aliases.nextElement());
        if (c != null) add(into, "file", c, count);
      }
      return;
    }
    try (InputStream in = new FileInputStream(file)) {  // PEM or DER, one certificate or several
      for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) add(into, "file", c, count);
    }
  }

  private static void add(KeyStore store, String source, Certificate certificate, int[] count) throws Exception {
    store.setCertificateEntry(source + "-" + (count[0]++), certificate);
  }
}
