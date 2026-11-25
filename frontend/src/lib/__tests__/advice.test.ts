import { describe, expect, it } from 'vitest';
import type { Category, HealthProfile } from '../../api/types';
import { actionCodes, isSensitive } from '../advice';

const CATS: Category[] = ['GOOD', 'SATISFACTORY', 'MODERATE', 'POOR', 'VERY_POOR', 'SEVERE'];
const PROFILES: HealthProfile[] = ['GENERAL', 'ASTHMA', 'CHILD', 'ELDERLY', 'EXERCISER'];

describe('health advice rules', () => {
  it('always gives at least one action for every category and profile', () => {
    for (const c of CATS) for (const p of PROFILES) expect(actionCodes(c, p).length).toBeGreaterThan(0);
  });

  it('is deterministic and free of duplicates', () => {
    const a = actionCodes('SEVERE', 'ASTHMA');
    expect(actionCodes('SEVERE', 'ASTHMA')).toEqual(a);
    expect(new Set(a).size).toBe(a.length);
  });

  it('gives sensitive groups at least the general advice when the air is poor', () => {
    expect(isSensitive('GENERAL')).toBe(false);
    for (const p of PROFILES.filter(isSensitive)) {
      for (const c of ['POOR', 'VERY_POOR', 'SEVERE'] as Category[]) {
        expect(actionCodes(c, p).length).toBeGreaterThanOrEqual(actionCodes(c, 'GENERAL').length);
      }
    }
  });
});
