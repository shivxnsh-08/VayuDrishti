import { describe, expect, it } from 'vitest';
import type { LinkCode, TelegramStatus } from '../../api/types';
import { formatCountdown, telegramView } from '../telegram';

const NOW = Date.parse('2026-11-24T10:00:00Z');
const status = (o: Partial<TelegramStatus> = {}): TelegramStatus => ({
  configured: true, botState: 'OK', botUsername: 'VayuTestBot', botLink: 'https://t.me/VayuTestBot', linked: false, ...o,
});
const code = (expiresAt: string, deepLink: string | null = 'https://t.me/VayuTestBot?start=ABCD2345'): LinkCode =>
  ({ code: 'ABCD2345', expiresAt, botUsername: 'VayuTestBot', deepLink });

describe('Telegram card states', () => {
  it('shows loading until the backend answers', () => {
    expect(telegramView(null, null, NOW).kind).toBe('LOADING');
  });

  it('explains when the server has no bot configured', () => {
    expect(telegramView(status({ configured: false, botState: 'NOT_CONFIGURED' }), null, NOW).kind).toBe('NOT_CONFIGURED');
  });

  it('is linked only when the backend confirms, even while a code is shown', () => {
    expect(telegramView(status(), code('2026-11-24T10:15:00Z'), NOW).kind).toBe('CODE_ACTIVE');
    expect(telegramView(status({ linked: true }), code('2026-11-24T10:15:00Z'), NOW).kind).toBe('LINKED');
  });

  it('counts the code down and expires it', () => {
    const v = telegramView(status(), code('2026-11-24T10:14:30Z'), NOW);
    expect(v).toMatchObject({ kind: 'CODE_ACTIVE', code: 'ABCD2345', secondsLeft: 870, link: 'https://t.me/VayuTestBot?start=ABCD2345' });
    expect(telegramView(status(), code('2026-11-24T09:59:59Z'), NOW).kind).toBe('CODE_EXPIRED');
  });

  it('falls back to the bot page when no deep link is available', () => {
    expect(telegramView(status(), code('2026-11-24T10:15:00Z', null), NOW)).toMatchObject({ link: 'https://t.me/VayuTestBot' });
  });

  it('formats the countdown', () => {
    expect(formatCountdown(870)).toBe('14:30');
    expect(formatCountdown(5)).toBe('0:05');
    expect(formatCountdown(-3)).toBe('0:00');
  });
});
