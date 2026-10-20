import React from 'react';
import type { FilterState, MeasurementPoint } from '../types/cellscope';
import { resolveEarfcn } from '../utils/telecom';
import { Filter, X, RotateCcw, Radio, Activity, Zap, AlertTriangle, Layers, Building2 } from 'lucide-react';

interface FilterDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  filters: FilterState;
  setFilters: React.Dispatch<React.SetStateAction<FilterState>>;
  measurements: MeasurementPoint[];
  totalPointsCount: number;
  filteredPointsCount: number;
}

export const FilterDrawer: React.FC<FilterDrawerProps> = ({
  isOpen,
  onClose,
  filters,
  setFilters,
  measurements,
  totalPointsCount,
  filteredPointsCount,
}) => {
  if (!isOpen) return null;

  // Extract unique values for filter options from raw measurements
  const uniquePcis = Array.from(
    new Set(measurements.map((m) => m.serving_cell_id).filter((c): c is number => c !== null))
  ).sort((a, b) => a - b);

  const uniqueOperators = Array.from(
    new Set(measurements.map((m) => m.operator).filter((op): op is string => Boolean(op && op.trim())))
  ).sort();

  const uniqueEarfcns = Array.from(
    new Set(measurements.map((m) => m.earfcn).filter((ch): ch is number => ch !== null && ch !== undefined && ch > 0))
  ).sort((a, b) => a - b);

  // Active filter count
  const activeCount = [
    filters.servingCellId !== null,
    filters.networkGen !== null,
    filters.operator !== null,
    filters.bandEarfcn !== null,
    filters.rsrpClass !== null,
    filters.rsrqClass !== null,
    filters.minCinr !== null,
    filters.handoversOnly,
    filters.weakZonesOnly,
  ].filter(Boolean).length;

  const handleReset = () => {
    setFilters({
      servingCellId: null,
      networkGen: null,
      operator: null,
      bandEarfcn: null,
      rsrpClass: null,
      rsrqClass: null,
      minCinr: null,
      handoversOnly: false,
      weakZonesOnly: false,
    });
  };

  const pct = totalPointsCount > 0 ? Math.round((filteredPointsCount / totalPointsCount) * 100) : 0;

  return (
    <div className="fixed inset-0 z-[2000] flex justify-end bg-slate-950/60 backdrop-blur-sm animate-fade-in">
      <div className="w-full max-w-md h-full bg-slate-900 border-l border-slate-800 shadow-2xl flex flex-col overflow-hidden">
        {/* Header */}
        <div className="p-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/90">
          <div className="flex items-center gap-2">
            <Filter className="w-5 h-5 text-cyan-400" />
            <h2 className="text-base font-bold text-white">Telecom Filters</h2>
            {activeCount > 0 && (
              <span className="px-2 py-0.5 text-xs font-semibold rounded-full bg-cyan-950 text-cyan-400 border border-cyan-800">
                {activeCount} active
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            {activeCount > 0 && (
              <button
                onClick={handleReset}
                className="text-xs text-slate-400 hover:text-cyan-400 flex items-center gap-1 px-2 py-1 rounded bg-slate-800/60 hover:bg-slate-800 transition-colors"
                title="Reset all filters"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                <span>Reset</span>
              </button>
            )}
            <button
              onClick={onClose}
              className="p-1 text-slate-400 hover:text-white rounded-lg hover:bg-slate-800 transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Filter Summary Metric Bar */}
        <div className="px-4 py-2.5 bg-slate-950 border-b border-slate-800 flex items-center justify-between text-xs font-mono">
          <div className="text-slate-400">
            Visible Samples: <span className="text-cyan-400 font-bold">{filteredPointsCount.toLocaleString()}</span> / {totalPointsCount.toLocaleString()}
          </div>
          <div className="text-slate-400">
            Pass Rate: <span className="text-emerald-400 font-bold">{pct}%</span>
          </div>
        </div>

        {/* Filter Controls Scroll Area */}
        <div className="flex-1 overflow-y-auto p-4 space-y-5 text-xs">
          {/* Quick Filter Toggles */}
          <div className="space-y-2">
            <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] block">
              Quick Focus Toggles
            </label>
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => setFilters((prev) => ({ ...prev, handoversOnly: !prev.handoversOnly }))}
                className={`p-2.5 rounded-lg border text-left flex items-center gap-2 transition-all ${
                  filters.handoversOnly
                    ? 'bg-rose-950/80 border-rose-600 text-rose-300 font-semibold shadow-sm'
                    : 'bg-slate-800/40 border-slate-700/60 text-slate-400 hover:border-slate-600'
                }`}
              >
                <Zap className={`w-4 h-4 ${filters.handoversOnly ? 'text-rose-400' : 'text-slate-500'}`} />
                <div>
                  <div className="text-[11px] leading-tight">Handovers Only</div>
                  <div className="text-[10px] text-slate-500">Only HO trigger events</div>
                </div>
              </button>

              <button
                onClick={() => setFilters((prev) => ({ ...prev, weakZonesOnly: !prev.weakZonesOnly }))}
                className={`p-2.5 rounded-lg border text-left flex items-center gap-2 transition-all ${
                  filters.weakZonesOnly
                    ? 'bg-amber-950/80 border-amber-600 text-amber-300 font-semibold shadow-sm'
                    : 'bg-slate-800/40 border-slate-700/60 text-slate-400 hover:border-slate-600'
                }`}
              >
                <AlertTriangle className={`w-4 h-4 ${filters.weakZonesOnly ? 'text-amber-400' : 'text-slate-500'}`} />
                <div>
                  <div className="text-[11px] leading-tight">Weak Coverage</div>
                  <div className="text-[10px] text-slate-500">RSRP &lt; -100 dBm</div>
                </div>
              </button>
            </div>
          </div>

          {/* Serving Cell PCI */}
          <div className="space-y-1.5">
            <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] flex items-center gap-1.5">
              <Radio className="w-3.5 h-3.5 text-cyan-400" /> Serving PCI
            </label>
            <select
              value={filters.servingCellId ?? ''}
              onChange={(e) =>
                setFilters((prev) => ({
                  ...prev,
                  servingCellId: e.target.value ? Number(e.target.value) : null,
                }))
              }
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-cyan-500 font-mono"
            >
              <option value="">All Serving Cells ({uniquePcis.length} PCIs)</option>
              {uniquePcis.map((pci) => (
                <option key={pci} value={pci}>
                  PCI {pci}
                </option>
              ))}
            </select>
          </div>

          {/* Network Generation (RAT) */}
          <div className="space-y-1.5">
            <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] flex items-center gap-1.5">
              <Layers className="w-3.5 h-3.5 text-cyan-400" /> RAT Generation
            </label>
            <div className="flex flex-wrap gap-1.5">
              <button
                onClick={() => setFilters((prev) => ({ ...prev, networkGen: null }))}
                className={`px-2.5 py-1 rounded text-xs transition-all ${
                  filters.networkGen === null
                    ? 'bg-cyan-600 text-white font-bold'
                    : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                }`}
              >
                All RATs
              </button>
              {['5G NR', '4G LTE', '3G UMTS', '2G GSM'].map((gen) => {
                const isSelected = filters.networkGen === gen;
                return (
                  <button
                    key={gen}
                    onClick={() =>
                      setFilters((prev) => ({
                        ...prev,
                        networkGen: isSelected ? null : gen,
                      }))
                    }
                    className={`px-2.5 py-1 rounded text-xs transition-all ${
                      isSelected
                        ? 'bg-cyan-600 text-white font-bold'
                        : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                    }`}
                  >
                    {gen}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Carrier Band / EARFCN */}
          <div className="space-y-1.5">
            <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] flex items-center gap-1.5">
              <Activity className="w-3.5 h-3.5 text-cyan-400" /> Carrier Frequency / EARFCN
            </label>
            <select
              value={filters.bandEarfcn ?? ''}
              onChange={(e) =>
                setFilters((prev) => ({
                  ...prev,
                  bandEarfcn: e.target.value ? Number(e.target.value) : null,
                }))
              }
              className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-cyan-500 font-mono"
            >
              <option value="">All Channels / Bands ({uniqueEarfcns.length} Channels)</option>
              {uniqueEarfcns.map((earfcn) => {
                const res = resolveEarfcn(earfcn);
                const label = res
                  ? `EARFCN ${earfcn} • Band ${res.bandNumber} (${res.dlFrequencyMhz} MHz)`
                  : `EARFCN ${earfcn}`;
                return (
                  <option key={earfcn} value={earfcn}>
                    {label}
                  </option>
                );
              })}
            </select>
          </div>

          {/* Mobile Operator */}
          {uniqueOperators.length > 0 && (
            <div className="space-y-1.5">
              <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] flex items-center gap-1.5">
                <Building2 className="w-3.5 h-3.5 text-cyan-400" /> Network Operator
              </label>
              <select
                value={filters.operator ?? ''}
                onChange={(e) =>
                  setFilters((prev) => ({
                    ...prev,
                    operator: e.target.value || null,
                  }))
                }
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-slate-200 focus:outline-none focus:ring-1 focus:ring-cyan-500"
              >
                <option value="">All Operators ({uniqueOperators.length})</option>
                {uniqueOperators.map((op) => (
                  <option key={op} value={op}>
                    {op}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* RSRP Coverage Quality Class */}
          <div className="space-y-1.5">
            <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] block">
              RSRP Coverage Classification
            </label>
            <div className="grid grid-cols-2 gap-1.5">
              {[
                { label: 'All RSRP', val: null },
                { label: 'Excellent (≥ -80)', val: 'Excellent', color: 'border-emerald-700 text-emerald-400' },
                { label: 'Good (-80 to -90)', val: 'Good', color: 'border-lime-700 text-lime-400' },
                { label: 'Fair (-90 to -100)', val: 'Fair', color: 'border-amber-700 text-amber-400' },
                { label: 'Poor (< -100)', val: 'Poor', color: 'border-rose-700 text-rose-400' },
              ].map((opt) => {
                const isSelected = filters.rsrpClass === opt.val;
                return (
                  <button
                    key={opt.label}
                    onClick={() =>
                      setFilters((prev) => ({
                        ...prev,
                        rsrpClass: isSelected ? null : opt.val,
                      }))
                    }
                    className={`px-2.5 py-1.5 rounded-lg border text-left transition-all text-[11px] ${
                      isSelected
                        ? 'bg-cyan-950 border-cyan-500 text-cyan-300 font-bold'
                        : `bg-slate-950/60 border-slate-800 text-slate-400 hover:border-slate-700`
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* CINR / SINR Threshold */}
          <div className="space-y-1.5">
            <label className="text-slate-400 font-semibold uppercase tracking-wider text-[11px] flex items-center justify-between">
              <span>Minimum CINR / SINR</span>
              <span className="font-mono text-cyan-400">
                {filters.minCinr !== null ? `≥ ${filters.minCinr} dB` : 'Any'}
              </span>
            </label>
            <div className="flex items-center gap-2">
              <input
                type="range"
                min="-10"
                max="25"
                step="1"
                value={filters.minCinr ?? -10}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  setFilters((prev) => ({
                    ...prev,
                    minCinr: val <= -10 ? null : val,
                  }));
                }}
                className="w-full accent-cyan-500 bg-slate-800 h-1.5 rounded-lg cursor-pointer"
              />
              {filters.minCinr !== null && (
                <button
                  onClick={() => setFilters((prev) => ({ ...prev, minCinr: null }))}
                  className="text-[10px] text-slate-400 hover:text-white px-1.5 py-0.5 rounded bg-slate-800"
                >
                  Clear
                </button>
              )}
            </div>
            <div className="flex justify-between text-[10px] text-slate-500 font-mono">
              <span>-10 dB (Edge)</span>
              <span>0 dB (Fair)</span>
              <span>+13 dB (Good)</span>
              <span>+25 dB (Exc)</span>
            </div>
          </div>
        </div>

        {/* Footer Actions */}
        <div className="p-4 border-t border-slate-800 bg-slate-900/90 flex items-center gap-3">
          <button
            onClick={handleReset}
            className="flex-1 py-2 text-xs font-semibold rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 hover:text-white transition-colors"
          >
            Clear All Filters
          </button>
          <button
            onClick={onClose}
            className="flex-1 py-2 text-xs font-semibold rounded-lg bg-cyan-600 text-white hover:bg-cyan-500 transition-colors shadow-sm"
          >
            Apply & View
          </button>
        </div>
      </div>
    </div>
  );
};
