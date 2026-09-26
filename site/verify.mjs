// 서버(Kotlin) 스케줄러가 만든 테스트 벡터로 JS 이식판을 검증한다.
//   node site/verify.mjs services/order-service/build/scheduler-vectors.json
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
await import(join(dirname(fileURLToPath(import.meta.url)), 'scheduler.js'));
const { schedule, nearestAlternatives } = globalThis.BrewScheduler;

const cases = JSON.parse(readFileSync(process.argv[2], 'utf8'));
let failed = 0;
cases.forEach((c, i) => {
  const reserved = new Map(c.reserved.map((r) => [r.station + '@' + r.slot, r.units]));
  const usage = { stateOf: (st, s) => ({ capacity: c.capacity[st], reserved: reserved.get(st + '@' + s) || 0 }) };
  const got = schedule(c.pickup, c.demands, usage, c.earliest);
  const alts = nearestAlternatives(c.pickup, c.candidates, c.demands, usage, c.earliest);
  const same = JSON.stringify(got) === JSON.stringify(c.result) && JSON.stringify(alts) === JSON.stringify(c.alternatives);
  if (!same) {
    failed++;
    if (failed <= 3) console.error(`case ${i} mismatch\n  server: ${JSON.stringify(c.result)} ${c.alternatives}\n  js    : ${JSON.stringify(got)} ${alts}`);
  }
});
console.log(`scheduler vectors: ${cases.length - failed}/${cases.length} identical`);
process.exit(failed ? 1 : 0);
