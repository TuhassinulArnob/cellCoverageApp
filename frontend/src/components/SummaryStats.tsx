import React from 'react';
import type { DatasetSummary, DistributionStats } from '../types/cellscope';
import { KpiCard } from './KpiCard';
import {
  Signal,
  Activity,
  Radio,
  Zap,
  Gauge,
  AlertTriangle,
  CheckCircle2,
  FileSpreadsheet,
  Route,
  Target,
  ShieldCheck,
  Layers,
  Clock,
} from 'lucide-react';

interface SummaryStatsProps {
  summary: DatasetSummary | null;
}

export const SummaryStats: React.FC<SummaryStatsProps> = ({ summary }) => {
  if (!summary) return null;

  const renderDistributionBar = (title: string, dist: DistributionStats, total: number) => {
    if (total === 0) return null;
    const excPct = Math.round((dist.excellent / total) * 100);
    const goodPct = Math.round((dist.good / total) * 100);
    const fairPct = Math.round((dist.fair / total) * 100);
    const poorPct = Math.round((dist.poor / total) * 100);

    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm font-semibold text-slate-200">{title} Quality Distribution</span>
          <span className="text-xs text-slate-400 font-mono">{total.toLocaleString()} samples</span>
        </div>

        {/* Multi-segment Progress Bar */}
        <div className="w-full h-3 bg-slate-800 rounded-full overflow-hidden flex mb-3">
          <div
            style={{ width: `${excPct}%` }}
            className="bg-[#10b981] transition-all"
            title={`Excellent: ${dist.excellent} (${excPct}%)`}
          />
          <div
            style={{ width: `${goodPct}%` }}
            className="bg-[#84cc16] transition-all"
            title={`Good: ${dist.good} (${goodPct}%)`}
          />
          <div
            style={{ width: `${fairPct}%` }}
            className="bg-[#f59e0b] transition-all"
            title={`Fair: ${dist.fair} (${fairPct}%)`}
          />
          <div
            style={{ width: `${poorPct}%` }}
            className="bg-[#ef4444] transition-all"
            title={`Poor: ${dist.poor} (${poorPct}%)`}
          />
        </div>

        {/* Legend percentages */}
        <div className="grid grid-cols-4 gap-2 text-xs font-mono text-center">
          <div className="bg-slate-800/60 p-1.5 rounded border border-slate-700/50">
            <span className="text-[#10b981] block font-bold">{excPct}%</span>
            <span className="text-slate-400 text-[10px]">Excellent</span>
          </div>
          <div className="bg-slate-800/60 p-1.5 rounded border border-slate-700/50">
            <span className="text-[#84cc16] block font-bold">{goodPct}%</span>
            <span className="text-slate-400 text-[10px]">Good</span>
          </div>
          <div className="bg-slate-800/60 p-1.5 rounded border border-slate-700/50">
            <span className="text-[#f59e0b] block font-bold">{fairPct}%</span>
            <span className="text-slate-400 text-[10px]">Fair</span>
          </div>
          <div className="bg-slate-800/60 p-1.5 rounded border border-slate-700/50">
            <span className="text-[#ef4444] block font-bold">{poorPct}%</span>
            <span className="text-slate-400 text-[10px]">Poor</span>
          </div>
        </div>
      </div>
    );
  };

  const formatDuration = (sec: number | null | undefined) => {
    if (!sec || sec <= 0) return '—';
    const m = Math.floor(sec / 60);
    const s = Math.round(sec % 60);
    return `${m}m ${s}s`;
  };

  return (
    <div className="space-y-6">
      {/* Session KPIs Grid */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 xl:grid-cols-9 gap-3">
        <KpiCard
          title="Mean RSRP"
          value={summary.mean_rsrp_dbm}
          unit="dBm"
          icon={Signal}
          badge={{
            text: summary.mean_rsrp_dbm ? (summary.mean_rsrp_dbm >= -80 ? 'Exc' : summary.mean_rsrp_dbm >= -90 ? 'Good' : summary.mean_rsrp_dbm >= -100 ? 'Fair' : 'Poor') : 'N/A',
            type: summary.mean_rsrp_dbm ? (summary.mean_rsrp_dbm >= -80 ? 'excellent' : summary.mean_rsrp_dbm >= -90 ? 'good' : summary.mean_rsrp_dbm >= -100 ? 'fair' : 'poor') : 'neutral',
          }}
          subtitle={summary.min_rsrp_dbm ? `Min: ${summary.min_rsrp_dbm} dBm` : undefined}
        />
        <KpiCard
          title="Mean RSRQ"
          value={summary.mean_rsrq_db}
          unit="dB"
          icon={Activity}
          badge={{
            text: summary.mean_rsrq_db ? (summary.mean_rsrq_db >= -10 ? 'Exc' : summary.mean_rsrq_db >= -14 ? 'Good' : summary.mean_rsrq_db >= -17 ? 'Fair' : 'Poor') : 'N/A',
            type: summary.mean_rsrq_db ? (summary.mean_rsrq_db >= -10 ? 'excellent' : summary.mean_rsrq_db >= -14 ? 'good' : summary.mean_rsrq_db >= -17 ? 'fair' : 'poor') : 'neutral',
          }}
          subtitle={summary.min_rsrq_db ? `Min: ${summary.min_rsrq_db} dB` : undefined}
        />
        <KpiCard
          title="Mean CINR"
          value={summary.mean_cinr_db}
          unit="dB"
          icon={Gauge}
          badge={{
            text: summary.mean_cinr_db ? (summary.mean_cinr_db >= 20 ? 'Exc' : summary.mean_cinr_db >= 13 ? 'Good' : summary.mean_cinr_db >= 0 ? 'Fair' : 'Poor') : 'N/A',
            type: summary.mean_cinr_db ? (summary.mean_cinr_db >= 20 ? 'excellent' : summary.mean_cinr_db >= 13 ? 'good' : summary.mean_cinr_db >= 0 ? 'fair' : 'poor') : 'neutral',
          }}
          subtitle={summary.max_cinr_db ? `Max: ${summary.max_cinr_db} dB` : undefined}
        />
        <KpiCard
          title="Duration"
          value={formatDuration(summary.duration_sec)}
          unit=""
          icon={Clock}
          subtitle={summary.duration_sec ? `${Math.round(summary.duration_sec)} total sec` : 'Estimated'}
        />
        <KpiCard
          title="Route Distance"
          value={summary.total_distance_km !== null && summary.total_distance_km !== undefined ? summary.total_distance_km : (summary.has_gps ? '0.0' : 'N/A')}
          unit={summary.total_distance_km ? 'km' : ''}
          icon={Route}
          subtitle={summary.has_gps ? `${summary.records_with_gps} GPS fixes` : 'Non-GPS log'}
        />
        <KpiCard
          title="HO Triggers"
          value={summary.handover_trigger_count}
          unit={`(${summary.handover_trigger_rate_pct}%)`}
          icon={Zap}
          subtitle={`${summary.handover_trigger_count} handover events`}
        />
        <KpiCard
          title="Unique PCIs"
          value={summary.unique_serving_cells}
          unit="Cells"
          icon={Radio}
          subtitle={`+ ${summary.unique_neighbor_cells} neighbor PCIs`}
        />
        <KpiCard
          title="GPS Accuracy"
          value={summary.mean_accuracy_m ? `±${summary.mean_accuracy_m}` : '±5.0'}
          unit="m"
          icon={Target}
          subtitle={summary.records_invalid_gps > 0 ? `${summary.records_invalid_gps} invalid filtered` : '100% valid fixes'}
        />
        <KpiCard
          title="Completeness"
          value={summary.data_completeness_pct ?? 100}
          unit="%"
          icon={ShieldCheck}
          badge={{
            text: (summary.data_completeness_pct ?? 100) >= 95 ? 'High' : 'Moderate',
            type: (summary.data_completeness_pct ?? 100) >= 95 ? 'excellent' : 'fair'
          }}
          subtitle={`${summary.total_records.toLocaleString()} total rows`}
        />
      </div>

      {/* Network Generation Distribution */}
      {summary.network_gen_distribution && Object.keys(summary.network_gen_distribution).length > 0 && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
          <div className="flex items-center justify-between mb-3">
            <span className="text-sm font-semibold text-slate-200 flex items-center gap-2">
              <Layers className="w-4 h-4 text-cyan-400" /> Network Generation Distribution
            </span>
            <span className="text-xs text-slate-400 font-mono">Multi-RAT Coverage Audit</span>
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {Object.entries(summary.network_gen_distribution).map(([gen, count]) => {
              const pct = Math.round((count / summary.total_records) * 100);
              const color =
                gen.includes('5G') ? 'text-purple-400 border-purple-800/60 bg-purple-950/40' :
                gen.includes('4G') || gen.includes('LTE') ? 'text-sky-400 border-sky-800/60 bg-sky-950/40' :
                gen.includes('3G') ? 'text-amber-400 border-amber-800/60 bg-amber-950/40' :
                'text-rose-400 border-rose-800/60 bg-rose-950/40';
              return (
                <div key={gen} className={`p-3 rounded-lg border ${color}`}>
                  <div className="text-xs font-semibold">{gen}</div>
                  <div className="text-xl font-bold font-mono mt-1">{count} <span className="text-xs text-slate-400 font-normal">({pct}%)</span></div>
                  <div className="w-full bg-slate-800 h-1.5 rounded-full mt-2 overflow-hidden">
                    <div className="h-full bg-current" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Distribution Bars */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {renderDistributionBar('RSRP Coverage', summary.rsrp_distribution, summary.total_records)}
        {renderDistributionBar('RSRQ Quality', summary.rsrq_distribution, summary.total_records)}
        {renderDistributionBar('CINR / SINR Ratio', summary.cinr_distribution, summary.total_records)}
      </div>

      {/* Per-Cell Statistics Table */}
      {summary.per_cell_stats && summary.per_cell_stats.length > 0 && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Radio className="w-4 h-4 text-cyan-400" />
              <h4 className="font-semibold text-sm text-slate-200">Per-Cell Radio Performance Statistics ({summary.per_cell_stats.length} Serving PCIs)</h4>
            </div>
            <span className="text-xs text-slate-400 font-mono">Sorted by Sample Volume</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-800 text-slate-400 font-mono">
                  <th className="pb-2">Serving PCI</th>
                  <th className="pb-2">Generation</th>
                  <th className="pb-2">Channel (EARFCN)</th>
                  <th className="pb-2">TAC</th>
                  <th className="pb-2">Samples (%)</th>
                  <th className="pb-2">Mean RSRP</th>
                  <th className="pb-2">Min RSRP</th>
                  <th className="pb-2">Mean RSRQ</th>
                  <th className="pb-2">Mean CINR</th>
                  <th className="pb-2">HO Events</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60 text-slate-300 font-mono">
                {summary.per_cell_stats.map((cell) => (
                  <tr key={cell.pci} className="hover:bg-slate-800/40">
                    <td className="py-2.5 font-bold text-cyan-400">PCI {cell.pci}</td>
                    <td className="py-2.5">
                      <span className="px-2 py-0.5 rounded text-[10px] bg-sky-950 text-sky-400 border border-sky-800/60">
                        {cell.network_gen}
                      </span>
                    </td>
                    <td className="py-2.5 text-slate-400">{cell.earfcn ? `EARFCN ${cell.earfcn}` : 'Standard'}</td>
                    <td className="py-2.5 text-slate-400">{cell.tac ?? 'N/A'}</td>
                    <td className="py-2.5 font-semibold text-white">
                      {cell.sample_count} <span className="text-slate-500 font-normal">({cell.sample_pct}%)</span>
                    </td>
                    <td className="py-2.5 font-bold text-emerald-400">{cell.mean_rsrp_dbm ?? 'N/A'} dBm</td>
                    <td className="py-2.5 text-slate-400">{cell.min_rsrp_dbm ?? 'N/A'} dBm</td>
                    <td className="py-2.5 text-sky-300">{cell.mean_rsrq_db ?? 'N/A'} dB</td>
                    <td className="py-2.5 text-amber-300">{cell.mean_cinr_db ?? 'N/A'} dB</td>
                    <td className="py-2.5">
                      {cell.handover_count > 0 ? (
                        <span className="text-rose-400 font-bold">⚡ {cell.handover_count}</span>
                      ) : (
                        <span className="text-slate-500">0</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Warnings & Data Integrity */}
      {summary.warnings.length > 0 && (
        <div className="bg-amber-950/30 border border-amber-800/60 rounded-xl p-4">
          <div className="flex items-center gap-2 mb-2 text-amber-400 font-semibold text-sm">
            <AlertTriangle className="w-4 h-4" />
            <span>Data Ingestion & Integrity Notices ({summary.warnings.length})</span>
          </div>
          <ul className="list-disc list-inside space-y-1 text-xs text-amber-300/80 font-mono">
            {summary.warnings.map((w, idx) => (
              <li key={idx}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Schema Mapping Inspector */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center gap-2 mb-4">
          <FileSpreadsheet className="w-4 h-4 text-cyan-400" />
          <h4 className="font-semibold text-sm text-slate-200">Schema Auto-Detection & Column Mapping</h4>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-800 text-slate-400">
                <th className="pb-2 font-medium">Canonical Field</th>
                <th className="pb-2 font-medium">Detected CSV Column</th>
                <th className="pb-2 font-medium">Status</th>
                <th className="pb-2 font-medium">Measurement Standard</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800 text-slate-300 font-mono">
              {Object.entries(summary.detected_schema).map(([canon, raw]) => (
                <tr key={canon} className="hover:bg-slate-800/40">
                  <td className="py-2 text-cyan-400 font-bold">{canon}</td>
                  <td className="py-2 text-slate-300">"{raw}"</td>
                  <td className="py-2">
                    <span className="inline-flex items-center gap-1 text-[11px] text-emerald-400 bg-emerald-950/60 px-2 py-0.5 rounded border border-emerald-800/50">
                      <CheckCircle2 className="w-3 h-3" /> Mapped
                    </span>
                  </td>
                  <td className="py-2 text-slate-400 text-[11px]">
                    {canon === 'rsrp'
                      ? '3GPP TS 36.133 (IE -> dBm)'
                      : canon === 'rsrq'
                      ? '3GPP TS 36.133 (IE -> dB)'
                      : canon === 'cinr'
                      ? 'Physical CINR/SINR (dB)'
                      : canon === 'latitude' || canon === 'longitude'
                      ? 'WGS84 Coordinates'
                      : 'Normalized'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
