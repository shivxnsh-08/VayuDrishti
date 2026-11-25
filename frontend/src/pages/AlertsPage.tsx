import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Alert } from '../api/types';
import { Card, CategoryBadge, ErrorNotice, Loading, Notice } from '../components/ui';
import { formatTime } from '../lib/aqi';
import { changeText, kindLabel, type MessagePart, parseTelegramHtml, statusLabel } from '../lib/notifications';
import { useAuth } from '../lib/auth';
import { useAsync } from '../lib/useAsync';

const STATUS_STYLE: Record<Alert['status'], string> = {
  SENT: 'bg-teal-500/15 text-teal-300',
  PENDING: 'bg-sky-500/15 text-sky-300',
  SENDING: 'bg-sky-500/15 text-sky-300',
  SUPPRESSED: 'bg-slate-500/20 text-slate-300',
  FAILED: 'bg-red-500/15 text-red-300',
};

export default function AlertsPage() {
  const { token } = useAuth();
  if (!token) {
    return (
      <Notice title="Sign in to see your alerts">
        <Link to="/profile" className="underline">Sign in or create an account</Link>, choose a station and link Telegram.
      </Notice>
    );
  }
  return <AlertList token={token} />;
}

function AlertList({ token }: { token: string }) {
  const { logout } = useAuth();
  // Refreshes every 5 minutes like the dashboard, so new alerts appear without reloading the page.
  const { data, error, loading, reload } = useAsync(() => api.alerts(token), [token], 5 * 60_000);
  const expired = (error as { status?: number } | null)?.status === 401;
  useEffect(() => { if (expired) logout(); }, [expired, logout]);

  if (loading) return <Loading />;
  if (expired) return <Notice tone="warn">Your session expired. Please sign in again.</Notice>;
  if (error) return <ErrorNotice error={error} onRetry={reload} />;
  const alerts = data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-100">Alert history</h1>
          <p className="text-sm text-slate-400">
            Every Telegram notification for you - alerts, reminders, briefings and tests - with its delivery status.
          </p>
        </div>
        <button onClick={reload} className="text-sm text-teal-400 hover:text-teal-300">Refresh</button>
      </div>
      {alerts.length === 0 ? (
        <Notice>
          No notifications yet. You are alerted on Telegram when your station's AQI reaches your alert level, changes
          significantly or improves, with a reminder every 8 hours while it stays above your level, and when the
          forecast predicts a worse category. New readings are checked every 2 minutes.
        </Notice>
      ) : (
        <div className="space-y-3">
          {alerts.map((a) => <AlertRow key={a.id} a={a} />)}
        </div>
      )}
    </div>
  );
}

function AlertRow({ a }: { a: Alert }) {
  const change = changeText(a);
  const note = a.status === 'SUPPRESSED';
  return (
    <Card>
      <article aria-label={kindLabel(a.kind)}>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="font-semibold text-slate-100">{kindLabel(a.kind)}</span>
          <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_STYLE[a.status]}`}>{statusLabel(a)}</span>
          {a.category && <CategoryBadge category={a.category} aqi={a.peakAqi} />}
          {change && <span className="text-xs text-slate-400">({change})</span>}
        </div>
        <dl className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
          {a.observedAt && <Fact k="Observed" v={formatTime(a.observedAt)} />}
          {a.scheduledFor && <Fact k="Scheduled" v={formatTime(a.scheduledFor)} />}
          <Fact k="Created" v={formatTime(a.createdAt)} />
          {a.sentAt && <Fact k="Sent" v={formatTime(a.sentAt)} />}
          {a.attempts > 1 && <Fact k="Attempts" v={String(a.attempts)} />}
        </dl>
        {a.message && (
          <div className="mt-3 whitespace-pre-line rounded-lg border border-navy-700 bg-navy-950/60 p-3 text-sm leading-relaxed text-slate-300">
            <TelegramText html={a.message} />
          </div>
        )}
        {a.lastError && (
          <p className={`mt-2 text-xs ${note ? 'text-slate-400' : 'text-red-300'}`}>{note ? 'Note' : 'Last error'}: {a.lastError}</p>
        )}
      </article>
    </Card>
  );
}

function Fact({ k, v }: { k: string; v: string }) {
  return <div><dt className="inline">{k}: </dt><dd className="inline text-slate-400">{v}</dd></div>;
}

/** Shows the exact Telegram message with its formatting, built only from the safe parsed tree. */
export function TelegramText({ html }: { html: string }) {
  return <>{render(parseTelegramHtml(html))}</>;
}

function render(parts: MessagePart[]): ReactNode[] {
  return parts.map((p, i) => {
    switch (p.type) {
      case 'text': return p.text;
      case 'b': return <b key={i} className="text-slate-100">{render(p.children)}</b>;
      case 'i': return <i key={i}>{render(p.children)}</i>;
      case 'code': return <code key={i} className="font-mono">{render(p.children)}</code>;
      case 'a': return <a key={i} href={p.href} target="_blank" rel="noopener noreferrer" className="text-teal-400 underline">{render(p.children)}</a>;
      default: return null;
    }
  });
}
