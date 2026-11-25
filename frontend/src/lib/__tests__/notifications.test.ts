// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { changeText, kindLabel, parseTelegramHtml, statusLabel } from '../notifications';

describe('notification labels', () => {
  it('names every notification type readably', () => {
    expect(kindLabel('AQI_THRESHOLD_CROSSED')).toBe('Alert level reached');
    expect(kindLabel('AQI_STABILITY_REMINDER')).toBe('8-hour reminder');
    expect(kindLabel('FORECAST')).toBe('Forecast alert');
    expect(kindLabel('STALE_NOTICE')).toBe('Stale data');
    expect(kindLabel('SOMETHING_NEW')).toBe('something new');
  });

  it('describes delivery status honestly', () => {
    expect(statusLabel({ status: 'SENT', lastError: null })).toBe('Delivered to Telegram');
    expect(statusLabel({ status: 'PENDING', lastError: null })).toBe('Queued');
    expect(statusLabel({ status: 'PENDING', lastError: 'HTTP 502' })).toBe('Retrying');
    expect(statusLabel({ status: 'SUPPRESSED', lastError: 'suppressed: quiet hours (x)' })).toBe('Held: quiet hours');
    expect(statusLabel({ status: 'FAILED', lastError: 'PERMANENT: HTTP 403' })).toBe('Failed');
    expect(statusLabel({ status: 'FAILED', lastError: 'expired: not delivered within the briefing window' })).toBe('Expired');
  });

  it('formats the AQI change only when both values are known', () => {
    expect(changeText({ aqiChange: 42, previousAqi: 173 })).toBe('+42 from 173');
    expect(changeText({ aqiChange: -30, previousAqi: 180 })).toBe('-30 from 180');
    expect(changeText({ aqiChange: null, previousAqi: 180 })).toBeNull();
  });
});

describe('parseTelegramHtml', () => {
  it('keeps only b, i, code and http(s) links; everything else becomes text', () => {
    const parts = parseTelegramHtml('<b>AQI <i>215</i></b> <code>x</code> <u>u</u> <a href="https://v.d">go</a><a href="javascript:x">no</a>');
    expect(parts).toEqual([
      { type: 'b', children: [{ type: 'text', text: 'AQI ' }, { type: 'i', children: [{ type: 'text', text: '215' }] }] },
      { type: 'text', text: ' ' }, { type: 'code', children: [{ type: 'text', text: 'x' }] }, { type: 'text', text: ' ' },
      { type: 'text', text: 'u' }, { type: 'text', text: ' ' },
      { type: 'a', href: 'https://v.d', children: [{ type: 'text', text: 'go' }] }, { type: 'text', text: 'no' },
    ]);
  });
});
