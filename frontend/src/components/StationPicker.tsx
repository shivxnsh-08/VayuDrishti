import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { StationSummary } from '../api/types';
import { Button, inputClass } from './ui';

/** Searchable station selector (from the full station list) plus "nearest to me" via PostGIS. */
export default function StationPicker({ stations, value, onChange, showNearest = true }:
  { stations: StationSummary[]; value: string | null; onChange: (id: string) => void; showNearest?: boolean }) {
  const [query, setQuery] = useState('');
  const [locating, setLocating] = useState(false);
  const [geoError, setGeoError] = useState<string | null>(null);

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q.length < 2) return [];
    return stations.filter((s) =>
      `${s.name} ${s.city ?? ''} ${s.state ?? ''}`.toLowerCase().includes(q)).slice(0, 8);
  }, [query, stations]);

  const current = stations.find((s) => s.id === value);

  function useLocation() {
    if (!navigator.geolocation) {
      setGeoError('Location is not available in this browser.');
      return;
    }
    setLocating(true);
    setGeoError(null);
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        try {
          const near = await api.nearest(pos.coords.latitude, pos.coords.longitude, 1);
          if (near.length) onChange(near[0].id);
          else setGeoError('No active station found.');
        } catch (e) {
          setGeoError((e as Error).message);
        } finally {
          setLocating(false);
        }
      },
      () => { setLocating(false); setGeoError('Location permission was denied.'); },
      { timeout: 10000 },
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-col gap-2 sm:flex-row">
        <div className="relative flex-1">
          <input className={inputClass} value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder={current ? `${current.name}` : 'Search a station, city or state'} />
          {matches.length > 0 && (
            <ul className="absolute z-20 mt-1 w-full overflow-hidden rounded-lg border border-navy-600 bg-navy-900 shadow-lg">
              {matches.map((s) => (
                <li key={s.id}>
                  <button className="w-full px-3 py-2 text-left text-sm hover:bg-navy-800"
                    onClick={() => { onChange(s.id); setQuery(''); }}>
                    <span className="text-slate-100">{s.name}</span>
                    <span className="ml-2 text-xs text-slate-500">{[s.city, s.state].filter(Boolean).join(', ')}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        {showNearest && (
          <Button variant="ghost" onClick={useLocation} disabled={locating}>
            {locating ? 'Locating...' : 'Nearest to me'}
          </Button>
        )}
      </div>
      {geoError && <p className="text-xs text-amber-300">{geoError}</p>}
    </div>
  );
}
