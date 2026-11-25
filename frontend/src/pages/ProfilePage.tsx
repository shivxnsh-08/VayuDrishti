import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import type { Category, HealthProfile, Profile, ProfileUpdate, StationSummary } from '../api/types';
import StationPicker from '../components/StationPicker';
import TelegramCard from '../components/TelegramCard';
import { Button, Card, Collapsible, ErrorNotice, Field, FlashNotice, Loading, Notice, inputClass } from '../components/ui';
import { CATEGORIES, categoryLabel } from '../lib/aqi';
import { useAuth } from '../lib/auth';
import { isSavedProfile, profileSummary } from '../lib/profile';
import { useAsync } from '../lib/useAsync';

export default function ProfilePage() {
  const { token } = useAuth();
  return token ? <ProfileEditor token={token} /> : <AuthForm />;
}

function AuthForm() {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await (mode === 'login' ? login : register)(email, password);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-md">
      <Card title={mode === 'login' ? 'Sign in to VayuDrishti' : 'Create a VayuDrishti account'}>
        <form onSubmit={submit} className="space-y-4">
          <Field label="Email">
            <input className={inputClass} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Field label="Password" hint={mode === 'register' ? 'At least 8 characters.' : undefined}>
            <input className={inputClass} type="password" required minLength={8} maxLength={72}
              value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          {error && <Notice tone="error">{error}</Notice>}
          <Button type="submit" disabled={busy} className="w-full">
            {busy ? 'Please wait...' : mode === 'login' ? 'Sign in' : 'Create account'}
          </Button>
        </form>
        <p className="mt-4 text-center text-sm text-slate-400">
          {mode === 'login' ? 'New here? ' : 'Already registered? '}
          <button className="text-teal-400 hover:text-teal-300"
            onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}>
            {mode === 'login' ? 'Create an account' : 'Sign in'}
          </button>
        </p>
      </Card>
    </div>
  );
}

const PROFILES: { key: HealthProfile; label: string }[] = [
  { key: 'GENERAL', label: 'General' },
  { key: 'ASTHMA', label: 'Asthma' },
  { key: 'CHILD', label: 'Child' },
  { key: 'ELDERLY', label: 'Elderly' },
  { key: 'EXERCISER', label: 'Outdoor exerciser' },
];

/** The editable fields of a saved profile (what "Edit profile" restores). */
function formFrom(p: Profile): ProfileUpdate {
  return {
    healthProfile: p.healthProfile, latitude: p.latitude, longitude: p.longitude, homeCity: p.homeCity,
    stationId: p.stationId, language: p.language, alertMinCategory: p.alertMinCategory,
    quietStart: p.quietStart?.slice(0, 5) ?? null, quietEnd: p.quietEnd?.slice(0, 5) ?? null,
    severeOverridesQuiet: p.severeOverridesQuiet,
    morningBriefingEnabled: p.morningBriefingEnabled ?? true,
    morningBriefingTime: (p.morningBriefingTime ?? '07:00').slice(0, 5),
    aqiAlertsEnabled: p.aqiAlertsEnabled ?? true,
  };
}

const EMPTY: ProfileUpdate = {
  healthProfile: 'GENERAL', latitude: null, longitude: null, homeCity: null, stationId: null, language: 'en',
  alertMinCategory: null, quietStart: null, quietEnd: null, severeOverridesQuiet: false,
  morningBriefingEnabled: true, morningBriefingTime: '07:00', aqiAlertsEnabled: true,
};

