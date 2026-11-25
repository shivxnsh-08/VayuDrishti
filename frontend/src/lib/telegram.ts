import type { LinkCode, TelegramStatus } from '../api/types';

export type TelegramView =
  | { kind: 'LOADING' }
  | { kind: 'NOT_CONFIGURED' }
  | { kind: 'LINKED' }
  | { kind: 'NOT_LINKED' }
  | { kind: 'CODE_ACTIVE'; code: string; secondsLeft: number; link: string | null }
  | { kind: 'CODE_EXPIRED' };

/**
 * What the Telegram card shows. "Linked" only when the backend says so: a generated code is never treated as a
 * connection. The link to open is the per-code deep link, or the bot's page.
 */
export function telegramView(status: TelegramStatus | null, code: LinkCode | null, nowMs: number): TelegramView {
  if (!status) return { kind: 'LOADING' };
  if (!status.configured) return { kind: 'NOT_CONFIGURED' };
  if (status.linked) return { kind: 'LINKED' };
  if (code) {
    const secondsLeft = Math.floor((Date.parse(code.expiresAt) - nowMs) / 1000);
    if (secondsLeft <= 0) return { kind: 'CODE_EXPIRED' };
    return { kind: 'CODE_ACTIVE', code: code.code, secondsLeft, link: code.deepLink ?? status.botLink };
  }
  return { kind: 'NOT_LINKED' };
}

export function formatCountdown(seconds: number): string {
  const s = Math.max(0, seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
