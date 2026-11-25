import type { HealthProfile, Profile, StationSummary } from '../api/types';
import { categoryLabel } from './aqi';

export const PROFILE_LABELS: Record<HealthProfile, string> = {
  GENERAL: 'General',
  ASTHMA: 'Asthma',
  CHILD: 'Child',
  ELDERLY: 'Elderly',
  EXERCISER: 'Outdoor exerciser',
};

/**
 * A profile counts as saved (shown collapsed) only when the backend has one with a monitoring station.
 * Linking Telegram alone creates a minimal profile row without a station: that still needs the full form.
 */
export function isSavedProfile(p: Profile | null | undefined): p is Profile {
  return !!p && !!p.stationId;
}

/** The compact summary: labels only, never coordinates. */
export function profileSummary(p: Profile, stations: StationSummary[] | null | undefined) {
  const station = stations?.find((s) => s.id === p.stationId);
  return {
    healthProfile: PROFILE_LABELS[p.healthProfile] ?? p.healthProfile,
    language: p.language === 'hi' ? 'Hindi' : 'English',
    station: station ? (station.city && !station.name.includes(station.city) ? `${station.name}, ${station.city}` : station.name)
      : (p.stationId ?? 'Not set'),
    alertCategory: p.alertMinCategory
      ? `${categoryLabel(p.alertMinCategory)} and worse`
      : `${categoryLabel(p.effectiveMinCategory)} and worse (default for this profile)`,
    quietHours: p.quietStart && p.quietEnd ? `${p.quietStart.slice(0, 5)}–${p.quietEnd.slice(0, 5)}` : 'Off',
    aqiAlerts: p.aqiAlertsEnabled === false ? 'Off'
      : `On${p.telegramLinked ? '' : ' (needs Telegram)'}`,
    morningBriefing: p.morningBriefingEnabled === false ? 'Off'
      : `Daily at ${(p.morningBriefingTime ?? '07:00').slice(0, 5)} IST${p.telegramLinked ? '' : ' (needs Telegram)'}`,
  };
}
