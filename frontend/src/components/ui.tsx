import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Category } from '../api/types';
import { categoryColor, categoryLabel } from '../lib/aqi';

export function Card({ title, action, children, className = '' }:
  { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-xl border border-navy-700 bg-navy-900 p-4 sm:p-5 ${className}`}>
      {(title || action) && (
        <div className="mb-3 flex items-center justify-between gap-2">
          {title && <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-400">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="rounded-xl border border-navy-700 bg-navy-900 p-4">
      <div className="text-xs uppercase tracking-wide text-slate-400">{label}</div>
      <div className="mt-1 text-2xl font-semibold text-slate-100">{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500">{hint}</div>}
    </div>
  );
}

export function CategoryBadge({ category, aqi }: { category: Category | null | undefined; aqi?: number | null }) {
  const color = categoryColor(category);
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium"
      style={{ backgroundColor: `${color}26`, color, border: `1px solid ${color}66` }}>
      <span className="h-2 w-2 rounded-full" style={{ backgroundColor: color }} />
      {aqi != null && <span className="font-semibold">{aqi}</span>}
      {categoryLabel(category)}
    </span>
  );
}

type Tone = 'info' | 'warn' | 'error';
const TONES: Record<Tone, string> = {
  info: 'border-teal-700/60 bg-teal-950/40 text-teal-200',
  warn: 'border-amber-700/60 bg-amber-950/40 text-amber-200',
  error: 'border-red-800/60 bg-red-950/40 text-red-200',
};

export function Notice({ tone = 'info', title, children }: { tone?: Tone; title?: string; children?: ReactNode }) {
  return (
    <div className={`rounded-lg border px-4 py-3 text-sm ${TONES[tone]}`}>
      {title && <div className="font-semibold">{title}</div>}
      {children && <div className={title ? 'mt-0.5 opacity-90' : ''}>{children}</div>}
    </div>
  );
}

export function Loading({ label = 'Loading...' }: { label?: string }) {
  return <div className="py-8 text-center text-sm text-slate-400">{label}</div>;
}

export function ErrorNotice({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  return (
    <Notice tone="error" title="Could not load data">
      {error.message}
      {onRetry && <button onClick={onRetry} className="ml-2 underline hover:no-underline">Retry</button>}
    </Notice>
  );
}

export function Button({ children, variant = 'primary', className = '', ...rest }:
  React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const styles = {
    primary: 'bg-teal-500 text-navy-950 hover:bg-teal-400',
    ghost: 'border border-navy-600 text-slate-200 hover:bg-navy-800',
    danger: 'border border-red-800 text-red-300 hover:bg-red-950/60',
  }[variant];
  return (
    <button {...rest}
      className={`rounded-lg px-3.5 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${styles} ${className}`}>
      {children}
    </button>
  );
}

export const inputClass =
  'w-full rounded-lg border border-navy-600 bg-navy-950 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-teal-500 focus:outline-none';

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-slate-300">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-slate-500">{hint}</span>}
    </label>
  );
}

/**
 * Smoothly expands/collapses its content (height + opacity). Collapsed content stays mounted (form values are
 * kept) but is hidden from assistive tech and unreachable by keyboard (inert). Clipping is removed shortly after
 * opening so dropdowns and focus rings are not cut off; a timer is used (not transitionend) so it also works
 * with reduced motion.
 */
export function Collapsible({ open, children, id }: { open: boolean; children: ReactNode; id?: string }) {
  const inner = useRef<HTMLDivElement>(null);
  const [clip, setClip] = useState(!open);
  useEffect(() => {
    const el = inner.current;
    if (el) {
      if (open) el.removeAttribute('inert');
      else el.setAttribute('inert', '');
    }
    if (!open) {
      setClip(true);
      return undefined;
    }
    const t = setTimeout(() => setClip(false), 320);
    return () => clearTimeout(t);
  }, [open]);
  return (
    <div id={id} aria-hidden={!open} data-state={open ? 'open' : 'closed'}
      className={`grid transition-[grid-template-rows,opacity] duration-300 ease-out motion-reduce:transition-none ${
        open ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
      <div ref={inner} className={`min-h-0 ${clip ? 'overflow-hidden' : ''}`}>{children}</div>
    </div>
  );
}

/**
 * A success message that fades out by itself after {@code ms}. Pass a new {@code id} to show it again (the timing
 * restarts); timers are cleared on unmount.
 */
export function FlashNotice({ id, text, ms = 4000, onDone }: { id: number; text: string; ms?: number; onDone: () => void }) {
  const [fading, setFading] = useState(false);
  useEffect(() => {
    setFading(false);
    const fade = setTimeout(() => setFading(true), ms);
    const done = setTimeout(onDone, ms + 500);
    return () => { clearTimeout(fade); clearTimeout(done); };
  }, [id]);   // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div role="status" className={`transition-opacity duration-500 motion-reduce:transition-none ${fading ? 'opacity-0' : 'opacity-100'}`}>
      <Notice>{text}</Notice>
    </div>
  );
}
