import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Area, CartesianGrid, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { ApiError, api } from '../api/client';
import type { CurrentAqi, Forecast, ForecastPoint, HistoryPoint, ModelInfoResponse } from '../api/types';
import HealthAdvice from '../components/HealthAdvice';
import StationPicker from '../components/StationPicker';
import { Card, CategoryBadge, ErrorNotice, Loading, Notice, Stat } from '../components/ui';
import { categoryColor, categoryLabel, formatAge, formatTime, pollutantLabel } from '../lib/aqi';
import { useAuth } from '../lib/auth';
import { useLocationState } from '../lib/locationContext';
import { useAsync } from '../lib/useAsync';
import { useAutoStation, type AutoState } from '../lib/useAutoStation';

// Mirrors the backend's readiness rule (saanscheck.forecast.history-hours / min-history-points):
// 24 distinct valid hourly observations within the last 168 hours. The backend's status is authoritative;
// these numbers only explain it.
const HISTORY_WINDOW_HOURS = 168;
const REQUIRED_HOURS = 24;
const CHART_HISTORY_HOURS = 48;
/** Station data and forecasts change hourly at the source; polling every 5 min shows new hours promptly. */
const REFRESH_MS = 5 * 60_000;

export default function ForecastPage() {
  const [params, setParams] = useSearchParams();
  const { token } = useAuth();
  const manualId = params.get('station');                 // manual choice (search, map, links) wins
  const auto = useAutoStation(!manualId, token);
  const stationId = manualId ?? auto.stationId;
  const stations = useAsync(api.stations, []);

  const backToNearest = () => {
    if (manualId) setParams({});
    auto.requestNearest();
  };

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold text-slate-100">AI forecast</h1>
        <p className="text-sm text-slate-400">
          Next 24 hours of AQI for the monitoring station nearest to you, or any station you choose.
        </p>
      </div>

      <SelectionBanner manual={!!manualId} state={auto.state} permission={auto.permission}
        stationName={stations.data?.find((s) => s.id === stationId)?.name ?? stationId} onNearest={backToNearest} />

      {stations.error ? <ErrorNotice error={stations.error} onRetry={stations.reload} /> : (
        <div className="space-y-1">
          <div className="text-xs text-slate-500">Search another city or station</div>
          <StationPicker stations={stations.data ?? []} value={stationId} showNearest={false}
            onChange={(id) => setParams({ station: id })} />
        </div>
      )}
      {stationId ? <StationForecast key={stationId} stationId={stationId} /> : null}
    </div>
  );
}

/** Which station is shown and why: automatic (nearest eligible), saved, or manual - never ambiguous. */
function SelectionBanner({ manual, state, permission, stationName, onNearest }: {
  manual: boolean; state: AutoState; permission: string | null; stationName: string | null; onNearest: () => void;
}) {
  const { dismissed, dismiss } = useLocationState();      // "Not now" is remembered for the browsing session
  const canLocate = permission !== 'denied' && permission !== 'unsupported';
  const deniedHelp = permission === 'denied' && (
    <span className="mt-1 block text-xs opacity-90">
      Location is blocked for this site in your browser, so VayuDrishti will not ask again. To use your nearest
      station, allow location for this site in your browser's site settings (the icon next to the address bar).
    </span>
  );
  const nearestButton = canLocate && (
    <button onClick={onNearest} className="font-medium text-teal-400 hover:text-teal-300">
      {manual ? 'Back to my nearest station' : 'Use my location'}
    </button>
  );

  if (manual) {
    return (
      <Notice title={`Selected manually: ${stationName ?? ''}`}>
        Your choice stays until you pick another station{canLocate ? ' or go back to your nearest one' : ''}.{' '}
        {nearestButton}
      </Notice>
    );
  }
  if (state.kind === 'checking' || state.kind === 'locating') {
    return <Notice>{state.kind === 'locating' ? 'Finding the nearest monitoring station to you...' : 'Checking location settings...'}</Notice>;
  }
  if (state.kind === 'auto') {
    const { station, note, excluded } = state.choice;
    return (
      <Notice tone={note ? 'warn' : 'info'} title={`Nearest station to you: ${station.name} · ${station.distanceKm.toFixed(1)} km away`}>
        <span className="block">Detected automatically from your location (used only to find the station; not stored).</span>
        {note && <span className="mt-1 block">{note}</span>}
        {excluded.length > 0 && (
          <span className="mt-1 block">
            Closer stations skipped: {excluded.map((x) => `${x.name} (${x.distanceKm.toFixed(1)} km): ${x.reason}`).join('; ')}.
          </span>
        )}
        <span className="mt-1 block text-xs opacity-80">Location re-checked {new Date(state.updatedAt).toLocaleTimeString()}.</span>
      </Notice>
    );
  }
  const explain = !dismissed && canLocate && permission === 'prompt' && (
    <Notice title="Show the forecast for the station nearest to you?">
      Allow location access and VayuDrishti picks the nearest monitoring station automatically. Your location is only
      used to find that station: it is rounded to about 1 km, not stored, and not shared.{' '}
      <button onClick={onNearest} className="font-medium text-teal-400 hover:text-teal-300">Use my location</button>
      {' · '}
      <button onClick={dismiss} className="text-slate-300 hover:text-slate-100">Not now</button>
    </Notice>
  );
  if (state.kind === 'saved') {
    return (
      <div className="space-y-3">
        {explain}
        <Notice title={`Your saved station: ${stationName ?? ''}`}>
          From your Profile. {state.reason} {!explain && nearestButton}
          {deniedHelp}
        </Notice>
      </div>
    );
  }
  if (state.kind === 'none') {
    return (
      <div className="space-y-3">
        {explain}
        <Notice tone="warn" title="Choose a monitoring station">
          {state.reason} Search for a city or station below{canLocate && !explain ? ', or ' : '.'}
          {!explain && nearestButton}
          {deniedHelp}
        </Notice>
      </div>
    );
  }
  return null;
}

