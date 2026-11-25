import type {
  Alert, CurrentAqi, Forecast, HistoryPage, IngestionHealth, LinkCode, TelegramStatus, ForecastCandidate,
  AccountInfo, AdminStats, AdminUserDetail, AdminUserPage, AdminUserQuery, ModelInfoResponse, NearestStation, Profile,
  ProfileUpdate, StationSummary, TokenResponse,
} from './types';

const BASE = '/api/v1';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(path: string, opts: { method?: string; body?: unknown; token?: string | null } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the VayuDrishti API. Is the backend running?');
  }
  if (!res.ok) {
    // Backend errors are RFC 7807 problem documents with a "detail" field.
    let detail = res.statusText || `HTTP ${res.status}`;
    try {
      const body = await res.json();
      if (body?.detail) detail = body.detail;
    } catch { /* not JSON */ }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  stations: () => request<StationSummary[]>('/stations'),
  forecastCandidates: (lat: number, lon: number, limit = 10) =>
    request<ForecastCandidate[]>(`/stations/nearest/forecast-candidates?lat=${lat.toFixed(2)}&lon=${lon.toFixed(2)}&limit=${limit}`),
  nearest: (lat: number, lon: number, limit = 5) =>
    request<NearestStation[]>(`/stations/nearest?lat=${lat}&lon=${lon}&limit=${limit}`),
  current: (id: string) => request<CurrentAqi>(`/stations/${encodeURIComponent(id)}/aqi/current`),
  history: (id: string, from: Date) =>
    request<HistoryPage>(`/stations/${encodeURIComponent(id)}/aqi/history?from=${from.toISOString()}&size=500`),
  forecast: (id: string) => request<Forecast>(`/stations/${encodeURIComponent(id)}/forecast`),
  modelInfo: () => request<ModelInfoResponse>('/model-info'),
  ingestionHealth: () => request<IngestionHealth>('/ingestion/health'),

  login: (email: string, password: string) =>
    request<TokenResponse>('/auth/login', { method: 'POST', body: { email, password } }),
  register: (email: string, password: string) =>
    request<TokenResponse>('/auth/register', { method: 'POST', body: { email, password } }),

  profile: (token: string) => request<Profile>('/me/profile', { token }),
  saveProfile: (token: string, body: ProfileUpdate) => request<Profile>('/me/profile', { method: 'PUT', body, token }),
  deleteAccount: (token: string) => request<void>('/me', { method: 'DELETE', token }),
  telegramLink: (token: string) => request<LinkCode>('/me/telegram/link', { method: 'POST', token }),
  telegramStatus: (token: string) => request<TelegramStatus>('/me/telegram', { token }),
  telegramUnlink: (token: string) => request<void>('/me/telegram', { method: 'DELETE', token }),
  telegramTest: (token: string) =>
    request<{ sent: boolean; message: string }>('/me/telegram/test', { method: 'POST', token }),
  alerts: (token: string) => request<Alert[]>('/me/alerts?limit=200', { token }),
  account: (token: string) => request<AccountInfo>('/me/account', { token }),

  // Admin-only (the backend returns 401/403 for anyone else).
  adminUserStats: (token: string) => request<AdminStats>('/admin/users/stats', { token }),
  adminUsers: (token: string, q: AdminUserQuery) => {
    const p = new URLSearchParams();
    Object.entries(q).forEach(([k, v]) => { if (v !== undefined && v !== '') p.set(k, String(v)); });
    return request<AdminUserPage>(`/admin/users?${p.toString()}`, { token });
  },
  adminUser: (token: string, id: number) => request<AdminUserDetail>(`/admin/users/${id}`, { token }),
};
