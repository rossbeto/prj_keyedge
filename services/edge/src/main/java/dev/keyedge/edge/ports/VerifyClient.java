package dev.keyedge.edge.ports;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;

/** One call to the control plane's /internal/v1/verify (no caching). */
public interface VerifyClient {
  KeyCheck verify(KeyId keyId, KeySecret secret);
}
