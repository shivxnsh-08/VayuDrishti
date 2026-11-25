import { describe, expect, it } from 'vitest';
import type { StationSummary } from '../../api/types';
import { cityAqi, rankCities } from '../cities';

const st = (id: string, city: string | null, aqi: number | null, stale = false): StationSummary =>
  ({ id, name: id, city, state: 'S', latitude: 0, longitude: 0, ts: '2026-11-24T09:30:00Z', aqi,
     category: null, dominantPollutant: null, stale } as unknown as StationSummary);

describe('city AQI', () => {
  it('averages only fresh stations with an AQI, never stale ones', () => {
    const rows = cityAqi([st('a', 'Delhi', 300), st('b', 'Delhi', 100), st('c', 'Delhi', 500, true), st('d', 'Delhi', null)]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ city: 'Delhi', aqi: 200, stations: 2, maxStationAqi: 300, category: 'MODERATE' });
  });

  it('ranks most and least polluted and counts cities', () => {
    const r = rankCities([st('a', 'X', 50), st('b', 'Y', 250), st('c', 'Z', 150), st('d', null, 400)], 2);
    expect(r.most.map((c) => c.city)).toEqual(['Y', 'Z']);
    expect(r.least.map((c) => c.city)).toEqual(['X', 'Z']);
    expect(r.cityCount).toBe(3);
  });
});
