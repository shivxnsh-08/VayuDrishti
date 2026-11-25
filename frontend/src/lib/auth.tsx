import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, api } from '../api/client';

export type Role = 'ADMIN' | 'USER';

interface AuthState {
  token: string | null;
  email: string | null;
  /** The role confirmed by the server (GET /me/account); null while signed out. */
  role: Role | null;
  /** True while a stored session is being re-checked with the server (page load / refresh). */
  checking: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

interface Session {
  token: string;
  email: string;
  expiresAt: string;
  role?: string;
}

const AuthContext = createContext<AuthState | null>(null);
const KEY = 'saanscheck.session';

function load(): Session | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const s = JSON.parse(raw);
    return new Date(s.expiresAt) > new Date() ? s : null;
  } catch {
    return null;
  }
}

function save(value: Session | null) {
  try {
    if (value) localStorage.setItem(KEY, JSON.stringify(value));
    else localStorage.removeItem(KEY);
  } catch { /* storage unavailable: session lasts for this tab only */ }
}

const toRole = (r: string | undefined | null): Role => (r === 'ADMIN' ? 'ADMIN' : 'USER');

/**
 * Session state. The role is never trusted from storage: after a page load the stored session is re-checked with
 * GET /me/account and the server's answer decides which interface is shown. (The backend authorises every request
 * itself; this only chooses the UI.) An expired or invalid session signs out.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(load);
  const [role, setRole] = useState<Role | null>(null);
  const [checking, setChecking] = useState<boolean>(() => load() !== null);

  const logout = useCallback(() => {
    save(null);
    setSession(null);
    setRole(null);
    setChecking(false);
  }, []);

  // Re-check a stored session with the server (page load, refresh, another tab).
  const token = session?.token ?? null;
  useEffect(() => {
    if (!token) {
      setRole(null);
      setChecking(false);
      return undefined;
    }
    let alive = true;
    setChecking(true);
    api.account(token).then(
      (a) => { if (alive) { setRole(toRole(a.role)); setChecking(false); } },
      (e) => {
        if (!alive) return;
        if (e instanceof ApiError && (e.status === 401 || e.status === 403 || e.status === 404)) logout();
        else {
          // Backend unreachable: keep the session and use the role from sign-in only to choose the UI. This grants
          // nothing: every admin API call is still authorised by the backend.
          setRole(toRole(load()?.role));
          setChecking(false);
        }
      },
    );
    return () => { alive = false; };
  }, [token, logout]);

  const accept = useCallback((email: string, t: { accessToken: string; expiresAt: string; role?: string }) => {
    const s: Session = { token: t.accessToken, email, expiresAt: t.expiresAt, role: t.role };
    save(s);
    setSession(s);                                     // the effect above confirms the role with the server
  }, []);

  const value = useMemo<AuthState>(() => ({
    token,
    email: session?.email ?? null,
    role,
    checking,
    login: async (email, password) => accept(email, await api.login(email, password)),
    register: async (email, password) => accept(email, await api.register(email, password)),
    logout,
  }), [token, session, role, checking, accept, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
