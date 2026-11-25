import type { CityAqi } from '../lib/cities';
import { CategoryBadge, Notice } from './ui';

/** Compact ranking table. Rows come from cityAqi() over real station data; nothing is hard-coded. */
export default function CityRankings({ rows }: { rows: CityAqi[] }) {
  if (rows.length === 0) {
    return <Notice tone="warn">No city has a station reporting right now.</Notice>;
  }
  return (
    <table className="w-full text-left text-sm">
      <thead className="text-[11px] uppercase tracking-wide text-slate-500">
        <tr>
          <th className="w-10 py-1.5">#</th>
          <th className="py-1.5">City</th>
          <th className="py-1.5 text-right">AQI</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-navy-800">
        {rows.map((c, i) => (
          <tr key={`${c.city}|${c.state}`}>
            <td className="py-2 text-slate-500">{i + 1}</td>
            <td className="py-2">
              <div className="text-slate-100">{c.city}</div>
              <div className="text-[11px] text-slate-500">
                {c.state} · {c.stations} station{c.stations > 1 ? 's' : ''}
              </div>
            </td>
            <td className="py-2 text-right"><CategoryBadge category={c.category} aqi={c.aqi} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