function StationForecast({ stationId }: { stationId: string }) {
  const current = useAsync(() => api.current(stationId), [stationId], REFRESH_MS);
  const history = useAsync(
    () => api.history(stationId, new Date(Date.now() - HISTORY_WINDOW_HOURS * 3600_000)), [stationId], REFRESH_MS);
  const forecast = useAsync(() => api.forecast(stationId), [stationId], REFRESH_MS);
  const model = useAsync(api.modelInfo, [], REFRESH_MS);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  useEffect(() => {
    if (current.data || forecast.data) setUpdatedAt(new Date());
  }, [current.data, forecast.data]);

  if (current.loading) return <Loading />;
  if (current.error) return <ErrorNotice error={current.error} onRetry={current.reload} />;
  const c = current.data!;
  const items = history.data?.items ?? [];
  const validHours = new Set(items.filter((i) => i.aqi != null).map((i) => i.ts)).size;

  return (
    <div className="space-y-5">
      <CurrentCard c={c} />
      <p className="-mt-3 text-xs text-slate-500">
        Updates automatically every 5 minutes{updatedAt ? ` · last updated ${updatedAt.toLocaleTimeString()}` : ''}.
      </p>

      <div className="grid gap-5 lg:grid-cols-3">
        <Card title="Forecast readiness">
          {history.loading ? <Loading /> : history.error ? <ErrorNotice error={history.error} onRetry={history.reload} /> : (
            <Readiness validHours={validHours} c={c} />
          )}
        </Card>
        <Card title="Model status">
          <ModelStatus model={model.data} error={model.error} loading={model.loading} forecast={forecast.data} />
        </Card>
        <Card title="Forecast summary">
          {forecast.loading ? <Loading /> : <Summary f={forecast.data} error={forecast.error} c={c} />}
        </Card>
      </div>

      {forecast.loading ? null : forecast.error ? (
        <Notice tone="error" title="Forecast request failed">
          {forecast.error.message} The backend could not be reached or returned an error; no forecast is shown.
        </Notice>
      ) : <ForecastStatus f={forecast.data!} validHours={validHours} />}

      {forecast.data?.status === 'AVAILABLE' && forecast.data.points.length > 0 && (
        <HourlyStrip points={forecast.data.points} method={forecast.data.method} />
      )}

      {!history.loading && !history.error && (
        <ForecastChart history={items} forecast={forecast.data} />
      )}
      {history.data && <QualityNote items={items} />}

      <Card title="Health advice for the current reading">
        <AdviceForStation c={c} />
      </Card>
    </div>
  );
}

