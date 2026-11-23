package dev.keyedge.infra.constructs;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.services.iam.CfnOIDCProvider;
import software.amazon.awscdk.services.iam.FederatedPrincipal;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

/**
 * The role GitHub Actions assumes with OIDC (no long-lived keys). L1 provider on purpose: the L2
 * {@code OpenIdConnectProvider} deploys a Lambda (an asset), and these stacks have none (D-42).
 * Synthesised and tested only; policies are not enforced by the emulators.
 */
public final class GithubOidcRole extends Construct {
  private final Role role;

  public GithubOidcRole(Construct scope, String id, String repo, String thumbprint) {
    super(scope, id);
    var provider =
        CfnOIDCProvider.Builder.create(this, "Provider")
            .url("https://token.actions.githubusercontent.com")
            .clientIdList(List.of("sts.amazonaws.com"))
            .thumbprintList(List.of(thumbprint))
            .build();
    role =
        Role.Builder.create(this, "Role")
            .description("GitHub Actions for " + repo + " (tags only)")
            .assumedBy(
                new FederatedPrincipal(
                    provider.getAttrArn(),
                    new java.util.TreeMap<>(
                        Map.of( // ordered: identical templates on every run
                            "StringEquals",
                            Map.of("token.actions.githubusercontent.com:aud", "sts.amazonaws.com"),
                            "StringLike",
                            Map.of(
                                "token.actions.githubusercontent.com:sub",
                                "repo:" + repo + ":ref:refs/tags/v*"))),
                    "sts:AssumeRoleWithWebIdentity"))
            .build();
  }

  public Role role() {
    return role;
  }
}
