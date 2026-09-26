import json
from pathlib import Path

from plan_doctor.cli import load, main
from plan_doctor.rules import analyze, to_markdown

FIXTURES = Path(__file__).parent / "fixtures"


def report(name: str, profile: str = "oltp"):
    return analyze(*load(FIXTURES / name), profile=profile)


def test_keyset_plan_from_regression_test_is_clean():
    r = report("good-keyset.json")
    assert r.findings == []
    assert r.worst is None


def test_outbox_seq_scan_is_flagged_high_with_filter_waste():
    rules = {f.rule: f.severity for f in report("bad-outbox-seqscan.json").findings}
    assert rules["SEQ_SCAN_LARGE"] == "high"
    assert rules["FILTER_WASTE"] == "medium"


def test_offset_pagination_disk_sort_is_flagged():
    r = report("bad-offset-sort.json")
    assert r.findings[0].rule == "SORT_SPILL"
    assert r.worst == "high"


def test_batch_profile_is_more_lenient():
    rules = {f.rule for f in report("bad-outbox-seqscan.json", profile="batch").findings}
    assert "SEQ_SCAN_LARGE" in rules  # 200만 행은 배치 기준(100만)에서도 크다
    assert "FILTER_WASTE" in rules


def test_markdown_lists_every_query():
    md = to_markdown([report("good-keyset.json"), report("bad-offset-sort.json")])
    assert "member-orders-first-page" in md and "member-orders-offset" in md
    assert "SORT_SPILL" in md


def test_cli_fail_on_gate(tmp_path, capsys):
    assert main([str(FIXTURES / "good-keyset.json"), "--fail-on", "high"]) == 0
    assert main([str(FIXTURES / "*.json"), "--fail-on", "high", "--out", str(tmp_path / "r.md")]) == 1
    assert "SEQ_SCAN_LARGE" in (tmp_path / "r.md").read_text(encoding="utf-8")


def test_accepts_raw_explain_json(tmp_path):
    raw = json.loads((FIXTURES / "bad-outbox-seqscan.json").read_text())["plan"]
    p = tmp_path / "raw.json"
    p.write_text(json.dumps(raw))
    assert report_path(p)


def report_path(p: Path):
    return analyze(*load(p)).findings
