import type { ReactNode } from 'react';
import type { Category, HealthProfile } from '../api/types';
import { actionCodes, disclaimer, isSensitive, sentence, type Trend } from '../lib/advice';
import { categoryLabel, pollutantLabel } from '../lib/aqi';
import { CategoryBadge, Notice } from './ui';

const PROFILE_LABEL: Record<HealthProfile, string> = {
  GENERAL: 'general public', ASTHMA: 'people with asthma', CHILD: 'children', ELDERLY: 'older adults',
  EXERCISER: 'outdoor exercisers',
};

type Level = 'advised' | 'reduce' | 'ok';
const LEVEL_STYLE: Record<Level, string> = {
  advised: 'border-amber-600/60 bg-amber-950/30 text-amber-200',
  reduce: 'border-sky-600/50 bg-sky-950/30 text-sky-200',
  ok: 'border-navy-600 bg-navy-950 text-slate-400',
};

/** Summary tiles derived from the rule output: each tile reflects which action codes were chosen. */
function tiles(codes: string[]): { key: string; label: string; status: string; level: Level; icon: ReactNode }[] {
  const has = (c: string) => codes.includes(c);
  return [
    {
      key: 'mask', label: 'N95 mask', icon: <MaskIcon />,
      ...(has('WEAR_N95') ? { status: 'If going out', level: 'advised' as Level } : { status: 'Not needed', level: 'ok' as Level }),
    },
    {
      key: 'exercise', label: 'Outdoor exercise', icon: <RunIcon />,
      ...(has('AVOID_OUTDOOR_EXERCISE') ? { status: 'Avoid', level: 'advised' as Level }
        : has('REDUCE_PROLONGED_EXERTION') ? { status: 'Reduce', level: 'reduce' as Level }
          : { status: 'OK', level: 'ok' as Level }),
    },
    {
      key: 'outdoors', label: 'Time outdoors', icon: <HomeIcon />,
      ...(has('STAY_INDOORS') ? { status: 'Stay indoors', level: 'advised' as Level }
        : has('LIMIT_TIME_OUTDOORS') ? { status: 'Limit', level: 'reduce' as Level }
          : { status: 'No limit', level: 'ok' as Level }),
    },
    {
      key: 'windows', label: 'Windows', icon: <WindowIcon />,
      ...(has('KEEP_WINDOWS_CLOSED') ? { status: 'Keep closed', level: 'advised' as Level } : { status: 'No change', level: 'ok' as Level }),
    },
  ];
}

/**
 * Health advice for one station's current reading, from the backend's advice rules.
 * Personalised when the signed-in user's saved health profile is known; otherwise the general profile.
 */
export default function HealthAdvice({ aqi, category, stale, dominantPollutant, profile, profileSource, trend }: {
  aqi: number | null;
  category: Category | null;
  stale: boolean;
  dominantPollutant?: string | null;
  profile?: HealthProfile | null;
  profileSource?: 'saved' | 'default';
  trend?: Trend;
}) {
  if (aqi == null || category == null) {
    return <Notice tone="warn" title="No advice available">There is no current reading for this station.</Notice>;
  }
  const p: HealthProfile = profile ?? 'GENERAL';
  const codes = actionCodes(category, p, trend ?? 'STABLE');

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-sm text-slate-300">
        <CategoryBadge category={category} aqi={aqi} />
        <span>for {PROFILE_LABEL[p]}</span>
        {dominantPollutant && <span className="text-slate-500">· main pollutant {pollutantLabel(dominantPollutant)}</span>}
      </div>

      {stale && (
        <Notice tone="warn">This reading is stale (the station stopped reporting). Advice reflects the last known value.</Notice>
      )}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {tiles(codes).map((t) => (
          <div key={t.key} className={`flex flex-col items-center gap-1 rounded-lg border px-2 py-3 text-center ${LEVEL_STYLE[t.level]}`}>
            <span className="h-7 w-7">{t.icon}</span>
            <span className="text-xs font-medium text-slate-200">{t.label}</span>
            <span className="text-xs">{t.status}</span>
          </div>
        ))}
      </div>

      <ul className="space-y-1.5 text-sm text-slate-300">
        {codes.map((c) => (
          <li key={c} className="flex gap-2"><span className="text-teal-400">•</span>{sentence(c)}</li>
        ))}
      </ul>

      <p className="text-xs text-slate-500">
        Rule-based guidance from VayuDrishti's advice rules for the {categoryLabel(category)} category
        {profileSource === 'saved' ? ' and your saved health profile' : ' (general profile; sign in and save a health profile to personalise)'}.
        {isSensitive(p) && ' Sensitive groups are advised earlier than the general public.'} Not AI-generated. {disclaimer()}
      </p>
    </div>
  );
}

// Small inline icons (no icon library needed).
const stroke = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
function MaskIcon() {
  return <svg viewBox="0 0 24 24" {...stroke}><path d="M4 9c3-2 13-2 16 0v3c0 4-4 7-8 7s-8-3-8-7z" /><path d="M4 10 2 9m18 1 2-1M8 12h8M8 15h8" /></svg>;
}
function RunIcon() {
  return <svg viewBox="0 0 24 24" {...stroke}><circle cx="14" cy="4.5" r="1.8" /><path d="M10 21l2.5-5.5L15 17v4M7 12l3-4h4l2 3 3 1M12.5 15.5 11 9" /></svg>;
}
function HomeIcon() {
  return <svg viewBox="0 0 24 24" {...stroke}><path d="M3 11 12 4l9 7" /><path d="M5 10v10h14V10M10 20v-5h4v5" /></svg>;
}
function WindowIcon() {
  return <svg viewBox="0 0 24 24" {...stroke}><rect x="4" y="3" width="16" height="18" rx="1" /><path d="M12 3v18M4 12h16" /></svg>;
}
