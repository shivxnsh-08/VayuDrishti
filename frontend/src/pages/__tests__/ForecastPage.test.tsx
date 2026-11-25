// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ForecastCandidate, StationSummary } from '../../api/types';

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client');
  return {
    ...actual,
    api: {
      stations: vi.fn(), forecastCandidates: vi.fn(), profile: vi.fn(), current: vi.fn(), history: vi.fn(),
      forecast: vi.fn(), modelInfo: vi.fn(), nearest: vi.fn(),
    },
  };
});
let authToken: string | null = 'tok';
vi.mock('../../lib/auth', () => ({ useAuth: () => ({ token: authToken, email: null, logout: vi.fn() }) }));

import { ApiError, api } from '../../api/client';
import ForecastPage from '../ForecastPage';
import { LocationProvider } from '../../lib/locationContext';

const m = api as unknown as Record<string, ReturnType<typeof vi.fn>>;

// ---- browser location mocks (no real location is ever read) ----------------------------------------------------
const geo = { getCurrentPosition: vi.fn() };
let permissionState: string | null = 'granted';
let position = { latitude: 28.6100, longitude: 77.2100 };
let positionError: { code: number } | null = null;

beforeEach(() => {
  Object.defineProperty(navigator, 'geolocation', { value: geo, configurable: true });
  Object.defineProperty(navigator, 'permissions', {
    configurable: true,
    value: { query: vi.fn(async () => (permissionState ? { state: permissionState } : Promise.reject(new Error('no api')))) },
  });
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
  geo.getCurrentPosition.mockReset().mockImplementation((ok: (p: unknown) => void, fail: (e: unknown) => void) => {
    if (positionError) fail(positionError);
    else ok({ coords: { ...position } });
  });
  try { localStorage.clear(); sessionStorage.clear(); } catch { /* ignore */ }
  authToken = 'tok';
  permissionState = 'granted';
  position = { latitude: 28.6100, longitude: 77.2100 };
  positionError = null;
  Object.values(m).forEach((f) => f.mockReset());

  m.stations.mockResolvedValue(STATIONS);
  m.forecastCandidates.mockResolvedValue(NEAR);
  m.profile.mockResolvedValue({ stationId: 'saved-st' });
  m.current.mockImplementation(async (id: string) => ({
    stationId: id, stationName: STATIONS.find((s) => s.id === id)?.name ?? id, ts: '2026-11-24T09:30:00Z', aqi: 180,
    category: 'MODERATE', dominantPollutant: 'PM25', stale: false, ageMinutes: 40,
  }));
  m.history.mockResolvedValue({ items: [], total: 0, page: 0, size: 500 });
  m.forecast.mockImplementation(async (id: string) => ({
    stationId: id, status: 'INSUFFICIENT_DATA', modelVersion: null, method: null, generatedAt: null, points: [],
    message: 'Only 13 hourly points available; need 24',
  }));
  m.modelInfo.mockResolvedValue({ available: true, info: { model_loaded: false, serving: 'BASELINE_PERSISTENCE' } });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const st = (id: string, name: string, city = 'Delhi') =>
  ({ id, name, city, state: 'Delhi', latitude: 28.6, longitude: 77.2, ts: null, aqi: 150, category: 'MODERATE',
     dominantPollutant: 'PM25', stale: false }) as unknown as StationSummary;
const STATIONS = [st('near-stale', 'Near Stale'), st('mid-short', 'Mid Short'), st('far-ready', 'Far Ready'),
  st('other-ready', 'Other Ready'), st('saved-st', 'Saved Station'), st('mumbai', 'Colaba, Mumbai', 'Mumbai')];
const cand = (id: string, distanceKm: number, o: Partial<ForecastCandidate> = {}): ForecastCandidate => ({
  id, name: STATIONS.find((s) => s.id === id)!.name, city: 'Delhi', state: 'Delhi', distanceKm,
  latestTs: '2026-11-24T09:30:00Z', aqi: 150, category: 'MODERATE', stale: false, validHours: 30, requiredHours: 24,
  forecastReady: true, reason: null, ...o,
});
const NEAR = [
  cand('near-stale', 0.6, { stale: true, forecastReady: false, reason: 'latest reading is 10 h old (stale)' }),
  cand('mid-short', 2.4, { validHours: 13, forecastReady: false, reason: 'only 13 of 24 hourly observations needed for a forecast' }),
  cand('far-ready', 8.3),
];

const renderAt = (url = '/forecast') => render(
  <MemoryRouter initialEntries={[url]}><LocationProvider><ForecastPage /></LocationProvider></MemoryRouter>);

describe('Forecast page - automatic nearest station', () => {
  it('with permission granted, selects the nearest eligible station and explains skipped closer ones', async () => {
    renderAt();
    expect(await screen.findByText('Nearest station to you: Far Ready · 8.3 km away')).toBeTruthy();
    expect(screen.getByText(/Near Stale \(0.6 km\): latest reading is 10 h old \(stale\)/)).toBeTruthy();
    expect(screen.getByText(/Mid Short \(2.4 km\): only 13 of 24/)).toBeTruthy();
    await waitFor(() => expect(m.current).toHaveBeenCalledWith('far-ready'));
    expect(m.forecastCandidates).toHaveBeenCalledWith(28.61, 77.21);
  });

  it('never shows a made-up forecast: insufficient history is reported as such', async () => {
    m.forecastCandidates.mockResolvedValue([NEAR[1]]);                    // nothing forecast-ready nearby
    renderAt();
    expect(await screen.findByText(/no station within 50 km has enough history for a forecast yet/i)).toBeTruthy();
    expect((await screen.findAllByText(/Only 13 hourly points available; need 24/)).length).toBeGreaterThan(0);
  });

  it('permission denied: does not ask for location, uses the saved station', async () => {
    permissionState = 'denied';
    renderAt();
    expect(await screen.findByText('Your saved station: Saved Station')).toBeTruthy();
    expect(screen.getByText(/Location access is blocked/)).toBeTruthy();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Use my location' })).toBeNull();   // never re-requested
    await waitFor(() => expect(m.current).toHaveBeenCalledWith('saved-st'));
  });

  it('permission not decided: explains first and only asks when the user clicks', async () => {
    permissionState = 'prompt';
    renderAt();
    expect(await screen.findByText('Show the forecast for the station nearest to you?')).toBeTruthy();
    expect(screen.getByText(/rounded to about 1 km, not stored, and not shared/)).toBeTruthy();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();

    fireEvent.click(screen.getAllByRole('button', { name: 'Use my location' })[0]);
    expect(await screen.findByText('Nearest station to you: Far Ready · 8.3 km away')).toBeTruthy();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('"Not now" hides the explanation without asking', async () => {
    permissionState = 'prompt';
    renderAt();
    fireEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    expect(screen.queryByText('Show the forecast for the station nearest to you?')).toBeNull();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
  });

  it('location unavailable: falls back to the saved station with the reason', async () => {
    positionError = { code: 2 };
    renderAt();
    expect(await screen.findByText('Your saved station: Saved Station')).toBeTruthy();
    expect(screen.getByText(/could not be determined/)).toBeTruthy();
  });

  it('no location and no saved station: asks the user to choose', async () => {
    permissionState = 'denied';
    authToken = null;
    renderAt();
    expect(await screen.findByText('Choose a monitoring station')).toBeTruthy();
    expect(m.current).not.toHaveBeenCalled();
  });

  it('signed-in user without a saved station gets the same choice prompt', async () => {
    permissionState = 'denied';
    m.profile.mockRejectedValue(new ApiError(404, 'Not found'));
    renderAt();
    expect(await screen.findByText('Choose a monitoring station')).toBeTruthy();
  });
});

describe('Forecast page - manual search', () => {
  it('a manual station (link or search) overrides location; "Back to my nearest station" returns', async () => {
    renderAt('/forecast?station=mumbai');
    expect(await screen.findByText('Selected manually: Colaba, Mumbai')).toBeTruthy();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    await waitFor(() => expect(m.current).toHaveBeenCalledWith('mumbai'));

    fireEvent.click(screen.getByRole('button', { name: 'Back to my nearest station' }));
    expect(await screen.findByText('Nearest station to you: Far Ready · 8.3 km away')).toBeTruthy();
  });

  it('searching by city and picking a station switches to manual selection', async () => {
    renderAt();
    await screen.findByText('Nearest station to you: Far Ready · 8.3 km away');
    fireEvent.change(screen.getByPlaceholderText(/Far Ready|Search a station/), { target: { value: 'mumbai' } });
    fireEvent.click(await screen.findByRole('button', { name: /Colaba, Mumbai/ }));
    expect(await screen.findByText('Selected manually: Colaba, Mumbai')).toBeTruthy();
    await waitFor(() => expect(m.current).toHaveBeenCalledWith('mumbai'));
  });
});

describe('Forecast page - automatic updates', () => {
  it('refreshes data every 5 minutes and keeps the station stable under GPS jitter, switching after a real move', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderAt();
    await screen.findByText('Nearest station to you: Far Ready · 8.3 km away');
    const forecastCalls = m.forecast.mock.calls.length;

    // 300 m of jitter, and another forecast-ready station now appears marginally closer: keep Far Ready.
    position = { latitude: 28.6127, longitude: 77.2100 };
    m.forecastCandidates.mockResolvedValue([...NEAR.slice(0, 2), cand('other-ready', 8.1), { ...NEAR[2], distanceKm: 8.2 }]);
    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000 + 100); });
    expect(m.forecast.mock.calls.length).toBeGreaterThan(forecastCalls);          // periodic refresh
    expect(screen.getByText(/Nearest station to you: Far Ready/)).toBeTruthy();
    expect(m.current).not.toHaveBeenCalledWith('other-ready');
    expect(screen.getByText(/Updates automatically every 5 minutes · last updated/)).toBeTruthy();

    // A real move of ~5 km: re-select.
    position = { latitude: 28.6550, longitude: 77.2100 };
    m.forecastCandidates.mockResolvedValue([cand('other-ready', 1.2), { ...NEAR[2], distanceKm: 6.0 }]);
    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000 + 100); });
    expect(await screen.findByText('Nearest station to you: Other Ready · 1.2 km away')).toBeTruthy();
  });
});
