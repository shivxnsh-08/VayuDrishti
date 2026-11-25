import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { LinkCode, TelegramStatus } from '../api/types';
import { formatTime } from '../lib/aqi';
import { formatCountdown, telegramView } from '../lib/telegram';
import { Button, Card, Notice } from './ui';

const POLL_MS = 4000;   // while a code is active, check whether the bot has linked this account

type Busy = null | 'code' | 'test' | 'unlink';

function errorText(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) return 'Your session has expired. Sign out and sign in again.';
  return (err as Error).message;
}

/**
 * Telegram connection. Everything shown comes from GET /me/telegram (the backend's authoritative status);
 * "Connected" is shown only when the backend says linked=true.
 */
export default function TelegramCard({ token }: { token: string }) {
  const [status, setStatus] = useState<TelegramStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [code, setCode] = useState<LinkCode | null>(null);
  const [message, setMessage] = useState<{ tone: 'info' | 'error'; text: string } | null>(null);
  const [busy, setBusy] = useState<Busy>(null);
  const [confirmUnlink, setConfirmUnlink] = useState(false);
  const [now, setNow] = useState(Date.now());

  async function refresh(): Promise<TelegramStatus | null> {
    try {
      const s = await api.telegramStatus(token);
      setStatus(s);
      setLoadError(null);
      return s;
    } catch (err) {
      setLoadError(errorText(err));
      return null;
    }
  }

  useEffect(() => { void refresh(); }, [token]);   // eslint-disable-line react-hooks/exhaustive-deps

  const view = telegramView(status, code, now);

  // While a code is active: tick the countdown every second and ask the backend every few seconds.
  useEffect(() => {
    if (view.kind !== 'CODE_ACTIVE') return;
    const tick = setInterval(() => setNow(Date.now()), 1000);
    const poll = setInterval(async () => {
      const s = await refresh();
      if (s?.linked) {
        setCode(null);
        setMessage({ tone: 'info', text: 'Telegram connected. Alerts for your saved station will arrive there.' });
      }
    }, POLL_MS);
    return () => { clearInterval(tick); clearInterval(poll); };
  }, [view.kind]);   // eslint-disable-line react-hooks/exhaustive-deps

  async function run(kind: Exclude<Busy, null>, action: () => Promise<void>) {
    setBusy(kind);
    setMessage(null);
    try {
      await action();
    } catch (err) {
      setMessage({ tone: 'error', text: errorText(err) });
    } finally {
      setBusy(null);
    }
  }

  const generate = () => run('code', async () => {
    setCode(await api.telegramLink(token));
    setNow(Date.now());
  });
  const unlink = () => run('unlink', async () => {
    await api.telegramUnlink(token);
    setConfirmUnlink(false);
    setCode(null);
    await refresh();
    setMessage({ tone: 'info', text: 'Telegram disconnected. No more alerts will be sent there.' });
  });
  const sendTest = () => run('test', async () => {
    const r = await api.telegramTest(token);
    setMessage({ tone: 'info', text: r.message });
  });

  const botName = status?.botUsername ? `@${status.botUsername}` : null;
  const openLink = status?.botLink ? (
    <a href={status.botLink} target="_blank" rel="noreferrer" className="font-medium text-teal-400 hover:text-teal-300">
      Open Telegram
    </a>
  ) : null;
  const botLine = botName && (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-slate-300">
      <span>Bot: <span className="font-mono text-slate-100">{botName}</span></span>
      {openLink}
    </div>
  );

  return (
    <Card title="Telegram alerts">
      <div className="space-y-3 text-sm">
        {view.kind === 'LOADING' && !loadError && <p className="text-slate-400">Checking Telegram status...</p>}

        {loadError && (
          <Notice tone="error" title="Could not load Telegram status">
            {loadError}{' '}
            <button onClick={() => void refresh()} className="underline hover:no-underline">Retry</button>
          </Notice>
        )}

        {view.kind === 'NOT_CONFIGURED' && (
          <Notice tone="warn" title="Telegram is not set up on this server">
            The administrator has not configured a Telegram bot yet, so alerts cannot be delivered.
          </Notice>
        )}

        {status?.configured && status.botState === 'TOKEN_REJECTED' && (
          <Notice tone="error" title="Telegram bot unavailable">
            Telegram rejected this server's bot token. Linking and alerts will not work until the administrator fixes it.
          </Notice>
        )}

        {view.kind === 'LINKED' && (
          <>
            <div className="flex items-center gap-2">
              <span aria-hidden className="h-2.5 w-2.5 rounded-full bg-emerald-400" />
              <span className="font-semibold text-emerald-300">Connected</span>
            </div>
            <p className="text-slate-300">Telegram account successfully linked. Alerts for your saved station arrive there.</p>
            {botLine}
            <div className="flex flex-wrap gap-2">
              <Button variant="ghost" onClick={sendTest} disabled={busy !== null}>
                {busy === 'test' ? 'Sending...' : 'Send test message'}
              </Button>
              {!confirmUnlink ? (
                <Button variant="ghost" onClick={() => setConfirmUnlink(true)} disabled={busy !== null}>Unlink</Button>
              ) : (
                <>
                  <Button variant="danger" onClick={unlink} disabled={busy !== null}>
                    {busy === 'unlink' ? 'Unlinking...' : 'Yes, unlink Telegram'}
                  </Button>
                  <Button variant="ghost" onClick={() => setConfirmUnlink(false)} disabled={busy !== null}>Cancel</Button>
                </>
              )}
            </div>
            {confirmUnlink && <p className="text-xs text-slate-400">Unlinking stops all alerts to this Telegram chat.</p>}
          </>
        )}

        {(view.kind === 'NOT_LINKED' || view.kind === 'CODE_EXPIRED' || view.kind === 'CODE_ACTIVE') && (
          <div className="flex items-center gap-2">
            <span aria-hidden className="h-2.5 w-2.5 rounded-full border border-slate-400" />
            <span className="font-semibold text-slate-300">Not connected</span>
          </div>
        )}

        {(view.kind === 'NOT_LINKED' || view.kind === 'CODE_EXPIRED') && (
          <>
            {botLine}
            <p className="text-slate-400">
              Generate a one-time code, then send it to the bot to receive alerts on Telegram.
            </p>
            {view.kind === 'CODE_EXPIRED' && <Notice tone="warn">That code expired. Generate a new one.</Notice>}
            <Button variant="ghost" onClick={generate} disabled={busy !== null}>
              {busy === 'code' ? 'Generating...' : 'Generate link code'}
            </Button>
          </>
        )}

        {view.kind === 'CODE_ACTIVE' && (
          <div className="space-y-3">
            {botLine}
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="font-mono text-2xl tracking-widest text-teal-300">{view.code}</span>
              <span className="text-xs text-slate-400">
                expires in {formatCountdown(view.secondsLeft)} ({formatTime(code?.expiresAt, false)}), works once
              </span>
            </div>
            <ol className="list-decimal space-y-1 pl-5 text-slate-300">
              <li>
                Open {botName ?? 'the bot'} in Telegram
                {view.link && (
                  <> - <a href={view.link} target="_blank" rel="noreferrer" className="font-medium text-teal-400 hover:text-teal-300">Open Telegram</a></>
                )}
              </li>
              <li>Press <span className="font-medium">Start</span>.</li>
              <li>If the code is not sent automatically, send <span className="font-mono">/start {view.code}</span>.</li>
            </ol>
            <p className="text-xs text-slate-500">This page updates by itself once the bot confirms the link.</p>
            <Button variant="ghost" onClick={generate} disabled={busy !== null}>
              {busy === 'code' ? 'Generating...' : 'New code'}
            </Button>
          </div>
        )}

        {message && <Notice tone={message.tone === 'error' ? 'error' : 'info'}>{message.text}</Notice>}
      </div>
    </Card>
  );
}
