import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';

/** Retry delay while the API is unreachable (backend restarting/starting); the error stays visible meanwhile. */
export const UNAVAILABLE_RETRY_MS = 10_000;

/** Network failure or gateway error: the backend is down or starting, not a problem with the request. */
export function isUnavailable(error: unknown): boolean {
  return error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
}

/**
 * Minimal data-loading hook: { data, error, loading, reload }. Ignores results of stale calls.
 * With refreshMs, reloads periodically in the background and keeps showing the last data meanwhile.
 * While the API is unreachable it also retries every UNAVAILABLE_RETRY_MS, so the page recovers on its own
 * after a backend restart; the error is shown until then.
 */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[], refreshMs?: number) {
  const [state, setState] = useState<{ data: T | null; error: Error | null; loading: boolean }>(
    { data: null, error: null, loading: true });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: s.data === null, error: null }));
    fn().then(
      (data) => alive && setState({ data, error: null, loading: false }),
      (error: Error) => alive && setState((s) => ({ data: s.data, error, loading: false })),
    );
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  useEffect(() => {
    if (!refreshMs) return;
    const id = setInterval(() => setTick((t) => t + 1), refreshMs);
    return () => clearInterval(id);
  }, [refreshMs]);

  useEffect(() => {
    if (!isUnavailable(state.error)) return;
    const id = setTimeout(() => setTick((t) => t + 1), UNAVAILABLE_RETRY_MS);
    return () => clearTimeout(id);
  }, [state.error]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ...state, reload };
}
