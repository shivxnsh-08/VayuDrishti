// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminUserRow } from '../api/types';

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: { login: vi.fn(), register: vi.fn(), account: vi.fn(), adminUserStats: vi.fn(), adminUsers: vi.fn(), adminUser: vi.fn() },
  };
});
// Normal pages are stand-ins here: these tests are about which interface and routes each role gets.
vi.mock('../pages/Dashboard', () => ({ default: () => <div>User dashboard page</div> }));
vi.mock('../pages/MapPage', () => ({ default: () => <div>Map page</div> }));
vi.mock('../pages/ForecastPage', () => ({ default: () => <div>Forecast page</div> }));
vi.mock('../pages/AlertsPage', () => ({ default: () => <div>Alerts page</div> }));
vi.mock('../pages/ProfilePage', async () => {
  const { useAuth } = await import('../lib/auth');
  return {
    default: function ProfileStub() {
      const { login } = useAuth();
      return <div>Profile page <button onClick={() => void login('root@vd.test', 'secret-Pass#1')}>Sign in</button></div>;
    },
  };
});
vi.mock('../components/UpdateBanner', () => ({ default: () => null }));

import { ApiError, api } from '../api/client';
import App from '../App';
import { AuthProvider } from '../lib/auth';

const m = api as unknown as Record<string, ReturnType<typeof vi.fn>>;
const KEY = 'saanscheck.session';

function storeSession(role: string) {
  localStorage.setItem(KEY, JSON.stringify({ token: 'tok', email: 'someone@vd.test', role,
    expiresAt: new Date(Date.now() + 3600_000).toISOString() }));
}

function Where() {
  return <div data-testid="path">{useLocation().pathname}</div>;
}

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /><Where /></AuthProvider></MemoryRouter>);
}

const row = (id: number, email: string, o: Partial<AdminUserRow> = {}): AdminUserRow => ({
  id, name: null, email, role: 'USER', status: 'NO_LOGIN_RECORDED', createdAt: '2026-11-24T09:00:00Z',
  lastLoginAt: null, stationId: null, stationName: null, telegramLinked: false, ...o,
});
const STATS = { totalUsers: 3, adminAccounts: 1, normalAccounts: 2, activeLast30Days: 2, noLoginRecorded: 1,
  registeredLast7Days: 3, registeredLast30Days: 3, recentRegistrations: [row(3, 'new@vd.test')] };

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  Object.values(m).forEach((f) => f.mockReset());
  m.adminUserStats.mockResolvedValue(STATS);
  m.adminUsers.mockResolvedValue({ items: [row(1, 'root@vd.test', { role: 'ADMIN', status: 'ACTIVE' }), row(2, 'a@vd.test')], total: 2, page: 0, size: 20 });
});
afterEach(() => { cleanup(); vi.useRealTimers(); });

const adminNav = () => screen.queryByRole('navigation', { name: 'Admin' });

describe('role-aware interface', () => {
  it('visitors get the normal app; admin URLs send them to sign in', async () => {
    renderAt('/admin/users');
    expect(await screen.findByText(/Profile page/)).toBeTruthy();
    expect(screen.getByTestId('path').textContent).toBe('/profile');
    expect(adminNav()).toBeNull();
    expect(m.adminUsers).not.toHaveBeenCalled();
  });

  it('normal users keep the full app, never see admin navigation, and are refused admin URLs', async () => {
    storeSession('USER');
    m.account.mockResolvedValue({ id: 2, email: 'someone@vd.test', role: 'USER' });
    renderAt('/');
    expect(await screen.findByText('User dashboard page')).toBeTruthy();
    for (const label of ['Dashboard', 'Live AQI Map', 'Forecast', 'Alerts', 'Profile']) {
      expect(screen.getByRole('link', { name: label })).toBeTruthy();
    }
    expect(screen.queryByText('Admin Dashboard')).toBeNull();
    expect(screen.queryByText('User Management')).toBeNull();
    cleanup();
    renderAt('/admin/users');
    expect(await screen.findByText('Not authorised')).toBeTruthy();
    expect(m.adminUsers).not.toHaveBeenCalled();
  });

  it('a role edited in browser storage gains nothing: the server decides', async () => {
    storeSession('ADMIN');                                         // tampered
    m.account.mockResolvedValue({ id: 2, email: 'someone@vd.test', role: 'USER' });
    renderAt('/admin');
    expect(await screen.findByText('Not authorised')).toBeTruthy();
    expect(adminNav()).toBeNull();
  });

  it('the admin sees only the admin interface; normal pages are unreachable by URL', { timeout: 30_000 }, async () => {
    storeSession('ADMIN');
    m.account.mockResolvedValue({ id: 1, email: 'root@vd.test', role: 'ADMIN' });
    for (const path of ['/', '/map', '/forecast', '/alerts', '/profile', '/nowhere']) {
      cleanup();
      renderAt(path);
      // generous timeout: six full app renders in a row can be slow when the whole suite runs in parallel
      expect(await screen.findByRole('heading', { name: 'Admin Dashboard' }, { timeout: 5000 })).toBeTruthy();
      expect(screen.getByTestId('path').textContent).toBe('/admin');
      expect(screen.queryByText(/User dashboard page|Map page|Forecast page|Alerts page|Profile page/)).toBeNull();
    }
    const nav = adminNav()!;
    expect(within(nav).getByRole('link', { name: 'Admin Dashboard' })).toBeTruthy();
    expect(within(nav).getByRole('link', { name: 'User Management' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Logout' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Live AQI Map' })).toBeNull();
    expect(screen.getByText('Total registered users')).toBeTruthy();
    expect(screen.getByText('new@vd.test')).toBeTruthy();
  });

  it('signing in on the normal login page as the admin opens the admin dashboard', async () => {
    m.login.mockResolvedValue({ accessToken: 'tok', tokenType: 'Bearer', expiresAt: new Date(Date.now() + 3600_000).toISOString(), role: 'ADMIN' });
    m.account.mockResolvedValue({ id: 1, email: 'root@vd.test', role: 'ADMIN' });
    renderAt('/profile');
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Admin Dashboard' })).toBeTruthy();
    expect(m.account).toHaveBeenCalledWith('tok');                  // role confirmed with the server
  });

  it('logout returns to the normal sign-in experience and clears the session', async () => {
    storeSession('ADMIN');
    m.account.mockResolvedValue({ id: 1, email: 'root@vd.test', role: 'ADMIN' });
    renderAt('/admin');
    fireEvent.click(await screen.findByRole('button', { name: 'Logout' }));
    expect(await screen.findByText(/Profile page/)).toBeTruthy();
    expect(localStorage.getItem(KEY)).toBeNull();
    expect(adminNav()).toBeNull();
  });

  it('an expired session signs out instead of showing either interface', async () => {
    storeSession('ADMIN');
    m.account.mockRejectedValue(new ApiError(401, 'Unauthorized'));
    renderAt('/admin');
    expect(await screen.findByText(/Profile page/)).toBeTruthy();
    expect(localStorage.getItem(KEY)).toBeNull();
  });
});

