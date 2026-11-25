import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { isUnavailable, UNAVAILABLE_RETRY_MS } from '../useAsync';

describe('API unavailability (auto-retry after a backend restart)', () => {
  it('treats network errors and gateway errors as "backend unavailable"', () => {
    for (const s of [0, 502, 503, 504]) expect(isUnavailable(new ApiError(s, 'x'))).toBe(true);
  });

  it('never auto-retries request errors or auth failures', () => {
    for (const s of [400, 401, 403, 404, 409, 422, 429, 500]) expect(isUnavailable(new ApiError(s, 'x'))).toBe(false);
    expect(isUnavailable(new Error('plain'))).toBe(false);
    expect(isUnavailable(null)).toBe(false);
  });

  it('retries at a modest pace', () => {
    expect(UNAVAILABLE_RETRY_MS).toBeGreaterThanOrEqual(5_000);
  });
});
