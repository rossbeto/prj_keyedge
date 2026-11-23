package dev.keyedge.fanout;

import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.function.LongSupplier;

/**
 * Finds fan-out adapters with {@link ServiceLoader}, so an application can depend on the structured
 * adapter at <b>runtime only</b>: javac refuses to read a class compiled with preview features
 * unless the reader is compiled with {@code --enable-preview} too, and this keeps the flag in one
 * module (the JVM still needs it when that adapter is loaded).
 */
public interface FanOutProvider {
  /** {@code "structured"} or {@code "future"}. */
  String name();

  FanOut create(Resources resources);

  /** What an adapter may need; each takes what it uses. */
  record Resources(LongSupplier nanos, ExecutorService executor, ContextCarrier context) {}

  /** The adapter called {@code name}; fails clearly if its module is not on the classpath. */
  static FanOut load(String name, Resources resources) {
    return ServiceLoader.load(FanOutProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.name().equals(name))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no fan-out adapter '" + name + "' on the classpath"))
        .create(resources);
  }

  /** The executor-based adapter. */
  final class Future implements FanOutProvider {
    @Override
    public String name() {
      return "future";
    }

    @Override
    public FanOut create(Resources r) {
      return new FutureFanOut(r.executor(), r.nanos(), r.context());
    }
  }
}
