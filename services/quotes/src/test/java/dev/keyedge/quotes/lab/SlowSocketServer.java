package dev.keyedge.quotes.lab;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A raw HTTP/1.1 server that answers after {@code delayMs} and notices when the client closes the
 * connection first (read() returns -1): the independent measurement of "was the call cancelled".
 */
final class SlowSocketServer implements AutoCloseable {
  private final ServerSocket socket;
  final AtomicInteger cancelled = new AtomicInteger();
  final AtomicInteger completed = new AtomicInteger();
  private final String body;

  SlowSocketServer(long delayMs, String body) throws IOException {
    this.body = body;
    socket = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
    var acceptor = Executors.newVirtualThreadPerTaskExecutor();
    Thread.ofVirtual()
        .start(() -> {
          while (!socket.isClosed()) {
            try {
              Socket s = socket.accept();
              acceptor.submit(() -> serve(s, delayMs));
            } catch (IOException e) {
              return;
            }
          }
        });
  }

  String url() {
    return "http://127.0.0.1:" + socket.getLocalPort();
  }

  private void serve(Socket s, long delayMs) {
    try (s) {
      InputStream in = s.getInputStream();
      int matched = 0;
      while (matched < 4) { // read the request head up to \r\n\r\n
        int b = in.read();
        if (b < 0) {
          return;
        }
        matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
      }
      s.setSoTimeout(5);
      long end = System.nanoTime() + delayMs * 1_000_000;
      while (System.nanoTime() < end) {
        try {
          if (in.read() < 0) {
            cancelled.incrementAndGet(); // the client hung up before we answered
            return;
          }
        } catch (SocketTimeoutException e) {
          // still connected, keep waiting
        }
      }
      byte[] out = body.getBytes(StandardCharsets.UTF_8);
      s.getOutputStream()
          .write(
              ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                      + out.length
                      + "\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      s.getOutputStream().write(out);
      completed.incrementAndGet();
    } catch (IOException e) {
      cancelled.incrementAndGet();
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
