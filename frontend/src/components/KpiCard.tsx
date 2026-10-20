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
        return 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/80 dark:text-emerald-400 dark:border-emerald-800';
      case 'good':
        return 'bg-sky-50 text-sky-700 border-sky-200 dark:bg-cyan-950/80 dark:text-cyan-400 dark:border-cyan-800';
      case 'fair':
        return 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/80 dark:text-amber-400 dark:border-amber-800';
      case 'poor':
        return 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/80 dark:text-rose-400 dark:border-rose-800';
      default:
        return 'bg-slate-100 text-slate-700 border-slate-200 dark:bg-slate-800 dark:text-slate-400 dark:border-slate-700';
    }
  };

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:border-slate-300 dark:hover:border-slate-700 transition-all flex flex-col justify-between">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">{title}</span>
        <div className="p-2 rounded-lg bg-slate-100 text-cyan-600 border border-slate-200 dark:bg-slate-800/80 dark:text-cyan-400 dark:border-slate-700/60">
          <Icon className="w-4 h-4" />
        </div>
      </div>

      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white font-mono">
          {value !== null && value !== undefined ? value : '—'}
        </span>
        {unit && <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold">{unit}</span>}
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

      {subtitle && <p className="mt-2 text-xs text-slate-500 dark:text-slate-500 truncate">{subtitle}</p>}
    </div>
  );
};
