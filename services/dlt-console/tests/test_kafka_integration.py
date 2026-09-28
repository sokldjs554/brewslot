"""실제 Kafka 로 조회·재처리·중복 방지를 검증한다 (Docker 필요)."""
import asyncio
import json

import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi.testclient import TestClient

from dlt_console.app import create_app
from dlt_console.kafka_broker import KafkaBroker

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def bootstrap():
    kafka = pytest.importorskip("testcontainers.kafka")
    try:
        container = kafka.KafkaContainer().with_kraft()
        container.start()
    except Exception as e:  # Docker 가 없는 환경
        pytest.skip(f"docker unavailable: {e}")
    yield container.get_bootstrap_server()
    container.stop()


async def _produce_dlt(bootstrap: str):
    p = AIOKafkaProducer(bootstrap_servers=bootstrap)
    await p.start()
    env = {"eventId": "evt-42", "eventType": "PointsRedeemed", "aggregateId": "o-42", "payload": {"orderId": "o-42"}}
    await p.send_and_wait(
        "loyalty.events.DLT",
        value=json.dumps(env).encode(),
        key=b"o-42",
        headers=[("kafka_dlt-original-topic", b"loyalty.events"), ("kafka_dlt-exception-message", b"settlement DB down")],
    )
    await p.stop()


async def _read_original(bootstrap: str):
    c = AIOKafkaConsumer("loyalty.events", bootstrap_servers=bootstrap, auto_offset_reset="earliest", group_id=None)
    await c.start()
    try:
        batch = await c.getmany(timeout_ms=10_000)
        return [r for rs in batch.values() for r in rs]
    finally:
        await c.stop()


def test_실제_Kafka_에서_DLT_메시지를_조회하고_한_번만_재처리한다(bootstrap):
    asyncio.run(_produce_dlt(bootstrap))
    with TestClient(create_app(KafkaBroker(bootstrap), ops_token="t")) as client:
        assert client.get("/dlt/topics").json()["loyalty.events.DLT"] == 1

        msg = client.get("/dlt/loyalty.events.DLT/messages").json()[0]
        assert msg["error"] == "settlement DB down"
        assert msg["eventId"] == "evt-42"

        path = f"/dlt/loyalty.events.DLT/messages/{msg['partition']}/{msg['offset']}/replay"
        first = client.post(path, headers={"X-Ops-Token": "t", "X-Operator": "ops-kim"})
        assert first.status_code == 202, first.text
        assert client.post(path, headers={"X-Ops-Token": "t"}).status_code == 409  # 재처리 기록이 Kafka 에 남아 있다

    records = asyncio.run(_read_original(bootstrap))
    assert len(records) == 1
    headers = dict(records[0].headers)
    assert headers["brewslot-replayed-from"].decode().startswith("loyalty.events.DLT:")
    assert json.loads(records[0].value)["eventId"] == "evt-42"
