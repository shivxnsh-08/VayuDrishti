import type { Category, StationSummary } from '../api/types';
import { categorize } from './aqi';

export interface CityAqi {
  city: string;
  state: string | null;
  aqi: number;            // mean of the city's reporting stations, rounded
  category: Category;
  stations: number;       // stations that contributed
  maxStationAqi: number;
  latestTs: string;
}

/**
 * City-level AQI derived from real station data (GET /api/v1/stations): the mean AQI of the city's
 * stations that are reporting now (not stale). Cities are keyed by city AND state because some names
 * exist in several states (e.g. Aurangabad). No values are invented: a city with no fresh station
 * is simply absent. See docs/frontend-api-gaps.md for the server-side endpoint this could move to.
 */
export function cityAqi(stations: StationSummary[]): CityAqi[] {
  const groups = new Map<string, StationSummary[]>();
  for (const s of stations) {
    if (s.aqi == null || s.stale || !s.city) continue;
    const key = `${s.city}|${s.state ?? ''}`;
    const list = groups.get(key);
    if (list) list.push(s);
    else groups.set(key, [s]);
  }
  return [...groups.values()].map((list) => {
    const mean = list.reduce((sum, s) => sum + (s.aqi as number), 0) / list.length;
    return {
      city: list[0].city as string,
      state: list[0].state,
      aqi: Math.round(mean),
      category: categorize(mean),
      stations: list.length,
      maxStationAqi: Math.max(...list.map((s) => s.aqi as number)),
      latestTs: list.map((s) => s.ts as string).sort().pop() as string,
    };
  });
}

export function rankCities(stations: StationSummary[], n = 8) {
  const cities = cityAqi(stations);
  const byAqi = [...cities].sort((a, b) => b.aqi - a.aqi || a.city.localeCompare(b.city));
  return {
    most: byAqi.slice(0, n),
    least: [...byAqi].reverse().slice(0, n),
    cityCount: cities.length,
  };
}
