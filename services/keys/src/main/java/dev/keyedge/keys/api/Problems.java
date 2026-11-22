package dev.keyedge.keys.api;

import dev.keyedge.keys.domain.KeysError;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;

/** Maps the sealed {@link KeysError} to problems: one place, exhaustive. */
final class Problems {
  private Problems() {}

  static ProblemException of(KeysError error) {
    return new ProblemException(
        switch (error) {
          case KeysError.NotFound n -> Problem.of(ProblemType.NOT_FOUND, null);
          case KeysError.Conflict c -> Problem.of(ProblemType.CONFLICT, null);
          case KeysError.Invalid(String reason) -> Problem.of(ProblemType.VALIDATION, reason);
        });
  }

  static ProblemException validation(String detail) {
    return new ProblemException(Problem.of(ProblemType.VALIDATION, detail));
  }
}
