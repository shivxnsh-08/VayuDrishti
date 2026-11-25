import type { Alert, NotificationKind } from '../api/types';

export const KIND_LABEL: Record<NotificationKind, string> = {
  AQI_THRESHOLD_CROSSED: 'Alert level reached',
  AQI_DETERIORATION: 'AQI deterioration',
  AQI_RECOVERY: 'Air quality improved',
  AQI_STABILITY_REMINDER: '8-hour reminder',
  FORECAST: 'Forecast alert',
  STALE_NOTICE: 'Stale data',
  MORNING_BRIEFING: 'Morning briefing',
  TEST_MESSAGE: 'Test message',
  CURRENT: 'Current AQI alert',
};

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind as NotificationKind] ?? kind.replaceAll('_', ' ').toLowerCase();
}

/** Status as a person would describe it. */
export function statusLabel(a: Pick<Alert, 'status' | 'lastError'>): string {
  switch (a.status) {
    case 'SENT': return 'Delivered to Telegram';
    case 'PENDING': return a.lastError ? 'Retrying' : 'Queued';
    case 'SENDING': return 'Sending';
    case 'SUPPRESSED': return a.lastError?.includes('quiet hours') ? 'Held: quiet hours' : 'Not sent';
    case 'FAILED': return a.lastError?.startsWith('expired') || a.lastError?.includes('expired') ? 'Expired' : 'Failed';
    default: return a.status;
  }
}

export function changeText(a: Pick<Alert, 'aqiChange' | 'previousAqi'>): string | null {
  if (a.aqiChange == null || a.previousAqi == null) return null;
  const sign = a.aqiChange > 0 ? '+' : '';
  return `${sign}${a.aqiChange} from ${a.previousAqi}`;
}

export type MessagePart =
  | { type: 'text'; text: string }
  | { type: 'b' | 'i' | 'code'; children: MessagePart[] }
  | { type: 'a'; href: string; children: MessagePart[] };

const ALLOWED = new Set(['b', 'i', 'code', 'a']);

/**
 * Parses a stored Telegram-HTML message into a safe tree: only <b> <i> <code> <a href="http(s)://..."> survive,
 * everything else becomes plain text. Rendering this tree never injects HTML.
 */
export function parseTelegramHtml(html: string): MessagePart[] {
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const walk = (node: Node): MessagePart[] => {
    const out: MessagePart[] = [];
    node.childNodes.forEach((n) => {
      if (n.nodeType === Node.TEXT_NODE) {
        out.push({ type: 'text', text: n.textContent ?? '' });
      } else if (n.nodeType === Node.ELEMENT_NODE) {
        const el = n as Element;
        const tag = el.tagName.toLowerCase();
        const children = walk(el);
        if (!ALLOWED.has(tag)) {
          out.push(...children);
        } else if (tag === 'a') {
          const href = el.getAttribute('href') ?? '';
          if (/^https?:\/\//i.test(href)) out.push({ type: 'a', href, children });
          else out.push(...children);
        } else {
          out.push({ type: tag as 'b' | 'i' | 'code', children });
        }
      }
    });
    return out;
  };
  return walk(doc.body.firstChild as Node);
}
