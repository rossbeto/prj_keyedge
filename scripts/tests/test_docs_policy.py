"""Docs policy (Phase 016): the portfolio card keeps the contract the portfolio site parses; every relative link in
README.md and docs/ resolves; every README line that states a measured number links to its evidence (a results file,
the lab notebook, the parity page or a chart)."""

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
CARD = ROOT / "docs" / "portfolio-card.md"
SECTIONS = ("Title", "Pitch", "Media", "Highlights", "Tech stack", "Links")
HEADING = re.compile(r"^## +(.+?) *$", re.MULTILINE)
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(([^)\s]+)\)")
FENCE = re.compile(r"```.*?```", re.DOTALL)
MEASURE = re.compile(r"\d[\d.,]*\s?(ms|%|req/s|MiB|MB|ns)\b")
EVIDENCE = ("results/", "lab-notebook", "parity.md", "docs/media/", "media/")
DOCS = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]


def card_sections() -> dict[str, str]:
    parts = HEADING.split(COMMENT.sub("", CARD.read_text()))
    found: dict[str, str] = {}
    for heading, content in zip(parts[1::2], parts[2::2], strict=True):
        assert heading not in found, f"'## {heading}' appears twice"
        found[heading] = content.strip()
    return found


def test_portfolio_card_contract() -> None:
    found = card_sections()
    assert sorted(found) == sorted(SECTIONS)
    assert all(found.values()), "a section is empty"
    assert "\n" not in found["Title"]
    text = CARD.read_text()
    assert "[X]" not in text and "status:" not in text  # the site's project front matter uses `stage`


def test_portfolio_card_images_are_repo_files() -> None:
    for target in re.findall(r"!\[[^\]]*\]\(([^)\s]+)\)", CARD.read_text()):
        assert not re.match(r"^[a-z][a-z0-9+.-]*:", target), f"hotlinked image {target}"
        assert (CARD.parent / target).resolve().is_file(), f"missing image {target}"


@pytest.mark.parametrize("doc", DOCS, ids=lambda p: str(p.relative_to(ROOT)))
def test_relative_links_resolve(doc: Path) -> None:
    text = FENCE.sub("", COMMENT.sub("", doc.read_text()))
    for target in LINK.findall(text):
        if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
            continue
        path = target.split("#", 1)[0]
        assert (doc.parent / path).resolve().exists(), f"{doc.relative_to(ROOT)}: broken link {target}"


def test_readme_numbers_link_to_evidence() -> None:
    text = FENCE.sub("", COMMENT.sub("", (ROOT / "README.md").read_text()))
    for line in text.splitlines():
        if MEASURE.search(line) and not line.lstrip().startswith(("|---", "#")):
            assert any(e in line for e in EVIDENCE), f"README: a number without its evidence link: {line.strip()}"