function CurrentCard({ c }: { c: CurrentAqi }) {
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <div className="col-span-2 rounded-xl border border-navy-700 bg-navy-900 p-4">
        <div className="text-xs uppercase tracking-wide text-slate-400">Station</div>
        <div className="mt-1 text-lg font-semibold text-slate-100">{c.stationName}</div>
        <div className="mt-2 flex items-center gap-2">
          <span className="text-xs text-slate-400">Current AQI</span>
          <CategoryBadge category={c.category} aqi={c.aqi} />
        </div>
      </div>
      <Stat label="Dominant pollutant" value={pollutantLabel(c.dominantPollutant)} />
      <Stat label="Observed" value={<span className="text-lg">{formatTime(c.ts)}</span>}
        hint={c.ts == null ? 'No observation yet'
          : c.stale ? <span className="text-amber-300">Stale · {formatAge(c.ts)}</span> : `Fresh · ${formatAge(c.ts)}`} />
    </div>
  );
}

function Readiness({ validHours, c }: { validHours: number; c: CurrentAqi }) {
  const pct = Math.min(100, (validHours / REQUIRED_HOURS) * 100);
  const ready = validHours >= REQUIRED_HOURS;
  return (
    <div className="space-y-3 text-sm">
      <div>
        <div className="mb-1 flex justify-between text-slate-300">
          <span>Valid hourly observations</span>
          <span className={ready ? 'text-teal-300' : 'text-amber-300'}>{Math.min(validHours, 999)} / {REQUIRED_HOURS}</span>
        </div>
        <div className="h-2 overflow-hidden rounded-full bg-navy-800">
          <div className={`h-full ${ready ? 'bg-teal-500' : 'bg-amber-500'}`} style={{ width: `${pct}%` }} />
        </div>
        <p className="mt-1 text-xs text-slate-500">Counted over the last 7 days, as the backend does.</p>
      </div>
      <div className="flex justify-between">
        <span className="text-slate-400">Latest data</span>
        <span className={c.stale ? 'text-amber-300' : 'text-slate-200'}>
          {c.ts ? `${c.stale ? 'Stale' : 'Fresh'}, ${formatAge(c.ts)}` : 'None'}
        </span>
      </div>
      <p className="text-xs text-slate-500">
        Forecasts need {REQUIRED_HOURS} real hourly observations and fresh data. Missing hours are never padded.
      </p>
    </div>
  );
}

function ModelStatus({ model, error, loading, forecast }: {
  model: ModelInfoResponse | null; error: Error | null; loading: boolean; forecast: Forecast | null;
}) {
  if (loading) return <Loading />;
  if (error) return <ErrorNotice error={error} />;
  if (!model?.available || !model.info) {
    return <Notice tone="error" title="Forecast service unavailable">Model information cannot be read right now.</Notice>;
  }
  const info = model.info;
  const lgbmMae = info.validation?.overall?.lgbm_mae;
  const coverage = info.validation?.interval_coverage_q10_q90;
  return (
    <dl className="space-y-2 text-sm">
      <Row k="Service" v={<span className="text-teal-300">Reachable</span>} />
      <Row k="Trained model" v={info.model_loaded ? <span className="font-mono text-teal-300">{info.version}</span>
        : <span className="text-amber-300">None promoted yet</span>} />
      {!info.model_loaded && <Row k="Serving" v="Persistence baseline" />}
      {info.training_window && <Row k="Trained on" v={`${formatTime(info.training_window.start)} to ${formatTime(info.training_window.end)}`} />}
      {lgbmMae != null && <Row k="Holdout MAE" v={lgbmMae.toFixed(1)} />}
      {coverage != null && <Row k="Range coverage" v={`${(coverage * 100).toFixed(1)}% (nominal 80%)`} />}
      {forecast?.status === 'AVAILABLE' && <Row k="This station" v={methodLabel(forecast.method)} />}
    </dl>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-slate-400">{k}</dt>
      <dd className="text-right text-slate-200">{v}</dd>
    </div>
  );
}

function methodLabel(method: string | null) {
  if (!method) return '-';
  if (method === 'MODEL') return 'LightGBM model';
  return `${method.replace('BASELINE_', '').replaceAll('_', ' ').toLowerCase()} baseline`;
}

