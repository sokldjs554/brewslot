// 출근 러시 시나리오: "몇 시에 받을 수 있지?" 조회 폭주 + 주문·결제 동시 유입
// 실행: docker run --rm --network host -v $PWD/load-test:/scripts grafana/k6 run /scripts/rush-hour.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const ORDER = __ENV.ORDER_URL || 'http://localhost:8081';
const STORE = 201; // 대형 매장: setup 에서 에스프레소 용량을 크게 올린다
const accepted = new Counter('orders_accepted');
const rejected = new Counter('orders_rejected_capacity');
const paymentAccepted = new Counter('payments_accepted');
const placeLatency = new Trend('place_order_ms', true);

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate', exec: 'browse',
      rate: 200, timeUnit: '1s', duration: '60s', preAllocatedVUs: 50, maxVUs: 200,
    },
    order_and_pay: {
      executor: 'constant-arrival-rate', exec: 'orderAndPay',
      rate: 50, timeUnit: '1s', duration: '60s', preAllocatedVUs: 50, maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{name:pickup_times}': ['p(95)<200'],
    'http_req_duration{name:place_order}': ['p(95)<300'],
    'http_req_duration{name:start_payment}': ['p(95)<200'],
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };

function slotsAhead(minMinutes, count) {
  const now = Math.floor(Date.now() / 1000);
  const first = Math.ceil((now + minMinutes * 60) / 300) * 300;
  return Array.from({ length: count }, (_, i) => new Date((first + i * 300) * 1000).toISOString().replace('.000', ''));
}

export function setup() {
  const res = http.put(`${ORDER}/stores/${STORE}/capacity-profile`, JSON.stringify({ unitsPerSlot: { ESPRESSO: Number(__ENV.CAPACITY || 400) } }), json);
  check(res, { 'capacity raised': (r) => r.status === 200 });
  return { pickups: slotsAhead(Number(__ENV.AHEAD_MINUTES || 15), 12) };
}

export function browse() {
  const res = http.get(`${ORDER}/stores/${STORE}/pickup-times?items=3001:1&items=3002:1`, { tags: { name: 'pickup_times' } });
  check(res, { 'pickup-times 200': (r) => r.status === 200 });
}

export function orderAndPay(data) {
  const member = 100000 + Math.floor(Math.random() * 900000);
  const pickupAt = data.pickups[Math.floor(Math.random() * data.pickups.length)];
  const body = JSON.stringify({
    storeId: STORE, pickupAt, pointsToUse: 0,
    items: [{ menuItemId: 3001, quantity: 1 + Math.floor(Math.random() * 2) }, { menuItemId: 3003, quantity: 1 }],
  });
  const headers = { 'Content-Type': 'application/json', 'X-Member-Id': `${member}`, 'Idempotency-Key': `lt-${member}-${__VU}-${__ITER}` };
  const placed = http.post(`${ORDER}/orders`, body, { headers, tags: { name: 'place_order' } });
  placeLatency.add(placed.timings.duration);
  if (placed.status === 409) { rejected.add(1); return; }
  if (!check(placed, { 'order 201': (r) => r.status === 201 })) return;
  accepted.add(1);
  const orderId = placed.json('orderId');
  const paid = http.post(`${ORDER}/orders/${orderId}/payment`, JSON.stringify({ cardToken: 'tok_visa' }),
    { headers: { 'Content-Type': 'application/json', 'X-Member-Id': `${member}` }, tags: { name: 'start_payment' } });
  if (check(paid, { 'payment 202': (r) => r.status === 202 })) paymentAccepted.add(1);
}
