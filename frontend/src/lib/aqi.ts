import type { Category } from '../api/types';

// Display only. Station categories always come from the backend (bands live in backend aqi.yml).
// The numeric bounds below are used only to label values the frontend derives itself (a city's mean
// AQI in the rankings); keep them in sync with aqi.yml. Colours follow the commonly used National
// AQI scheme (VERIFY with CPCB).
export const CATEGORIES: { key: Category; label: string; color: string; range: string; min: number; max: number }[] = [
  { key: 'GOOD', label: 'Good', color: '#2e9e4f', range: '0-50', min: 0, max: 50 },
  { key: 'SATISFACTORY', label: 'Satisfactory', color: '#9acd32', range: '51-100', min: 51, max: 100 },
  { key: 'MODERATE', label: 'Moderate', color: '#f2c618', range: '101-200', min: 101, max: 200 },
  { key: 'POOR', label: 'Poor', color: '#f28c28', range: '201-300', min: 201, max: 300 },
  { key: 'VERY_POOR', label: 'Very Poor', color: '#e53935', range: '301-400', min: 301, max: 400 },
  { key: 'SEVERE', label: 'Severe', color: '#8e1b2b', range: '401-500', min: 401, max: 500 },
];

export const NO_DATA_COLOR = '#64748b';

export function categoryInfo(c: Category | null | undefined) {
  return CATEGORIES.find((x) => x.key === c) ?? null;
}

export function categoryColor(c: Category | null | undefined) {
  return categoryInfo(c)?.color ?? NO_DATA_COLOR;
}

export function categoryLabel(c: Category | null | undefined) {
  return categoryInfo(c)?.label ?? 'No data';
}

/** Category for an AQI value the frontend computed itself (e.g. a city mean). */
export function categorize(aqi: number): Category {
  const v = Math.round(aqi);
  for (const c of CATEGORIES) if (v <= c.max) return c.key;
  return 'SEVERE';
}

export function formatTime(iso: string | null | undefined, withDate = true) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString(undefined, withDate
    ? { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' });
}

export function formatAge(iso: string | null | undefined) {
  if (!iso) return 'never';
  const minutes = Math.round((Date.now() - Date.parse(iso)) / 60000);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? `${hours} h ${minutes % 60} min ago` : `${Math.floor(hours / 24)} days ago`;
}

export function pollutantLabel(p: string | null | undefined) {
  if (!p) return '-';
  return p === 'PM25' ? 'PM2.5' : p;
}
