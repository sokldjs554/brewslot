// 인기 시각 쏠림: 100명이 같은 매장·같은 픽업 시각에 동시에 주문 → 초과 예약 0건, 거절 응답에 대안 시각 포함
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const ORDER = __ENV.ORDER_URL || 'http://localhost:8081';
const accepted = new Counter('hot_accepted');
const rejectedWithAlternatives = new Counter('hot_rejected_with_alternatives');

http.setResponseCallback(http.expectedStatuses(201, 409));

export const options = {
  scenarios: { hot: { executor: 'per-vu-iterations', vus: 100, iterations: 1, maxDuration: '30s' } },
  thresholds: { 'http_req_duration': ['p(95)<500'], 'http_req_failed': ['rate<0.01'] },
};

export function setup() {
  const now = Math.floor(Date.now() / 1000);
  const hot = Math.ceil((now + 40 * 60) / 300) * 300; // 40분 뒤 정각 슬롯
  return { pickupAt: new Date(hot * 1000).toISOString().replace('.000', '') };
}

export default function (data) {
  const res = http.post(`${ORDER}/orders`, JSON.stringify({
    storeId: 101, pickupAt: data.pickupAt, items: [{ menuItemId: 1003, quantity: 1 }], pointsToUse: 0,
  }), { headers: { 'Content-Type': 'application/json', 'X-Member-Id': `${9000 + __VU}`, 'Idempotency-Key': `hot-${__VU}-${Date.now()}` } });
  if (res.status === 201) accepted.add(1);
  if (res.status === 409 && (res.json('alternatives') || []).length > 0) rejectedWithAlternatives.add(1);
  check(res, { '201 or 409': (r) => r.status === 201 || r.status === 409 });
}
