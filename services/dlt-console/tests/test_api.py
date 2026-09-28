"""API 규칙 단위 테스트 — Kafka 없이 FakeBroker 로."""
import pytest
from fastapi.testclient import TestClient

from dlt_console.app import create_app
from dlt_console.broker import DltMessage, original_topic_of

ENVELOPE = '{"eventId":"e-1","eventType":"PaymentCaptured","aggregateId":"o-1","payload":{}}'


class FakeBroker:
    def __init__(self):
        self.messages = [
            DltMessage("payment.events.DLT", 0, 7, "o-1", ENVELOPE, 1000, {"kafka_dlt-exception-message": "boom", "kafka_dlt-original-topic": "payment.events"}),
            DltMessage("payment.events.DLT", 1, 3, "o-2", "not-json", 2000, {}),
        ]
        self.ledger: dict[str, str] = {}
        self.sent: list[tuple[str, str | None, str]] = []

    async def dlt_topics(self):
        return {"payment.events.DLT": len(self.messages)}

    async def read(self, topic, limit):
        return [m for m in self.messages if m.topic == topic][-limit:]

    async def find(self, topic, partition, offset):
        return next((m for m in self.messages if (m.topic, m.partition, m.offset) == (topic, partition, offset)), None)

    async def replayed(self, ref):
        return self.ledger.get(ref)

    async def replay(self, message, replay_id, operator):
        self.sent.append((message.original_topic, message.key, message.value))
        self.ledger[message.ref] = replay_id


@pytest.fixture
def broker():
    return FakeBroker()


@pytest.fixture
def client(broker):
    with TestClient(create_app(broker, ops_token="secret")) as c:
        yield c


AUTH = {"X-Ops-Token": "secret", "X-Operator": "kim"}


def test_DLT_토픽별_적체_수를_보여준다(client):
    assert client.get("/dlt/topics").json() == {"payment.events.DLT": 2}


def test_메시지마다_원래_토픽과_실패_원인과_이벤트를_풀어서_보여준다(client):
    body = client.get("/dlt/payment.events.DLT/messages").json()
    first = body[0]
    assert first["originalTopic"] == "payment.events"
    assert first["error"] == "boom"
    assert (first["eventType"], first["eventId"]) == ("PaymentCaptured", "e-1")
    assert body[1]["value"] == "not-json"  # 봉투가 깨진 메시지도 원문 그대로 보여 준다


def test_DLT가_아닌_토픽은_다루지_않는다(client):
    assert client.get("/dlt/payment.events/messages").status_code == 400


def test_재처리는_원래_토픽으로_같은_key와_value를_보낸다(client, broker):
    res = client.post("/dlt/payment.events.DLT/messages/0/7/replay", headers=AUTH)
    assert res.status_code == 202
    assert res.json()["sentTo"] == "payment.events"
    assert res.json()["eventId"] == "e-1"
    assert broker.sent == [("payment.events", "o-1", ENVELOPE)]


def test_같은_메시지를_두번_재처리하면_409_의도했다면_force(client, broker):
    client.post("/dlt/payment.events.DLT/messages/0/7/replay", headers=AUTH)
    second = client.post("/dlt/payment.events.DLT/messages/0/7/replay", headers=AUTH)
    assert second.status_code == 409
    forced = client.post("/dlt/payment.events.DLT/messages/0/7/replay?force=true", headers=AUTH)
    assert forced.status_code == 202
    assert len(broker.sent) == 2


def test_토큰이_없으면_재처리할_수_없다(client, broker):
    assert client.post("/dlt/payment.events.DLT/messages/0/7/replay").status_code == 401
    assert client.post("/dlt/payment.events.DLT/messages/0/7/replay", headers={"X-Ops-Token": "wrong"}).status_code == 401
    assert broker.sent == []


def test_없는_메시지는_404(client):
    assert client.post("/dlt/payment.events.DLT/messages/0/999/replay", headers=AUTH).status_code == 404


def test_원래_토픽_이름_규칙():
    assert original_topic_of("loyalty.commands.DLT") == "loyalty.commands"
    with pytest.raises(ValueError):
        original_topic_of("loyalty.commands")


def test_지표를_노출한다(client):
    client.post("/dlt/payment.events.DLT/messages/0/7/replay", headers=AUTH)
    assert "brewslot_dlt_replays_total" in client.get("/metrics").text
