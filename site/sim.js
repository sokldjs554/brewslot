/*
 * 러시아워 비교 시뮬레이션 (데모 페이지용 모델).
 * 같은 손님 흐름을 두 방식으로 처리한다.
 *  - 건수 제한: 픽업 시각(5분)당 주문 N건까지 받는 흔한 방식. 바리스타는 픽업이 이른 음료부터 만든다.
 *  - BrewSlot : 서버와 같은 스케줄러(scheduler.js)로 스테이션별 부하 · 신선도 창 안에서만 받는다.
 * 가정: 매장은 설정된 용량만큼 정확히 만들고, 손님은 제안된 시각이 원래보다 15분 이내면 받아들인다.
 */
(function (root) {
  const S = root.BrewScheduler, SLOT = S.SLOT;

  function rng(seed) { return function () { seed |= 0; seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
  const ceilSlot = (t) => Math.ceil(t / SLOT) * SLOT;

  // base: 07:00 의 epoch 초. 손님은 07:10 ~ 09:00 에 주문, 원하는 픽업은 08:30 ~ 08:55 에 몰린다.
  function demand(base, n, menu, seed) {
    const r = rng(seed), out = [];
    const weights = [30, 26, 14, 14, 9, 7]; // 메뉴별 주문 비중 (아메리카노가 가장 많다)
    const pickMenu = () => { let x = r() * 100; for (let i = 0; i < menu.length; i++) { if ((x -= weights[i]) < 0) return menu[i]; } return menu[0]; };
    for (let i = 0; i < n; i++) {
      const arrival = base + 600 + Math.floor(r() * 110 * 60);
      const peak = base + 90 * 60 + Math.floor(r() * 6) * SLOT; // 08:30 ~ 08:55
      let want = r() < 0.6 ? peak : arrival + (2 + Math.floor(r() * 6)) * SLOT;
      want = Math.max(ceilSlot(arrival + 5 * 60), ceilSlot(want));
      const k = 1 + (r() < 0.35 ? 1 : 0) + (r() < 0.12 ? 1 : 0);
      const items = Array.from({ length: k }, pickMenu);
      out.push({ id: i + 1, arrival, want, items });
    }
    return out.sort((a, b) => a.arrival - b.arrival);
  }

  const demandsOf = (items) => items.map((m) => ({ station: m.station, load: m.load, fresh: m.fresh }));
  const TOL = 15 * 60;

  function brewslot(customers, cap, base) {
    const reserved = new Map();
    const usage = { stateOf: (st, s) => ({ capacity: cap[st], reserved: reserved.get(st + '@' + s) || 0 }) };
    const orders = [];
    let lost = 0, shifted = 0;
    for (const c of customers) {
      const earliest = ceilSlot(c.arrival);
      const d = demandsOf(c.items);
      let promise = c.want;
      let r = S.schedule(promise, d, usage, earliest);
      if (!r.feasible) {
        const cands = []; for (let t = earliest + SLOT; t <= base + 3 * 3600; t += SLOT) cands.push(t);
        const alt = S.nearestAlternatives(c.want, cands, d, usage, earliest).find((t) => Math.abs(t - c.want) <= TOL);
        if (alt === undefined) { lost++; continue; }
        promise = alt; r = S.schedule(promise, d, usage, earliest); shifted++;
      }
      r.allocations.forEach((a) => reserved.set(a.station + '@' + a.slot, (reserved.get(a.station + '@' + a.slot) || 0) + a.units));
      const ready = Math.max(...r.allocations.map((a) => a.slot + SLOT));
      orders.push({ c, promise, ready, late: Math.max(0, ready - promise) });
    }
    return summarize(orders, lost, shifted);
  }

  function countCap(customers, cap, perSlot, base) {
    const count = new Map(), orders = [];
    let lost = 0, shifted = 0;
    for (const c of customers) {
      let promise = c.want;
      if ((count.get(promise) || 0) >= perSlot) {
        const earliest = ceilSlot(c.arrival) + SLOT;
        const alts = [];
        for (let t = earliest; t <= base + 3 * 3600; t += SLOT) if ((count.get(t) || 0) < perSlot && Math.abs(t - c.want) <= TOL) alts.push(t);
        alts.sort((a, b) => Math.abs(a - c.want) - Math.abs(b - c.want) || b - a);
        if (!alts.length) { lost++; continue; }
        promise = alts[0]; shifted++;
      }
      count.set(promise, (count.get(promise) || 0) + 1);
      orders.push({ c, promise, ready: 0, late: 0, drinks: c.items.map((m) => ({ m, done: 0 })) });
    }
    // 제조: 슬롯마다 스테이션 용량만큼, 픽업이 이른 음료부터. 신선도 창보다 일찍은 만들지 않는다.
    const pending = orders.flatMap((o) => o.drinks.map((d) => ({ o, d })));
    for (let s = base; pending.some((x) => !x.d.done) && s < base + 6 * 3600; s += SLOT) {
      const left = { ...cap };
      pending.filter((x) => !x.d.done && x.o.c.arrival <= s && s >= x.o.promise - Math.max(1, Math.ceil(x.d.m.fresh / 5)) * SLOT)
        .sort((a, b) => a.o.promise - b.o.promise || a.o.c.arrival - b.o.c.arrival)
        .forEach((x) => { if (left[x.d.m.station] >= x.d.m.load) { left[x.d.m.station] -= x.d.m.load; x.d.done = s + SLOT; } });
    }
    orders.forEach((o) => { o.ready = Math.max(...o.drinks.map((d) => d.done)); o.late = Math.max(0, o.ready - o.promise); });
    return summarize(orders, lost, shifted);
  }

  function summarize(orders, lost, shifted) {
    const late = orders.filter((o) => o.late > 0);
    return {
      orders, lost, shifted,
      accepted: orders.length,
      onTime: orders.length - late.length,
      onTimeRate: orders.length ? (orders.length - late.length) / orders.length : 1,
      avgLateMin: late.length ? late.reduce((s, o) => s + o.late, 0) / late.length / 60 : 0,
      maxLateMin: late.length ? Math.max(...late.map((o) => o.late)) / 60 : 0,
    };
  }

  root.RushSim = { demand, brewslot, countCap };
})(typeof globalThis !== 'undefined' ? globalThis : window);
