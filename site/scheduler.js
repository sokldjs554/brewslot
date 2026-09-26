/*
 * BrewScheduler / PickupTimeAdvisor 의 JavaScript 이식판.
 * 원본: services/order-service/src/main/kotlin/com/brewslot/order/scheduling/domain/
 * 서버와 같은 입력에 같은 결과를 내는지 site/verify.mjs 가 서버에서 생성한 테스트 벡터 500개로 CI 에서 검증한다.
 * 시각은 epoch 초(정수), 슬롯은 5분(300초).
 */
(function (root) {
  const STATIONS = ['ESPRESSO', 'BLENDER', 'BREW_BAR'];
  const SLOT = 300;

  function productionSlots(pickup, freshMinutes, earliest) {
    const depth = Math.max(1, Math.ceil(freshMinutes / 5));
    const out = [];
    for (let k = 1; k <= depth; k++) {
      const s = pickup - k * SLOT;
      if (s >= earliest) out.push(s);
    }
    return out; // 늦은 슬롯부터
  }

  // usage: { stateOf(station, slot) -> {capacity, reserved} }
  function schedule(pickup, demands, usage, earliest) {
    const tentative = new Map();
    const key = (st, s) => st + '@' + s;
    const ordered = demands.slice().sort((a, b) => a.fresh - b.fresh || b.load - a.load); // 안정 정렬
    for (const d of ordered) {
      const slots = productionSlots(pickup, d.fresh, earliest);
      if (slots.length === 0) return { feasible: false, bottleneck: d.station, reason: 'NO_PRODUCTION_WINDOW' };
      const chosen = slots.find((s) => {
        const st = usage.stateOf(d.station, s);
        return st.capacity - st.reserved - (tentative.get(key(d.station, s)) || 0) >= d.load;
      });
      if (chosen === undefined) {
        const maxCap = Math.max(...slots.map((s) => usage.stateOf(d.station, s).capacity));
        return { feasible: false, bottleneck: d.station, reason: d.load > maxCap ? 'EXCEEDS_SLOT_CAPACITY' : 'CAPACITY_EXHAUSTED' };
      }
      tentative.set(key(d.station, chosen), (tentative.get(key(d.station, chosen)) || 0) + d.load);
    }
    const allocations = [...tentative.entries()].map(([k, units]) => {
      const [station, slot] = k.split('@');
      return { station, slot: Number(slot), units };
    });
    allocations.sort((a, b) => STATIONS.indexOf(a.station) - STATIONS.indexOf(b.station) || a.slot - b.slot);
    return { feasible: true, allocations };
  }

  function nearestAlternatives(requested, candidates, demands, usage, earliest, limit = 3) {
    return candidates
      .filter((t) => t !== requested)
      .sort((a, b) => Math.abs(a - requested) - Math.abs(b - requested) || b - a)
      .filter((t) => schedule(t, demands, usage, earliest).feasible)
      .slice(0, limit)
      .sort((a, b) => a - b);
  }

  function loadPercentBefore(pickup, usage) {
    return Math.max(...STATIONS.map((st) => {
      const s = usage.stateOf(st, pickup - SLOT);
      return s.capacity === 0 ? 0 : Math.round((s.reserved * 100) / s.capacity);
    }));
  }

  root.BrewScheduler = { STATIONS, SLOT, productionSlots, schedule, nearestAlternatives, loadPercentBefore };
})(typeof globalThis !== 'undefined' ? globalThis : window);
