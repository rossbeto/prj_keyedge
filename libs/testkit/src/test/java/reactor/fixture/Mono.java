package reactor.fixture;

/** Stand-in for a reactive library type, used only to prove the framework-free rule fires. */
public final class Mono {
  public static String just(String s) {
    return s;
  }
}
