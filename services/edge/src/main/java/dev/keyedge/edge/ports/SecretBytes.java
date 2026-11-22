package dev.keyedge.edge.ports;

/** One entry of a JSON secret, base64-decoded (the sidecar's credential and principal key). */
@FunctionalInterface
public interface SecretBytes {
  byte[] bytes(String secretId, String entry);
}
