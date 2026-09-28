"""
Datadog 대시보드·모니터가 참조하는 지표가 코드에 실제로 존재하는지 검사한다 (CI).

지표 이름을 코드에서 바꾸고 모니터를 안 고치면 알림이 조용히 "데이터 없음" 이 된다.
API 명세 드리프트 테스트와 같은 목적의, 모니터링 설정 드리프트 테스트다.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
# Micrometer → Datadog 변환 시 붙는 접미사 (Timer/DistributionSummary 의 count·percentile 등)
SUFFIXES = (".percentile", ".count", ".avg", ".max", ".sum")


def queries(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("q", "query") and isinstance(v, str):
                yield v
            else:
                yield from queries(v)
    elif isinstance(node, list):
        for v in node:
            yield from queries(v)


def base(metric: str) -> str:
    for s in SUFFIXES:
        if metric.endswith(s):
            return metric[: -len(s)]
    return metric


def main() -> int:
    source = "\n".join(p.read_text(encoding="utf-8") for p in ROOT.glob("**/src/main/kotlin/**/*.kt"))
    referenced = set()
    for f in ("dashboard.json", "monitors.json"):
        for q in queries(json.loads((HERE / f).read_text(encoding="utf-8"))):
            referenced.update(base(m) for m in re.findall(r"brewslot\.[a-z_.]+[a-z_]", q))
    missing = sorted(m for m in referenced if f'"{m}"' not in source)
    for m in sorted(referenced):
        print(("MISSING " if m in missing else "ok      ") + m)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
