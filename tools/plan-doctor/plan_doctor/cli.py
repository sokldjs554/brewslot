"""사용법:
    python -m plan_doctor.cli services/*/build/query-plans/*.json              # 규칙 기반 리포트
    python -m plan_doctor.cli --ai --schema 'services/*/src/main/resources/db/**/*.sql' ...   # + Claude 리뷰
    python -m plan_doctor.cli --fail-on high ...                               # CI 게이트
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
from pathlib import Path

from .rules import SEVERITY_ORDER, analyze, to_markdown


def load(path: Path) -> tuple[str, str, list]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(doc, dict) and "plan" in doc:  # test-support QueryPlan 이 남긴 형식
        return doc.get("name", path.stem), doc.get("sql", ""), doc["plan"]
    return path.stem, "", doc  # 순수 EXPLAIN JSON


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="plan-doctor", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+", help="EXPLAIN JSON 파일 또는 glob")
    parser.add_argument("--profile", choices=["oltp", "batch"], default="oltp")
    parser.add_argument("--out", type=Path, help="마크다운 리포트 저장 경로 (기본: stdout)")
    parser.add_argument("--fail-on", choices=["high", "medium", "low"], help="이 심각도 이상이 있으면 종료 코드 1")
    parser.add_argument("--ai", action="store_true", help="Claude 리뷰 추가 (ANTHROPIC_API_KEY 또는 ant auth 프로필 필요)")
    parser.add_argument("--schema", action="append", default=[], help="--ai 에 함께 보낼 스키마 SQL glob (반복 가능)")
    args = parser.parse_args(argv)

    paths = sorted({Path(p) for pattern in args.files for p in glob.glob(pattern, recursive=True)})
    if not paths:
        print("plan-doctor: 입력 파일이 없습니다", file=sys.stderr)
        return 2

    reports, raw = [], {}
    for path in paths:
        name, sql, plan = load(path)
        raw[name] = plan
        reports.append(analyze(name, sql, plan, args.profile))

    output = to_markdown(reports)
    if args.ai:
        from .ai import review  # anthropic 은 --ai 일 때만 필요

        schema = "\n\n".join(Path(p).read_text(encoding="utf-8") for pattern in args.schema for p in sorted(glob.glob(pattern, recursive=True)))
        output += "\n# Claude review\n\n" + review(reports, raw, schema)

    if args.out:
        args.out.write_text(output, encoding="utf-8")
    else:
        print(output)

    if args.fail_on:
        threshold = SEVERITY_ORDER[args.fail_on]
        if any(SEVERITY_ORDER[f.severity] <= threshold for r in reports for f in r.findings):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
