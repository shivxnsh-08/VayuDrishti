import { describe, expect, it } from 'vitest';
import type { ForecastCandidate } from '../../api/types';
import { MAX_AUTO_KM, chooseStation, distanceKm, keepCurrent } from '../stationChoice';

const cand = (id: string, distanceKm: number, o: Partial<ForecastCandidate> = {}): ForecastCandidate => ({
  id, name: id.toUpperCase(), city: 'Delhi', state: 'Delhi', distanceKm, latestTs: '2026-11-24T09:30:00Z', aqi: 150,
  category: 'MODERATE', stale: false, validHours: 30, requiredHours: 24, forecastReady: true, reason: null, ...o,
});
const stale = (id: string, km: number) => cand(id, km, { stale: true, forecastReady: false, reason: 'latest reading is 10 h old (stale)' });
const short = (id: string, km: number) => cand(id, km, { validHours: 10, forecastReady: false, reason: 'only 10 of 24 hourly observations needed for a forecast' });

describe('chooseStation (nearest eligible station)', () => {
  it('picks the nearest station when it is eligible', () => {
    expect(chooseStation([cand('a', 1.2), cand('b', 3)])).toMatchObject({ station: { id: 'a' }, forecastReady: true, excluded: [], note: null });
  });

  it('skips closer stale or short-history stations and says why', () => {
    const c = chooseStation([stale('a', 0.8), short('b', 2.1), cand('c', 6.4)])!;
    expect(c.station.id).toBe('c');
    expect(c.excluded).toEqual([
      { id: 'a', name: 'A', distanceKm: 0.8, reason: 'latest reading is 10 h old (stale)' },
      { id: 'b', name: 'B', distanceKm: 2.1, reason: 'only 10 of 24 hourly observations needed for a forecast' },
    ]);
  });

  it('when no station can be forecast yet, uses the nearest one with current data and says so', () => {
    const c = chooseStation([stale('a', 1), short('b', 2), short('c', 3)])!;
    expect(c).toMatchObject({ station: { id: 'b' }, forecastReady: false });
    expect(c.note).toMatch(/enough history for a forecast yet/);
  });

  it('labels an all-stale neighbourhood as stale instead of presenting it as current', () => {
    const c = chooseStation([stale('a', 1), stale('b', 2)])!;
    expect(c).toMatchObject({ station: { id: 'a' }, forecastReady: false });
    expect(c.note).toMatch(/stale data/);
  });

  it('does not present a far-away station as "near you"', () => {
    expect(chooseStation([cand('far', MAX_AUTO_KM + 1)])).toBeNull();
    expect(chooseStation([])).toBeNull();
  });
});

describe('keepCurrent (stable selection)', () => {
  const cands = [cand('a', 1.0), cand('b', 0.9)];      // b became marginally closer (GPS jitter)
  it('keeps the station for small moves even if a same-tier neighbour is now closer', () => {
    expect(keepCurrent('a', chooseStation(cands), cands, 0.4)).toBe(true);
  });
  it('re-selects after a material move', () => {
    expect(keepCurrent('a', chooseStation(cands), cands, 2.5)).toBe(false);
  });
  it('switches when the current station becomes unsuitable and a better one exists', () => {
    const now = [stale('a', 1.0), cand('b', 1.5)];
    expect(keepCurrent('a', chooseStation(now), now, 0)).toBe(false);
  });
  it('switches when the current station disappears', () => {
    const now = [cand('b', 1.5)];
    expect(keepCurrent('a', chooseStation(now), now, 0)).toBe(false);
  });
});

describe('distanceKm', () => {
  it('computes great-circle distance', () => {
    expect(distanceKm({ lat: 28.6139, lon: 77.209 }, { lat: 19.076, lon: 72.8777 })).toBeGreaterThan(1140);
    expect(distanceKm({ lat: 28.6139, lon: 77.209 }, { lat: 19.076, lon: 72.8777 })).toBeLessThan(1160);
    expect(distanceKm({ lat: 28.6, lon: 77.2 }, { lat: 28.6, lon: 77.2 })).toBe(0);
  });
});
