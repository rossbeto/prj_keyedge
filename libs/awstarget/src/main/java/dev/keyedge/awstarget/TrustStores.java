package dev.keyedge.awstarget;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/** Builds an in-memory trust store from a PEM file (AWS Emulator's per-run {@code ca.pem}). */
public final class TrustStores {
  private TrustStores() {}

  /** A PKCS12 store with every certificate in the PEM file. */
  public static KeyStore fromPem(Path pem) {
    try (InputStream in = Files.newInputStream(pem)) {
      Collection<? extends Certificate> certs =
          CertificateFactory.getInstance("X.509").generateCertificates(in);
      if (certs.isEmpty()) {
        throw new IllegalArgumentException("no certificate in " + pem);
      }
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      int i = 0;
      for (Certificate cert : certs) {
        store.setCertificateEntry("ca-" + i++, cert);
      }
      return store;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException("cannot read CA certificate " + pem, e);
    }
  }

  /** Trust managers that trust only the given store. */
  public static TrustManager[] trustManagers(KeyStore store) {
    try {
      var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      return factory.getTrustManagers();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot build trust managers", e);
    }
  }
}
