import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { Coords, LocationPermission } from './location';
import type { StationChoice } from './stationChoice';

/**
 * Application-level location state, shared by every page and kept across internal navigation (the Forecast page
 * remounting must not forget a grant or ask again).
 *
 * - `browserPermission`: what the browser's Permissions API reports, kept live through its `change` event.
 * - `sessionGranted`: a location fix succeeded in this page session. Needed because some browsers keep reporting
 *   "prompt" for a temporary grant (e.g. Firefox without "Remember this decision"). It is cleared when the browser
 *   reports a revocation ("denied") or the end of a temporary grant ("granted" -> "prompt"), when a fix fails with
 *   PERMISSION_DENIED, and on a full reload (memory only - never stored).
 * - `lastFix`/`lastChoice`: the last position and chosen station, in memory only, so returning to Forecast within
 *   the refresh interval reuses them instead of requesting the position again. Coordinates are never written to
 *   storage.
 * - `dismissed`: "Not now" for the current browsing session (sessionStorage; it holds no location data).
 */
export interface LocationState {
  browserPermission: LocationPermission | null;
  sessionGranted: boolean;
  /** What the UI should act on: granted (persistent or this session), prompt, denied or unsupported. */
  effective: LocationPermission | null;
  dismissed: boolean;
  lastFix: { coords: Coords; at: number } | null;
  lastChoice: StationChoice | null;
  dismiss: () => void;
  recordFix: (coords: Coords, choice: StationChoice | null) => void;
  recordDenied: () => void;
}

const DISMISS_KEY = 'vd.locationPromptDismissed';

function readDismissed(): boolean {
  try {
    return sessionStorage.getItem(DISMISS_KEY) === '1';
  } catch {
    return false;
  }
}

const Ctx = createContext<LocationState | null>(null);

export function LocationProvider({ children }: { children: ReactNode }) {
  const [browserPermission, setBrowserPermission] = useState<LocationPermission | null>(null);
  const [sessionGranted, setSessionGranted] = useState(false);
  const [dismissed, setDismissed] = useState(readDismissed);
  const [lastFix, setLastFix] = useState<{ coords: Coords; at: number } | null>(null);
  const [lastChoice, setLastChoice] = useState<StationChoice | null>(null);
  const previous = useRef<LocationPermission | null>(null);

  // Read the browser's permission once per page load and follow its changes while the app is open.
  useEffect(() => {
    let status: PermissionStatus | null = null;
    let alive = true;
    const apply = (state: LocationPermission) => {
      const before = previous.current;
      previous.current = state;
      setBrowserPermission(state);
      if (state === 'denied' || (before === 'granted' && state === 'prompt')) {
        setSessionGranted(false);              // revoked, or a temporary grant ended
        setLastFix(null);
        setLastChoice(null);
      }
    };
    const onChange = () => { if (status) apply(status.state as LocationPermission); };
    (async () => {
      if (typeof navigator === 'undefined' || !navigator.geolocation) {
        if (alive) apply('unsupported');
        return;
      }
      try {
        const s = await navigator.permissions?.query({ name: 'geolocation' as PermissionName });
        if (!alive) return;
        if (!s) {
          apply('prompt');                     // no Permissions API: we only learn the answer by asking
          return;
        }
        status = s;
        apply(s.state as LocationPermission);
        s.addEventListener?.('change', onChange);
      } catch {
        if (alive) apply('prompt');
      }
    })();
    return () => {
      alive = false;
      status?.removeEventListener?.('change', onChange);
    };
  }, []);

  const dismiss = useCallback(() => {
    try {
      sessionStorage.setItem(DISMISS_KEY, '1');
    } catch { /* private mode: dismissal lasts for this page only */ }
    setDismissed(true);
  }, []);

  const recordFix = useCallback((coords: Coords, choice: StationChoice | null) => {
    setSessionGranted(true);
    setLastFix({ coords, at: Date.now() });
    setLastChoice(choice);
  }, []);

  const recordDenied = useCallback(() => {
    setSessionGranted(false);
    setLastFix(null);
    setLastChoice(null);
    previous.current = 'denied';
    setBrowserPermission('denied');
  }, []);

  const effective: LocationPermission | null = browserPermission === null ? null
    : browserPermission === 'denied' || browserPermission === 'unsupported' ? browserPermission
    : browserPermission === 'granted' || sessionGranted ? 'granted' : 'prompt';

  const value = useMemo<LocationState>(() => ({
    browserPermission, sessionGranted, effective, dismissed, lastFix, lastChoice, dismiss, recordFix, recordDenied,
  }), [browserPermission, sessionGranted, effective, dismissed, lastFix, lastChoice, dismiss, recordFix, recordDenied]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useLocationState(): LocationState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useLocationState must be used inside <LocationProvider>');
  return v;
}
