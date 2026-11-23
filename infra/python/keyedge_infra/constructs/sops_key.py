"""The KMS key SOPS encrypts secrets files to (second recipient besides age, D-44)."""

from aws_cdk import Duration, RemovalPolicy
from aws_cdk import aws_kms as kms
from constructs import Construct


class SopsKey(Construct):
    def __init__(self, scope: Construct, construct_id: str, alias: str, retain: bool) -> None:
        super().__init__(scope, construct_id)
        self.key = kms.Key(
            self,
            "Key",
            alias=alias,
            description="keyedge SOPS data key encryption",
            enable_key_rotation=True,
            removal_policy=RemovalPolicy.RETAIN if retain else RemovalPolicy.DESTROY,
            pending_window=Duration.days(7),
        )
