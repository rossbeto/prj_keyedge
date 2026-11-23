"""A Secrets Manager JSON document with one CloudFormation-generated field (never in the template)."""

from dataclasses import dataclass

from aws_cdk import RemovalPolicy
from aws_cdk import aws_secretsmanager as sm
from constructs import Construct


@dataclass(frozen=True)
class VersionedSecretProps:
    secret_name: str
    template: str
    generated_key: str
    length: int
    retain: bool


class VersionedSecret(Construct):
    def __init__(self, scope: Construct, construct_id: str, p: VersionedSecretProps) -> None:
        super().__init__(scope, construct_id)
        self.secret = sm.Secret(
            self,
            "Secret",
            secret_name=p.secret_name,
            description=f"keyedge {p.secret_name} (JSON document)",
            generate_secret_string=sm.SecretStringGenerator(
                secret_string_template=p.template,
                generate_string_key=p.generated_key,
                password_length=p.length,
                exclude_punctuation=True,  # base64 alphabet: the services decode it
                include_space=False,
            ),
            removal_policy=RemovalPolicy.RETAIN if p.retain else RemovalPolicy.DESTROY,
        )
