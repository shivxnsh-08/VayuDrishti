import { describe, expect, it } from 'vitest';
import type { Profile, StationSummary } from '../../api/types';
import { isSavedProfile, profileSummary } from '../profile';

const P = {
  healthProfile: 'CHILD', latitude: 28.63, longitude: 77.24, homeCity: null, stationId: 'ito', language: 'en',
  alertMinCategory: 'POOR', effectiveMinCategory: 'POOR', quietStart: null, quietEnd: null, severeOverridesQuiet: false,
  telegramLinked: false,
} as Profile;

describe('saved profile summary', () => {
  it('counts as saved only with a monitoring station', () => {
    expect(isSavedProfile(P)).toBe(true);
    expect(isSavedProfile({ ...P, stationId: null })).toBe(false);
    expect(isSavedProfile(null)).toBe(false);
  });

  it('summarises with labels only, never coordinates', () => {
    const s = profileSummary(P, [{ id: 'ito', name: 'ITO', city: 'Delhi' } as StationSummary]);
    expect(s).toEqual({ healthProfile: 'Child', language: 'English', station: 'ITO, Delhi', alertCategory: 'Poor and worse',
      quietHours: 'Off', aqiAlerts: 'On (needs Telegram)', morningBriefing: 'Daily at 07:00 IST (needs Telegram)' });
    expect(JSON.stringify(s)).not.toMatch(/28\.63|77\.24/);
  });

  it('describes the morning briefing setting', () => {
    expect(profileSummary({ ...P, morningBriefingEnabled: true, morningBriefingTime: '06:30:00', telegramLinked: true }, null)
      .morningBriefing).toBe('Daily at 06:30 IST');
    expect(profileSummary({ ...P, morningBriefingEnabled: false }, null).morningBriefing).toBe('Off');
  });

  it('falls back sensibly while stations are loading', () => {
    expect(profileSummary({ ...P, alertMinCategory: null }, null)).toMatchObject({
      station: 'ito', alertCategory: 'Poor and worse (default for this profile)' });
  });
});
