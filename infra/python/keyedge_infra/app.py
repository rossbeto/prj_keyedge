"""The only entry point: config from context (mode, env) and the spec, all stacks, cdk-nag on everything.

uv run --frozen python -m keyedge_infra.app          (CDK_OUTDIR / CDK_CONTEXT_JSON from make cdk-synth)
"""

from typing import Any

from aws_cdk import (
    App,
    BootstraplessSynthesizer,
    DefaultStackSynthesizer,
    IStackSynthesizer,
    Stack,
    Validations,
)
from cdk_nag import AwsSolutionsChecks

from keyedge_infra.config import InfraConfig, InfraSpec, Mode
from keyedge_infra.stacks.cluster import ClusterStack
from keyedge_infra.stacks.core import ConfigStack, DataStack, KmsStack
from keyedge_infra.stacks.edge import CiStack, EdgeLbStack, ObservabilityStack
from keyedge_infra.stacks.service import ServiceProps, ServiceStack

LOCAL_DEPLOYED = ("KeyedgeKms", "KeyedgeData", "KeyedgeConfig")


def new_app(context: dict[str, Any] | None = None, outdir: str | None = None) -> App:
    """Version reporting and path metadata off: templates hold only real content (parity diff, D-47)."""
    ctx: dict[str, Any] = dict(context or {})
    ctx["aws:cdk:version-reporting"] = False
    ctx["aws:cdk:enable-path-metadata"] = False
    return App(analytics_reporting=False, context=ctx, outdir=outdir)


def config_from(app: App) -> InfraConfig:
    mode = Mode.parse(str(app.node.try_get_context("mode") or "local"))
    env = app.node.try_get_context("env")
    return InfraConfig.of(mode, str(env) if env else mode.key, InfraSpec.load())


def _synth_for(cfg: InfraConfig) -> IStackSynthesizer:
    return DefaultStackSynthesizer() if cfg.prodlike else BootstraplessSynthesizer()


def build(app: App, cfg: InfraConfig) -> list[Stack]:
    kms = KmsStack(app, "KeyedgeKms", cfg, _synth_for(cfg))
    data = DataStack(app, "KeyedgeData", cfg, _synth_for(cfg))
    config = ConfigStack(app, "KeyedgeConfig", cfg, _synth_for(cfg))
    cluster = ClusterStack(app, "KeyedgeCluster", cfg, _synth_for(cfg))
    keys = ServiceStack(app, "KeyedgeKeysService", cfg, _synth_for(cfg), ServiceProps("keys", False, cluster))
    quotes = ServiceStack(
        app, "KeyedgeQuotesService", cfg, _synth_for(cfg), ServiceProps("quotes", True, cluster)
    )
    keys.allow_from(quotes.security_group, cfg.spec.num("service.keysPort"))
    lb = EdgeLbStack(app, "KeyedgeEdgeLb", cfg, _synth_for(cfg), cluster, quotes)
    obs = ObservabilityStack(app, "KeyedgeObservability", cfg, _synth_for(cfg), lb)
    ci = CiStack(app, "KeyedgeCi", cfg, _synth_for(cfg))
    Validations.of(app).add_plugins(AwsSolutionsChecks(app, verbose=True))
    keys.add_dependency(data)
    keys.add_dependency(config)
    quotes.add_dependency(config)
    return [kms, data, config, cluster, keys, quotes, lb, obs, ci]


def main() -> None:
    app = new_app()
    build(app, config_from(app))
    app.synth()


if __name__ == "__main__":
    main()