describe('user management', () => {
  beforeEach(() => {
    storeSession('ADMIN');
    m.account.mockResolvedValue({ id: 1, email: 'root@vd.test', role: 'ADMIN' });
  });

  it('lists users with safe columns and supports search, filters, sorting and paging', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    m.adminUsers.mockResolvedValue({ items: Array.from({ length: 20 }, (_, i) => row(i + 1, `u${i + 1}@vd.test`)), total: 45, page: 0, size: 20 });
    renderAt('/admin/users');
    expect(await screen.findByText('Showing 1–20 of 45')).toBeTruthy();
    for (const h of ['ID', 'Name', 'Email', 'Registered', 'Role', 'Status', 'Monitoring station', 'Last login']) {
      expect(screen.getByRole('columnheader', { name: new RegExp(h) })).toBeTruthy();
    }
    expect(screen.getByRole('table').textContent).not.toMatch(/password|\$2a\$|token|chat/i);   // the data itself

    fireEvent.change(screen.getByLabelText('Search users'), { target: { value: 'priya' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(350); });
    await waitFor(() => expect(m.adminUsers).toHaveBeenLastCalledWith('tok', expect.objectContaining({ search: 'priya', page: 0 })));
    fireEvent.change(screen.getByLabelText('Role filter'), { target: { value: 'ADMIN' } });
    await waitFor(() => expect(m.adminUsers).toHaveBeenLastCalledWith('tok', expect.objectContaining({ role: 'ADMIN' })));
    fireEvent.change(screen.getByLabelText('Status filter'), { target: { value: 'INACTIVE' } });
    await waitFor(() => expect(m.adminUsers).toHaveBeenLastCalledWith('tok', expect.objectContaining({ status: 'INACTIVE' })));
    fireEvent.click(screen.getByRole('button', { name: /^Email/ }));
    await waitFor(() => expect(m.adminUsers).toHaveBeenLastCalledWith('tok', expect.objectContaining({ sort: 'email', dir: 'asc' })));
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    await waitFor(() => expect(m.adminUsers).toHaveBeenLastCalledWith('tok', expect.objectContaining({ page: 1 })));
  });

  it('opens a user details view', async () => {
    m.adminUser.mockResolvedValue({ account: row(2, 'a@vd.test', { stationName: 'ITO, Delhi', telegramLinked: true, status: 'ACTIVE' }),
      healthProfile: 'ASTHMA', language: 'en', alertMinCategory: 'MODERATE', quietStart: '23:00:00', quietEnd: '06:00:00',
      aqiAlertsEnabled: true, morningBriefingEnabled: false, morningBriefingTime: '07:00:00', notifications: 4,
      lastNotificationAt: '2026-11-24T12:45:00Z' });
    renderAt('/admin/users');
    fireEvent.click(await screen.findByRole('link', { name: 'a@vd.test' }));
    expect(await screen.findByRole('heading', { name: 'a@vd.test' })).toBeTruthy();
    expect(screen.getByText('ASTHMA')).toBeTruthy();
    expect(screen.getByText('ITO, Delhi')).toBeTruthy();
    expect(screen.getByText('Linked')).toBeTruthy();
    expect(screen.getByText('Not collected')).toBeTruthy();
    expect(m.adminUser).toHaveBeenCalledWith('tok', 2);
  });

  it('a 403 from the backend is shown as not authorised; a 401 signs out', async () => {
    m.adminUsers.mockRejectedValue(new ApiError(403, 'Forbidden'));
    renderAt('/admin/users');
    expect(await screen.findByText('This account does not have administrator access.')).toBeTruthy();
    cleanup();
    m.adminUsers.mockRejectedValue(new ApiError(401, 'Unauthorized'));
    renderAt('/admin/users');
    await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull());
  });
});
