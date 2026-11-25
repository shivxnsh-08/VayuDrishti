// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Profile, StationSummary, TelegramStatus } from '../../api/types';

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client');
  return {
    ...actual,
    api: {
      profile: vi.fn(), saveProfile: vi.fn(), stations: vi.fn(), deleteAccount: vi.fn(), nearest: vi.fn(),
      telegramStatus: vi.fn(), telegramLink: vi.fn(), telegramUnlink: vi.fn(), telegramTest: vi.fn(),
    },
  };
});
vi.mock('../../lib/auth', () => ({ useAuth: () => ({ token: 'tok', email: 'me@example.test', logout: vi.fn() }) }));

import { ApiError, api } from '../../api/client';
import ProfilePage from '../ProfilePage';

const m = api as unknown as Record<string, ReturnType<typeof vi.fn>>;

const STATIONS = [
  { id: 'ito-delhi-cpcb', name: 'ITO, Delhi - CPCB', city: 'Delhi', state: 'Delhi', latitude: 28.63, longitude: 77.24,
    ts: null, aqi: 180, category: 'MODERATE', dominantPollutant: 'PM25', stale: false },
  { id: 'anand-vihar-delhi-dpcc', name: 'Anand Vihar, Delhi - DPCC', city: 'Delhi', state: 'Delhi', latitude: 28.65,
    longitude: 77.32, ts: null, aqi: 250, category: 'POOR', dominantPollutant: 'PM10', stale: false },
] as unknown as StationSummary[];

const SAVED: Profile = {
  healthProfile: 'ASTHMA', latitude: 28.63, longitude: 77.24, homeCity: null, stationId: 'ito-delhi-cpcb',
  language: 'hi', alertMinCategory: null, effectiveMinCategory: 'MODERATE', quietStart: '23:00:00', quietEnd: '06:00:00',
  severeOverridesQuiet: true, telegramLinked: true, morningBriefingEnabled: true, morningBriefingTime: '06:30:00',
} as Profile;
const TELEGRAM: TelegramStatus = {
  configured: true, botState: 'OK', botUsername: 'test_bot', botLink: 'https://t.me/test_bot', linked: true,
};

const settings = () => document.getElementById('profile-settings') as HTMLElement;
const isOpen = () => settings().getAttribute('data-state') === 'open';

