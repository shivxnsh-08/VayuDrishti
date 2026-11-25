/** The hashed entry bundle referenced by an index.html, e.g. "assets/index-DO79lQD3.js" (null if none). */
export function bundleOf(html: string): string | null {
  const m = html.match(/assets\/index-[A-Za-z0-9_-]+\.js/);
  return m ? m[0] : null;
}

/** The bundle this tab is running, read from the page's own script tag. */
export function runningBundle(doc: Document = document): string | null {
  for (const s of Array.from(doc.querySelectorAll('script[src]'))) {
    const b = bundleOf(s.getAttribute('src') ?? '');
    if (b) return b;
  }
  return null;
}

/** True when the server now serves a different bundle than the one running here (a deploy happened). */
export function isOutdated(running: string | null, servedHtml: string): boolean {
  const served = bundleOf(servedHtml);
  return running !== null && served !== null && served !== running;
}