function ProfileEditor({ token }: { token: string }) {
  const { logout } = useAuth();
  const stations = useAsync(api.stations, []);
  const loaded = useAsync(async () => {
    try {
      return await api.profile(token);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null;   // no profile saved yet
      throw e;
    }
  }, [token]);
  const [form, setForm] = useState<ProfileUpdate>(EMPTY);
  const [saved, setSaved] = useState<Profile | null>(null);
  const [status, setStatus] = useState<{ tone: 'info' | 'error'; text: string } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  // null = not decided yet: collapsed when the backend already has a saved profile (with a station).
  const [editing, setEditing] = useState<boolean | null>(null);
  const [flash, setFlash] = useState<{ id: number; text: string } | null>(null);

  const expired = loaded.error instanceof ApiError && loaded.error.status === 401;
  useEffect(() => { if (expired) logout(); }, [expired, logout]);

  useEffect(() => {
    if (loaded.data) {
      setSaved(loaded.data);
      setForm(formFrom(loaded.data));
    }
  }, [loaded.data]);

  if (loaded.loading) return <Loading />;
  if (loaded.error && !expired) return <ErrorNotice error={loaded.error} onRetry={loaded.reload} />;

  const showForm = editing ?? !isSavedProfile(saved ?? loaded.data);
  const set = <K extends keyof ProfileUpdate>(k: K, v: ProfileUpdate[K]) => setForm((f) => ({ ...f, [k]: v }));
  const station = stations.data?.find((s) => s.id === form.stationId);

  async function save(e: FormEvent) {
    e.preventDefault();
    setStatus(null);
    try {
      const p = await api.saveProfile(token, form);
      setSaved(p);
      setForm(formFrom(p));
      // Collapse only after the backend confirmed the save (a failure keeps the form open).
      setEditing(!isSavedProfile(p));
      setFlash({ id: Date.now(), text: 'Profile saved successfully.' });
    } catch (err) {
      setStatus({ tone: 'error', text: (err as Error).message });
    }
  }

  function edit() {
    if (saved) setForm(formFrom(saved));   // start from exactly what is saved
    setStatus(null);
    setEditing(true);
  }

  function cancelEdit() {
    if (saved) setForm(formFrom(saved));   // discard unsaved changes
    setStatus(null);
    setEditing(false);
  }

  async function deleteAccount() {
    try {
      await api.deleteAccount(token);
      logout();
    } catch (err) {
      setStatus({ tone: 'error', text: (err as Error).message });
    }
  }

  function useMyLocation() {
    navigator.geolocation?.getCurrentPosition(
      (pos) => setForm((f) => ({ ...f, latitude: pos.coords.latitude, longitude: pos.coords.longitude, stationId: null })),
      () => setStatus({ tone: 'error', text: 'Location permission was denied.' }),
    );
  }

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="space-y-5 lg:col-span-2">
        {flash && <FlashNotice id={flash.id} text={flash.text} onDone={() => setFlash(null)} />}

        {!showForm && saved && <SavedProfileSummary profile={saved} stations={stations.data} onEdit={edit} />}

        <Collapsible open={showForm} id="profile-settings">
          <form onSubmit={save} className="space-y-5">
          <Card title="Health and alerts">
            <label className="mb-4 flex items-start gap-3 text-sm text-slate-200">
              <input type="checkbox" role="switch" aria-label="AQI alerts" aria-checked={form.aqiAlertsEnabled}
                className="mt-0.5 h-4 w-4 accent-teal-500" checked={form.aqiAlertsEnabled}
                onChange={(e) => set('aqiAlertsEnabled', e.target.checked)} />
              <span>
                <span className="font-medium">AQI alerts on Telegram</span>
                <span className="block text-xs text-slate-400">
                  When the AQI reaches your alert level, changes by 25+ points or a category, improves again, and a
                  reminder every 8 hours while it stays above your level. Separate from the morning briefing.
                </span>
              </span>
            </label>
            <div className={`grid gap-4 sm:grid-cols-2 ${form.aqiAlertsEnabled ? '' : 'opacity-60'}`}>
              <Field label="Health profile" hint="Sensitive profiles are alerted at lower AQI levels.">
                <select className={inputClass} value={form.healthProfile}
                  onChange={(e) => set('healthProfile', e.target.value as HealthProfile)}>
                  {PROFILES.map((p) => <option key={p.key} value={p.key}>{p.label}</option>)}
                </select>
              </Field>
              <Field label="Alert language">
                <select className={inputClass} value={form.language} onChange={(e) => set('language', e.target.value as 'en' | 'hi')}>
                  <option value="en">English</option>
                  <option value="hi">Hindi</option>
                </select>
              </Field>
              <Field label="Alert me from category"
                hint={saved ? `Currently in effect: ${categoryLabel(saved.effectiveMinCategory)}` : 'Default depends on the health profile.'}>
                <select className={inputClass} value={form.alertMinCategory ?? ''}
                  onChange={(e) => set('alertMinCategory', (e.target.value || null) as Category | null)}>
                  <option value="">Default for my profile</option>
                  {CATEGORIES.map((c) => <option key={c.key} value={c.key}>{c.label} ({c.range})</option>)}
                </select>
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Quiet from">
                  <input type="time" className={inputClass} value={form.quietStart ?? ''}
                    onChange={(e) => set('quietStart', e.target.value || null)} />
                </Field>
                <Field label="Quiet until">
                  <input type="time" className={inputClass} value={form.quietEnd ?? ''}
                    onChange={(e) => set('quietEnd', e.target.value || null)} />
                </Field>
              </div>
            </div>
            <label className="mt-4 flex items-center gap-2 text-sm text-slate-300">
              <input type="checkbox" className="accent-teal-500" checked={form.severeOverridesQuiet}
                onChange={(e) => set('severeOverridesQuiet', e.target.checked)} />
              Send Severe alerts even during quiet hours
            </label>
            <p className="mt-1 text-xs text-slate-500">
              During quiet hours, reminders and routine updates wait until the quiet period ends. Only alerts for
              Severe air (AQI 401+) are sent during quiet hours, and only if you tick this box.
            </p>
          </Card>

          <Card title="Location">
            <p className="mb-3 text-sm text-slate-400">
              Only a coarse location is stored (rounded to about 1 km). Choose a station directly,
              or share your location and the nearest station is assigned.
            </p>
            {stations.data && (
              <StationPicker stations={stations.data} value={form.stationId}
                onChange={(id) => setForm((f) => ({ ...f, stationId: id }))} />
            )}
            <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
              <span className="text-slate-300">
                Station: <span className="text-slate-100">{station ? station.name : form.stationId ?? 'not set'}</span>
              </span>
              {form.latitude != null && (
                <span className="text-slate-500">({form.latitude.toFixed(2)}, {form.longitude?.toFixed(2)})</span>
              )}
              <button type="button" onClick={useMyLocation} className="text-teal-400 hover:text-teal-300">
                Use my location instead
              </button>
            </div>
          </Card>

          <Card title="Morning AQI briefing">
            <label className="flex items-center gap-3 text-sm text-slate-200">
              <input type="checkbox" role="switch" aria-label="Morning briefing" aria-checked={form.morningBriefingEnabled}
                className="h-4 w-4 accent-teal-500" checked={form.morningBriefingEnabled}
                onChange={(e) => set('morningBriefingEnabled', e.target.checked)} />
              Send me a morning air-quality briefing on Telegram
            </label>
            <div className={`mt-4 grid gap-4 sm:grid-cols-2 ${form.morningBriefingEnabled ? '' : 'opacity-50'}`}>
              <Field label="Delivery time (IST)" hint="One message a day. If the server is down then, it is sent up to 3 h late.">
                <input type="time" className={inputClass} value={form.morningBriefingTime}
                  disabled={!form.morningBriefingEnabled}
                  onChange={(e) => set('morningBriefingTime', e.target.value || '07:00')} />
              </Field>
              <div className="space-y-1 text-sm">
                <div className="text-xs font-medium text-slate-300">Uses</div>
                <div className="text-slate-300">Language: <span className="text-slate-100">{form.language === 'hi' ? 'Hindi' : 'English'}</span>
                  <span className="text-slate-500"> (alert language above)</span></div>
                <div className="text-slate-300">Station: <span className="text-slate-100">{station ? station.name : form.stationId ?? 'choose one in Location'}</span></div>
              </div>
            </div>
            <p className="mt-3 text-xs text-slate-500">
              Contains the latest reading with its observation time (marked stale if old), advice for your health
              profile and today's forecast when one is available.
              {!saved?.telegramLinked && ' Connect Telegram (Telegram alerts card) to receive it.'}
            </p>
          </Card>

          {status && <Notice tone={status.tone === 'error' ? 'error' : 'info'}>{status.text}</Notice>}
          <div className="flex flex-wrap gap-2">
            <Button type="submit">Save profile</Button>
            {isSavedProfile(saved) && <Button type="button" variant="ghost" onClick={cancelEdit}>Cancel</Button>}
          </div>
          </form>
        </Collapsible>

        {!showForm && status && <Notice tone={status.tone === 'error' ? 'error' : 'info'}>{status.text}</Notice>}
      </div>

      <div className="space-y-6">
        <TelegramCard token={token} />

        <Card title="Account">
          {!confirmDelete ? (
            <Button variant="danger" onClick={() => setConfirmDelete(true)}>Delete account and data</Button>
          ) : (
            <div className="space-y-3">
              <Notice tone="error">This deletes your account, profile and alert history. It cannot be undone.</Notice>
              <div className="flex gap-2">
                <Button variant="danger" onClick={deleteAccount}>Yes, delete</Button>
                <Button variant="ghost" onClick={() => setConfirmDelete(false)}>Cancel</Button>
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

/** Compact view of a saved profile: labels only, no coordinates. */
function SavedProfileSummary({ profile, stations, onEdit }:
  { profile: Profile; stations: StationSummary[] | null; onEdit: () => void }) {
  const sum = profileSummary(profile, stations);
  const rows: [string, string][] = [
    ['AQI alerts', sum.aqiAlerts],
    ['Health profile', sum.healthProfile],
    ['Alert language', sum.language],
    ['Monitoring station', sum.station],
    ['Alert category', sum.alertCategory],
    ['Quiet hours', sum.quietHours],
    ['Morning briefing', sum.morningBriefing],
  ];
  return (
    <Card title="Profile preferences"
      action={<Button variant="ghost" onClick={onEdit} aria-controls="profile-settings">Edit profile</Button>}>
      <p className="mb-4 text-sm text-slate-400">
        Your health profile, alert preferences and monitoring station have been saved.
      </p>
      <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        {rows.map(([k, v]) => (
          <div key={k} className="min-w-0">
            <dt className="text-xs uppercase tracking-wide text-slate-500">{k}</dt>
            <dd className="truncate text-slate-100" title={v}>{v}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}
