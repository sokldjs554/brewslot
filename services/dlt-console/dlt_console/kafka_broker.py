from __future__ import annotations

import json
import time

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, NewTopic

from .broker import DLT_SUFFIX, REPLAY_LEDGER_TOPIC, DltMessage


class KafkaBroker:
    """aiokafka 구현. 소비자 그룹 없이 파티션을 직접 배정해 읽으므로, 조회가 서비스의 오프셋에 영향을 주지 않는다."""

    def __init__(self, bootstrap: str):
        self.bootstrap = bootstrap
        self._producer: AIOKafkaProducer | None = None

    async def start(self) -> None:
        self._producer = AIOKafkaProducer(bootstrap_servers=self.bootstrap, acks="all", enable_idempotence=True)
        try:
            await self._producer.start()
        except Exception:
            await self._producer.stop()  # 연결 실패 시 자원을 정리하고 기동 실패를 그대로 알린다(쿠버네티스가 재시작)
            raise
        admin = AIOKafkaAdminClient(bootstrap_servers=self.bootstrap)
        await admin.start()
        try:
            if REPLAY_LEDGER_TOPIC not in await admin.list_topics():
                await admin.create_topics(
                    [NewTopic(REPLAY_LEDGER_TOPIC, num_partitions=1, replication_factor=1, topic_configs={"cleanup.policy": "compact"})]
                )
        except Exception:  # 다른 인스턴스가 먼저 만들었으면 그대로 쓴다
            pass
        finally:
            await admin.close()

    async def stop(self) -> None:
        if self._producer:
            await self._producer.stop()

    def _consumer(self) -> AIOKafkaConsumer:
        return AIOKafkaConsumer(bootstrap_servers=self.bootstrap, enable_auto_commit=False, group_id=None)

    async def _read_partitions(self, topic: str, limit: int, predicate=None) -> list[DltMessage]:
        consumer = self._consumer()
        await consumer.start()
        try:
            parts = consumer.partitions_for_topic(topic) or set()
            if not parts:
                await consumer.topics()  # 메타데이터 갱신
                parts = consumer.partitions_for_topic(topic) or set()
            tps = [TopicPartition(topic, p) for p in sorted(parts)]
            if not tps:
                return []
            consumer.assign(tps)
            begin = await consumer.beginning_offsets(tps)
            end = await consumer.end_offsets(tps)
            for tp in tps:
                consumer.seek(tp, max(begin[tp], end[tp] - limit))
            out: list[DltMessage] = []
            pending = {tp for tp in tps if end[tp] > max(begin[tp], end[tp] - limit)}
            deadline = time.monotonic() + 10
            while pending and time.monotonic() < deadline:
                batch = await consumer.getmany(timeout_ms=500)
                for tp, records in batch.items():
                    for r in records:
                        m = DltMessage(
                            topic=r.topic,
                            partition=r.partition,
                            offset=r.offset,
                            key=r.key.decode() if r.key else None,
                            value=r.value.decode() if r.value else "",
                            timestamp_ms=r.timestamp,
                            headers={k: v.decode(errors="replace") for k, v in (r.headers or [])},
                        )
                        if predicate is None or predicate(m):
                            out.append(m)
                        if r.offset >= end[tp] - 1:
                            pending.discard(tp)
            out.sort(key=lambda m: (m.timestamp_ms, m.partition, m.offset))
            return out[-limit:] if predicate is None else out
        finally:
            await consumer.stop()

    async def dlt_topics(self) -> dict[str, int]:
        consumer = self._consumer()
        await consumer.start()
        try:
            topics = sorted(t for t in await consumer.topics() if t.endswith(DLT_SUFFIX))
            counts: dict[str, int] = {}
            for t in topics:
                tps = [TopicPartition(t, p) for p in consumer.partitions_for_topic(t) or set()]
                begin = await consumer.beginning_offsets(tps)
                end = await consumer.end_offsets(tps)
                counts[t] = sum(end[tp] - begin[tp] for tp in tps)
            return counts
        finally:
            await consumer.stop()

    async def read(self, topic: str, limit: int) -> list[DltMessage]:
        return await self._read_partitions(topic, limit)

    async def find(self, topic: str, partition: int, offset: int) -> DltMessage | None:
        consumer = self._consumer()
        await consumer.start()
        try:
            tp = TopicPartition(topic, partition)
            if partition not in (consumer.partitions_for_topic(topic) or set()):
                await consumer.topics()
                if partition not in (consumer.partitions_for_topic(topic) or set()):
                    return None
            consumer.assign([tp])
            begin = (await consumer.beginning_offsets([tp]))[tp]
            end = (await consumer.end_offsets([tp]))[tp]
            if not begin <= offset < end:
                return None
            consumer.seek(tp, offset)
            batch = await consumer.getmany(tp, timeout_ms=5000, max_records=1)
            for r in batch.get(tp, []):
                return DltMessage(
                    topic, partition, r.offset, r.key.decode() if r.key else None, r.value.decode() if r.value else "",
                    r.timestamp, {k: v.decode(errors="replace") for k, v in (r.headers or [])},
                )
            return None
        finally:
            await consumer.stop()

    async def replayed(self, ref: str) -> str | None:
        found = await self._read_partitions(REPLAY_LEDGER_TOPIC, 100_000, predicate=lambda m: m.key == ref)
        return json.loads(found[-1].value)["replayId"] if found else None

    async def replay(self, message: DltMessage, replay_id: str, operator: str) -> None:
        assert self._producer
        headers = [
            ("brewslot-replayed-from", message.ref.encode()),
            ("brewslot-replay-id", replay_id.encode()),
        ]
        await self._producer.send_and_wait(
            message.original_topic,
            value=message.value.encode(),
            key=message.key.encode() if message.key else None,
            headers=headers,
        )
        record = {"replayId": replay_id, "operator": operator, "originalTopic": message.original_topic, "at": int(time.time() * 1000)}
        await self._producer.send_and_wait(REPLAY_LEDGER_TOPIC, value=json.dumps(record).encode(), key=message.ref.encode())
