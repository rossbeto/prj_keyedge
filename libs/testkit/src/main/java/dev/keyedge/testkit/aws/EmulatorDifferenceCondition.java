package dev.keyedge.testkit.aws;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips a case on the target its {@link EmulatorDifference} names, with a {@code PARITY id:}
 * reason.
 */
public final class EmulatorDifferenceCondition implements ExecutionCondition {
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    var method = context.getTestMethod();
    var instance = context.getTestInstance();
    if (method.isEmpty() || instance.isEmpty() || !(instance.get() instanceof OnTarget onTarget)) {
      return ConditionEvaluationResult.enabled("not a contract case");
    }
    String target = onTarget.kind().id();
    return Stream.concat(
            Stream.ofNullable(method.get().getAnnotation(EmulatorDifference.class)),
            Stream.ofNullable(method.get().getAnnotation(EmulatorDifferences.class))
                .flatMap(d -> Arrays.stream(d.value())))
        .filter(d -> d.target().equals(target))
        .findFirst()
        .map(d -> ConditionEvaluationResult.disabled("PARITY " + d.id() + ": " + d.reason()))
        .orElse(ConditionEvaluationResult.enabled("no declared difference on " + target));
  }
}
