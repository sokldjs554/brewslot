-- Transactional Outbox: 도메인 상태 변경과 "발행할 메시지"를 같은 로컬 트랜잭션에 기록한다.
CREATE TABLE outbox_event
(
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(200) NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    event_type   VARCHAR(200) NOT NULL,
    envelope     JSONB        NOT NULL,
    trace_parent VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- 릴레이는 "미발행" 행만 id 순으로 읽는다. 발행 완료 행이 수백만 건 쌓여도
-- 부분 인덱스 크기는 미발행 건수에만 비례하므로 폴링 비용이 일정하다. (docs/performance/query-plans.md #1)
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published_at ON outbox_event (published_at) WHERE published_at IS NOT NULL;

-- Idempotent Consumer: (consumer, event_id) 로 at-least-once 전달의 중복을 흡수한다.
CREATE TABLE inbox_message
(
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    event_type   VARCHAR(200) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
