package dev.keyedge.edge.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The app behind the sidecar: echoes the headers it received (lower-cased names) as JSON. */
final class FakeApp implements AutoCloseable {
  final HttpServer server;
  final AtomicInteger calls = new AtomicInteger();

  FakeApp() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        ex -> {
          calls.incrementAndGet();
          Map<String, String> h = new TreeMap<>();
          ex.getRequestHeaders()
              .forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
          StringBuilder json =
              new StringBuilder("{\"path\":\"" + ex.getRequestURI().getPath() + "\"");
          h.forEach((k, v) ->
              json.append(",\"")
                  .append(k)
                  .append("\":\"")
                  .append(v.replace("\"", "'"))
                  .append('"'));
          byte[] out = json.append('}').toString().getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
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
