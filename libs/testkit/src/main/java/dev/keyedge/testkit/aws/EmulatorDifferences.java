package dev.keyedge.testkit.aws;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/** Container for repeated {@link EmulatorDifference}s. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ExtendWith(EmulatorDifferenceCondition.class)
public @interface EmulatorDifferences {
  EmulatorDifference[] value();
}
