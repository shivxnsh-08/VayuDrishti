// Shapes of the backend JSON (Java records serialised by Jackson, camelCase).

export type Category = 'GOOD' | 'SATISFACTORY' | 'MODERATE' | 'POOR' | 'VERY_POOR' | 'SEVERE';
export type HealthProfile = 'GENERAL' | 'ASTHMA' | 'CHILD' | 'ELDERLY' | 'EXERCISER';

export interface StationSummary {
  id: string;
  name: string;
  city: string | null;
  state: string | null;
  latitude: number;
  longitude: number;
  ts: string | null;
  aqi: number | null;
  category: Category | null;
  dominantPollutant: string | null;
  stale: boolean;
}

export interface NearestStation {
  id: string;
  name: string;
  city: string | null;
  state: string | null;
  latitude: number;
  longitude: number;
  distanceMeters: number;
}

export interface CurrentAqi {
  stationId: string;
  stationName: string;
  ts: string | null;
  aqi: number | null;
  category: Category | null;
  dominantPollutant: string | null;
  stale: boolean;
  ageMinutes: number;
}

export interface HistoryPoint {
  ts: string;
  aqi: number | null;
  category: Category | null;
  dominantPollutant: string | null;
  pollutantCount: number | null;
  qualityFlags: string[];
}

export interface HistoryPage {
  stationId: string;
  from: string;
  to: string;
  page: number;
  size: number;
  total: number;
  items: HistoryPoint[];
}

export type ForecastStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'INSUFFICIENT_DATA' | 'STALE_DATA';

export interface ForecastPoint {
  targetTs: string;
  predictedAqi: number;
  lower: number | null;
  upper: number | null;
  category: Category;
}

export interface Forecast {
  stationId: string;
  status: ForecastStatus;
  modelVersion: string | null;
  method: string | null;
  generatedAt: string | null;
  points: ForecastPoint[];
  message: string | null;
}

export interface ModelInfoResponse {
  available: boolean;
  message?: string;
  info?: {
    model_loaded: boolean;
    serving?: string;
    note?: string;
    version?: string;
    freq?: string;
    trained_at?: string;
    training_window?: { start: string; end: string };
    n_stations?: number;
    validation?: {
      method?: string;
      overall?: Record<string, number>;
      interval_coverage_q10_q90?: number | null;
    };
    stations_served_by_baseline?: Record<string, string>;
  };
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  role: string;
}

export interface Profile {
  healthProfile: HealthProfile;
  latitude: number | null;
  longitude: number | null;
  homeCity: string | null;
  stationId: string | null;
  language: 'en' | 'hi';
  alertMinCategory: Category | null;
  effectiveMinCategory: Category;
  quietStart: string | null;
  quietEnd: string | null;
  severeOverridesQuiet: boolean;
  telegramLinked: boolean;
  /** Daily Telegram briefing (defaults: on, 07:00 Asia/Kolkata). */
  morningBriefingEnabled: boolean;
  morningBriefingTime: string;   // "HH:mm" or "HH:mm:ss"
  /** AQI event alerts (threshold, changes, recovery, 8-hour reminders); independent of the briefing. */
  aqiAlertsEnabled: boolean;
}

/** GET /stations/nearest/forecast-candidates: nearest stations with freshness and forecast readiness. */
export interface ForecastCandidate {
  id: string;
  name: string;
  city: string | null;
  state: string | null;
  distanceKm: number;
  latestTs: string | null;
  aqi: number | null;
  category: Category | null;
  stale: boolean;
  validHours: number;
  requiredHours: number;
  forecastReady: boolean;
  reason: string | null;
}

export type ProfileUpdate = Omit<Profile, 'effectiveMinCategory' | 'telegramLinked'>;

export type NotificationKind =
  | 'AQI_THRESHOLD_CROSSED' | 'AQI_DETERIORATION' | 'AQI_RECOVERY' | 'AQI_STABILITY_REMINDER'
  | 'FORECAST' | 'STALE_NOTICE' | 'MORNING_BRIEFING' | 'TEST_MESSAGE' | 'CURRENT';

/** One notification-history entry. `message` is the exact Telegram text (Telegram HTML subset). */
export interface Alert {
  id: number;
  stationId: string | null;
  kind: NotificationKind;
  category: Category | null;
  peakAqi: number | null;
  windowStart: string | null;
  message: string | null;
  status: 'PENDING' | 'SENDING' | 'SENT' | 'FAILED' | 'SUPPRESSED';
  attempts: number;
  createdAt: string;
  sentAt: string | null;
  lastError: string | null;
  scheduledFor?: string | null;
  previousAqi?: number | null;
  aqiChange?: number | null;
  observedAt?: string | null;
}

export interface LinkCode {
  code: string;
  expiresAt: string;
  botUsername: string | null;
  deepLink: string | null;
}

/** GET /me/telegram: never contains a chat id. */
export interface TelegramStatus {
  configured: boolean;
  botState: 'NOT_CONFIGURED' | 'UNVERIFIED' | 'OK' | 'TOKEN_REJECTED';
  botUsername: string | null;
  botLink: string | null;
  linked: boolean;
}

export type IngestionStatus = 'HEALTHY' | 'DEGRADED' | 'STALE' | 'FAILED' | 'SOURCE_UNAVAILABLE' | 'UNKNOWN';

/** GET /ingestion/health - whether the data pipeline is actually delivering fresh observations. */
export interface IngestionHealth {
  status: IngestionStatus;
  reasons: string[];
  evaluatedAt: string;
  lastRunAt: string | null;
  lastRunStatus: string | null;
  lastRunTrigger: string | null;
  lastSuccessfulRunAt: string | null;
  lastSuccessfulFetchAt: string | null;
  latestObservationAt: string | null;
  latestObservationAgeMinutes: number | null;
  minutesSinceLastRun: number | null;
  consecutiveFailures: number;
  lastErrorCategory: string | null;
  stationsActive: number;
  stationsFresh: number;
  stationsStale: number;
  staleAfterMinutes: number;
}

/** GET /me/account: who the caller is, according to the server. */
export interface AccountInfo {
  id: number;
  email: string;
  role: 'ADMIN' | 'USER' | string;
}

export type AdminUserStatus = 'ACTIVE' | 'INACTIVE' | 'NO_LOGIN_RECORDED';

/** Admin user list row: safe fields only (no password hash, tokens, chat ids or coordinates). */
export interface AdminUserRow {
  id: number;
  name: string | null;
  email: string;
  role: string;
  status: AdminUserStatus;
  createdAt: string | null;
  lastLoginAt: string | null;
  stationId: string | null;
  stationName: string | null;
  telegramLinked: boolean;
}

export interface AdminUserPage {
  items: AdminUserRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdminStats {
  totalUsers: number;
  adminAccounts: number;
  normalAccounts: number;
  activeLast30Days: number;
  noLoginRecorded: number;
  registeredLast7Days: number;
  registeredLast30Days: number;
  recentRegistrations: AdminUserRow[];
}

export interface AdminUserDetail {
  account: AdminUserRow;
  healthProfile: string | null;
  language: string | null;
  alertMinCategory: string | null;
  quietStart: string | null;
  quietEnd: string | null;
  aqiAlertsEnabled: boolean | null;
  morningBriefingEnabled: boolean | null;
  morningBriefingTime: string | null;
  notifications: number;
  lastNotificationAt: string | null;
}

export interface AdminUserQuery {
  search?: string;
  role?: string;
  status?: string;
  sort?: string;
  dir?: 'asc' | 'desc';
  page?: number;
  size?: number;
}
