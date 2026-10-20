import React from 'react';
import {
  Radio,
  Map,
  BarChart3,
  Gauge,
  Table,
  UploadCloud,
  Signal,
  AlertTriangle,
  ShieldCheck,
  FileDown,
  Sun,
  Moon,
} from 'lucide-react';
import type { DatasetSummary } from '../types/cellscope';
import { useTheme } from '../context/useTheme';

interface NavbarProps {
  currentTab: string;
  setCurrentTab: (tab: string) => void;
  summary: DatasetSummary | null;
  loading: boolean;
}

export const Navbar: React.FC<NavbarProps> = ({
  currentTab,
  setCurrentTab,
  summary,
}) => {
  const { theme, toggleTheme } = useTheme();

  const tabs = [
    { id: 'map', fullLabel: 'Coverage Map', shortLabel: 'Map', icon: Map },
    { id: 'analytics', fullLabel: 'RF Analytics', shortLabel: 'RF Analytics', icon: BarChart3 },
    { id: 'performance', fullLabel: 'Speed & QoE', shortLabel: 'Speed/QoE', icon: Gauge },
    { id: 'events', fullLabel: 'Events & Anomalies', shortLabel: 'Events', icon: AlertTriangle },
    { id: 'quality', fullLabel: 'Data Quality', shortLabel: 'Quality', icon: ShieldCheck },
    { id: 'table', fullLabel: 'Data Table', shortLabel: 'Table', icon: Table },
    { id: 'report', fullLabel: 'Reports & Export', shortLabel: 'Reports', icon: FileDown },
    { id: 'import', fullLabel: 'Import / Presets', shortLabel: 'Import', icon: UploadCloud },
  ];

  return (
    <header className="w-full bg-white/95 dark:bg-slate-900/95 backdrop-blur-md border-b border-slate-200 dark:border-slate-800 sticky top-0 z-50 shadow-sm transition-colors duration-200">
      <div className="max-w-[1720px] w-full mx-auto px-3 sm:px-4 lg:px-6">
        <div className="flex items-center justify-between h-16 gap-2 sm:gap-4 min-w-0">
          
          {/* 1. Logo & Branding */}
          <div className="flex items-center gap-2.5 flex-shrink-0 min-w-0">
            <div className="bg-cyan-500/10 dark:bg-cyan-500/10 p-2 rounded-lg border border-cyan-500/30 text-cyan-600 dark:text-cyan-400 flex items-center justify-center flex-shrink-0">
              <Signal className="w-5 h-5 animate-pulse" />
            </div>
            <div className="flex flex-col justify-center min-w-0">
              <div className="flex items-center gap-1.5 flex-wrap">
                <span className="font-bold text-lg sm:text-xl tracking-tight text-slate-900 dark:text-white whitespace-nowrap">
                  CellScope 5G
                </span>
                <span className="px-1.5 py-0.5 text-[10px] font-semibold tracking-wider bg-cyan-100 text-cyan-800 border border-cyan-300 dark:bg-cyan-950 dark:text-cyan-400 dark:border-cyan-800/80 rounded uppercase whitespace-nowrap">
                  v3.0 Production
                </span>
              </div>
              <p className="text-[11px] text-slate-500 dark:text-slate-400 hidden xl:block truncate max-w-[280px]">
                Passive Telemetry & Drive-Test Intelligence
              </p>
            </div>
          </div>

          {/* 2. Active Dataset Info Badge (Middle) */}
          {summary && (
            <div
              className="hidden md:flex items-center gap-2 bg-slate-100/90 hover:bg-slate-100 dark:bg-slate-800/80 dark:hover:bg-slate-800 px-2.5 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700/80 text-xs text-slate-600 dark:text-slate-300 min-w-0 max-w-[280px] lg:max-w-[360px] flex-shrink transition-colors"
              title={`Loaded dataset: ${summary.filename} (${summary.total_records.toLocaleString()} rows)`}
            >
              <Radio className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400 flex-shrink-0" />
              <span className="font-medium text-slate-800 dark:text-slate-200 truncate min-w-0">
                {summary.filename}
              </span>
              <span className="text-slate-400 dark:text-slate-600 flex-shrink-0">|</span>
              <span className="text-cyan-700 dark:text-cyan-400 font-mono flex-shrink-0 font-medium">
                {summary.total_records.toLocaleString()}
              </span>
              {summary.has_gps ? (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-emerald-100 text-emerald-800 border border-emerald-300 dark:bg-emerald-950/80 dark:text-emerald-400 dark:border-emerald-800/80 flex-shrink-0">
                  GPS Active
                </span>
              ) : (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-amber-100 text-amber-800 border border-amber-300 dark:bg-amber-950/80 dark:text-amber-400 dark:border-amber-800/80 flex-shrink-0">
                  No GPS
                </span>
              )}
            </div>
          )}

          {/* 3. Navigation Tabs & Theme Toggle */}
          <div className="flex items-center gap-1 sm:gap-2 flex-shrink-0">
            <nav className="flex items-center gap-1 sm:gap-1.5 flex-shrink-0" aria-label="Main Navigation">
              {tabs.map((tab) => {
                const Icon = tab.icon;
                const isActive = currentTab === tab.id;
                return (
                  <button
                    key={tab.id}
                    onClick={() => setCurrentTab(tab.id)}
                    title={tab.fullLabel}
                    className={`flex items-center gap-1.5 px-2.5 lg:px-3 py-1.5 rounded-lg text-xs lg:text-sm font-medium transition-all whitespace-nowrap ${
                      isActive
                        ? 'bg-cyan-600 text-white shadow-sm shadow-cyan-500/30'
                        : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100 dark:text-slate-300 dark:hover:text-white dark:hover:bg-slate-800/80'
                    }`}
                  >
                    <Icon className="w-4 h-4 flex-shrink-0" />
                    <span className="hidden sm:inline 2xl:hidden">{tab.shortLabel}</span>
                    <span className="hidden 2xl:inline">{tab.fullLabel}</span>
                  </button>
                );
              })}
            </nav>

            {/* Global Theme Toggle Button */}
            <button
              onClick={toggleTheme}
              title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
              aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
              className="p-2 rounded-lg border transition-all text-slate-600 hover:text-slate-900 bg-slate-100 hover:bg-slate-200/80 border-slate-200 dark:bg-slate-800 dark:hover:bg-slate-700 dark:text-slate-300 dark:hover:text-white dark:border-slate-700 shadow-sm"
            >
              {theme === 'dark' ? (
                <Sun className="w-4 h-4 text-amber-400" />
              ) : (
                <Moon className="w-4 h-4 text-slate-700" />
              )}
            </button>
          </div>

        </div>
      </div>
    </header>
  );
};
