import { useEffect, useState } from 'react';
import { isOutdated, runningBundle } from '../lib/version';

const CHECK_MS = 5 * 60 * 1000;

/**
 * A single-page app keeps running the JavaScript it loaded first, so a tab opened before a deploy shows the old
 * interface until it is reloaded. Check every 5 minutes (and when the tab regains focus) and offer a reload.
 */
export default function UpdateBanner() {
  const [outdated, setOutdated] = useState(false);

  useEffect(() => {
    const running = runningBundle();
    if (!running) return;   // dev server: no hashed bundle
    let alive = true;
    const check = async () => {
      try {
        const res = await fetch('/', { cache: 'no-store' });
        if (alive && res.ok && isOutdated(running, await res.text())) setOutdated(true);
      } catch { /* offline or backend restarting: try again later */ }
    };
    const id = setInterval(check, CHECK_MS);
    window.addEventListener('focus', check);
    return () => { alive = false; clearInterval(id); window.removeEventListener('focus', check); };
  }, []);

  if (!outdated) return null;
  return (
    <div role="status" className="border-b border-teal-700/60 bg-teal-950/80 px-4 py-2 text-center text-sm text-teal-100">
      A new version of VayuDrishti is available.{' '}
      <button onClick={() => window.location.reload()} className="font-semibold underline hover:no-underline">Reload</button>
    </div>
  );
}
