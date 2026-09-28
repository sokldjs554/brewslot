"""
DLT 재처리 콘솔 (FastAPI).

소비자가 재시도 후에도 처리하지 못한 메시지는 `<토픽>.DLT` 로 격리된다(자동 복구되지 않는 유일한 경로).
운영자는 원인을 고친 뒤 이 콘솔로 메시지를 원래 토픽에 다시 보낸다.

안전장치
- 모든 소비자는 Inbox(consumer, eventId)로 멱등하므로, 재처리된 메시지가 두 번 처리되지 않는다.
- 같은 DLT 메시지를 두 번 재처리하려 하면 409 (재처리 기록 토픽 `ops.dlt-replays` 로 판단). 의도적이면 force=true.
- 재처리는 X-Ops-Token 이 필요하고, 누가 언제 했는지 기록한다.
"""
from __future__ import annotations

import os
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, generate_latest
from pydantic import BaseModel

from .broker import DLT_SUFFIX, Broker, DltMessage

REPLAYS = Counter("brewslot_dlt_replays_total", "DLT 재처리 건수", ["topic", "result"])


class MessageView(BaseModel):
    ref: str
    topic: str
    partition: int
    offset: int
    key: str | None
    originalTopic: str
    eventType: str | None
    eventId: str | None
    error: str | None
    timestampMs: int
    value: dict | str

    @classmethod
    def of(cls, m: DltMessage) -> "MessageView":
        env = m.envelope
        return cls(
            ref=m.ref, topic=m.topic, partition=m.partition, offset=m.offset, key=m.key,
            originalTopic=m.original_topic, eventType=env.get("eventType"), eventId=env.get("eventId"),
            error=m.error, timestampMs=m.timestamp_ms, value=env or m.value,
        )


class ReplayResult(BaseModel):
    replayId: str
    ref: str
    sentTo: str
    eventId: str | None


def create_app(broker: Broker | None = None, ops_token: str | None = None) -> FastAPI:
    token = ops_token if ops_token is not None else os.environ.get("OPS_TOKEN", "")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        b = broker
        if b is None:
            from .kafka_broker import KafkaBroker

            b = KafkaBroker(os.environ.get("KAFKA_BOOTSTRAP", "localhost:9092"))
        if hasattr(b, "start"):
            await b.start()
        app.state.broker = b
        try:
            yield
        finally:
            if hasattr(b, "stop"):
                await b.stop()

    app = FastAPI(title="BrewSlot DLT Console", version="0.1.0", lifespan=lifespan)

    def get_broker() -> Broker:
        return app.state.broker

    def require_dlt(topic: str) -> str:
        if not topic.endswith(DLT_SUFFIX):
            raise HTTPException(400, detail=f"DLT 토픽만 다룹니다 (…{DLT_SUFFIX})")
        return topic

    def require_operator(x_ops_token: str | None = Header(default=None), x_operator: str | None = Header(default=None)) -> str:
        if not token or x_ops_token != token:
            raise HTTPException(401, detail="X-Ops-Token 이 필요합니다")
        return x_operator or "unknown"

    @app.get("/health")
    async def health():
        return {"status": "UP"}

    @app.get("/metrics", response_class=PlainTextResponse)
    async def metrics():
        return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.get("/dlt/topics")
    async def topics(b: Broker = Depends(get_broker)) -> dict[str, int]:
        """DLT 토픽별로 쌓여 있는 메시지 수"""
        return await b.dlt_topics()

    @app.get("/dlt/{topic}/messages")
    async def messages(topic: str, limit: int = Query(50, ge=1, le=500), b: Broker = Depends(get_broker)) -> list[MessageView]:
        """가장 최근 메시지부터 최대 limit 건. 실패 원인(예외 메시지)과 원래 토픽을 함께 보여 준다."""
        return [MessageView.of(m) for m in await b.read(require_dlt(topic), limit)]

    @app.post("/dlt/{topic}/messages/{partition}/{offset}/replay", status_code=202)
    async def replay(
        topic: str,
        partition: int,
        offset: int,
        force: bool = False,
        operator: str = Depends(require_operator),
        b: Broker = Depends(get_broker),
    ) -> ReplayResult:
        """원래 토픽으로 같은 key·value 를 다시 보낸다. 소비자는 eventId 로 멱등하므로 이미 처리된 메시지는 무시된다."""
        m = await b.find(require_dlt(topic), partition, offset)
        if m is None:
            REPLAYS.labels(topic, "not_found").inc()
            raise HTTPException(404, detail="메시지를 찾을 수 없습니다 (보관 기간이 지났을 수 있음)")
        previous = await b.replayed(m.ref)
        if previous and not force:
            REPLAYS.labels(topic, "duplicate").inc()
            raise HTTPException(409, detail={"message": "이미 재처리한 메시지입니다", "replayId": previous})
        replay_id = str(uuid.uuid4())
        await b.replay(m, replay_id, operator)
        REPLAYS.labels(topic, "sent").inc()
        return ReplayResult(replayId=replay_id, ref=m.ref, sentTo=m.original_topic, eventId=m.envelope.get("eventId"))

    return app


app = create_app()