function Summary({ f, error, c }: { f: Forecast | null; error: Error | null; c: CurrentAqi }) {
  if (error || !f) return <p className="text-sm text-slate-400">No forecast (see below).</p>;
  if (f.status !== 'AVAILABLE' || f.points.length === 0) {
    return (
      <div className="space-y-2 text-sm">
        <Row k="Status" v={<span className="text-amber-300">{f.status.replace('_', ' ').toLowerCase()}</span>} />
        <Row k="Current AQI" v={c.aqi ?? '-'} />
        <p className="text-xs text-slate-500">No predicted values are shown until the backend returns a forecast.</p>
      </div>
    );
  }
  const next = f.points[0];
  const peak = f.points.reduce((m, p) => (p.predictedAqi > m.predictedAqi ? p : m), f.points[0]);
  return (
    <dl className="space-y-2 text-sm">
      <Row k="Next hour" v={<CategoryBadge category={next.category} aqi={next.predictedAqi} />} />
      <Row k="Peak (24 h)" v={<span><CategoryBadge category={peak.category} aqi={peak.predictedAqi} /> <span className="text-xs text-slate-400">at {formatTime(peak.targetTs, false)}</span></span>} />
      <Row k="Horizon" v={`${f.points.length} h (to ${formatTime(f.points[f.points.length - 1].targetTs)})`} />
      <Row k="Method" v={methodLabel(f.method)} />
      {f.modelVersion && f.method === 'MODEL' && <Row k="Model version" v={<span className="font-mono">{f.modelVersion}</span>} />}
      <Row k="Generated" v={`${formatTime(f.generatedAt)} (${formatAge(f.generatedAt)})`} />
    </dl>
  );
}

function ForecastStatus({ f, validHours }: { f: Forecast; validHours: number }) {
  switch (f.status) {
    case 'AVAILABLE':
      return f.method?.startsWith('BASELINE_') ? (
        <Notice tone="warn" title="Forecast available (baseline)">
          Served by the {methodLabel(f.method)}: no trained model is promoted yet, or a baseline was more accurate for this
          station on held-out data. Ranges, when shown, come from this station's recent hour-to-hour changes.
        </Notice>
      ) : (
        <Notice title="Forecast available (AI model)">
          LightGBM quantile model {f.modelVersion}. The shaded range is the model's 10th-90th percentile.
        </Notice>
      );
    case 'INSUFFICIENT_DATA':
      return (
        <Notice tone="warn" title="Insufficient historical data">
          {f.message} ({validHours} of {REQUIRED_HOURS} counted here.) The forecast starts automatically once enough
          real hourly observations have been ingested.
        </Notice>
      );
    case 'STALE_DATA':
      return (
        <Notice tone="warn" title="Stale input data">
          {f.message} A forecast from old data would be misleading, so none is produced.
        </Notice>
      );
    default:
      return (
        <Notice tone="error" title="Forecast service unavailable or prediction failed">
          {f.message ?? 'Try again later.'} The backend does not distinguish an unreachable service from a failed
          prediction; current AQI is unaffected.
        </Notice>
      );
  }
}

