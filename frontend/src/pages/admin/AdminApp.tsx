import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { ApiError, api } from '../../api/client';
import type { AdminUserQuery, AdminUserRow, AdminUserStatus } from '../../api/types';
import { Button, Card, ErrorNotice, Loading, Notice, Stat, inputClass } from '../../components/ui';
import { formatTime } from '../../lib/aqi';
import { useAuth } from '../../lib/auth';
import { useAsync } from '../../lib/useAsync';

export const STATUS_LABEL: Record<AdminUserStatus, string> = {
  ACTIVE: 'Active',
  INACTIVE: 'Inactive (30+ days)',
  NO_LOGIN_RECORDED: 'No sign-in recorded',
};
const STATUS_STYLE: Record<AdminUserStatus, string> = {
  ACTIVE: 'bg-teal-500/15 text-teal-300',
  INACTIVE: 'bg-amber-500/15 text-amber-300',
  NO_LOGIN_RECORDED: 'bg-slate-500/20 text-slate-300',
};

const NAV = [
  { to: '/admin', label: 'Admin Dashboard', end: true },
  { to: '/admin/users', label: 'User Management', end: false },
];

/**
 * The complete interface for an ADMIN: only the admin dashboard and user management. Every other path redirects
 * here, so normal pages (map, forecast, alerts, profile, Telegram) are unreachable by URL. Data access is enforced
 * by the backend (/api/v1/admin/** requires ADMIN); this is only the UI.
 */
