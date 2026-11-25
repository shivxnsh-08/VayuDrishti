// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ForecastCandidate, StationSummary } from '../../api/types';

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client');
  return {
    ...actual,
    api: { stations: vi.fn(), forecastCandidates: vi.fn(), profile: vi.fn(), current: vi.fn(), history: vi.fn(),
      forecast: vi.fn(), modelInfo: vi.fn(), nearest: vi.fn() },
  };
});
vi.mock('../../lib/auth', () => ({ useAuth: () => ({ token: 'tok', email: null, logout: vi.fn() }) }));

import { api } from '../../api/client';
import { LocationProvider } from '../../lib/locationContext';
import ForecastPage from '../ForecastPage';

const m = api as unknown as Record<string, ReturnType<typeof vi.fn>>;

/** A browser PermissionStatus whose state can change while the app is open (fires "change"). */
class FakeStatus extends EventTarget {
  constructor(public state: PermissionState) {
    super();
  }
  set(state: PermissionState) {
    this.state = state;
    this.dispatchEvent(new Event('change'));
  }
}

let status: FakeStatus | null;
let permissionsApi = true;
const geo = { getCurrentPosition: vi.fn() };
let positionError: { code: number } | null = null;

const BANNER = 'Show the forecast for the station nearest to you?';
const NEAREST = 'Nearest station to you: Ready Station · 3.2 km away';
const st = (id: string, name: string) => ({ id, name, city: 'Delhi', state: 'Delhi', latitude: 0, longitude: 0, ts: null,
  aqi: 150, category: 'MODERATE', dominantPollutant: 'PM25', stale: false }) as unknown as StationSummary;
const READY: ForecastCandidate = { id: 'ready', name: 'Ready Station', city: 'Delhi', state: 'Delhi', distanceKm: 3.2,
  latestTs: '2026-11-24T09:30:00Z', aqi: 150, category: 'MODERATE', stale: false, validHours: 30, requiredHours: 24,
  forecastReady: true, reason: null };

function setup(initial: PermissionState | 'none') {
  status = initial === 'none' ? null : new FakeStatus(initial);
  permissionsApi = initial !== 'none';
}

beforeEach(() => {
  Object.defineProperty(navigator, 'geolocation', { value: geo, configurable: true });
  Object.defineProperty(navigator, 'permissions', {
    configurable: true,
    get: () => (permissionsApi ? { query: vi.fn(async () => status) } : undefined),
  });
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
  positionError = null;
  geo.getCurrentPosition.mockReset().mockImplementation((ok: (p: unknown) => void, fail: (e: unknown) => void) => {
    if (positionError) fail(positionError);
    else ok({ coords: { latitude: 28.61, longitude: 77.21 } });
  });
  sessionStorage.clear();
  localStorage.clear();
  Object.values(m).forEach((f) => f.mockReset());
  m.stations.mockResolvedValue([st('ready', 'Ready Station'), st('saved-st', 'Saved Station')]);
  m.forecastCandidates.mockResolvedValue([READY]);
  m.profile.mockResolvedValue({ stationId: 'saved-st' });
  m.current.mockImplementation(async (id: string) => ({ stationId: id, stationName: id, ts: '2026-11-24T09:30:00Z', aqi: 150,
    category: 'MODERATE', dominantPollutant: 'PM25', stale: false, ageMinutes: 30 }));
  m.history.mockResolvedValue({ items: [], total: 0, page: 0, size: 500 });
  m.forecast.mockImplementation(async (id: string) => ({ stationId: id, status: 'INSUFFICIENT_DATA', modelVersion: null,
    method: null, generatedAt: null, points: [], message: 'Only 13 hourly points available; need 24' }));
  m.modelInfo.mockResolvedValue({ available: true, info: { model_loaded: false, serving: 'BASELINE_PERSISTENCE' } });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/** The real app layout in miniature: one LocationProvider above the routes, like main.tsx. */
function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/forecast']}>
      <LocationProvider>
        <nav>
          <Link to="/forecast">Forecast tab</Link>
          <Link to="/profile">Profile tab</Link>
        </nav>
        <Routes>
          <Route path="/forecast" element={<ForecastPage />} />
          <Route path="/profile" element={<div>Profile page</div>} />
        </Routes>
      </LocationProvider>
    </MemoryRouter>,
  );
}

async function goTo(tab: 'Forecast' | 'Profile') {
  fireEvent.click(screen.getByRole('link', { name: `${tab} tab` }));
  if (tab === 'Profile') await screen.findByText('Profile page');
}

/** A full page reload: the whole app (and its in-memory state) is recreated; sessionStorage survives. */
function reload() {
  cleanup();
  return renderApp();
}