function HourlyStrip({ points, method }: { points: ForecastPoint[]; method: string | null }) {
  const hasRanges = points.some((p) => p.lower != null && p.upper != null);
  return (
    <Card title="Hourly forecast">
      <div className="flex gap-2 overflow-x-auto pb-1">
        {points.map((p) => (
          <div key={p.targetTs} className="min-w-[78px] rounded-lg border border-navy-700 bg-navy-950 px-2 py-2 text-center">
            <div className="text-[11px] text-slate-400">{formatTime(p.targetTs, false)}</div>
            <div className="text-lg font-semibold" style={{ color: categoryColor(p.category) }}>{p.predictedAqi}</div>
            <div className="text-[10px] text-slate-500">{categoryLabel(p.category)}</div>
            {p.lower != null && p.upper != null && (
              <div className="text-[10px] text-slate-500">{p.lower}-{p.upper}</div>
            )}
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs text-slate-500">
        {hasRanges
          ? method === 'MODEL' ? 'Ranges: model 10th-90th percentile.' : 'Ranges: empirical 10th-90th percentile of recent changes.'
          : 'No range: too little history to estimate one honestly.'}
      </p>
    </Card>
  );
}

interface ChartRow { t: number; actual?: number; predicted?: number; range?: [number, number] }

function ForecastChart({ history, forecast }: { history: HistoryPoint[]; forecast: Forecast | null }) {
  const data = useMemo(() => {
    const since = Date.now() - CHART_HISTORY_HOURS * 3600_000;
    const rows: ChartRow[] = history.filter((h) => h.aqi != null && Date.parse(h.ts) >= since)
      .map((h) => ({ t: Date.parse(h.ts), actual: h.aqi! }));
    for (const p of forecast?.status === 'AVAILABLE' ? forecast.points : []) {
      rows.push({
        t: Date.parse(p.targetTs), predicted: p.predictedAqi,
        range: p.lower != null && p.upper != null ? [p.lower, p.upper] : undefined,
      });
    }
    return rows.sort((a, b) => a.t - b.t);
  }, [history, forecast]);

  const hasForecast = forecast?.status === 'AVAILABLE';
  return (
    <Card title={hasForecast ? `Observed (last ${CHART_HISTORY_HOURS} h) and forecast (next 24 h)` : `Observed AQI, last ${CHART_HISTORY_HOURS} h`}>
      {data.length === 0 ? <Notice tone="warn">No observations for this station yet.</Notice> : (
        <div className="h-80">
          <ResponsiveContainer>
            <ComposedChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
              <CartesianGrid stroke="#1a2b4a" strokeDasharray="3 3" />
              <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']}
                tickFormatter={(t) => formatTime(new Date(t).toISOString())} tick={{ fill: '#94a3b8', fontSize: 11 }}
                minTickGap={40} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} domain={[0, 'auto']} />
              <Tooltip
                contentStyle={{ background: '#111e36', border: '1px solid #27406a', borderRadius: 8 }}
                labelFormatter={(t) => formatTime(new Date(t as number).toISOString())}
                formatter={(v: number | [number, number], name: string) =>
                  [Array.isArray(v) ? `${v[0]} - ${v[1]}` : v, name]} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <ReferenceLine x={Date.now()} stroke="#64748b" strokeDasharray="4 4"
                label={{ value: 'now', fill: '#94a3b8', fontSize: 11 }} />
              {hasForecast && (
                <Area dataKey="range" name="Forecast range (10th-90th pct.)" stroke="none" fill="#14b8a6"
                  fillOpacity={0.18} connectNulls isAnimationActive={false} />
              )}
              <Line dataKey="actual" name="Observed AQI" stroke="#e2e8f0" dot={{ r: 2 }} strokeWidth={2}
                connectNulls={false} isAnimationActive={false} />
              {hasForecast && (
                <Line dataKey="predicted" name="Forecast" stroke="#2dd4bf" strokeDasharray="5 4" dot={false}
                  strokeWidth={2} isAnimationActive={false} />
              )}
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      )}
      {!hasForecast && <p className="mt-2 text-xs text-slate-500">No forecast line: the backend has not produced a forecast for this station.</p>}
    </Card>
  );
}

function QualityNote({ items }: { items: HistoryPoint[] }) {
  const flagged = items.filter((i) => i.qualityFlags.length > 0);
  if (flagged.length === 0) return null;
  const flags = [...new Set(flagged.flatMap((i) => i.qualityFlags))].slice(0, 6).join(', ');
  return (
    <p className="text-xs text-slate-500">
      Data quality: {flagged.length} of {items.length} hours had flagged pollutant readings ({flags}).
      Flagged readings are excluded from the AQI.
    </p>
  );
}

/** Uses the signed-in user's saved health profile when there is one; otherwise the general profile. */
function AdviceForStation({ c }: { c: CurrentAqi }) {
  const { token } = useAuth();
  const profile = useAsync(async () => {
    if (!token) return null;
    try {
      return await api.profile(token);
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 401)) return null;
      throw e;
    }
  }, [token]);
  if (profile.loading) return <Loading />;
  const saved = profile.data?.healthProfile ?? null;
  return (
    <>
      <HealthAdvice aqi={c.aqi} category={c.category} stale={c.stale} dominantPollutant={c.dominantPollutant}
        profile={saved} profileSource={saved ? 'saved' : 'default'} />
      {!token && (
        <p className="mt-2 text-xs text-slate-500">
          <Link to="/profile" className="underline">Sign in</Link> to use your saved health profile.
        </p>
      )}
    </>
  );
}
