"""
Kafka 접근 계층. API 는 이 Protocol 에만 의존하므로 단위 테스트는 FakeBroker 로, 통합 테스트는 실제 Kafka 로 돈다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Protocol

DLT_SUFFIX = ".DLT"  # Spring Kafka DeadLetterPublishingRecoverer 규칙 (libs/messaging Topics.DLT_SUFFIX 와 같음)
REPLAY_LEDGER_TOPIC = "ops.dlt-replays"  # 재처리 기록 (compacted). key = "<dlt토픽>:<파티션>:<오프셋>"


@dataclass(frozen=True)
class DltMessage:
    topic: str
    partition: int
    offset: int
    key: str | None
    value: str
    timestamp_ms: int
    headers: dict[str, str] = field(default_factory=dict)

    @property
    def original_topic(self) -> str:
        return self.headers.get("kafka_dlt-original-topic") or original_topic_of(self.topic)

    @property
    def error(self) -> str | None:
        return self.headers.get("kafka_dlt-exception-message")

    @property
    def envelope(self) -> dict:
        try:
            return json.loads(self.value)
        except (json.JSONDecodeError, TypeError):
            return {}

    @property
    def ref(self) -> str:
        return f"{self.topic}:{self.partition}:{self.offset}"


def original_topic_of(dlt_topic: str) -> str:
    if not dlt_topic.endswith(DLT_SUFFIX):
        raise ValueError(f"{dlt_topic} 는 DLT 토픽이 아닙니다")
    return dlt_topic[: -len(DLT_SUFFIX)]


class Broker(Protocol):
    async def dlt_topics(self) -> dict[str, int]:
        """DLT 토픽별 보관 중인 메시지 수"""

    async def read(self, topic: str, limit: int) -> list[DltMessage]:
        """토픽의 가장 최근 메시지 최대 limit 건 (오래된 것부터)"""

    async def find(self, topic: str, partition: int, offset: int) -> DltMessage | None: ...

    async def replayed(self, ref: str) -> str | None:
        """이미 재처리한 메시지면 그때의 재처리 ID"""

    async def replay(self, message: DltMessage, replay_id: str, operator: str) -> None:
        """원래 토픽으로 같은 key·value 를 다시 보내고, 재처리 기록을 남긴다"""
