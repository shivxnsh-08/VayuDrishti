// @vitest-environment jsdom
import { act, cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Alert } from '../../api/types';

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client');
  return { ...actual, api: { alerts: vi.fn() } };
});
vi.mock('../../lib/auth', () => ({ useAuth: () => ({ token: 'tok', email: null, logout: vi.fn() }) }));

import { api } from '../../api/client';
import AlertsPage from '../AlertsPage';

const alerts = api.alerts as unknown as ReturnType<typeof vi.fn>;
const base = (o: Partial<Alert>): Alert => ({
  id: 1, stationId: 'ito', kind: 'AQI_DETERIORATION', category: 'POOR', peakAqi: 215, windowStart: null,
  message: null, status: 'SENT', attempts: 1, createdAt: '2026-11-24T12:00:00Z', sentAt: '2026-11-24T12:00:07Z',
  lastError: null, scheduledFor: null, previousAqi: 173, aqiChange: 42, observedAt: '2026-11-24T11:30:00Z', ...o,
});

beforeEach(() => alerts.mockReset());
afterEach(() => { cleanup(); vi.useRealTimers(); });

const renderPage = () => render(<MemoryRouter><AlertsPage /></MemoryRouter>);

describe('Alert history', () => {
  it('shows readable types, statuses, the AQI change and the timeline', async () => {
    alerts.mockResolvedValue([
      base({ id: 1 }),
      base({ id: 2, kind: 'AQI_STABILITY_REMINDER', status: 'PENDING', aqiChange: null, previousAqi: null, sentAt: null }),
      base({ id: 3, kind: 'AQI_RECOVERY', status: 'SUPPRESSED', aqiChange: -30, previousAqi: 180, sentAt: null,
        lastError: 'suppressed: quiet hours (better_category;quiet_hours)' }),
      base({ id: 4, kind: 'MORNING_BRIEFING', status: 'FAILED', category: 'MODERATE', aqiChange: null, previousAqi: null,
        sentAt: null, scheduledFor: '2026-11-24T01:30:00Z', lastError: 'expired: not delivered within the briefing window' }),
      base({ id: 5, kind: 'TEST_MESSAGE', category: null, aqiChange: null, previousAqi: null, observedAt: null }),
      base({ id: 6, kind: 'FORECAST' }), base({ id: 7, kind: 'STALE_NOTICE', category: null }),
      base({ id: 8, kind: 'AQI_THRESHOLD_CROSSED', status: 'PENDING', lastError: 'HTTP 502', attempts: 2, sentAt: null }),
    ]);
    renderPage();
    const first = await screen.findByRole('article', { name: 'AQI deterioration' });
    expect(within(first).getByText('Delivered to Telegram')).toBeTruthy();
    expect(within(first).getByText('(+42 from 173)')).toBeTruthy();
    expect(within(first).getByText('Observed:')).toBeTruthy();
    expect(within(first).getByText('Sent:')).toBeTruthy();

    expect(within(screen.getByRole('article', { name: '8-hour reminder' })).getByText('Queued')).toBeTruthy();
    const held = screen.getByRole('article', { name: 'Air quality improved' });
    expect(within(held).getByText('Held: quiet hours')).toBeTruthy();
    expect(within(held).getByText(/^Note:/)).toBeTruthy();                         // a hold is not an error
    const brief = screen.getByRole('article', { name: 'Morning briefing' });
    expect(within(brief).getByText('Expired')).toBeTruthy();
    expect(within(brief).getByText('Scheduled:')).toBeTruthy();
    expect(screen.getByRole('article', { name: 'Test message' })).toBeTruthy();
    expect(screen.getByRole('article', { name: 'Forecast alert' })).toBeTruthy();
    expect(screen.getByRole('article', { name: 'Stale data' })).toBeTruthy();
    const retry = screen.getByRole('article', { name: 'Alert level reached' });
    expect(within(retry).getByText('Retrying')).toBeTruthy();
    expect(within(retry).getByText('Attempts:')).toBeTruthy();
  });

  it('renders the exact Telegram message safely: allowed formatting only, no injected markup', async () => {
    alerts.mockResolvedValue([base({
      message: '🚨 <b>VayuDrishti | AQI ALERT</b>\n<b>Monitoring station:</b> A &amp; B &lt;x&gt;\n'
        + '<a href="http://localhost:5173/forecast?station=ito">Open VayuDrishti Dashboard</a>'
        + '<script>alert(1)</script><img src=x onerror="alert(2)"><a href="javascript:alert(3)">bad</a>',
    })]);
    const { container } = renderPage();
    const link = await screen.findByRole('link', { name: 'Open VayuDrishti Dashboard' });
    expect(link.getAttribute('href')).toBe('http://localhost:5173/forecast?station=ito');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(screen.getByText('VayuDrishti | AQI ALERT').tagName).toBe('B');
    expect(screen.getByText(/A & B <x>/)).toBeTruthy();                           // entities shown as text
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(screen.queryByRole('link', { name: 'bad' })).toBeNull();               // javascript: links dropped
  });

  it('explains when there are no notifications yet', async () => {
    alerts.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText(/No notifications yet/)).toBeTruthy();
    expect(screen.getByText(/reminder every 8 hours/)).toBeTruthy();
  });

  it('refreshes by itself every 5 minutes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    alerts.mockResolvedValueOnce([]).mockResolvedValue([base({ kind: 'AQI_THRESHOLD_CROSSED' })]);
    renderPage();
    await screen.findByText(/No notifications yet/);
    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000 + 50); });
    expect(await screen.findByRole('article', { name: 'Alert level reached' })).toBeTruthy();
    expect(alerts).toHaveBeenCalledTimes(2);
  });
});
