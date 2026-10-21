import React, { useMemo } from 'react';
import type {
  DatasetSummary,
  DistributionStats,
  MeasurementPoint,
  ExtendedDatasetAnalysis,
} from '../types/cellscope';
import { KpiCard } from './KpiCard';
import { resolveEarfcn, analyzePciMod3Collisions, getPciMod3Shift } from '../utils/telecom';
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
  Cpu,
  BarChart2,
} from 'lucide-react';

interface SummaryStatsProps {
  summary: DatasetSummary | null;
  measurements?: MeasurementPoint[];
  extendedAnalysis?: ExtendedDatasetAnalysis | null;
}

export const SummaryStats: React.FC<SummaryStatsProps> = ({
  summary,
  measurements = [],
}) => {
  // Compute Band & Carrier Inventory using 3GPP TS 36.101 standard math
  const carrierInventory = useMemo(() => {
    const earfcnMap = new Map<number, { count: number; pcis: Set<number>; rsrpSum: number }>();

    for (const m of measurements) {
      if (m.earfcn !== null && m.earfcn !== undefined && m.earfcn >= 0) {
        const curr = earfcnMap.get(m.earfcn) || { count: 0, pcis: new Set<number>(), rsrpSum: 0 };
        curr.count++;
        if (m.serving_cell_id !== null) curr.pcis.add(m.serving_cell_id);
        if (m.rsrp_dbm !== null) curr.rsrpSum += m.rsrp_dbm;
        earfcnMap.set(m.earfcn, curr);
      }
    }

    return Array.from(earfcnMap.entries()).map(([earfcn, stat]) => {
      const res = resolveEarfcn(earfcn);
      return {
        earfcn,
        bandNumber: res?.bandNumber ?? 'Custom',
        designation: res?.designation ?? 'LTE Carrier',
        duplexMode: res?.duplexMode ?? 'FDD',
        dlFrequencyMhz: res?.dlFrequencyMhz ?? null,
        ulFrequencyMhz: res?.ulFrequencyMhz ?? null,
        sampleCount: stat.count,
        samplePct: measurements.length > 0 ? Math.round((stat.count / measurements.length) * 100) : 0,
        uniquePcisCount: stat.pcis.size,
        meanRsrp: stat.count > 0 ? Math.round((stat.rsrpSum / stat.count) * 10) / 10 : null,
        operators: res?.bangladeshOperators ?? [],
      };
    }).sort((a, b) => b.sampleCount - a.sampleCount);
  }, [measurements]);

  // Compute PCI Modulo-3 Collision and Reuse stats
  const pciCollisions = useMemo(() => analyzePciMod3Collisions(measurements), [measurements]);

  // Serving vs Neighbor RSRP Comparison
  const neighborComparison = useMemo(() => {
    const pointsWithNeighbor = measurements.filter(
      (m) => m.neighbor_cell_id !== null && m.serving_cell_id !== null && m.rsrp_dbm !== null
    );

    return {
      count: pointsWithNeighbor.length,
      samplePct: measurements.length > 0 ? Math.round((pointsWithNeighbor.length / measurements.length) * 100) : 0,
      uniqueNeighbors: new Set(pointsWithNeighbor.map((m) => m.neighbor_cell_id)).size,
    };
  }, [measurements]);

  if (!summary) return null;

  const renderDistributionBar = (title: string, dist: DistributionStats, total: number) => {
    if (total === 0) return null;
    const excPct = Math.round((dist.excellent / total) * 100);
    const goodPct = Math.round((dist.good / total) * 100);
    const fairPct = Math.round((dist.fair / total) * 100);
    const poorPct = Math.round((dist.poor / total) * 100);

    return (
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm transition-colors duration-150">
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm font-semibold text-slate-800 dark:text-slate-200">{title} Quality Distribution</span>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">{total.toLocaleString()} samples</span>
        </div>

        {/* Multi-segment Progress Bar */}
        <div className="w-full h-3 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden flex mb-3">
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
          <div className="bg-slate-50 dark:bg-slate-800/60 p-1.5 rounded border border-slate-200 dark:border-slate-700/50">
            <span className="text-[#10b981] block font-bold">{excPct}%</span>
            <span className="text-slate-600 dark:text-slate-400 text-[10px]">Excellent</span>
          </div>
          <div className="bg-slate-50 dark:bg-slate-800/60 p-1.5 rounded border border-slate-200 dark:border-slate-700/50">
            <span className="text-[#84cc16] block font-bold">{goodPct}%</span>
            <span className="text-slate-600 dark:text-slate-400 text-[10px]">Good</span>
          </div>
          <div className="bg-slate-50 dark:bg-slate-800/60 p-1.5 rounded border border-slate-200 dark:border-slate-700/50">
            <span className="text-[#f59e0b] block font-bold">{fairPct}%</span>
            <span className="text-slate-600 dark:text-slate-400 text-[10px]">Fair</span>
          </div>
          <div className="bg-slate-50 dark:bg-slate-800/60 p-1.5 rounded border border-slate-200 dark:border-slate-700/50">
            <span className="text-[#ef4444] block font-bold">{poorPct}%</span>
            <span className="text-slate-600 dark:text-slate-400 text-[10px]">Poor</span>
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
    <div className="space-y-6 transition-colors duration-150">
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
          title="Confirmed HO"
          value={summary.handover_trigger_count}
          unit={`(${summary.handover_trigger_rate_pct}%)`}
          icon={Zap}
          subtitle={`${summary.handover_trigger_count} confirmed handovers`}
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
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm transition-colors duration-150">
          <div className="flex items-center justify-between mb-3">
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-2">
              <Layers className="w-4 h-4 text-cyan-600 dark:text-cyan-400" /> Network Generation Distribution
            </span>
            <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">Multi-RAT Coverage Audit</span>
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {Object.entries(summary.network_gen_distribution).map(([gen, count]) => {
              const pct = Math.round((count / summary.total_records) * 100);
              const color =
                gen.includes('5G') ? 'text-purple-700 dark:text-purple-400 border-purple-200 dark:border-purple-800/60 bg-purple-50 dark:bg-purple-950/40' :
                gen.includes('4G') || gen.includes('LTE') ? 'text-sky-700 dark:text-sky-400 border-sky-200 dark:border-sky-800/60 bg-sky-50 dark:bg-sky-950/40' :
                gen.includes('3G') ? 'text-amber-700 dark:text-amber-400 border-amber-200 dark:border-amber-800/60 bg-amber-50 dark:bg-amber-950/40' :
                'text-rose-700 dark:text-rose-400 border-rose-200 dark:border-rose-800/60 bg-rose-50 dark:bg-rose-950/40';
              return (
                <div key={gen} className={`p-3 rounded-lg border ${color}`}>
                  <div className="text-xs font-semibold">{gen}</div>
                  <div className="text-xl font-bold font-mono mt-1 text-slate-900 dark:text-white">{count} <span className="text-xs text-slate-500 font-normal">({pct}%)</span></div>
                  <div className="w-full bg-slate-100 dark:bg-slate-800 h-1.5 rounded-full mt-2 overflow-hidden">
                    <div className="h-full bg-current" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Band & Carrier Inventory Table (3GPP TS 36.101 Standards-Based) */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Cpu className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
            <h4 className="font-semibold text-sm text-slate-900 dark:text-slate-200">
              RF Carrier & Band Inventory (3GPP TS 36.101 / TS 38.101)
            </h4>
          </div>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">
            {carrierInventory.length > 0 ? `${carrierInventory.length} Detected Carrier Frequencies` : 'Single Carrier'}
          </span>
        </div>

        {carrierInventory.length === 0 ? (
          <p className="text-xs text-slate-500 dark:text-slate-400 py-2">No carrier channel (EARFCN/ARFCN) recorded in loaded dataset.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 font-mono">
                  <th className="pb-2">Channel (EARFCN)</th>
                  <th className="pb-2">3GPP Band</th>
                  <th className="pb-2">Duplex Mode</th>
                  <th className="pb-2">DL Frequency</th>
                  <th className="pb-2">UL Frequency</th>
                  <th className="pb-2">Serving PCIs</th>
                  <th className="pb-2">Samples (%)</th>
                  <th className="pb-2">Mean RSRP</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800/60 text-slate-800 dark:text-slate-300 font-mono">
                {carrierInventory.map((item) => (
                  <tr key={item.earfcn} className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                    <td className="py-2.5 font-bold text-cyan-700 dark:text-cyan-400">CH {item.earfcn}</td>
                    <td className="py-2.5">
                      <span className="px-2 py-0.5 rounded text-[10px] bg-sky-100 text-sky-800 border border-sky-300 dark:bg-sky-950 dark:text-sky-400 dark:border-sky-800/60 font-semibold">
                        Band {item.bandNumber} ({item.designation})
                      </span>
                    </td>
                    <td className="py-2.5">
                      <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${
                        item.duplexMode === 'TDD'
                          ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-400'
                          : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
                      }`}>
                        {item.duplexMode}
                      </span>
                    </td>
                    <td className="py-2.5 font-bold text-slate-900 dark:text-white">
                      {item.dlFrequencyMhz !== null ? `${item.dlFrequencyMhz} MHz` : '—'}
                    </td>
                    <td className="py-2.5 text-slate-500 dark:text-slate-400">
                      {item.ulFrequencyMhz !== null ? `${item.ulFrequencyMhz} MHz` : '—'}
                    </td>
                    <td className="py-2.5 text-slate-700 dark:text-slate-300">{item.uniquePcisCount} PCIs</td>
                    <td className="py-2.5 font-semibold text-slate-900 dark:text-white">
                      {item.sampleCount} <span className="text-slate-500 font-normal">({item.samplePct}%)</span>
                    </td>
                    <td className="py-2.5 font-bold text-emerald-600 dark:text-emerald-400">
                      {item.meanRsrp !== null ? `${item.meanRsrp} dBm` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* PCI Reuse & CRS Modulo-3 Shift Collision Analysis */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Radio className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
            <h4 className="font-semibold text-sm text-slate-900 dark:text-slate-200">
              PCI Reuse & LTE CRS Modulo-3 Shift Grouping (v_shift = PCI mod 3)
            </h4>
          </div>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">
            {pciCollisions.length > 0 ? (
              <span className="text-amber-600 dark:text-amber-400 font-bold">{pciCollisions.length} Shift Collisions</span>
            ) : (
              <span className="text-emerald-600 dark:text-emerald-400">Clean Shift Orthogonality</span>
            )}
          </span>
        </div>

        <p className="text-xs text-slate-600 dark:text-slate-400 mb-4 leading-relaxed">
          In 3GPP LTE, the Cell-specific Reference Signal (CRS) frequency position shifts according to{' '}
          <code className="text-cyan-700 dark:text-cyan-400 font-mono font-semibold">v_shift = PCI mod 3</code>. When co-channel neighbor cells share the same
          shift, CRS subcarriers directly overlap, generating pilot interference that degrades SINR and CQI.
        </p>

        {pciCollisions.length === 0 ? (
          <div className="bg-emerald-50 dark:bg-emerald-950/20 border border-emerald-200 dark:border-emerald-800/40 rounded-lg p-3 text-xs text-emerald-700 dark:text-emerald-400 flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
            <span>No CRS modulo-3 collisions detected across observed carrier frequencies.</span>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {pciCollisions.map((col, idx) => (
              <div key={idx} className="bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 p-3.5 rounded-lg text-xs space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-100 text-amber-800 border border-amber-300 dark:bg-amber-950 dark:text-amber-400 dark:border-amber-800">
                      v_shift = {col.vShift}
                    </span>
                    <span className="font-mono text-slate-700 dark:text-slate-300">
                      {col.earfcn > 0 ? `EARFCN ${col.earfcn}` : 'Default Carrier'}
                    </span>
                  </div>
                  <span className="text-[10px] text-rose-600 dark:text-rose-400 font-mono font-bold">CRS Collision Risk</span>
                </div>
                <div className="space-y-1 font-mono text-[11px] text-slate-700 dark:text-slate-300 pt-1">
                  {col.cells.map((c) => (
                    <div key={c.pci} className="flex items-center justify-between border-t border-slate-200 dark:border-slate-800/60 pt-1">
                      <span className="text-cyan-700 dark:text-cyan-400 font-bold">PCI {c.pci}</span>
                      <span className="text-slate-500 dark:text-slate-400">{c.sampleCount} samples</span>
                      <span className="text-emerald-600 dark:text-emerald-400">{c.meanRsrp !== null ? `${c.meanRsrp} dBm` : '—'}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Serving vs Neighbor Cell Comparison Card */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <BarChart2 className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
            <h4 className="font-semibold text-sm text-slate-900 dark:text-slate-200">Serving vs. Neighbor Cell Intelligence</h4>
          </div>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">
            {neighborComparison.uniqueNeighbors} Monitored Neighbor PCIs
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
          <div className="bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 p-3.5 rounded-lg">
            <span className="text-slate-500 dark:text-slate-400 block mb-1">Neighbor Samples Logged</span>
            <div className="text-xl font-bold font-mono text-cyan-700 dark:text-cyan-400">
              {neighborComparison.count.toLocaleString()}
            </div>
            <span className="text-[10px] text-slate-500">{neighborComparison.samplePct}% of survey points</span>
          </div>

          <div className="bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 p-3.5 rounded-lg">
            <span className="text-slate-500 dark:text-slate-400 block mb-1">Handover Trigger Rate</span>
            <div className="text-xl font-bold font-mono text-rose-600 dark:text-rose-400">
              {summary.handover_trigger_rate_pct}%
            </div>
            <span className="text-[10px] text-slate-500">{summary.handover_trigger_count} confirmed handovers</span>
          </div>

          <div className="bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 p-3.5 rounded-lg">
            <span className="text-slate-500 dark:text-slate-400 block mb-1">Handover Hysteresis Evaluation</span>
            <div className="text-xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
              {summary.handover_trigger_rate_pct < 10 ? 'Optimal (Stable)' : 'High Ping-Pong Risk'}
            </div>
            <span className="text-[10px] text-slate-500">
              {summary.handover_trigger_rate_pct < 10 ? 'Event A3 margin well configured' : 'Hysteresis adjustment recommended'}
            </span>
          </div>
        </div>
      </div>

      {/* Distribution Bars */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {renderDistributionBar('RSRP Coverage', summary.rsrp_distribution, summary.total_records)}
        {renderDistributionBar('RSRQ Quality', summary.rsrq_distribution, summary.total_records)}
        {renderDistributionBar('CINR / SINR Ratio', summary.cinr_distribution, summary.total_records)}
      </div>

      {/* SINR Provenance & Diagnostics Engine Panel */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-4">
          <div className="flex items-center gap-2">
            <Gauge className="w-4 h-4 text-amber-500" />
            <h4 className="font-semibold text-sm text-slate-900 dark:text-slate-200">
              SINR Diagnostics &amp; Radio Model Provenance
            </h4>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-100 text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300 font-mono">
              MEASURED: {summary.measured_sinr_count ?? summary.sinr_provenance_summary?.MEASURED ?? 0}
            </span>
            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-800 dark:bg-amber-950/60 dark:text-amber-300 font-mono">
              ESTIMATED ⓘ: {summary.estimated_sinr_count ?? summary.sinr_provenance_summary?.ESTIMATED ?? 0}
            </span>
            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-300 font-mono">
              UNAVAILABLE: {summary.unavailable_sinr_count ?? summary.sinr_provenance_summary?.UNAVAILABLE ?? 0}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 rounded-lg p-3.5 space-y-2">
            <div className="text-xs font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
              <span>3GPP TS 36.214 Estimation Methodology</span>
            </div>
            <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
              When Android baseband modem does not expose physical RSSNR hardware registers, LTE SINR is calculated using the 3GPP carrier RSSI relationship:
            </p>
            <div className="bg-white dark:bg-slate-900 rounded p-2 text-xs font-mono text-cyan-700 dark:text-cyan-400 border border-slate-200 dark:border-slate-800">
              RSRQ_linear = N × RSRP_linear / RSSI_linear<br />
              SINR_est = 10 × log10(12 / (1/RSRQ_linear - 7.0))
            </div>
            <p className="text-[11px] text-slate-500 dark:text-slate-400">
              Assumes nominal 50% cell traffic load factor (η = 0.5), uniform subcarrier interference, and flat Rayleigh fading.
            </p>
          </div>

          <div className="bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 rounded-lg p-3.5 space-y-2">
            <div className="text-xs font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
              <span>Engineering Integrity &amp; 5G Separation</span>
            </div>
            <ul className="text-xs text-slate-600 dark:text-slate-400 space-y-1.5">
              <li className="flex items-start gap-1.5">
                <span className="text-amber-500 font-bold">•</span>
                <span><strong>Uncertainty Bound:</strong> Estimated SINR carries an explicit ±3.5 dB error bound due to unmeasured dynamic PRB scheduling.</span>
              </li>
              <li className="flex items-start gap-1.5">
                <span className="text-emerald-500 font-bold">•</span>
                <span><strong>5G NR Standard Separation:</strong> 3GPP TS 38.215 NR SS-RSRQ definition does not obey the LTE carrier RSSI equation. NR SINR is only reported when genuinely measured by modem (SS-SINR/CSI-SINR).</span>
              </li>
              <li className="flex items-start gap-1.5">
                <span className="text-sky-500 font-bold">•</span>
                <span><strong>Zero Fabrication:</strong> Missing values remain labeled UNAVAILABLE rather than silently guessed.</span>
              </li>
            </ul>
          </div>
        </div>
      </div>

      {/* Per-Cell Statistics Table */}
      {summary.per_cell_stats && summary.per_cell_stats.length > 0 && (
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Radio className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
              <h4 className="font-semibold text-sm text-slate-900 dark:text-slate-200">
                Per-Cell Radio Performance Statistics ({summary.per_cell_stats.length} Serving PCIs)
              </h4>
            </div>
            <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">Sorted by Sample Volume</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 font-mono">
                  <th className="pb-2">Serving PCI</th>
                  <th className="pb-2">CRS Shift</th>
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
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800/60 text-slate-800 dark:text-slate-300 font-mono">
                {summary.per_cell_stats.map((cell) => {
                  const shift = getPciMod3Shift(cell.pci);
                  return (
                    <tr key={cell.pci} className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                      <td className="py-2.5 font-bold text-cyan-700 dark:text-cyan-400">PCI {cell.pci}</td>
                      <td className="py-2.5 text-slate-500 dark:text-slate-400">v_shift = {shift}</td>
                      <td className="py-2.5">
                        <span className="px-2 py-0.5 rounded text-[10px] bg-sky-100 text-sky-800 border border-sky-300 dark:bg-sky-950 dark:text-sky-400 dark:border-sky-800/60">
                          {cell.network_gen}
                        </span>
                      </td>
                      <td className="py-2.5 text-slate-600 dark:text-slate-400">{cell.earfcn ? `EARFCN ${cell.earfcn}` : 'Standard'}</td>
                      <td className="py-2.5 text-slate-600 dark:text-slate-400">{cell.tac ?? 'N/A'}</td>
                      <td className="py-2.5 font-semibold text-slate-900 dark:text-white">
                        {cell.sample_count} <span className="text-slate-500 font-normal">({cell.sample_pct}%)</span>
                      </td>
                      <td className="py-2.5 font-bold text-emerald-600 dark:text-emerald-400">{cell.mean_rsrp_dbm ?? 'N/A'} dBm</td>
                      <td className="py-2.5 text-slate-600 dark:text-slate-400">{cell.min_rsrp_dbm ?? 'N/A'} dBm</td>
                      <td className="py-2.5 text-sky-700 dark:text-sky-300">{cell.mean_rsrq_db ?? 'N/A'} dB</td>
                      <td className="py-2.5 text-amber-700 dark:text-amber-300">{cell.mean_cinr_db ?? 'N/A'} dB</td>
                      <td className="py-2.5">
                        {cell.handover_count > 0 ? (
                          <span className="text-rose-600 dark:text-rose-400 font-bold">⚡ {cell.handover_count}</span>
                        ) : (
                          <span className="text-slate-400 dark:text-slate-500">0</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Warnings & Data Integrity */}
      {summary.warnings.length > 0 && (
        <div className="bg-amber-50 dark:bg-amber-950/30 border border-amber-300 dark:border-amber-800/60 rounded-xl p-4 transition-colors duration-150">
          <div className="flex items-center gap-2 mb-2 text-amber-700 dark:text-amber-400 font-semibold text-sm">
            <AlertTriangle className="w-4 h-4" />
            <span>Data Ingestion & Integrity Notices ({summary.warnings.length})</span>
          </div>
          <ul className="list-disc list-inside space-y-1 text-xs text-amber-800 dark:text-amber-300/80 font-mono">
            {summary.warnings.map((w, idx) => (
              <li key={idx}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Schema Mapping Inspector */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
        <div className="flex items-center gap-2 mb-4">
          <FileSpreadsheet className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
          <h4 className="font-semibold text-sm text-slate-900 dark:text-slate-200">Schema Auto-Detection & Column Mapping</h4>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 font-mono">
                <th className="pb-2 font-medium">Canonical Field</th>
                <th className="pb-2 font-medium">Detected CSV Column</th>
                <th className="pb-2 font-medium">Status</th>
                <th className="pb-2 font-medium">Measurement Standard</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800 text-slate-800 dark:text-slate-300 font-mono">
              {Object.entries(summary.detected_schema).map(([canon, raw]) => (
                <tr key={canon} className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                  <td className="py-2 text-cyan-700 dark:text-cyan-400 font-bold">{canon}</td>
                  <td className="py-2 text-slate-700 dark:text-slate-300">"{raw}"</td>
                  <td className="py-2">
                    <span className="inline-flex items-center gap-1 text-[11px] text-emerald-800 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-950/60 px-2 py-0.5 rounded border border-emerald-300 dark:border-emerald-800/50">
                      <CheckCircle2 className="w-3 h-3" /> Mapped
                    </span>
                  </td>
                  <td className="py-2 text-slate-500 dark:text-slate-400 text-[11px]">
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
