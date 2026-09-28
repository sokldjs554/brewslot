"""
n8n 워크플로 검증용 모의 서버: dlt-console · settlement-service · Slack 웹훅을 흉내 내고 Slack 으로 온 메시지를 파일에 기록한다.
응답 형식은 실제 서비스 API 와 같다 (dlt-console GET /dlt/topics, settlement POST /reconciliation-runs · /settlement-runs).
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

OUT = sys.argv[2] if len(sys.argv) > 2 else "slack.jsonl"


class H(BaseHTTPRequestHandler):
    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/dlt/topics":
            return self._json(200, {"payment.events.DLT": 2, "loyalty.commands.DLT": 0})
        self._json(404, {})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        if self.path == "/slack":
            with open(OUT, "a", encoding="utf-8") as f:
                f.write(json.dumps(body, ensure_ascii=False) + "\n")
            return self._json(200, {"ok": True})
        if self.path == "/reconciliation-runs":
            return self._json(200, {"runId": 1, "businessDate": body["businessDate"], "pgCount": 3, "ledgerCount": 3, "matchedCount": 2,
                                    "issues": [{"type": "AMOUNT_MISMATCH", "transactionId": "pg-1"}]})
        if self.path == "/settlement-runs":
            return self._json(200, [
                {"id": 1, "draft": {"storeId": 101, "payout": 102_374, "netSales": 107_300}},
                {"id": 2, "draft": {"storeId": 102, "payout": 50_000, "netSales": 52_000}},
            ])
        self._json(404, {})

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
