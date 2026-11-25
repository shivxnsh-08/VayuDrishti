import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './lib/auth';
import Dashboard from './pages/Dashboard';
import MapPage from './pages/MapPage';
import ForecastPage from './pages/ForecastPage';
import AlertsPage from './pages/AlertsPage';
import ProfilePage from './pages/ProfilePage';
import UpdateBanner from './components/UpdateBanner';
import AdminApp from './pages/admin/AdminApp';
import { Card, Loading, Notice } from './components/ui';

const NAV = [
  { to: '/', label: 'Dashboard' },
  { to: '/map', label: 'Live AQI Map' },
  { to: '/forecast', label: 'Forecast' },
  { to: '/alerts', label: 'Alerts' },
  { to: '/profile', label: 'Profile' },
];

/**
 * Role-aware shell. The role comes from the server (GET /me/account), never from the browser:
 * ADMIN -> the admin interface only; USER and visitors -> the normal VayuDrishti app. The backend enforces access
 * on every request; this only decides what is shown.
 */
export default function App() {
  const { token, role, checking } = useAuth();
  if (token && checking) {
    return <div className="flex min-h-screen items-center justify-center"><Loading label="Checking your session..." /></div>;
  }
  if (token && role === 'ADMIN') {
    return <><UpdateBanner /><AdminApp /></>;
  }
  return <UserApp />;
}

/** Admin URLs in the normal app: visitors sign in first, normal users are refused. */
function AdminRouteForNonAdmins() {
  const { token } = useAuth();
  if (!token) return <Navigate to="/profile" replace />;
  return (
    <div className="mx-auto max-w-lg">
      <Card>
        <Notice tone="error" title="Not authorised">
          This area is only for VayuDrishti administrators.
        </Notice>
        <NavLink to="/" className="mt-3 inline-block text-sm text-teal-400 hover:text-teal-300">Back to the dashboard</NavLink>
      </Card>
    </div>
  );
}

function UserApp() {
  const { email, logout } = useAuth();
  return (
    <div className="flex min-h-screen flex-col">
      <UpdateBanner />
      <header className="sticky top-0 z-[1000] border-b border-navy-700 bg-navy-900/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <NavLink to="/" className="flex items-center gap-2 text-lg font-semibold text-slate-100">
            <img src="/favicon.svg" alt="" className="h-7 w-7" />
            <span className="leading-tight">
              Vayu<span className="text-teal-400">Drishti</span>
              <span className="hidden text-[11px] font-normal text-slate-400 lg:block">See the Air. Predict the Future.</span>
            </span>
          </NavLink>
          <nav className="order-3 flex w-full gap-1 overflow-x-auto sm:order-none sm:w-auto">
            {NAV.map((n) => (
              <NavLink key={n.to} to={n.to} end={n.to === '/'}
                className={({ isActive }) =>
                  `whitespace-nowrap rounded-md px-3 py-1.5 text-sm ${isActive
                    ? 'bg-teal-500/15 text-teal-300' : 'text-slate-400 hover:bg-navy-800 hover:text-slate-200'}`}>
                {n.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto text-sm text-slate-400">
            {email ? (
              <span className="flex items-center gap-3">
                <span className="hidden md:inline">{email}</span>
                <button onClick={logout} className="text-teal-400 hover:text-teal-300">Sign out</button>
              </span>
            ) : (
              <NavLink to="/profile" className="text-teal-400 hover:text-teal-300">Sign in</NavLink>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-6">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/map" element={<MapPage />} />
          <Route path="/forecast" element={<ForecastPage />} />
          <Route path="/alerts" element={<AlertsPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/admin/*" element={<AdminRouteForNonAdmins />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      <footer className="border-t border-navy-800 px-4 py-4 text-center text-xs text-slate-500">
        VayuDrishti: AI-powered Air Quality Monitoring and Forecasting Platform.
        Air-quality data: Central Pollution Control Board via data.gov.in (Government Open Data Licence - India).
        General guidance only, not medical advice.
      </footer>
    </div>
  );
}
