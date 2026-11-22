package dev.keyedge.edge.api;

import com.sun.net.httpserver.HttpServer;
import dev.keyedge.keyformat.InternalSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A counting fake of keys' internal API: VALID for one secret, UNKNOWN otherwise; usage accepted.
 */
final class FakeKeys implements AutoCloseable {
  final HttpServer server;
  final AtomicInteger verifies = new AtomicInteger();
  final AtomicInteger usages = new AtomicInteger();
  volatile boolean down;
  volatile String goodSecret = "";
  volatile int limitPerSecond = 100;

  FakeKeys() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/internal/v1/verify",
        ex -> {
          verifies.incrementAndGet();
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          boolean signed =
              ex.getRequestHeaders().getFirst(InternalSignature.SIGNATURE) != null
                  && ex.getRequestHeaders().getFirst(InternalSignature.NONCE) != null;
          if (down || !signed) {
            ex.sendResponseHeaders(down ? 503 : 401, -1);
            ex.close();
            return;
          }
          String keyId = body.replaceAll(".*\"keyId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
          String json =
              body.contains("\"" + goodSecret + "\"")
                  ? "{\"verdict\":\"VALID\",\"tenant\":\"acme\",\"keyId\":\""
                      + keyId
                      + "\",\"scopes\":[\"quotes:read\"],"
                      + "\"rateLimit\":{\"perSecond\":"
                      + limitPerSecond
                      + ",\"burst\":"
                      + limitPerSecond
                      + "},\"ttlSeconds\":60}"
                  : "{\"verdict\":\"UNKNOWN\",\"keyId\":\"" + keyId + "\",\"ttlSeconds\":5}";
          byte[] out = json.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.createContext(
        "/internal/v1/usage",
        ex -> {
          usages.incrementAndGet();
          ex.getRequestBody().readAllBytes();
          ex.sendResponseHeaders(202, -1);
          ex.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
