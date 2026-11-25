import { describe, expect, it } from 'vitest';
import { bundleOf, isOutdated } from '../version';

describe('stale build detection', () => {
  const html = (b: string) => `<!doctype html><script type="module" crossorigin src="/${b}"></script>`;
  it('reads the hashed bundle from index.html', () => {
    expect(bundleOf(html('assets/index-DO79lQD3.js'))).toBe('assets/index-DO79lQD3.js');
    expect(bundleOf('<html></html>')).toBeNull();
  });
  it('flags a tab running an older bundle than the server serves', () => {
    expect(isOutdated('assets/index-OLD11111.js', html('assets/index-DO79lQD3.js'))).toBe(true);
    expect(isOutdated('assets/index-DO79lQD3.js', html('assets/index-DO79lQD3.js'))).toBe(false);
    expect(isOutdated(null, html('assets/index-DO79lQD3.js'))).toBe(false);     // dev server
    expect(isOutdated('assets/index-DO79lQD3.js', 'Bad Gateway')).toBe(false); // backend restarting
  });
});