export default function AdminApp() {
  const { email, logout } = useAuth();
  const navigate = useNavigate();
  const signOut = () => { logout(); navigate('/profile', { replace: true }); };
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-[1000] border-b border-navy-700 bg-navy-900/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <Link to="/admin" className="flex items-center gap-2 text-lg font-semibold text-slate-100">
            <img src="/favicon.svg" alt="" className="h-7 w-7" />
            <span>Vayu<span className="text-teal-400">Drishti</span></span>
            <span className="rounded-full border border-teal-600/60 bg-teal-950/60 px-2 py-0.5 text-xs font-medium text-teal-300">Admin</span>
          </Link>
          <nav aria-label="Admin" className="order-3 flex w-full gap-1 overflow-x-auto sm:order-none sm:w-auto">
            {NAV.map((n) => (
              <NavLink key={n.to} to={n.to} end={n.end}
                className={({ isActive }) => `whitespace-nowrap rounded-md px-3 py-1.5 text-sm ${isActive
                  ? 'bg-teal-500/15 text-teal-300' : 'text-slate-400 hover:bg-navy-800 hover:text-slate-200'}`}>
                {n.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm text-slate-400">
            <span className="hidden md:inline">{email}</span>
            <button onClick={signOut} className="text-teal-400 hover:text-teal-300">Logout</button>
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-6">
        <Routes>
          <Route path="/admin" element={<AdminDashboard />} />
          <Route path="/admin/users" element={<UserManagement />} />
          <Route path="/admin/users/:id" element={<UserDetail />} />
          <Route path="*" element={<Navigate to="/admin" replace />} />
        </Routes>
      </main>
      <footer className="border-t border-navy-800 px-4 py-4 text-center text-xs text-slate-500">
        VayuDrishti administration. Passwords, tokens and Telegram chat ids are never shown.
      </footer>
    </div>
  );
}

/** Loads admin data; an expired session signs out, a 403 is shown as "not authorised". */
function useAdmin<T>(fn: (token: string) => Promise<T>, deps: unknown[]) {
  const { token, logout } = useAuth();
  const res = useAsync(() => fn(token as string), [token, ...deps]);
  const expired = res.error instanceof ApiError && res.error.status === 401;
  useEffect(() => { if (expired) logout(); }, [expired, logout]);
  return res;
}

function AdminError({ error, onRetry }: { error: Error; onRetry: () => void }) {
  if (error instanceof ApiError && error.status === 403) {
    return <Notice tone="error" title="Not authorised">This account does not have administrator access.</Notice>;
  }
  return <ErrorNotice error={error} onRetry={onRetry} />;
}

function Header({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div>
      <h1 className="text-2xl font-semibold text-slate-100">{title}</h1>
      {children && <p className="text-sm text-slate-400">{children}</p>}
    </div>
  );
}

export function AdminDashboard() {
  const stats = useAdmin((t) => api.adminUserStats(t), []);
  return (
    <div className="space-y-6">
      <Header title="Admin Dashboard">Registered accounts at a glance.</Header>
      {stats.loading ? <Loading /> : stats.error ? <AdminError error={stats.error} onRetry={stats.reload} /> : stats.data && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat label="Total registered users" value={stats.data.totalUsers} />
            <Stat label="Active (signed in, 30 days)" value={stats.data.activeLast30Days}
              hint={`${stats.data.noLoginRecorded} with no sign-in recorded yet`} />
            <Stat label="Admin accounts" value={stats.data.adminAccounts} />
            <Stat label="Normal user accounts" value={stats.data.normalAccounts} />
          </div>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat label="Registered, last 7 days" value={stats.data.registeredLast7Days} />
            <Stat label="Registered, last 30 days" value={stats.data.registeredLast30Days} />
          </div>
          <Card title="Recent registrations" action={<Link to="/admin/users" className="text-sm text-teal-400 hover:text-teal-300">All users</Link>}>
            {stats.data.recentRegistrations.length === 0 ? <p className="text-sm text-slate-400">No users yet.</p> : (
              <ul className="divide-y divide-navy-800 text-sm">
                {stats.data.recentRegistrations.map((u) => (
                  <li key={u.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
                    <Link to={`/admin/users/${u.id}`} className="text-slate-100 hover:text-teal-300">{u.email}</Link>
                    <RoleBadge role={u.role} />
                    <span className="ml-auto text-xs text-slate-500">Registered {formatTime(u.createdAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </>
      )}
    </div>
  );
}

function RoleBadge({ role }: { role: string }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${role === 'ADMIN'
      ? 'bg-teal-500/20 text-teal-300' : 'bg-navy-800 text-slate-300'}`}>{role === 'ADMIN' ? 'Admin' : 'User'}</span>
  );
}

function StatusBadge({ status }: { status: AdminUserStatus }) {
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[status]}`}>{STATUS_LABEL[status]}</span>;
}

const PAGE_SIZE = 20;
const COLUMNS: { key: string; label: string; sortable: boolean }[] = [
  { key: 'id', label: 'ID', sortable: true },
  { key: 'name', label: 'Name', sortable: false },
  { key: 'email', label: 'Email', sortable: true },
  { key: 'createdAt', label: 'Registered', sortable: true },
  { key: 'role', label: 'Role', sortable: true },
  { key: 'status', label: 'Status', sortable: false },
  { key: 'station', label: 'Monitoring station', sortable: false },
  { key: 'lastLoginAt', label: 'Last login', sortable: true },
];

export function UserManagement() {
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState<AdminUserQuery>({ sort: 'createdAt', dir: 'desc', page: 0, size: PAGE_SIZE });
  useEffect(() => {                                  // debounce typing into the search box
    const t = setTimeout(() => setQuery((q) => (q.search === search ? q : { ...q, search, page: 0 })), 300);
    return () => clearTimeout(t);
  }, [search]);
  const list = useAdmin((t) => api.adminUsers(t, query), [JSON.stringify(query)]);
  const set = (patch: AdminUserQuery) => setQuery((q) => ({ ...q, ...patch }));
  const sortBy = (key: string) => set({ sort: key, dir: query.sort === key && query.dir === 'asc' ? 'desc' : 'asc', page: 0 });
  const data = list.data;
  const page = query.page ?? 0;
  const from = data && data.total > 0 ? page * PAGE_SIZE + 1 : 0;
  const to = data ? Math.min(data.total, (page + 1) * PAGE_SIZE) : 0;

  return (
    <div className="space-y-5">
      <Header title="User Management">All registered accounts. Passwords and private credentials are never shown.</Header>
      <Card>
        <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto]">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-300">Search</span>
            <input className={inputClass} value={search} onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by email (names are not collected)" aria-label="Search users" />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-300">Role</span>
            <select className={inputClass} aria-label="Role filter" value={query.role ?? ''}
              onChange={(e) => set({ role: e.target.value || undefined, page: 0 })}>
              <option value="">All roles</option>
              <option value="ADMIN">Admin</option>
              <option value="USER">User</option>
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-300">Status</span>
            <select className={inputClass} aria-label="Status filter" value={query.status ?? ''}
              onChange={(e) => set({ status: e.target.value || undefined, page: 0 })}>
              <option value="">All statuses</option>
              {(Object.keys(STATUS_LABEL) as AdminUserStatus[]).map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
            </select>
          </label>
        </div>
      </Card>

      {list.error ? <AdminError error={list.error} onRetry={list.reload} /> : !data ? <Loading /> : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-slate-500">
                <tr>
                  {COLUMNS.map((c) => (
                    <th key={c.key} className="whitespace-nowrap py-2 pr-4"
                      aria-sort={query.sort === c.key ? (query.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                      {c.sortable ? (
                        <button onClick={() => sortBy(c.key)} className="uppercase hover:text-slate-300">
                          {c.label}{query.sort === c.key ? (query.dir === 'asc' ? ' ▲' : ' ▼') : ''}
                        </button>
                      ) : c.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-navy-800">
                {data.items.length === 0 ? (
                  <tr><td colSpan={COLUMNS.length} className="py-6 text-center text-slate-400">No users match.</td></tr>
                ) : data.items.map((u) => <UserRow key={u.id} u={u} />)}
              </tbody>
            </table>
          </div>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-400">
            <span>{data.total === 0 ? 'No users' : `Showing ${from}–${to} of ${data.total}`}</span>
            <div className="flex gap-2">
              <Button variant="ghost" disabled={page === 0} onClick={() => set({ page: page - 1 })}>Previous</Button>
              <Button variant="ghost" disabled={to >= data.total} onClick={() => set({ page: page + 1 })}>Next</Button>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

function UserRow({ u }: { u: AdminUserRow }) {
  return (
    <tr>
      <td className="py-2 pr-4 text-slate-400">{u.id}</td>
      <td className="py-2 pr-4 text-slate-500">{u.name ?? '—'}</td>
      <td className="py-2 pr-4"><Link to={`/admin/users/${u.id}`} className="text-slate-100 hover:text-teal-300">{u.email}</Link></td>
      <td className="whitespace-nowrap py-2 pr-4 text-slate-400">{formatTime(u.createdAt)}</td>
      <td className="py-2 pr-4"><RoleBadge role={u.role} /></td>
      <td className="py-2 pr-4"><StatusBadge status={u.status} /></td>
      <td className="py-2 pr-4 text-slate-400">{u.stationName ?? u.stationId ?? '—'}</td>
      <td className="whitespace-nowrap py-2 text-slate-400">{u.lastLoginAt ? formatTime(u.lastLoginAt) : '—'}</td>
    </tr>
  );
}

export function UserDetail() {
  const { id } = useParams();
  const detail = useAdmin((t) => api.adminUser(t, Number(id)), [id]);
  const d = detail.data;
  return (
    <div className="space-y-5">
      <Link to="/admin/users" className="text-sm text-teal-400 hover:text-teal-300">← Back to User Management</Link>
      {detail.loading ? <Loading /> : detail.error ? <AdminError error={detail.error} onRetry={detail.reload} /> : d && (
        <>
          <Header title={d.account.email}>User #{d.account.id}</Header>
          <div className="grid gap-5 lg:grid-cols-2">
            <Card title="Account">
              <Facts rows={[
                ['Name', d.account.name ?? 'Not collected'],
                ['Email', d.account.email],
                ['Role', <RoleBadge key="r" role={d.account.role} />],
                ['Status', <StatusBadge key="s" status={d.account.status} />],
                ['Registered', formatTime(d.account.createdAt)],
                ['Last login', d.account.lastLoginAt ? formatTime(d.account.lastLoginAt) : 'No sign-in recorded'],
              ]} />
            </Card>
            <Card title="Preferences">
              {d.healthProfile == null ? <p className="text-sm text-slate-400">No profile saved yet.</p> : (
                <Facts rows={[
                  ['Health profile', d.healthProfile],
                  ['Monitoring station', d.account.stationName ?? d.account.stationId ?? '—'],
                  ['Alert language', d.language === 'hi' ? 'Hindi' : 'English'],
                  ['Alert level', d.alertMinCategory ?? 'Default for profile'],
                  ['Quiet hours', d.quietStart && d.quietEnd ? `${d.quietStart.slice(0, 5)}–${d.quietEnd.slice(0, 5)}` : 'Off'],
                  ['AQI alerts', d.aqiAlertsEnabled === false ? 'Off' : 'On'],
                  ['Morning briefing', d.morningBriefingEnabled === false ? 'Off' : `On, ${(d.morningBriefingTime ?? '07:00').slice(0, 5)} IST`],
                  ['Telegram', d.account.telegramLinked ? 'Linked' : 'Not linked'],
                  ['Notifications', `${d.notifications}${d.lastNotificationAt ? ` · last ${formatTime(d.lastNotificationAt)}` : ''}`],
                ]} />
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  );
}

function Facts({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-slate-500">{k}</dt>
          <dd className="text-slate-100">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