describe('location permission across navigation', () => {
  it('1. permission "prompt": the explanation banner shows and nothing is requested', async () => {
    setup('prompt');
    renderApp();
    expect(await screen.findByText(BANNER)).toBeTruthy();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
  });

  it('2+4+5. "Allow this time" (browser keeps reporting prompt): no banner again after Forecast → Profile → Forecast', async () => {
    setup('prompt');
    renderApp();
    fireEvent.click(await screen.findByRole('button', { name: 'Use my location' }));
    expect(await screen.findByText(NEAREST)).toBeTruthy();

    await goTo('Profile');
    await goTo('Forecast');
    expect(await screen.findByText(NEAREST)).toBeTruthy();
    expect(screen.queryByText(BANNER)).toBeNull();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);            // remount reused the recent fix
  });

  it('3+13. persistent permission: selected automatically, kept across tabs, no repeated requests', async () => {
    setup('granted');
    renderApp();
    expect(await screen.findByText(NEAREST)).toBeTruthy();
    for (let i = 0; i < 3; i++) {
      await goTo('Profile');
      await goTo('Forecast');
      expect(await screen.findByText(NEAREST)).toBeTruthy();
    }
    expect(screen.queryByText(BANNER)).toBeNull();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('6. reload while permission is still granted: no banner, nearest station found again', async () => {
    setup('granted');
    renderApp();
    await screen.findByText(NEAREST);
    status = new FakeStatus('granted');
    reload();
    expect(await screen.findByText(NEAREST)).toBeTruthy();
    expect(screen.queryByText(BANNER)).toBeNull();
  });

  it('7. reload after a temporary grant expired: the request UI is back and nothing is requested', async () => {
    setup('prompt');
    renderApp();
    fireEvent.click(await screen.findByRole('button', { name: 'Use my location' }));
    await screen.findByText(NEAREST);
    status = new FakeStatus('prompt');                                   // browser forgot the one-time grant
    reload();
    expect(await screen.findByText(BANNER)).toBeTruthy();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);            // only the original, explicit request
  });

  it('a temporary grant ending while the app is open (granted → prompt) restores the request UI', async () => {
    setup('granted');
    renderApp();
    await screen.findByText(NEAREST);
    act(() => status!.set('prompt'));
    expect(await screen.findByText(BANNER)).toBeTruthy();
    expect(screen.queryByText(NEAREST)).toBeNull();
  });

  it('8. permission revoked while open: denied state with instructions, and no more location requests', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setup('granted');
    renderApp();
    await screen.findByText(NEAREST);
    act(() => status!.set('denied'));
    expect(await screen.findByText(/Location is blocked for this site in your browser/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Use my location' })).toBeNull();
    expect(screen.getByText('Your saved station: Saved Station')).toBeTruthy();
    await act(async () => { await vi.advanceTimersByTimeAsync(11 * 60_000); });
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('9. permission denied: no prompt, instructions instead, never re-requested on navigation', async () => {
    setup('denied');
    renderApp();
    expect(await screen.findByText(/allow location for this site in your browser's site settings/)).toBeTruthy();
    await goTo('Profile');
    await goTo('Forecast');
    await screen.findByText('Your saved station: Saved Station');
    expect(screen.queryByText(BANNER)).toBeNull();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
  });

  it('a denial in the browser dialog is respected and not asked again', async () => {
    setup('prompt');
    positionError = { code: 1 };
    renderApp();
    fireEvent.click(await screen.findByRole('button', { name: 'Use my location' }));
    expect(await screen.findByText(/Location is blocked for this site/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Use my location' })).toBeNull();
    await goTo('Profile');
    await goTo('Forecast');
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('10+11. "Not now" stays dismissed across tabs and reloads in this session; manual request still works', async () => {
    setup('prompt');
    renderApp();
    fireEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    expect(screen.queryByText(BANNER)).toBeNull();
    await goTo('Profile');
    await goTo('Forecast');
    await screen.findByText('Your saved station: Saved Station');
    expect(screen.queryByText(BANNER)).toBeNull();
    status = new FakeStatus('prompt');
    reload();
    await screen.findByText('Your saved station: Saved Station');
    expect(screen.queryByText(BANNER)).toBeNull();                      // same browsing session
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Use my location' }));   // explicit request later
    expect(await screen.findByText(NEAREST)).toBeTruthy();
    expect(sessionStorage.getItem('vd.locationPromptDismissed')).toBe('1');
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/28\.6|77\.2/);   // no coordinates stored
  });

  it('12. browser without the Permissions API: treated as prompt, explicit request works', async () => {
    setup('none');
    renderApp();
    expect(await screen.findByText(BANNER)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Use my location' }));
    expect(await screen.findByText(NEAREST)).toBeTruthy();
    await goTo('Profile');
    await goTo('Forecast');
    expect(await screen.findByText(NEAREST)).toBeTruthy();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('14. five-minute refresh while Forecast is open, and a fresh fix after returning later', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setup('granted');
    renderApp();
    await screen.findByText(NEAREST);
    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000 + 100); });
    await waitFor(() => expect(geo.getCurrentPosition).toHaveBeenCalledTimes(2));    // periodic re-check

    await goTo('Profile');
    await act(async () => { await vi.advanceTimersByTimeAsync(6 * 60_000); });
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(2);            // nothing while Forecast is not open
    await goTo('Forecast');
    await waitFor(() => expect(geo.getCurrentPosition).toHaveBeenCalledTimes(3));    // older than 5 min: new fix
    expect(screen.queryByText(BANNER)).toBeNull();                      // granted: no prompt
    expect(m.forecastCandidates).toHaveBeenLastCalledWith(28.61, 77.21);
  });

  it('15. nearest-station fallback is unchanged: no station within 50 km falls back to the saved station', async () => {
    setup('granted');
    m.forecastCandidates.mockResolvedValue([{ ...READY, distanceKm: 80 }]);
    renderApp();
    expect(await screen.findByText('Your saved station: Saved Station')).toBeTruthy();
    expect(screen.getByText(/no active monitoring station within 50 km/)).toBeTruthy();
    await goTo('Profile');
    await goTo('Forecast');
    await screen.findByText('Your saved station: Saved Station');
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);            // no extra request for the empty result
  });
});
