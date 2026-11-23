"""The role GitHub Actions assumes with OIDC; L1 provider (the L2 one deploys a Lambda asset)."""

from aws_cdk import aws_iam as iam
from constructs import Construct


class GithubOidcRole(Construct):
    def __init__(self, scope: Construct, construct_id: str, repo: str, thumbprint: str) -> None:
        super().__init__(scope, construct_id)
        provider = iam.CfnOIDCProvider(
            self,
            "Provider",
            url="https://token.actions.githubusercontent.com",
            client_id_list=["sts.amazonaws.com"],
            thumbprint_list=[thumbprint],
        )
        self.role = iam.Role(
            self,
            "Role",
            description=f"GitHub Actions for {repo} (tags only)",
            assumed_by=iam.FederatedPrincipal(
                provider.attr_arn,
                {  # ordered like Java's TreeMap: identical templates
                    "StringEquals": {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com"},
                    "StringLike": {
                        "token.actions.githubusercontent.com:sub": f"repo:{repo}:ref:refs/tags/v*"
                    },
                },
                "sts:AssumeRoleWithWebIdentity",
            ),
        )
