package dev.keyedge.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class HealthProbeTest {
  @Test
  void okOnlyFor200() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        ex -> {
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/down",
        ex -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      assertThat(HealthProbe.check(base + "/ok")).isTrue();
      assertThat(HealthProbe.check(base + "/down")).isFalse();
      assertThat(HealthProbe.check("http://127.0.0.1:1/none")).isFalse();
      assertThat(HealthProbe.check("not a url")).isFalse();
    } finally {
      server.stop(0);
    }
  }
}
