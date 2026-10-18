import React from 'react';
import { Radio, Map, BarChart3, Table, UploadCloud, Signal, AlertTriangle, ShieldCheck, FileDown } from 'lucide-react';
import type { DatasetSummary } from '../types/cellscope';

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
  const tabs = [
    { id: 'map', label: 'Coverage Map', icon: Map },
    { id: 'analytics', label: 'RF Analytics', icon: BarChart3 },
    { id: 'events', label: 'Events & Anomalies', icon: AlertTriangle },
    { id: 'quality', label: 'Data Quality', icon: ShieldCheck },
    { id: 'table', label: 'Data Table', icon: Table },
    { id: 'report', label: 'Reports & Export', icon: FileDown },
    { id: 'import', label: 'Import / Presets', icon: UploadCloud },
  ];


  return (
    <header className="bg-slate-900 border-b border-slate-800 sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          {/* Logo & Title */}
          <div className="flex items-center gap-3">
            <div className="bg-cyan-500/10 p-2 rounded-lg border border-cyan-500/30 text-cyan-400">
              <Signal className="w-6 h-6 animate-pulse" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-bold text-xl tracking-tight text-white">CellScope</span>
                <span className="px-1.5 py-0.5 text-xs font-semibold uppercase tracking-wider bg-cyan-950 text-cyan-400 border border-cyan-800 rounded">
                  LTE RAN MVP
                </span>
              </div>
              <p className="text-xs text-slate-400 hidden sm:block">
                Mobile-First Drive-Test Analytics & RF Diagnostics
              </p>
            </div>
          </div>

          {/* Active Dataset Badge */}
          {summary && (
            <div className="hidden md:flex items-center gap-2 bg-slate-800/80 px-3 py-1.5 rounded-md border border-slate-700 text-xs text-slate-300">
              <Radio className="w-3.5 h-3.5 text-cyan-400" />
              <span className="font-medium text-slate-200 truncate max-w-xs">{summary.filename}</span>
              <span className="text-slate-500">|</span>
              <span className="text-cyan-400 font-mono">{summary.total_records.toLocaleString()}</span> records
              {summary.has_gps ? (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-emerald-950 text-emerald-400 border border-emerald-800">
                  GPS Active
                </span>
              ) : (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-amber-950 text-amber-400 border border-amber-800">
                  No GPS
                </span>
              )}
            </div>
          )}

          {/* Navigation Tabs */}
          <nav className="flex items-center gap-1 sm:gap-2">
            {tabs.map((tab) => {
              const Icon = tab.icon;
              const isActive = currentTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setCurrentTab(tab.id)}
                  className={`flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-cyan-600 text-white shadow-sm shadow-cyan-500/20'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800'
                  }`}
                >
                  <Icon className="w-4 h-4" />
                  <span className="hidden sm:inline">{tab.label}</span>
                </button>
              );
            })}
          </nav>
        </div>
      </div>
    </header>
  );
};
