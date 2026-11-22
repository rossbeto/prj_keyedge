package dev.keyedge.problem;

/**
 * Carries a {@link Problem} through layers that must throw (e.g. a framework filter). Use cases
 * return sealed results instead; this exists for adapters only. The message is the title, never a
 * secret.
 */
public final class ProblemException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final transient Problem problem;

  public ProblemException(Problem problem) {
    super(problem.title(), null, false, false);
    this.problem = problem;
  }

  public Problem problem() {
    return problem;
  }
}
