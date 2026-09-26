"""Claude 호출부는 네트워크 없이 요청 형태만 검증한다."""
from types import SimpleNamespace

from plan_doctor.ai import MODEL, review
from plan_doctor.rules import analyze


class FakeMessages:
    def __init__(self, response):
        self.response = response
        self.kwargs = None

    def create(self, **kwargs):
        self.kwargs = kwargs
        return self.response


def fake_client(response):
    messages = FakeMessages(response)
    return SimpleNamespace(beta=SimpleNamespace(messages=messages)), messages


PLAN = [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "t", "Actual Rows": 1, "Actual Loops": 1, "Rows Removed by Filter": 50000}}]


def test_review_sends_schema_plans_and_uses_fallbacks():
    response = SimpleNamespace(stop_reason="end_turn", content=[SimpleNamespace(type="text", text="인덱스를 추가하세요")])
    client, messages = fake_client(response)
    out = review([analyze("q1", "select 1", PLAN)], {"q1": PLAN}, "CREATE TABLE t(a int);", client=client)

    assert "인덱스를 추가하세요" in out
    assert messages.kwargs["model"] == MODEL
    assert messages.kwargs["fallbacks"] == "default"
    assert messages.kwargs["thinking"] == {"type": "adaptive"}
    content = messages.kwargs["messages"][0]["content"]
    assert "CREATE TABLE t" in content[0]["text"]
    assert "SEQ_SCAN_LARGE" in content[1]["text"]


def test_refusal_is_reported_not_crashed():
    client, _ = fake_client(SimpleNamespace(stop_reason="refusal", content=[]))
    assert "refusal" in review([analyze("q1", "", PLAN)], {"q1": PLAN}, "", client=client)
