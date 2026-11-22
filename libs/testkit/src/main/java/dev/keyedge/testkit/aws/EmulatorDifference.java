package dev.keyedge.testkit.aws;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declares a known difference of one emulator for one contract case. The case is reported as
 * skipped with a {@code PARITY <id>:} reason (never silently), and {@code docs/parity.md} must have
 * a row for the id. Use only after the behaviour has been observed and recorded.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(EmulatorDifferences.class)
@ExtendWith(EmulatorDifferenceCondition.class)
public @interface EmulatorDifference {
  /** The target the difference applies to: floci | aws-emulator. */
  String target();

  /** Parity row id, e.g. {@code P-03}. */
  String id();

  /** What differs. */
  String reason();
}
