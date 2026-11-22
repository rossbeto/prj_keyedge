package dev.keyedge.keyformat.demo;

import dev.keyedge.keyformat.DisplayHint;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyIds;
import dev.keyedge.keyformat.KeySecret;
import java.security.SecureRandom;
import java.time.InstantSource;

/**
 * {@code ./gradlew -q :libs:keyformat:run --args=demo}: prints a test key, its hint and its parse.
 */
public final class KeyformatDemo {
  private KeyformatDemo() {}

  public static void main(String[] args) {
    var random = new SecureRandom();
    var id = new KeyIds(InstantSource.system(), random).next();
    var secret = KeySecret.random(random);
    String key = KeyFormat.format(KeyEnvironment.TEST, id, secret);
    System.out.println(key);
    System.out.println("hint:  " + DisplayHint.of(KeyEnvironment.TEST, secret).value());
    System.out.println("parse: " + KeyFormat.parse(key).getClass().getSimpleName());
  }
}
