import { CATEGORIES, NO_DATA_COLOR } from '../lib/aqi';

export default function AqiLegend({ showNoData = false }: { showNoData?: boolean }) {
  return (
    <div className="flex flex-wrap overflow-hidden rounded-md text-[11px] font-medium">
      {CATEGORIES.map((c) => (
        <span key={c.key} className="flex-1 whitespace-nowrap px-2 py-1 text-center text-navy-950"
          style={{ background: c.color, color: c.key === 'VERY_POOR' || c.key === 'SEVERE' ? '#fff' : undefined }}
          title={c.label}>
          {c.label} {c.range}
        </span>
      ))}
      {showNoData && (
        <span className="flex-1 whitespace-nowrap px-2 py-1 text-center text-white" style={{ background: NO_DATA_COLOR }}>
          No data / stale
        </span>
      )}
    </div>
  );
}
