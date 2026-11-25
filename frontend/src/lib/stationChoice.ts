import type { ForecastCandidate } from '../api/types';

/** Stations further than this are not "near you": better to say so than to show a distant city's forecast. */
export const MAX_AUTO_KM = 50;
/** Re-select only after a real move; GPS jitter of a few hundred metres must not switch stations. */
export const MOVE_THRESHOLD_KM = 2;

export interface Exclusion {
  id: string;
  name: string;
  distanceKm: number;
  reason: string;
}

export interface StationChoice {
  station: ForecastCandidate;
  forecastReady: boolean;
  /** Closer stations that were skipped, with the reason. */
  excluded: Exclusion[];
  /** Why the chosen station is not ideal (no forecast yet / stale), or null. */
  note: string | null;
}

/** 2 = forecast-ready, 1 = fresh data but no forecast yet, 0 = stale or no data. */
export function tier(c: ForecastCandidate): number {
  return c.forecastReady ? 2 : c.stale ? 0 : 1;
}

function exclusions(before: ForecastCandidate[]): Exclusion[] {
  return before.map((c) => ({ id: c.id, name: c.name, distanceKm: c.distanceKm,
    reason: c.reason ?? (c.stale ? 'data is stale' : 'not enough history for a forecast') }));
}

/**
 * Picks the station for an automatic, location-based forecast from candidates sorted by distance:
 * the nearest forecast-ready one within MAX_AUTO_KM; otherwise the nearest one with fresh data (honestly
 * labelled as having no forecast yet); otherwise the nearest one, labelled stale. Null if nothing is near.
 */
export function chooseStation(candidates: ForecastCandidate[], maxKm = MAX_AUTO_KM): StationChoice | null {
  const near = candidates.filter((c) => c.distanceKm <= maxKm);
  if (near.length === 0) return null;
  const readyIdx = near.findIndex((c) => c.forecastReady);
  if (readyIdx >= 0) {
    return { station: near[readyIdx], forecastReady: true, excluded: exclusions(near.slice(0, readyIdx)), note: null };
  }
  const freshIdx = near.findIndex((c) => !c.stale);
  if (freshIdx >= 0) {
    return {
      station: near[freshIdx], forecastReady: false, excluded: exclusions(near.slice(0, freshIdx)),
      note: `No station within ${maxKm} km has enough history for a forecast yet, so this is the nearest station with `
        + 'current data. Its forecast appears automatically once it has enough observations.',
    };
  }
  return {
    station: near[0], forecastReady: false, excluded: [],
    note: `Every station within ${maxKm} km has stale data. This is the nearest one; its readings are not current.`,
  };
}

/**
 * Keep the current automatic station unless the user moved materially, it is gone, or a strictly better kind of
 * station (forecast-ready > fresh > stale) is now available. Same-tier neighbours never cause a switch.
 */
export function keepCurrent(currentId: string | null, next: StationChoice | null, candidates: ForecastCandidate[],
                            movedKm: number): boolean {
  if (!currentId || !next) return false;
  if (movedKm > MOVE_THRESHOLD_KM) return false;
  const current = candidates.find((c) => c.id === currentId && c.distanceKm <= MAX_AUTO_KM);
  if (!current) return false;
  return tier(current) >= tier(next.station);
}

/** Great-circle distance in km. */
export function distanceKm(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const r = (d: number) => (d * Math.PI) / 180;
  const dLat = r(b.lat - a.lat);
  const dLon = r(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}
