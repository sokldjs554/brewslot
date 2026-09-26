"""Claude 리뷰 (선택 기능, --ai).

규칙 엔진이 "무엇이 문제인지" 를 결정적으로 찾으면, Claude 는 스키마(마이그레이션 SQL)와 실행계획을 함께 읽고
"왜 그런 계획이 나왔는지 / 어떤 인덱스·쿼리 변경이 좋은지 / 그 대가(쓰기 비용, 인덱스 크기)는 무엇인지" 를 설명한다.
제안은 사람이 검토하는 PR 코멘트용이며, 자동 적용하지 않는다.
"""
from __future__ import annotations

import json

import anthropic

from .rules import PlanReport

MODEL = "claude-opus-5"

SYSTEM_PROMPT = """당신은 PostgreSQL 16 성능 리뷰어입니다. 한국어로 답합니다.
입력으로 스키마(Flyway 마이그레이션), 쿼리, EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) 결과, 규칙 엔진의 진단이 주어집니다.

각 쿼리에 대해:
1. 플래너가 이 계획을 고른 이유를 실행계획 수치(행 수, 버퍼, 시간)를 근거로 설명합니다.
2. 개선안을 제시합니다. 인덱스라면 정확한 CREATE INDEX 문, 쿼리라면 바뀐 SQL 을 씁니다.
3. 개선안의 대가(쓰기 증폭, 인덱스 크기, 잠금, 다른 쿼리에 미치는 영향)를 적습니다.
4. 문제가 없거나 현재 데이터 규모에서는 합리적인 선택이면 그렇다고 말하고, 어떤 데이터 분포에서 문제가 될지 적습니다.

추측이 필요한 부분은 추측이라고 밝히고, 확인할 수 있는 측정 방법(쿼리)을 함께 제시합니다."""


def review(reports: list[PlanReport], plans: dict[str, dict], schema_sql: str, client: anthropic.Anthropic | None = None) -> str:
    client = client or anthropic.Anthropic()
    payload = [
        {
            "name": r.name,
            "sql": r.sql,
            "execution_ms": r.execution_ms,
            "rule_findings": [f.__dict__ for f in r.findings],
            "plan": plans[r.name],
        }
        for r in reports
    ]
    response = client.beta.messages.create(
        model=MODEL,
        max_tokens=16000,
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        thinking={"type": "adaptive"},
        system=[{"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}],
        messages=[{
            "role": "user",
            "content": [
                {"type": "text", "text": f"<schema>\n{schema_sql}\n</schema>", "cache_control": {"type": "ephemeral"}},
                {"type": "text", "text": f"<plans>\n{json.dumps(payload, ensure_ascii=False)}\n</plans>"},
            ],
        }],
    )
    if response.stop_reason == "refusal":
        return "_Claude 리뷰를 생성하지 못했습니다 (refusal). 규칙 기반 결과만 참고하세요._\n"
    text = "".join(block.text for block in response.content if block.type == "text")
    if response.stop_reason == "max_tokens":
        text += "\n\n_(응답이 max_tokens 에서 잘렸습니다)_"
    return text + "\n"
