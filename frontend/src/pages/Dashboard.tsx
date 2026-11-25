import { Link } from 'react-router-dom';
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ApiError, api } from '../api/client';
import type { IngestionHealth, IngestionStatus, ModelInfoResponse, StationSummary } from '../api/types';
import AqiLegend from '../components/AqiLegend';
import CityRankings from '../components/CityRankings';
import HealthAdvice from '../components/HealthAdvice';
import { Card, CategoryBadge, ErrorNotice, Loading, Notice, Stat } from '../components/ui';
import { CATEGORIES, formatAge, formatTime, pollutantLabel } from '../lib/aqi';
import { useAuth } from '../lib/auth';
import { rankCities } from '../lib/cities';
import { useAsync } from '../lib/useAsync';

const REFRESH_MS = 5 * 60 * 1000;   // ingestion runs every 15 min; poll a bit more often than that

export default function Dashboard() {
  const stations = useAsync(api.stations, [], REFRESH_MS);
  const model = useAsync(api.modelInfo, [], REFRESH_MS);
  const ingestion = useAsync(api.ingestionHealth, [], REFRESH_MS);

  if (stations.loading) return <Loading />;
  if (stations.error && !stations.data) return <ErrorNotice error={stations.error} onRetry={stations.reload} />;
  const all = stations.data ?? [];
  const fresh = all.filter((s) => s.aqi != null && !s.stale);
  const latest = all.reduce<string | null>((m, s) => (s.ts && (!m || s.ts > m) ? s.ts : m), null);
  const distribution = CATEGORIES.map((c) => ({ ...c, count: fresh.filter((s) => s.category === c.key).length }));
  const worst = [...fresh].sort((a, b) => (b.aqi ?? 0) - (a.aqi ?? 0)).slice(0, 10);
  const ranking = rankCities(all, 8);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-100">Air quality across India</h1>
        <p className="text-sm text-slate-400">Live CPCB station data, ingested by the VayuDrishti backend.</p>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Active stations" value={all.length} />
        <Stat label="Reporting now" value={fresh.length} hint="Latest hour within the stale limit" />
        <Stat label="Stale or no data" value={all.length - fresh.length} />
        <Stat label="Latest observation" value={<span className="text-lg">{formatTime(latest)}</span>}
          hint={latest ? formatAge(latest) : undefined} />
      </div>
      {stations.error && <ErrorNotice error={stations.error} onRetry={stations.reload} />}

      <IngestionCard health={ingestion.data} error={ingestion.error} loading={ingestion.loading} />

      {all.length === 0 && (
        <Notice tone="warn" title="No stations yet">
          The ingestion job has not stored any stations. Check that DATA_GOV_API_KEY is configured.
        </Notice>
      )}

      <div className="grid gap-6 lg:grid-cols-5">
        <Card title="Stations by AQI category (reporting now)" className="lg:col-span-3">
          {fresh.length === 0 ? (
            <Notice tone="warn">No station has fresh data right now.</Notice>
          ) : (
            <div className="h-64">
              <ResponsiveContainer>
                <BarChart data={distribution} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
                  <XAxis dataKey="label" tick={{ fill: '#94a3b8', fontSize: 12 }} interval={0} />
                  <YAxis allowDecimals={false} tick={{ fill: '#94a3b8', fontSize: 12 }} />
                  <Tooltip cursor={{ fill: '#1a2b4a' }}
                    contentStyle={{ background: '#111e36', border: '1px solid #27406a', borderRadius: 8 }}
                    formatter={(v: number) => [`${v} stations`, '']} labelStyle={{ color: '#e2e8f0' }} />
                  <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                    {distribution.map((d) => <Cell key={d.key} fill={d.color} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Card>

        <ModelCard model={model.data} error={model.error} loading={model.loading} />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Most polluted cities">
          <CityRankings rows={ranking.most} />
        </Card>
        <Card title="Least polluted cities">
          <CityRankings rows={ranking.least} />
        </Card>
        <Card title="Health advice">
          <MyStationAdvice stations={all} />
        </Card>
      </div>
      <p className="-mt-3 text-xs text-slate-500">
        City AQI = mean of the city's stations reporting now ({ranking.cityCount} cities). Refreshes every 5 minutes.
      </p>
      <AqiLegend />

      <Card title="Highest station AQI right now">
        {worst.length === 0 ? <Notice tone="warn">No fresh readings.</Notice> : <WorstTable rows={worst} />}
      </Card>
    </div>
  );
}

const STATUS_STYLE: Record<IngestionStatus, { label: string; cls: string }> = {
  HEALTHY: { label: 'Healthy', cls: 'border-teal-600/60 bg-teal-950/50 text-teal-300' },
  DEGRADED: { label: 'Degraded', cls: 'border-amber-600/60 bg-amber-950/50 text-amber-300' },
  STALE: { label: 'Stale data', cls: 'border-orange-600/60 bg-orange-950/50 text-orange-300' },
  SOURCE_UNAVAILABLE: { label: 'Source unavailable', cls: 'border-red-700/60 bg-red-950/50 text-red-300' },
  FAILED: { label: 'Failing', cls: 'border-red-700/60 bg-red-950/50 text-red-300' },
  UNKNOWN: { label: 'Unknown', cls: 'border-slate-600/60 bg-navy-900 text-slate-300' },
};

/** Data-pipeline status: is ingestion actually delivering fresh observations (not just "is the server up"). */
function IngestionCard({ health, error, loading }: { health: IngestionHealth | null; error: Error | null; loading: boolean }) {
  if (loading && !health) return null;
  if (!health) {
    return error ? <Notice tone="warn" title="Data pipeline status unavailable">{error.message}</Notice> : null;
  }
  const style = STATUS_STYLE[health.status] ?? STATUS_STYLE.UNKNOWN;
  const staleHours = Math.round(health.staleAfterMinutes / 60);
  return (
    <Card title="Data pipeline" action={
      <span className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${style.cls}`}>{style.label}</span>}>
      <div className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm lg:grid-cols-4">
        <PipelineFact label="Last successful ingestion" value={formatTime(health.lastSuccessfulRunAt)}
          hint={formatAge(health.lastSuccessfulRunAt)} />
        <PipelineFact label="Newest observation" value={formatTime(health.latestObservationAt)}
          hint={health.latestObservationAt ? formatAge(health.latestObservationAt) : 'none stored'} />
        <PipelineFact label="Last run" value={health.lastRunStatus ?? '-'}
          hint={health.lastRunAt ? `${formatAge(health.lastRunAt)}${health.lastRunTrigger ? ` · ${health.lastRunTrigger.toLowerCase()}` : ''}` : 'never'} />
        <PipelineFact label="Stations fresh / stale" value={`${health.stationsFresh} / ${health.stationsStale}`}
          hint={`of ${health.stationsActive} active · fresh = within ${staleHours} h`} />
      </div>
      {health.reasons.length > 0 && health.status !== 'HEALTHY' && (
        <ul className="mt-3 list-disc space-y-0.5 pl-5 text-sm text-slate-300">
          {health.reasons.map((r) => <li key={r}>{r}</li>)}
        </ul>
      )}
      {health.stationsActive > 0 && health.stationsFresh === 0 && (
        <p className="mt-3 text-sm text-slate-400">
          No station has an observation from the last {staleHours} h, so current AQI values are hidden rather than shown
          as if they were live. The CPCB feed only publishes the latest hour; hours missed while ingestion was down cannot
          be filled in later. Fresh data appears after the next successful ingestion of a newly published hour.
        </p>
      )}
    </Card>
  );
}

function PipelineFact({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-slate-500">{label}</div>
      <div className="text-slate-100">{value}</div>
      {hint && <div className="text-xs text-slate-400">{hint}</div>}
    </div>
  );
}

function WorstTable({ rows }: { rows: StationSummary[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-slate-500">
          <tr>
            <th className="py-2 pr-4">Station</th>
            <th className="py-2 pr-4">State</th>
            <th className="py-2 pr-4">AQI</th>
            <th className="py-2 pr-4">Dominant</th>
            <th className="py-2">Updated</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-navy-800">
          {rows.map((s) => (
            <tr key={s.id}>
              <td className="py-2 pr-4">
                <Link to={`/forecast?station=${encodeURIComponent(s.id)}`} className="text-slate-100 hover:text-teal-300">
                  {s.name}
                </Link>
              </td>
              <td className="py-2 pr-4 text-slate-400">{s.state}</td>
              <td className="py-2 pr-4"><CategoryBadge category={s.category} aqi={s.aqi} /></td>
              <td className="py-2 pr-4 text-slate-400">{pollutantLabel(s.dominantPollutant)}</td>
              <td className="py-2 text-slate-400">{formatTime(s.ts)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ModelCard({ model, error, loading }: { model: ModelInfoResponse | null; error: Error | null; loading: boolean }) {
  return (
    <Card title="Forecast model" className="lg:col-span-2">
      {loading ? <Loading /> : error ? <ErrorNotice error={error} /> : !model?.available || !model.info ? (
        <Notice tone="error" title="Forecast service unavailable">Current AQI is still served.</Notice>
      ) : model.info.model_loaded ? (
        <ModelDetails info={model.info} />
      ) : (
        <div className="space-y-3 text-sm">
          <Notice tone="warn" title="No trained model promoted yet">
            Forecasts use the {model.info.serving === 'BASELINE_PERSISTENCE' ? 'persistence baseline (last value carried forward)' : 'baseline'}.
            A model is promoted only after it beats the current one on a held-out recent window.
          </Notice>
          <p className="text-slate-400">
            Stations also need 24 hourly observations before any forecast is produced.
          </p>
        </div>
      )}
    </Card>
  );
}

function ModelDetails({ info }: { info: NonNullable<ModelInfoResponse['info']> }) {
  const overall = info.validation?.overall ?? {};
  const rows = ['lgbm', 'persistence', 'same_hour_yesterday', 'same_hour_7d_mean']
    .filter((m) => overall[`${m}_mae`] != null)
    .map((m) => ({ m, mae: overall[`${m}_mae`], acc: overall[`${m}_cat_acc`] }));
  const coverage = info.validation?.interval_coverage_q10_q90;
  const baselineStations = Object.keys(info.stations_served_by_baseline ?? {}).length;
  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-slate-300">
        <span>Version <span className="font-mono text-teal-300">{info.version}</span></span>
        <span>{info.n_stations} stations</span>
      </div>
      {rows.length > 0 && (
        <table className="w-full text-left">
          <thead className="text-xs uppercase text-slate-500">
            <tr><th className="py-1">Method (holdout)</th><th>MAE</th><th>Category acc.</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.m} className={r.m === 'lgbm' ? 'text-teal-300' : 'text-slate-300'}>
                <td className="py-1">{r.m === 'lgbm' ? 'LightGBM' : r.m.replaceAll('_', ' ')}</td>
                <td>{r.mae.toFixed(1)}</td>
                <td>{r.acc != null ? `${(r.acc * 100).toFixed(1)}%` : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {coverage != null && (
        <p className="text-slate-400">q10-q90 interval coverage: {(coverage * 100).toFixed(1)}% (nominal 80%)</p>
      )}
      <p className="text-slate-400">
        {baselineStations > 0
          ? `${baselineStations} station(s) are served by a baseline because it beat the model there.`
          : 'The model beat every baseline on the holdout window.'}
      </p>
    </div>
  );
}

/** Advice for the signed-in user's saved station and health profile; honest fallbacks otherwise. */
function MyStationAdvice({ stations }: { stations: StationSummary[] }) {
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

  if (!token) {
    return (
      <Notice>
        <Link to="/profile" className="underline">Sign in</Link> and save a station and health profile to see
        advice for your area, or open any station on the <Link to="/forecast" className="underline">Forecast</Link> page.
      </Notice>
    );
  }
  if (profile.loading) return <Loading />;
  if (profile.error) return <ErrorNotice error={profile.error} onRetry={profile.reload} />;
  const station = stations.find((s) => s.id === profile.data?.stationId);
  if (!profile.data || !station) {
    return <Notice><Link to="/profile" className="underline">Choose a station in your profile</Link> to see advice here.</Notice>;
  }
  return (
    <div className="space-y-2">
      <Link to={`/forecast?station=${encodeURIComponent(station.id)}`} className="text-sm text-slate-100 hover:text-teal-300">
        {station.name}
      </Link>
      <HealthAdvice aqi={station.aqi} category={station.category} stale={station.stale}
        dominantPollutant={station.dominantPollutant} profile={profile.data.healthProfile} profileSource="saved" />
    </div>
  );
}