beforeEach(() => {
  Object.values(m).forEach((f) => f.mockReset());
  m.stations.mockResolvedValue(STATIONS);
  m.telegramStatus.mockResolvedValue(TELEGRAM);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Profile page: collapsible saved settings', () => {
  it('first-time user sees the full form expanded, no summary', async () => {
    m.profile.mockRejectedValue(new ApiError(404, 'Not found'));
    render(<ProfilePage />);
    expect(await screen.findByRole('button', { name: 'Save profile' })).toBeTruthy();
    expect(isOpen()).toBe(true);
    expect(screen.queryByText('Profile preferences')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();   // nothing saved to go back to
  });

  it('a profile without a station (e.g. created by linking Telegram) still shows the form', async () => {
    m.profile.mockResolvedValue({ ...SAVED, stationId: null, latitude: null, longitude: null });
    render(<ProfilePage />);
    expect(await screen.findByRole('button', { name: 'Save profile' })).toBeTruthy();
    expect(isOpen()).toBe(true);
  });

  it('after a refresh, a saved profile loads collapsed with a compact summary and no coordinates', async () => {
    m.profile.mockResolvedValue(SAVED);
    render(<ProfilePage />);

    const summary = (await screen.findByText('Profile preferences')).closest('section') as HTMLElement;
    expect(isOpen()).toBe(false);
    expect(settings().getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByRole('button', { name: 'Save profile' })).toBeNull();      // hidden from users
    expect(within(summary).getByText('Asthma')).toBeTruthy();
    expect(within(summary).getByText('Hindi')).toBeTruthy();
    await waitFor(() => expect(within(summary).getByText('ITO, Delhi - CPCB')).toBeTruthy());
    expect(within(summary).getByText(/Moderate and worse/)).toBeTruthy();
    expect(within(summary).getByText('23:00–06:00')).toBeTruthy();
    expect(summary.textContent).not.toMatch(/28\.6|77\.2/);                          // no coordinates
    expect(within(summary).getByRole('button', { name: 'Edit profile' })).toBeTruthy();
  });

  it('Edit profile expands the form with exactly the saved values', async () => {
    m.profile.mockResolvedValue(SAVED);
    render(<ProfilePage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit profile' }));

    expect(isOpen()).toBe(true);
    expect((screen.getByLabelText(/^Health profile/) as HTMLSelectElement).value).toBe('ASTHMA');
    expect((screen.getByLabelText(/^Alert language/) as HTMLSelectElement).value).toBe('hi');
    expect((screen.getByLabelText(/^Quiet from/) as HTMLInputElement).value).toBe('23:00');
    expect((screen.getByLabelText(/^Quiet until/) as HTMLInputElement).value).toBe('06:00');
    expect((screen.getByLabelText(/Send Severe alerts/) as HTMLInputElement).checked).toBe(true);
    expect(screen.getAllByText('ITO, Delhi - CPCB', { selector: 'span' })).toHaveLength(2);   // Location + briefing card
    expect((screen.getByRole('switch', { name: 'Morning briefing' }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(/^Delivery time/) as HTMLInputElement).value).toBe('06:30');
    expect(screen.queryByText('Profile preferences')).toBeNull();
  });

  it('saving collapses the form only after the backend confirms, and shows a fading success message', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    m.profile.mockResolvedValue(SAVED);
    m.saveProfile.mockImplementation(async (_t: string, body: Record<string, unknown>) => ({ ...SAVED, ...body, quietStart: '23:00:00', quietEnd: '06:00:00' }));
    render(<ProfilePage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit profile' }));
    fireEvent.change(screen.getByLabelText(/^Health profile/), { target: { value: 'ELDERLY' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText('Profile saved successfully.')).toBeTruthy();
    expect(isOpen()).toBe(false);
    expect(m.saveProfile).toHaveBeenCalledWith('tok', expect.objectContaining({ healthProfile: 'ELDERLY', stationId: 'ito-delhi-cpcb' }));
    const summary = screen.getByText('Profile preferences').closest('section') as HTMLElement;
    expect(within(summary).getByText('Elderly')).toBeTruthy();

    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });   // ~4 s visible + fade
    expect(screen.queryByText('Profile saved successfully.')).toBeNull();
  });

  it('a failed save keeps the form open with an error and no success message', async () => {
    m.profile.mockRejectedValue(new ApiError(404, 'Not found'));
    m.saveProfile.mockRejectedValue(new ApiError(400, 'stationId: unknown station'));
    render(<ProfilePage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText('stationId: unknown station')).toBeTruthy();
    expect(isOpen()).toBe(true);
    expect(screen.queryByText('Profile saved successfully.')).toBeNull();
    expect(screen.queryByText('Profile preferences')).toBeNull();
  });

  it('repeated edit-and-save cycles collapse every time and restart the success message', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    m.profile.mockResolvedValue(SAVED);
    m.saveProfile.mockImplementation(async (_t: string, body: Record<string, unknown>) => ({ ...SAVED, ...body }));
    render(<ProfilePage />);

    for (const language of ['en', 'hi']) {
      fireEvent.click(await screen.findByRole('button', { name: 'Edit profile' }));
      fireEvent.change(screen.getByLabelText(/^Alert language/), { target: { value: language } });
      fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));
      expect(await screen.findByText('Profile saved successfully.')).toBeTruthy();
      expect(isOpen()).toBe(false);
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });   // second save happens mid-message
    }
    expect(screen.getByText('Profile saved successfully.')).toBeTruthy();   // timing restarted by the 2nd save
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(screen.queryByText('Profile saved successfully.')).toBeNull();
    expect(m.saveProfile).toHaveBeenCalledTimes(2);
  });

  it('morning briefing settings are shown in the summary, saved, and can be switched off', async () => {
    m.profile.mockResolvedValue(SAVED);
    m.saveProfile.mockImplementation(async (_t: string, body: Record<string, unknown>) => ({ ...SAVED, ...body }));
    render(<ProfilePage />);
    const summary = (await screen.findByText('Profile preferences')).closest('section') as HTMLElement;
    expect(within(summary).getByText('Daily at 06:30 IST')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Edit profile' }));
    fireEvent.change(screen.getByLabelText(/^Delivery time/), { target: { value: '07:45' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    await screen.findByText('Profile saved successfully.');
    expect(m.saveProfile).toHaveBeenLastCalledWith('tok', expect.objectContaining({ morningBriefingEnabled: true, morningBriefingTime: '07:45' }));

    fireEvent.click(screen.getByRole('button', { name: 'Edit profile' }));
    fireEvent.click(screen.getByRole('switch', { name: 'Morning briefing' }));
    expect((screen.getByLabelText(/^Delivery time/) as HTMLInputElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(m.saveProfile).toHaveBeenLastCalledWith('tok', expect.objectContaining({ morningBriefingEnabled: false })));
    const after = (await screen.findByText('Profile preferences')).closest('section') as HTMLElement;
    expect(within(after).getByText('Off')).toBeTruthy();
  });

  it('AQI alerts have their own switch, independent of the morning briefing', async () => {
    m.profile.mockResolvedValue({ ...SAVED, aqiAlertsEnabled: true });
    m.saveProfile.mockImplementation(async (_t: string, body: Record<string, unknown>) => ({ ...SAVED, ...body }));
    render(<ProfilePage />);
    const summary = (await screen.findByText('Profile preferences')).closest('section') as HTMLElement;
    expect(within(summary).getByText('AQI alerts')).toBeTruthy();
    expect(within(summary).getByText('On')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Edit profile' }));
    const alertsSwitch = screen.getByRole('switch', { name: 'AQI alerts' }) as HTMLInputElement;
    expect(alertsSwitch.checked).toBe(true);
    expect(screen.getByText(/Only alerts for\s+Severe air \(AQI 401\+\) are sent during quiet hours/)).toBeTruthy();
    fireEvent.click(alertsSwitch);
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(m.saveProfile).toHaveBeenLastCalledWith('tok',
      expect.objectContaining({ aqiAlertsEnabled: false, morningBriefingEnabled: true })));   // briefing untouched
    const after = (await screen.findByText('Profile preferences')).closest('section') as HTMLElement;
    expect(within(after).getByText('Off')).toBeTruthy();
    expect(within(after).getByText('Daily at 06:30 IST')).toBeTruthy();
  });

  it('Cancel discards unsaved edits and returns to the summary', async () => {
    m.profile.mockResolvedValue(SAVED);
    render(<ProfilePage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit profile' }));
    fireEvent.change(screen.getByLabelText(/^Health profile/), { target: { value: 'CHILD' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(isOpen()).toBe(false);
    expect(m.saveProfile).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Edit profile' }));
    expect((screen.getByLabelText(/^Health profile/) as HTMLSelectElement).value).toBe('ASTHMA');
  });

  it('the Telegram card and the Account section stay visible in every state', async () => {
    m.profile.mockResolvedValue(SAVED);
    m.saveProfile.mockResolvedValue(SAVED);
    render(<ProfilePage />);
    expect(await screen.findByText('Telegram alerts')).toBeTruthy();
    expect(await screen.findByText('Connected')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Edit profile' }));
    expect(screen.getByText('Connected')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    await screen.findByText('Profile saved successfully.');
    expect(screen.getByText('Connected')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Delete account and data' })).toBeTruthy();
    expect(m.telegramUnlink).not.toHaveBeenCalled();
    expect(m.telegramLink).not.toHaveBeenCalled();
  });
});
