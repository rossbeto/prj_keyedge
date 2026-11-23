"""Grafana dashboards as code (Phase 011): make dashboards -> deploy/observe/grafana/dashboards/*.json

Deterministic output (sorted keys, fixed ids) so `git diff --exit-code` after `make dashboards` proves the
committed JSON is generated. Every panel is a PromQL query over metrics with bounded labels only.
    python3 scripts/build_dashboards.py [--check]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "deploy" / "observe" / "grafana" / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}


def panel(pid: int, title: str, exprs: list[tuple[str, str]], x: int, y: int, unit: str = "short",
          w: int = 12, h: int = 8, description: str = "") -> dict:
    return {
        "id": pid,
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}},
        "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": legend}
                    for i, (e, legend) in enumerate(exprs)],
    }


def grid(panels: list[tuple]) -> list[dict]:
    """Two columns, rows of height 8, ids in order."""
    out = []
    for i, p in enumerate(panels):
        title, exprs, unit = p[0], p[1], p[2]
        description = p[3] if len(p) > 3 else ""
        out.append(panel(i + 1, title, exprs, x=(i % 2) * 12, y=(i // 2) * 8, unit=unit, description=description))
    return out


def http_p99(job: str) -> tuple[str, str]:
    return (f'histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{job="{job}"}}[1m])))',
            "p99 {{uri}}")


def dashboard(uid: str, title: str, panels: list[dict]) -> dict:
    return {
        "uid": uid,
        "title": title,
        "tags": ["keyedge"],
        "schemaVersion": 39,
        "version": 1,
        "editable": False,
        "time": {"from": "now-15m", "to": "now"},
        "refresh": "5s",
        "panels": panels,
    }


EDGE = dashboard("keyedge-edge", "keyedge / edge sidecar", grid([
    ("Admissions by result", [('sum by (result) (rate(keyedge_edge_admission_total[1m]))', "{{result}}")], "reqps",
     "Every decision: admitted, admitted_stale (fail-static), or the rejection's problem type"),
    ("Decision latency (p50 / p99)", [
        ('histogram_quantile(0.5, sum by (le) (rate(keyedge_edge_decision_seconds_bucket[1m])))', "p50"),
        ('histogram_quantile(0.99, sum by (le) (rate(keyedge_edge_decision_seconds_bucket[1m])))', "p99")], "s",
     "Route + key format + verdict (cache) + scope + rate limit; excludes the app"),
    ("Verify calls to keys by kind/outcome", [
        ('sum by (kind, outcome) (rate(keyedge_verify_cache_loads_seconds_count[1m]))', "{{kind}} {{outcome}}")], "reqps",
     "miss / refresh / revalidate; outcome unavailable = control plane down"),
    ("Fail-static: stale answers", [('rate(keyedge_verify_cache_stale_served_total[1m])', "stale served")], "reqps",
     "Non-zero only while keys cannot answer (ADR 0010)"),
    ("Verify breaker state", [('max by (state) (keyedge_verify_breaker_state)', "{{state}}")], "short"),
    ("Concurrency gate", [('keyedge_edge_concurrency_limit', "limit"), ('keyedge_edge_inflight', "in flight"),
                          ('rate(keyedge_edge_shed_total[1m])', "shed/s")], "short",
     "Adaptive limit vs in-flight; shed = 503 overloaded"),
    ("Edge HTTP p99 by route", [http_p99("edge")], "s"),
    ("Usage metering backlog", [('keyedge_usage_pending_windows', "pending windows"),
                                ('increase(keyedge_usage_dropped_total[5m])', "dropped (5m)")], "short",
     "Windows waiting for keys; drops are counted, never silent (D-28)"),
]))

QUOTES = dashboard("keyedge-quotes", "keyedge / quotes fan-out", grid([
    ("Upstream calls by outcome", [
        ('sum by (upstream, outcome) (rate(keyedge_upstream_calls_total[1m]))', "{{upstream}} {{outcome}}")], "reqps",
     "Each attempt counts (retries included)"),
    ("Retries granted by the budget", [('sum by (upstream) (rate(keyedge_upstream_retries_total[1m]))', "{{upstream}}")],
     "reqps", "Capped at 10 + 10 % of calls per upstream (ADR 0011)"),
    ("Calls by concurrency model", [('sum by (model) (rate(keyedge_upstream_calls_total[1m]))', "{{model}}")], "reqps",
     "structured / future (blocking endpoint) vs reactor (twin)"),
    ("Quotes HTTP p99 by route", [http_p99("quotes")], "s"),
]))

KEYS = dashboard("keyedge-keys", "keyedge / keys control plane", grid([
    ("Requests by route and status", [
        ('sum by (uri, status) (rate(http_server_requests_seconds_count{job="keys"}[1m]))', "{{uri}} {{status}}")],
     "reqps"),
    ("Keys HTTP p99 by route", [http_p99("keys")], "s"),
    ("JVM heap used", [('sum by (job) (jvm_memory_used_bytes{area="heap"})', "{{job}}")], "bytes"),
    ("Live threads (platform)", [('jvm_threads_live_threads', "{{job}}")], "short"),
]))


def main() -> int:
    check = "--check" in sys.argv
    OUT.mkdir(parents=True, exist_ok=True)
    stale = []
    for name, d in (("edge", EDGE), ("quotes", QUOTES), ("keys", KEYS)):
        path = OUT / f"{name}.json"
        text = json.dumps(d, indent=2, sort_keys=True) + "\n"
        if check:
            if not path.exists() or path.read_text() != text:
                stale.append(path.name)
        else:
            path.write_text(text)
    if stale:
        print("dashboards out of date (run make dashboards):", ", ".join(stale))
        return 1
    print("dashboards up to date" if check else f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
