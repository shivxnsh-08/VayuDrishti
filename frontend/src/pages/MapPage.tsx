import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { CircleMarker, MapContainer, Popup, TileLayer, useMap } from 'react-leaflet';
import { api } from '../api/client';
import type { Category, StationSummary } from '../api/types';
import AqiLegend from '../components/AqiLegend';
import { CategoryBadge, ErrorNotice, Loading, Notice, inputClass } from '../components/ui';
import { CATEGORIES, NO_DATA_COLOR, categoryColor, formatAge, formatTime, pollutantLabel } from '../lib/aqi';
import { useAsync } from '../lib/useAsync';

const INDIA: [number, number] = [22.8, 80.5];

export default function MapPage() {
  const { data, error, loading, reload } = useAsync(api.stations, [], 5 * 60 * 1000);
  const [state, setState] = useState('');
  const [city, setCity] = useState('');
  const [query, setQuery] = useState('');
  const [freshOnly, setFreshOnly] = useState(false);
  const [hidden, setHidden] = useState<Set<Category>>(new Set());

  const all = data ?? [];
  const states = useMemo(() => [...new Set(all.map((s) => s.state).filter(Boolean) as string[])].sort(), [all]);
  const cities = useMemo(() => [...new Set(all.filter((s) => !state || s.state === state)
    .map((s) => s.city).filter(Boolean) as string[])].sort(), [all, state]);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return all.filter((s) => {
      const live = s.aqi != null && !s.stale;
      if (freshOnly && !live) return false;
      if (state && s.state !== state) return false;
      if (city && s.city !== city) return false;
      if (q && !s.name.toLowerCase().includes(q)) return false;
      if (live && s.category && hidden.has(s.category)) return false;
      return true;
    });
  }, [all, freshOnly, state, city, query, hidden]);

  if (loading) return <Loading label="Loading stations..." />;
  if (error && !data) return <ErrorNotice error={error} onRetry={reload} />;

  const toggle = (c: Category) => setHidden((h) => {
    const next = new Set(h);
    if (next.has(c)) next.delete(c); else next.add(c);
    return next;
  });

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold text-slate-100">Live AQI map</h1>
        <p className="text-sm text-slate-400">
          {shown.length} of {all.length} stations shown. Colours show each station's latest AQI category; grey means no data or stale.
        </p>
      </div>
      {error && <ErrorNotice error={error} onRetry={reload} />}

      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <select className={inputClass} value={state} onChange={(e) => { setState(e.target.value); setCity(''); }}>
          <option value="">All states</option>
          {states.map((s) => <option key={s}>{s}</option>)}
        </select>
        <select className={inputClass} value={city} onChange={(e) => setCity(e.target.value)}>
          <option value="">All cities{state ? ` in ${state}` : ''}</option>
          {cities.map((c) => <option key={c}>{c}</option>)}
        </select>
        <input className={inputClass} placeholder="Station name" value={query} onChange={(e) => setQuery(e.target.value)} />
        <label className="flex items-center gap-2 text-sm text-slate-300">
          <input type="checkbox" checked={freshOnly} onChange={(e) => setFreshOnly(e.target.checked)} className="accent-teal-500" />
          Only stations reporting now
        </label>
      </div>

      <div className="flex flex-wrap gap-1.5">
        {CATEGORIES.map((c) => {
          const off = hidden.has(c.key);
          return (
            <button key={c.key} onClick={() => toggle(c.key)}
              className={`flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs ${off
                ? 'border-navy-700 text-slate-500 line-through' : 'border-navy-600 text-slate-200'}`}>
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: off ? NO_DATA_COLOR : c.color }} />
              {c.label}
            </button>
          );
        })}
      </div>

      <div className="relative h-[62vh] min-h-[360px] overflow-hidden rounded-xl border border-navy-700">
        <MapContainer center={INDIA} zoom={5} className="h-full w-full" preferCanvas>
          <TileLayer
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
          <FitTo stations={shown} trigger={`${state}|${city}|${query}`} />
          {shown.map((s) => <StationMarker key={s.id} s={s} />)}
        </MapContainer>
        {shown.length === 0 && (
          <div className="pointer-events-none absolute inset-x-0 top-4 z-[500] mx-auto w-fit">
            <Notice tone="warn">No stations match these filters.</Notice>
          </div>
        )}
      </div>

      <AqiLegend showNoData />
    </div>
  );
}

function StationMarker({ s }: { s: StationSummary }) {
  const live = s.aqi != null && !s.stale;
  const color = live ? categoryColor(s.category) : NO_DATA_COLOR;
  return (
    <CircleMarker center={[s.latitude, s.longitude]} radius={live ? 7 : 5}
      pathOptions={{ color: '#0b1426', weight: 1, fillColor: color, fillOpacity: live ? 0.9 : 0.5 }}>
      <Popup>
        <div className="min-w-[210px] space-y-1.5 text-sm">
          <div className="font-semibold">{s.name}</div>
          <div className="text-xs text-slate-400">{[s.city, s.state].filter(Boolean).join(', ')}</div>
          <CategoryBadge category={s.category} aqi={s.aqi} />
          <div className="text-xs text-slate-400">
            Dominant pollutant: {pollutantLabel(s.dominantPollutant)}
          </div>
          <div className="text-xs text-slate-400">
            Last updated: {formatTime(s.ts)} ({formatAge(s.ts)})
            {s.stale && <span className="ml-1 text-amber-300">· stale</span>}
          </div>
          <Link to={`/forecast?station=${encodeURIComponent(s.id)}`} className="text-teal-300">
            Forecast, history and advice
          </Link>
        </div>
      </Popup>
    </CircleMarker>
  );
}

/** Zooms to the filtered stations when the location filters change (not on every data refresh). */
function FitTo({ stations, trigger }: { stations: StationSummary[]; trigger: string }) {
  const map = useMap();
  useEffect(() => {
    if (trigger === '||') {
      map.setView(INDIA, 5);
      return;
    }
    if (stations.length === 0) return;
    const lats = stations.map((s) => s.latitude);
    const lons = stations.map((s) => s.longitude);
    map.fitBounds([[Math.min(...lats), Math.min(...lons)], [Math.max(...lats), Math.max(...lons)]],
      { padding: [40, 40], maxZoom: 12 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trigger, map]);
  return null;
}
