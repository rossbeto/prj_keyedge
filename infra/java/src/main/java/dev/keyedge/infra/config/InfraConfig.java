package dev.keyedge.infra.config;

/**
 * What one synth is for: mode, environment name, account/region, and the spec. Built once in {@code
 * InfraApp} from CDK context ({@code -c mode=local|prodlike -c env=<name>}).
 */
public record InfraConfig(Mode mode, String env, String account, String region, InfraSpec spec) {
  public static InfraConfig of(Mode mode, String env, InfraSpec spec) {
    return new InfraConfig(
        mode,
        env,
        spec.str("envs." + mode.key() + ".account"),
        spec.str("envs." + mode.key() + ".region"),
        spec);
  }

  public boolean prodlike() {
    return mode == Mode.PRODLIKE;
  }

  /** {@code /keyedge/<env>/<key>}: where the services read the contract (D-34). */
  public String ssm(String contractKey) {
    return spec.str("names.ssmRoot") + "/" + env + "/" + spec.str("contract." + contractKey);
  }

  /** {@code /keyedge/<env>}: the path the services list. */
  public String ssmPath() {
    return spec.str("names.ssmRoot") + "/" + env;
  }
}
