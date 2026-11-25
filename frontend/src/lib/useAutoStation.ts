import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { type Coords, LocationError, currentPosition } from './location';
import { useLocationState } from './locationContext';
import { type StationChoice, chooseStation, distanceKm, keepCurrent } from './stationChoice';

/** How often the automatic selection re-checks location and station freshness (backend data changes hourly). */
export const AUTO_REFRESH_MS = 5 * 60_000;

export type AutoState =
  | { kind: 'idle' }                                   // manual selection: location is not used at all
  | { kind: 'checking' }
  | { kind: 'locating' }
  | { kind: 'auto'; choice: StationChoice; updatedAt: number }
  | { kind: 'saved'; stationId: string; reason: string }
  | { kind: 'none'; reason: string };

/**
 * Automatic station selection for the Forecast page, on top of the app-level {@link useLocationState}:
 * - granted (persistently, or earlier in this page session): reuse the last fix if it is younger than
 *   AUTO_REFRESH_MS, otherwise take a new one - never prompting;
 * - prompt: the browser is only asked when the user clicks "Use my location";
 * - denied / unsupported: never asked; the saved Profile station is used, else the user chooses.
 * While automatic it re-checks every AUTO_REFRESH_MS (only while permission stays granted) and keeps the station
 * unless the user moved more than 2 km or a better-suited station appears.
 */
export function useAutoStation(enabled: boolean, token: string | null) {
  const loc = useLocationState();
  const locRef = useRef(loc);
  locRef.current = loc;
  const [state, setState] = useState<AutoState>(enabled ? { kind: 'checking' } : { kind: 'idle' });
  const stateRef = useRef(state);
  stateRef.current = state;
  const coordsRef = useRef<Coords | null>(loc.lastFix?.coords ?? null);
  const currentRef = useRef<string | null>(loc.lastChoice?.station.id ?? null);
  const explicitRef = useRef(false);

  const fallback = useCallback(async (reason: string) => {
    if (token) {
      try {
        const p = await api.profile(token);
        if (p.stationId) {
          setState({ kind: 'saved', stationId: p.stationId, reason });
          return;
        }
      } catch { /* no profile (404) or signed out: fall through */ }
    }
    setState({ kind: 'none', reason });
  }, [token]);

  const locate = useCallback(async () => {
    setState({ kind: 'locating' });
    try {
      const coords = await currentPosition();
      coordsRef.current = coords;
      const choice = chooseStation(await api.forecastCandidates(coords.lat, coords.lon));
      locRef.current.recordFix(coords, choice);
      if (!choice) {
        await fallback('There is no active monitoring station within 50 km of your location.');
        return;
      }
      currentRef.current = choice.station.id;
      setState({ kind: 'auto', choice, updatedAt: Date.now() });
    } catch (e) {
      if (e instanceof LocationError) {
        if (e.kind === 'denied') locRef.current.recordDenied();
        await fallback(e.message);
      } else {
        await fallback(`Nearby stations could not be loaded (${(e as Error).message}).`);
      }
    }
  }, [fallback]);

  // Decide whenever the page (re)opens or the browser permission changes - without prompting unless asked.
  const effective = loc.effective;
  useEffect(() => {
    if (!enabled) {
      setState({ kind: 'idle' });
      return;
    }
    if (effective === null) {
      setState({ kind: 'checking' });                // the provider is still reading the browser permission
      return;
    }
    const asked = explicitRef.current;
    explicitRef.current = false;
    if (effective === 'granted') {
      const { lastFix, lastChoice } = locRef.current;
      if (lastFix && Date.now() - lastFix.at < AUTO_REFRESH_MS) {
        coordsRef.current = lastFix.coords;          // returning to Forecast: reuse, no new location request
        if (lastChoice) {
          currentRef.current = lastChoice.station.id;
          setState({ kind: 'auto', choice: lastChoice, updatedAt: lastFix.at });
        } else {
          void fallback('There is no active monitoring station within 50 km of your location.');
        }
        return;
      }
      void locate();
      return;
    }
    if (asked && effective === 'prompt') {
      void locate();
      return;
    }
    void fallback(effective === 'denied' ? 'Location access is blocked in this browser.'
      : effective === 'unsupported' ? 'Location is not available in this browser.'
        : 'Your location has not been shared.');
  }, [enabled, effective, locate, fallback]);

  // Periodic re-check while automatic, and only while permission stays granted (never triggers a prompt).
  const auto = state.kind === 'auto';
  useEffect(() => {
    if (!enabled || !auto) return undefined;
    const id = setInterval(async () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
      if (locRef.current.effective !== 'granted') return;
      try {
        const coords = await currentPosition();
        const moved = coordsRef.current ? distanceKm(coordsRef.current, coords) : Number.POSITIVE_INFINITY;
        const candidates = await api.forecastCandidates(coords.lat, coords.lon);
        const next = chooseStation(candidates);
        if (keepCurrent(currentRef.current, next, candidates, moved)) {
          const cur = candidates.find((c) => c.id === currentRef.current);
          const s = stateRef.current;
          if (s.kind === 'auto' && cur) {
            const choice = { ...s.choice, station: cur };
            locRef.current.recordFix(coordsRef.current ?? coords, choice);
            setState({ ...s, choice, updatedAt: Date.now() });
          }
          return;
        }
        coordsRef.current = coords;
        locRef.current.recordFix(coords, next);
        if (next) {
          currentRef.current = next.station.id;
          setState({ kind: 'auto', choice: next, updatedAt: Date.now() });
        }
      } catch (e) {
        if (e instanceof LocationError && e.kind === 'denied') locRef.current.recordDenied();
        /* otherwise keep the current selection; the next tick retries */
      }
    }, AUTO_REFRESH_MS);
    return () => clearInterval(id);
  }, [enabled, auto]);

  /** "Use my location" / "My nearest station": an explicit request (may show the browser prompt once). */
  const requestNearest = useCallback(() => {
    if (effective === 'denied' || effective === 'unsupported') return;
    if (enabled) {
      void locate();
    } else {
      explicitRef.current = true;   // picked up when the manual selection is cleared and the hook re-enables
    }
  }, [enabled, locate, effective]);

  const stationId = state.kind === 'auto' ? state.choice.station.id : state.kind === 'saved' ? state.stationId : null;
  return { state, permission: effective, stationId, requestNearest };
}
