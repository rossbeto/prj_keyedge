package dev.keyedge.infra.stacks;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.constructs.GithubOidcRole;
import software.amazon.awscdk.IStackSynthesizer;
import software.constructs.Construct;

/** The GitHub Actions OIDC role (synthesised and tested; never deployed from here). */
public final class CiStack extends KeyedgeStack {
  public CiStack(Construct scope, String id, InfraConfig cfg, IStackSynthesizer synth) {
    super(scope, id, cfg, synth, "CI identity");
    new GithubOidcRole(
        this, "Github", cfg.spec().str("ci.githubRepo"), cfg.spec().str("ci.oidcThumbprint"));
    suppressFromSpec();
  }
}
