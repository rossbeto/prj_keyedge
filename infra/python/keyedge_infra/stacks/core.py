"""KMS, data and config stacks: deployed to the emulators (bootstrap-less, no assets)."""

from aws_cdk import IStackSynthesizer
from aws_cdk import aws_dynamodb as dynamodb
from aws_cdk import aws_secretsmanager as sm
from constructs import Construct

from keyedge_infra.config import InfraConfig
from keyedge_infra.constructs import (
    KeysTable,
    KeysTableProps,
    SopsKey,
    SsmContract,
    VersionedSecret,
    VersionedSecretProps,
)
from keyedge_infra.stacks.base import KeyedgeStack


class KmsStack(KeyedgeStack):
    def __init__(self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer) -> None:
        super().__init__(scope, stack_id, cfg, synth, "KMS key for SOPS")
        key = SopsKey(self, "Sops", f"{cfg.spec.str('names.sopsKeyAlias')}-{cfg.env}", cfg.prodlike)
        SsmContract(self, "Contract", {cfg.ssm("sopsKmsArn"): key.key.key_arn})
        self.suppress_from_spec()


class DataStack(KeyedgeStack):
    def __init__(self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer) -> None:
        super().__init__(scope, stack_id, cfg, synth, "keys table")
        s = cfg.spec
        self.table: dynamodb.Table = KeysTable(
            self,
            "Keys",
            KeysTableProps(
                s.str("names.table"),
                s.str("table.partitionKey"),
                s.str("table.sortKey"),
                s.str("table.gsi1.name"),
                s.str("table.gsi1.partitionKey"),
                s.str("table.gsi1.sortKey"),
                s.str("table.ttlAttribute"),
                cfg.prodlike,
            ),
        ).table
        SsmContract(self, "Contract", {cfg.ssm("tableName"): self.table.table_name})
        self.suppress_from_spec()


class ConfigStack(KeyedgeStack):
    def __init__(self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer) -> None:
        super().__init__(scope, stack_id, cfg, synth, "secrets and service contract")
        s = cfg.spec
        self.secrets: dict[str, sm.ISecret] = {}
        for name in ("peppers", "callers", "principal"):
            self.secrets[name] = VersionedSecret(
                self,
                name[0].upper() + name[1:],
                VersionedSecretProps(
                    s.str(f"names.secrets.{name}"),
                    s.str(f"secrets.generated.{name}.template"),
                    s.str(f"secrets.generated.{name}.key"),
                    s.num("secrets.length"),
                    cfg.prodlike,
                ),
            ).secret
        SsmContract(
            self,
            "Contract",
            {
                cfg.ssm("pepperSecret"): s.str("names.secrets.peppers"),
                cfg.ssm("callerSecret"): s.str("names.secrets.callers"),
                cfg.ssm("principalSecret"): s.str("names.secrets.principal"),
                cfg.ssm("verifyUrl"): s.str("service.verifyUrl"),
            },
        )
        self.suppress_from_spec()
