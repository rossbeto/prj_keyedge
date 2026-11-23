"""Base of every keyedge stack: env, synthesizer, tags, and the spec's cdk-nag acknowledgments."""

from aws_cdk import Acknowledgment, Environment, IStackSynthesizer, Stack, Tags, Validations
from constructs import Construct, IConstruct

from keyedge_infra.config import InfraConfig


class KeyedgeStack(Stack):
    def __init__(
        self, scope: Construct, stack_id: str, cfg: InfraConfig, synth: IStackSynthesizer, what: str
    ) -> None:
        super().__init__(
            scope,
            stack_id,
            env=Environment(account=cfg.account, region=cfg.region),
            synthesizer=synth,
            description=f"keyedge {cfg.mode.key}: {what}",
        )
        self.cfg = cfg
        Tags.of(self).add("project", "keyedge")
        Tags.of(self).add("env", cfg.env)

    def _reason(self, rule: str) -> str:
        reason = self.cfg.spec.map(f"nag.{self.cfg.mode.key}").get(rule)
        if reason is None:
            raise ValueError(f"no reason for {rule} in nag.{self.cfg.mode.key} of the spec")
        return reason

    def acknowledge_on(self, construct: IConstruct, rule: str) -> None:
        Validations.of(construct).acknowledge(
            Acknowledgment(id=f"AwsSolutions::{rule}", reason=self._reason(rule))
        )

    def acknowledge_granular(self, construct: IConstruct, rule: str, finding: str) -> None:
        Validations.of(construct).acknowledge(
            Acknowledgment(id=f"AwsSolutions::{rule}[{finding}]", reason=self._reason(rule))
        )

    def suppress_from_spec(self) -> None:
        for rule, reason in self.cfg.spec.map(f"nag.{self.cfg.mode.key}").items():
            Validations.of(self).acknowledge(Acknowledgment(id=f"AwsSolutions::{rule}", reason=reason))
