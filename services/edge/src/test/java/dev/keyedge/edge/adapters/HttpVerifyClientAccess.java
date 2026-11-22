package dev.keyedge.edge.adapters;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import java.util.Map;

/** Test access to the package-private verdict mapping. */
final class HttpVerifyClientAccess {
  private HttpVerifyClientAccess() {}

  static KeyCheck toCheck(KeyId id, Map<String, Object> v) {
    return dev.keyedge.edge.adapters.http.HttpVerifyClientMapping.toCheck(id, v);
  }
}
