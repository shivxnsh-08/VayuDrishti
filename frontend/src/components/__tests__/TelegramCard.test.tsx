// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TelegramStatus } from '../../api/types';

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client');
  return {
    ...actual,
    api: {
      telegramStatus: vi.fn(),
      telegramLink: vi.fn(),
      telegramUnlink: vi.fn(),
      telegramTest: vi.fn(),
    },
  };
});

import { ApiError, api } from '../../api/client';
import TelegramCard from '../TelegramCard';

const mocked = api as unknown as Record<'telegramStatus' | 'telegramLink' | 'telegramUnlink' | 'telegramTest', ReturnType<typeof vi.fn>>;

const LINKED: TelegramStatus = {
  configured: true, botState: 'OK', botUsername: 'test_bot', botLink: 'https://t.me/test_bot', linked: true,
};
const UNLINKED: TelegramStatus = { ...LINKED, linked: false };

beforeEach(() => {
  Object.values(mocked).forEach((m) => m.mockReset());
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('TelegramCard - connected', () => {
  it('shows Connected, the bot, Open Telegram, Send test message and Unlink; no Generate link code', async () => {
    mocked.telegramStatus.mockResolvedValue(LINKED);
    render(<TelegramCard token="t" />);

    expect(await screen.findByText('Connected')).toBeTruthy();
    expect(screen.getByText('@test_bot')).toBeTruthy();
    const open = screen.getByRole('link', { name: 'Open Telegram' });
    expect(open.getAttribute('href')).toBe('https://t.me/test_bot');
    expect(screen.getByRole('button', { name: 'Send test message' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Unlink' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Generate link code' })).toBeNull();
    expect(screen.queryByText('Not connected')).toBeNull();
    expect(mocked.telegramLink).not.toHaveBeenCalled();             // never generates a code by itself
  });

  it('sends the test message for the signed-in user only (token, no chat id) and shows the result', async () => {
    mocked.telegramStatus.mockResolvedValue(LINKED);
    mocked.telegramTest.mockResolvedValue({ sent: true, message: 'Test message sent. Check Telegram.' });
    render(<TelegramCard token="tok-123" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Send test message' }));

    expect(await screen.findByText('Test message sent. Check Telegram.')).toBeTruthy();
    expect(mocked.telegramTest).toHaveBeenCalledWith('tok-123');
  });

  it('asks for confirmation before unlinking, then switches to Not connected', async () => {
    mocked.telegramStatus.mockResolvedValueOnce(LINKED).mockResolvedValueOnce(UNLINKED);
    mocked.telegramUnlink.mockResolvedValue(undefined);
    render(<TelegramCard token="t" />);

    fireEvent.click(await screen.findByRole('button', { name: 'Unlink' }));
    expect(mocked.telegramUnlink).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, unlink Telegram' }));

    expect(await screen.findByText('Not connected')).toBeTruthy();
    expect(mocked.telegramUnlink).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Generate link code' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Send test message' })).toBeNull();
  });

  it('shows a clear error when the test message fails', async () => {
    mocked.telegramStatus.mockResolvedValue(LINKED);
    mocked.telegramTest.mockRejectedValue(new ApiError(429, 'Too many test messages; try again in a few minutes'));
    render(<TelegramCard token="t" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Send test message' }));
    expect(await screen.findByText('Too many test messages; try again in a few minutes')).toBeTruthy();
    expect(screen.getByText('Connected')).toBeTruthy();             // a failed action does not change the status
  });
});

describe('TelegramCard - not connected', () => {
  it('shows Not connected, the bot and Generate link code; hides test and unlink', async () => {
    mocked.telegramStatus.mockResolvedValue(UNLINKED);
    render(<TelegramCard token="t" />);

    expect(await screen.findByText('Not connected')).toBeTruthy();
    expect(screen.getByText('@test_bot')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open Telegram' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Generate link code' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Send test message' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Unlink' })).toBeNull();
    expect(screen.queryByText('Connected')).toBeNull();
  });

  it('after a code is generated, switches to Connected by itself once the backend confirms the link', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mocked.telegramStatus.mockResolvedValueOnce(UNLINKED).mockResolvedValue(LINKED);
    mocked.telegramLink.mockResolvedValue({
      code: 'ABCD2345', expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
      botUsername: 'test_bot', deepLink: 'https://t.me/test_bot?start=ABCD2345',
    });
    render(<TelegramCard token="t" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Generate link code' }));
    expect(await screen.findByText('ABCD2345')).toBeTruthy();
    expect(screen.getByText('Not connected')).toBeTruthy();          // a code is not a connection

    await act(async () => { await vi.advanceTimersByTimeAsync(4100); });
    await waitFor(() => expect(screen.getByText('Connected')).toBeTruthy());
    expect(screen.queryByText('ABCD2345')).toBeNull();
  });
});

describe('TelegramCard - loading, errors and partial data', () => {
  it('shows a loading state first', () => {
    mocked.telegramStatus.mockReturnValue(new Promise(() => {}));
    render(<TelegramCard token="t" />);
    expect(screen.getByText('Checking Telegram status...')).toBeTruthy();
  });

  it('shows an error with Retry when the status cannot be loaded, and never claims a connection', async () => {
    mocked.telegramStatus.mockRejectedValueOnce(new ApiError(502, 'The VayuDrishti API is not reachable right now'))
      .mockResolvedValue(LINKED);
    render(<TelegramCard token="t" />);
    expect(await screen.findByText('Could not load Telegram status')).toBeTruthy();
    expect(screen.queryByText('Connected')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Connected')).toBeTruthy();
  });

  it('explains an expired session', async () => {
    mocked.telegramStatus.mockRejectedValue(new ApiError(401, 'Unauthorized'));
    render(<TelegramCard token="t" />);
    expect(await screen.findByText(/session has expired/)).toBeTruthy();
  });

  it('still renders Connected when optional bot fields are missing', async () => {
    mocked.telegramStatus.mockResolvedValue({ ...LINKED, botUsername: null, botLink: null });
    render(<TelegramCard token="t" />);
    expect(await screen.findByText('Connected')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Open Telegram' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Unlink' })).toBeTruthy();
  });

  it('treats a response without linked=true as not connected (never assumes a connection)', async () => {
    mocked.telegramStatus.mockResolvedValue({ configured: true, botState: 'OK', botUsername: 'test_bot' } as TelegramStatus);
    render(<TelegramCard token="t" />);
    expect(await screen.findByText('Not connected')).toBeTruthy();
  });

  it('explains when Telegram is not configured on the server', async () => {
    mocked.telegramStatus.mockResolvedValue({ configured: false, botState: 'NOT_CONFIGURED', botUsername: null, botLink: null, linked: false });
    render(<TelegramCard token="t" />);
    expect(await screen.findByText('Telegram is not set up on this server')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Generate link code' })).toBeNull();
  });
});
