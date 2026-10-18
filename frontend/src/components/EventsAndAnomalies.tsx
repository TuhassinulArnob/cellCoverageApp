import React from 'react';
import type { ExtendedDatasetAnalysis } from '../types/cellscope';
import {
  AlertTriangle,
  Zap,
  TrendingDown,
  ShieldAlert,
} from 'lucide-react';

interface EventsAndAnomaliesProps {
  analysis: ExtendedDatasetAnalysis | null;
}

export const EventsAndAnomalies: React.FC<EventsAndAnomaliesProps> = ({ analysis }) => {
  if (!analysis) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-8 text-center text-slate-400">
        Loading telecom events & anomaly diagnostics...
      </div>
    );
  }

  const { transitions, anomalies, service_holes, weak_segments } = analysis;

  return (
    <div className="space-y-6">
      {/* Overview Stat Badges */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Cell Transitions</span>
            <span className="text-2xl font-bold font-mono text-cyan-400">{transitions.length}</span>
          </div>
          <Zap className="w-6 h-6 text-cyan-400 opacity-60" />
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Detected Anomalies</span>
            <span className="text-2xl font-bold font-mono text-amber-400">{anomalies.length}</span>
          </div>
          <AlertTriangle className="w-6 h-6 text-amber-400 opacity-60" />
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Service Holes (&lt; -115 dBm)</span>
            <span className="text-2xl font-bold font-mono text-rose-400">{service_holes.length}</span>
          </div>
          <ShieldAlert className="w-6 h-6 text-rose-400 opacity-60" />
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Weak Coverage Segments</span>
            <span className="text-2xl font-bold font-mono text-orange-400">{weak_segments.length}</span>
          </div>
          <TrendingDown className="w-6 h-6 text-orange-400 opacity-60" />
        </div>
      </div>

      {/* Explainable Anomalies Section */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <AlertTriangle className="w-5 h-5 text-amber-400" />
            <h3 className="font-semibold text-base text-slate-100">Explainable Network Anomalies & Interference</h3>
          </div>
          <span className="text-xs text-slate-400 font-mono">{anomalies.length} detected incidents</span>
        </div>

        {anomalies.length === 0 ? (
          <p className="text-xs text-emerald-400 font-mono py-2">✓ No severe RF anomalies detected along this drive route.</p>
        ) : (
          <div className="space-y-3">
            {anomalies.map((a) => (
              <div
                key={a.id}
                className="bg-slate-950/60 border border-slate-800 p-3.5 rounded-lg flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs"
              >
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span
                      className={`px-2 py-0.5 rounded font-bold uppercase text-[10px] ${
                        a.severity === 'HIGH'
                          ? 'bg-rose-950 text-rose-400 border border-rose-800/80'
                          : 'bg-amber-950 text-amber-400 border border-amber-800/80'
                      }`}
                    >
                      {a.anomaly_type.replace(/_/g, ' ')}
                    </span>
                    <span className="text-slate-400 font-mono">Row #{a.index} ({a.timestamp_raw})</span>
                    {a.pci && <span className="text-cyan-400 font-mono font-semibold">PCI {a.pci}</span>}
                  </div>
                  <p className="text-slate-300 font-sans">{a.explanation}</p>
                </div>
                <div className="flex items-center gap-3 font-mono text-[11px] text-slate-400 flex-shrink-0">
                  {a.rsrp_dbm !== null && <span>RSRP: <strong className="text-white">{a.rsrp_dbm} dBm</strong></span>}
                  {a.rsrq_db !== null && <span>RSRQ: <strong className="text-sky-300">{a.rsrq_db} dB</strong></span>}
                  {a.cinr_db !== null && <span>CINR: <strong className="text-amber-300">{a.cinr_db} dB</strong></span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Weakest Coverage Segments */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <TrendingDown className="w-5 h-5 text-orange-400" />
            <h3 className="font-semibold text-base text-slate-100">Weakest Coverage Segments (RSRP &lt; -105 dBm)</h3>
          </div>
          <span className="text-xs text-slate-400 font-mono">{weak_segments.length} contiguous segments</span>
        </div>

        {weak_segments.length === 0 ? (
          <p className="text-xs text-emerald-400 font-mono py-2">✓ No contiguous weak coverage segments identified.</p>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {weak_segments.map((w) => (
              <div key={w.id} className="bg-slate-950/60 border border-slate-800 p-3.5 rounded-lg text-xs space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-bold text-orange-400">Segment #{w.id} ({w.sample_count} samples)</span>
                  <span className="text-[10px] text-slate-400 font-mono">Rows {w.start_index}–{w.end_index}</span>
                </div>
                <div className="grid grid-cols-2 gap-2 text-slate-300 font-mono text-[11px]">
                  <div>Min RSRP: <strong className="text-rose-400">{w.min_rsrp_dbm} dBm</strong></div>
                  <div>Mean RSRP: <strong className="text-amber-300">{w.mean_rsrp_dbm} dBm</strong></div>
                  <div>Serving Cell: <span className="text-cyan-400">PCI {w.pci ?? 'N/A'}</span></div>
                  <div>Reason: <span className="text-slate-400">{w.reason}</span></div>
                </div>
                {w.start_lat && w.start_lon && (
                  <div className="text-[10px] text-slate-500 font-mono pt-1 border-t border-slate-800/80">
                    Coords: {w.start_lat.toFixed(5)}, {w.start_lon.toFixed(5)} → {w.end_lat?.toFixed(5)}, {w.end_lon?.toFixed(5)}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Cell Transitions & Handover Events Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Zap className="w-5 h-5 text-cyan-400" />
            <h3 className="font-semibold text-base text-slate-100">Cell Transitions & Handover Log</h3>
          </div>
          <span className="text-xs text-slate-400 font-mono">{transitions.length} total transitions</span>
        </div>

        {transitions.length === 0 ? (
          <p className="text-xs text-slate-400 font-mono py-2">No serving cell transitions observed in this dataset.</p>
        ) : (
          <div className="overflow-x-auto max-h-96">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 bg-slate-900 border-b border-slate-800 text-slate-400 font-mono">
                <tr>
                  <th className="pb-2">Row</th>
                  <th className="pb-2">Timestamp</th>
                  <th className="pb-2">Transition</th>
                  <th className="pb-2">Type</th>
                  <th className="pb-2">Coordinates</th>
                  <th className="pb-2">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60 text-slate-300 font-mono">
                {transitions.map((t) => (
                  <tr key={t.id} className="hover:bg-slate-800/40">
                    <td className="py-2 text-slate-400 font-bold">#{t.index}</td>
                    <td className="py-2 text-slate-300">{t.timestamp_raw}</td>
                    <td className="py-2 font-bold">
                      <span className="text-slate-400">PCI {t.from_pci ?? '?'}</span>
                      <span className="text-cyan-400 mx-1.5">→</span>
                      <span className="text-cyan-400 font-bold">PCI {t.to_pci ?? '?'}</span>
                    </td>
                    <td className="py-2">
                      {t.is_handover_trigger ? (
                        <span className="inline-flex items-center gap-1 text-[10px] text-rose-300 bg-rose-950/80 px-2 py-0.5 rounded border border-rose-800/60">
                          ⚡ HO Trigger
                        </span>
                      ) : (
                        <span className="text-[10px] text-slate-400 bg-slate-800 px-2 py-0.5 rounded">
                          Cell Change
                        </span>
                      )}
                    </td>
                    <td className="py-2 text-slate-400 text-[11px]">
                      {t.latitude ? `${t.latitude.toFixed(4)}, ${t.longitude?.toFixed(4)}` : 'N/A'}
                    </td>
                    <td className="py-2 text-slate-400 text-[11px] truncate max-w-xs">{t.details}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
