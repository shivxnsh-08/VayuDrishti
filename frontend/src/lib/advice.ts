import type { Category, HealthProfile } from '../api/types';
import raw from './advice-rules.json';

/**
 * The backend's rule-based advice (forecast-service/advice/rules.yaml, copied to advice-rules.json by
 * scripts/sync_advice_rules.py). Same matching as advice/rules.py `action_codes`: union of the actions of
 * every rule whose categories, profiles ("SENSITIVE" = sensitive_profiles) and trends match, ordered by
 * priority, never empty. This is deterministic rule output, not AI-generated text.
 *
 * Why a copy: the backend does not expose advice over its public API yet (see docs/frontend-api-gaps.md).
 */
interface Rule { categories: string[]; profiles?: string[]; trends?: string[]; actions: string[] }
interface RuleBook {
  sensitive_profiles: string[];
  rules: Rule[];
  priority: string[];
  templates: Record<string, { en: string; hi: string }>;
  disclaimer: { en: string; hi: string };
}

const book = raw as unknown as RuleBook;
export type Trend = 'IMPROVING' | 'STABLE' | 'WORSENING';

function expand(profiles: string[] | undefined): Set<string> | null {
  if (!profiles) return null;
  const out = new Set<string>();
  for (const p of profiles) {
    if (p === 'SENSITIVE') book.sensitive_profiles.forEach((s) => out.add(s));
    else out.add(p);
  }
  return out;
}

export function actionCodes(category: Category, profile: HealthProfile, trend: Trend = 'STABLE'): string[] {
  const codes = new Set<string>();
  for (const r of book.rules) {
    if (!r.categories.includes(category)) continue;
    const profiles = expand(r.profiles);
    if (profiles && !profiles.has(profile)) continue;
    if (r.trends && !r.trends.includes(trend)) continue;
    r.actions.forEach((a) => codes.add(a));
  }
  if (codes.size === 0) codes.add('CHECK_AGAIN_LATER');
  const rank = new Map(book.priority.map((c, i) => [c, i]));
  return [...codes].sort((a, b) => (rank.get(a) ?? 99) - (rank.get(b) ?? 99));
}

export function sentence(code: string, language: 'en' | 'hi' = 'en') {
  const t = book.templates[code];
  return t ? (t[language] || t.en) : code;
}

export function disclaimer(language: 'en' | 'hi' = 'en') {
  return book.disclaimer[language] || book.disclaimer.en;
}

export function isSensitive(profile: HealthProfile) {
  return book.sensitive_profiles.includes(profile);
}
