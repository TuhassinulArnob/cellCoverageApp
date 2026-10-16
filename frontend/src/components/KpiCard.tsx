import React from 'react';
import type { LucideIcon } from 'lucide-react';

interface KpiCardProps {
  title: string;
  value: string | number | null;
  unit?: string;
  icon: LucideIcon;
  badge?: {
    text: string;
    type: 'excellent' | 'good' | 'fair' | 'poor' | 'neutral';
  };
  subtitle?: string;
}

export const KpiCard: React.FC<KpiCardProps> = ({
  title,
  value,
  unit,
  icon: Icon,
  badge,
  subtitle,
}) => {
  const getBadgeStyle = (type: string) => {
    switch (type) {
      case 'excellent':
        return 'bg-emerald-950 text-emerald-400 border-emerald-800';
      case 'good':
        return 'bg-cyan-950 text-cyan-400 border-cyan-800';
      case 'fair':
        return 'bg-amber-950 text-amber-400 border-amber-800';
      case 'poor':
        return 'bg-rose-950 text-rose-400 border-rose-800';
      default:
        return 'bg-slate-800 text-slate-400 border-slate-700';
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm hover:border-slate-700 transition-all flex flex-col justify-between">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-slate-400 uppercase tracking-wider">{title}</span>
        <div className="p-2 rounded-lg bg-slate-800/80 text-cyan-400 border border-slate-700/60">
          <Icon className="w-4 h-4" />
        </div>
      </div>

      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-2xl font-bold tracking-tight text-white font-mono">
          {value !== null && value !== undefined ? value : '—'}
        </span>
        {unit && <span className="text-xs text-slate-400 font-semibold">{unit}</span>}
        {badge && (
          <span
            className={`ml-auto text-xs font-semibold px-2 py-0.5 rounded-full border ${getBadgeStyle(
              badge.type
            )}`}
          >
            {badge.text}
          </span>
        )}
      </div>

      {subtitle && <p className="mt-2 text-xs text-slate-500 truncate">{subtitle}</p>}
    </div>
  );
};
