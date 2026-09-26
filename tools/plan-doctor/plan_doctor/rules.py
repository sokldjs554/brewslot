"""규칙 기반 실행계획 진단.

LLM 없이도 CI 에서 결정적으로 돌아가야 하므로 핵심 판단은 전부 여기(순수 함수)에 있다.
Claude 리뷰(ai.py)는 이 결과를 "설명하고 대안을 제안" 하는 보조 역할만 한다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterator

# 임계값은 OLTP 기준. 배치 쿼리는 --profile batch 로 완화한다.
PROFILES: dict[str, dict[str, float]] = {
    "oltp": {"seq_scan_rows": 10_000, "sort_rows": 1_000, "filter_waste_ratio": 50, "misestimate_ratio": 10, "heap_fetches": 1_000},
    "batch": {"seq_scan_rows": 1_000_000, "sort_rows": 100_000, "filter_waste_ratio": 1_000, "misestimate_ratio": 100, "heap_fetches": 100_000},
}

SEVERITY_ORDER = {"high": 0, "medium": 1, "low": 2}


@dataclass(frozen=True)
class Finding:
    rule: str
    severity: str
    node: str
    message: str
    hint: str


@dataclass
class PlanReport:
    name: str
    sql: str
    execution_ms: float | None
    findings: list[Finding] = field(default_factory=list)
    node_summary: list[str] = field(default_factory=list)

    @property
    def worst(self) -> str | None:
        if not self.findings:
            return None
        return min(self.findings, key=lambda f: SEVERITY_ORDER[f.severity]).severity


def walk(node: dict[str, Any], depth: int = 0, under_limit: bool = False) -> Iterator[tuple[dict[str, Any], int, bool]]:
    """(노드, 깊이, LIMIT 아래인가). LIMIT 아래 노드는 조기 종료되므로 예상/실제 행 수 비교가 무의미하다."""
    yield node, depth, under_limit
    child_under_limit = under_limit or node.get("Node Type") == "Limit"
    for child in node.get("Plans", []) or []:
        yield from walk(child, depth + 1, child_under_limit)


def describe(node: dict[str, Any]) -> str:
    parts = [node.get("Node Type", "?")]
    if rel := node.get("Relation Name"):
        parts.append(f"on {rel}")
    if idx := node.get("Index Name"):
        parts.append(f"using {idx}")
    return " ".join(parts)


def _actual_rows(node: dict[str, Any]) -> float:
    return float(node.get("Actual Rows", 0)) * float(node.get("Actual Loops", 1) or 1)


def analyze(name: str, sql: str, explain: list[dict[str, Any]] | dict[str, Any], profile: str = "oltp") -> PlanReport:
    """EXPLAIN (ANALYZE, FORMAT JSON) 결과 하나를 진단한다."""
    t = PROFILES[profile]
    root = explain[0] if isinstance(explain, list) else explain
    report = PlanReport(name=name, sql=sql, execution_ms=root.get("Execution Time"))

    for node, depth, under_limit in walk(root["Plan"]):
        label = describe(node)
        report.node_summary.append("  " * depth + label + (f" (rows={node['Actual Rows']})" if "Actual Rows" in node else ""))
        ntype = node.get("Node Type", "")
        actual = _actual_rows(node)
        removed = float(node.get("Rows Removed by Filter", 0))
        scanned = actual + removed

        if ntype == "Seq Scan" and scanned >= t["seq_scan_rows"]:
            report.findings.append(Finding(
                "SEQ_SCAN_LARGE", "high", label,
                f"{int(scanned):,}행을 순차 스캔해 {int(actual):,}행만 사용",
                "WHERE 조건 컬럼에 인덱스를 추가하거나, 소수 행만 대상이면 부분 인덱스(WHERE ...)를 고려",
            ))

        if ntype == "Sort":
            method = node.get("Sort Method", "")
            if "external" in method.lower():
                report.findings.append(Finding(
                    "SORT_SPILL", "high", label, f"정렬이 디스크로 넘침 ({method})",
                    "ORDER BY 순서와 같은 복합 인덱스로 정렬 자체를 제거하거나 work_mem 조정",
                ))
            elif actual >= t["sort_rows"] or any(_actual_rows(c) >= t["sort_rows"] for c in node.get("Plans", []) or []):
                src = max([_actual_rows(c) for c in node.get("Plans", []) or []] + [actual])
                report.findings.append(Finding(
                    "SORT_LARGE", "medium", label, f"{int(src):,}행을 읽어 메모리 정렬",
                    "LIMIT 이 있다면 (필터 컬럼, 정렬 컬럼) 복합 인덱스로 Top-N 을 인덱스 순서로 읽게 할 것 (keyset pagination)",
                ))

        if actual > 0 and removed / actual >= t["filter_waste_ratio"] and removed >= 1_000:
            report.findings.append(Finding(
                "FILTER_WASTE", "medium", label,
                f"읽은 행의 {removed / (removed + actual):.1%} 를 필터로 버림 ({int(removed):,}행)",
                "필터 조건을 인덱스 키에 포함하거나 부분 인덱스로 대상 행만 인덱싱",
            ))

        plan_rows = float(node.get("Plan Rows", 0) or 0)
        if "Actual Rows" in node and plan_rows > 0 and not under_limit:
            per_loop = float(node["Actual Rows"])
            ratio = max(per_loop, 1) / max(plan_rows, 1)
            if (ratio >= t["misestimate_ratio"] or 1 / ratio >= t["misestimate_ratio"]) and max(per_loop, plan_rows) >= 1_000:
                report.findings.append(Finding(
                    "ROW_MISESTIMATE", "low", label,
                    f"예상 {int(plan_rows):,}행 vs 실제 {int(per_loop):,}행",
                    "ANALYZE 로 통계 갱신, 상관된 컬럼이면 CREATE STATISTICS (dependencies) 고려",
                ))

        if ntype == "Index Only Scan" and float(node.get("Heap Fetches", 0)) >= t["heap_fetches"]:
            report.findings.append(Finding(
                "HEAP_FETCHES", "low", label, f"Index Only Scan 인데 힙 접근 {int(node['Heap Fetches']):,}회",
                "visibility map 이 낡음 — autovacuum 설정 점검 또는 VACUUM",
            ))

    report.findings.sort(key=lambda f: SEVERITY_ORDER[f.severity])
    return report


def to_markdown(reports: list[PlanReport]) -> str:
    lines = ["# plan-doctor report", "", "| query | exec(ms) | worst | findings |", "|---|---:|---|---|"]
    for r in reports:
        exec_ms = f"{r.execution_ms:.3f}" if r.execution_ms is not None else "-"
        lines.append(f"| {r.name} | {exec_ms} | {r.worst or 'ok'} | {len(r.findings)} |")
    for r in reports:
        if not r.findings:
            continue
        lines += ["", f"## {r.name}", "", "```sql", r.sql.strip(), "```", "", "```", *r.node_summary, "```", ""]
        for f in r.findings:
            lines.append(f"- **[{f.severity}] {f.rule}** `{f.node}` — {f.message}  \n  → {f.hint}")
    return "\n".join(lines) + "\n"
