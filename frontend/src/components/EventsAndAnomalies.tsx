import React, { useState } from 'react';
import type { ExtendedDatasetAnalysis } from '../types/cellscope';
import {
  AlertTriangle,
  Zap,
  TrendingDown,
  ShieldAlert,
  ArrowRight,
  MapPin,
  CheckCircle,
} from 'lucide-react';

interface EventsAndAnomaliesProps {
  analysis: ExtendedDatasetAnalysis | null;
  onNavigateToMap?: (lat: number, lon: number) => void;
}

export const EventsAndAnomalies: React.FC<EventsAndAnomaliesProps> = ({
  analysis,
  onNavigateToMap,
}) => {
  const [filterType, setFilterType] = useState<string>('all');

  if (!analysis) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-8 text-center text-slate-400">
        Loading telecom events & anomaly diagnostics...
      </div>
    );
  }

  const { transitions, anomalies, service_holes, weak_segments } = analysis;

  // Approximate distance in meters between two lat/lon coordinates
  const calculateDistanceMeters = (
    lat1: number | null,
    lon1: number | null,
    lat2: number | null,
    lon2: number | null
  ): number | null => {
    if (lat1 === null || lon1 === null || lat2 === null || lon2 === null) return null;
    const dLat = (lat2 - lat1) * 111320;
    const dLon = (lon2 - lon1) * (111320 * Math.cos(((lat1 + lat2) / 2) * (Math.PI / 180)));
    return Math.round(Math.sqrt(dLat * dLat + dLon * dLon));
  };

  const filteredAnomalies =
    filterType === 'all'
      ? anomalies
      : anomalies.filter((a) => a.severity.toLowerCase() === filterType.toLowerCase());

  return (
    <div className="space-y-6">
      {/* Overview Stat Badges */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Cell Transitions</span>
            <span className="text-2xl font-bold font-mono text-cyan-400">{transitions.length}</span>
            <span className="text-[10px] text-slate-500">
              {transitions.filter((t) => t.is_handover_trigger).length} active HO triggers
            </span>
          </div>
          <Zap className="w-6 h-6 text-cyan-400 opacity-60" />
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Detected Anomalies</span>
            <span className="text-2xl font-bold font-mono text-amber-400">{anomalies.length}</span>
            <span className="text-[10px] text-slate-500">Pilot pollution / interference</span>
          </div>
          <AlertTriangle className="w-6 h-6 text-amber-400 opacity-60" />
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Service Holes (&lt; -115 dBm)</span>
            <span className="text-2xl font-bold font-mono text-rose-400">{service_holes.length}</span>
            <span className="text-[10px] text-slate-500">Critical coverage loss</span>
          </div>
          <ShieldAlert className="w-6 h-6 text-rose-400 opacity-60" />
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-400 block">Weak Coverage Zones</span>
            <span className="text-2xl font-bold font-mono text-orange-400">{weak_segments.length}</span>
            <span className="text-[10px] text-slate-500">RSRP &lt; -105 dBm corridors</span>
          </div>
          <TrendingDown className="w-6 h-6 text-orange-400 opacity-60" />
        </div>
      </div>

      {/* Weakest Coverage Zones / Corridors Section (P3 Parity) */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <TrendingDown className="w-5 h-5 text-orange-400" />
            <div>
              <h3 className="font-semibold text-base text-slate-100">
                Weak Coverage Zones & Coverage Hole Corridors
              </h3>
              <p className="text-xs text-slate-400">
                Contiguous drive segments with RSRP &lt; -105 dBm requiring RF optimization
              </p>
            </div>
          </div>
          <span className="text-xs text-slate-400 font-mono">{weak_segments.length} contiguous corridors</span>
        </div>

        {weak_segments.length === 0 ? (
          <div className="bg-emerald-950/20 border border-emerald-800/40 rounded-lg p-4 text-xs text-emerald-400 flex items-center gap-2">
            <CheckCircle className="w-4 h-4 flex-shrink-0" />
            <span>✓ No contiguous weak coverage segments identified along this survey route.</span>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
            {weak_segments.map((w) => {
              const dist = calculateDistanceMeters(w.start_lat, w.start_lon, w.end_lat, w.end_lon);
              const estDurationSec = Math.max(1, w.sample_count); // 1 sample ≈ 1 sec in 1Hz surveys

              return (
                <div
                  key={w.id}
                  className="bg-slate-950/70 border border-orange-900/40 hover:border-orange-700/60 transition-colors p-4 rounded-xl text-xs space-y-3"
                >
                  <div className="flex items-center justify-between pb-2 border-b border-slate-800/80">
                    <div className="flex items-center gap-2">
                      <span className="w-2.5 h-2.5 rounded-full bg-orange-500 inline-block animate-pulse" />
                      <span className="font-bold text-orange-400 text-sm">Weak Zone #{w.id}</span>
                      <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-orange-950/60 text-orange-300 border border-orange-800/50">
                        {w.sample_count} Samples (~{estDurationSec}s)
                      </span>
                    </div>
                    <span className="text-[10px] text-slate-400 font-mono">
                      Rows #{w.start_index}–#{w.end_index}
                    </span>
                  </div>

                  <div className="grid grid-cols-2 gap-2 text-slate-300 font-mono text-[11px]">
                    <div className="bg-slate-900/80 p-2 rounded border border-slate-800">
                      <span className="text-slate-500 text-[10px] block">MIN RSRP (DEEPEST FADE)</span>
                      <strong className="text-rose-400 text-sm">{w.min_rsrp_dbm} dBm</strong>
                    </div>
                    <div className="bg-slate-900/80 p-2 rounded border border-slate-800">
                      <span className="text-slate-500 text-[10px] block">MEAN RSRP</span>
                      <strong className="text-amber-300 text-sm">{w.mean_rsrp_dbm} dBm</strong>
                    </div>
                    <div>
                      <span className="text-slate-500 text-[10px] block">SERVING PCI</span>
                      <span className="text-cyan-400 font-bold">PCI {w.pci ?? 'Unresolved'}</span>
                    </div>
                    <div>
                      <span className="text-slate-500 text-[10px] block">ESTIMATED SPAN</span>
                      <span className="text-slate-200">{dist !== null ? `${dist} meters` : 'Stationary'}</span>
                    </div>
                  </div>

                  <div className="text-[11px] text-slate-300 bg-slate-900/60 p-2.5 rounded border border-slate-800">
                    <span className="text-slate-500 text-[10px] block font-semibold mb-0.5">CANDIDATE ROOT CAUSE</span>
                    <p className="text-slate-300 font-sans leading-tight">{w.reason}</p>
                  </div>

                  {w.start_lat && w.start_lon && (
                    <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between text-[10px] text-slate-400 font-mono">
                      <div className="flex items-center gap-1">
                        <MapPin className="w-3 h-3 text-cyan-400" />
                        <span>
                          {w.start_lat.toFixed(4)}, {w.start_lon.toFixed(4)}
                        </span>
                        {w.end_lat && (
                          <>
                            <ArrowRight className="w-2.5 h-2.5 mx-0.5 text-slate-500" />
                            <span>
                              {w.end_lat.toFixed(4)}, {w.end_lon?.toFixed(4)}
                            </span>
                          </>
                        )}
                      </div>
                      {onNavigateToMap && (
                        <button
                          onClick={() => onNavigateToMap(w.start_lat!, w.start_lon!)}
                          className="text-cyan-400 hover:text-white underline font-sans text-[11px]"
                        >
                          View on Map →
                        </button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Cell Transitions & Handover Log (P4 Parity) */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Zap className="w-5 h-5 text-cyan-400" />
            <div>
              <h3 className="font-semibold text-base text-slate-100">Cell Transitions & Handover Intelligence</h3>
              <p className="text-xs text-slate-400">
                Mobility tracking: Handover triggers, cell reselection, and serving transitions
              </p>
            </div>
          </div>
          <span className="text-xs text-slate-400 font-mono">{transitions.length} total events</span>
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
                  <th className="pb-2">Serving Transition</th>
                  <th className="pb-2">Mobility Type</th>
                  <th className="pb-2">Classification</th>
                  <th className="pb-2">GPS Location</th>
                  <th className="pb-2">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60 text-slate-300 font-mono">
                {transitions.map((t) => {
                  const isHandover = t.is_handover_trigger;
                  const isIntraCell = t.from_pci === t.to_pci;

                  return (
                    <tr key={t.id} className="hover:bg-slate-800/40">
                      <td className="py-2.5 text-slate-400 font-bold">#{t.index}</td>
                      <td className="py-2.5 text-slate-300">{t.timestamp_raw}</td>
                      <td className="py-2.5 font-bold">
                        <span className="text-slate-400">PCI {t.from_pci ?? '?'}</span>
                        <span className="text-cyan-400 mx-1.5">→</span>
                        <span className="text-cyan-400 font-bold">PCI {t.to_pci ?? '?'}</span>
                      </td>
                      <td className="py-2.5">
                        {isHandover ? (
                          <span className="inline-flex items-center gap-1 text-[10px] text-rose-300 bg-rose-950/80 px-2 py-0.5 rounded border border-rose-800/60 font-semibold">
                            ⚡ Handover Trigger
                          </span>
                        ) : (
                          <span className="text-[10px] text-slate-400 bg-slate-800 px-2 py-0.5 rounded">
                            Serving Transition
                          </span>
                        )}
                      </td>
                      <td className="py-2.5">
                        <span className={`text-[10px] px-1.5 py-0.5 rounded ${
                          isIntraCell
                            ? 'bg-slate-800 text-slate-400'
                            : 'bg-sky-950 text-sky-400 border border-sky-800/50'
                        }`}>
                          {isIntraCell ? 'Intra-PCI Event' : 'Inter-PCI Handover'}
                        </span>
                      </td>
                      <td className="py-2.5 text-slate-400 text-[11px]">
                        {t.latitude ? `${t.latitude.toFixed(4)}, ${t.longitude?.toFixed(4)}` : 'N/A'}
                      </td>
                      <td className="py-2.5 text-slate-400 text-[11px] truncate max-w-xs">{t.details}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Explainable Anomalies Section */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <AlertTriangle className="w-5 h-5 text-amber-400" />
            <div>
              <h3 className="font-semibold text-base text-slate-100">
                Explainable Network Anomalies & Interference Diagnostics
              </h3>
              <p className="text-xs text-slate-400">
                Rule-based physical layer diagnosis: Pilot pollution, rapid fading, and uplink interference
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <select
              value={filterType}
              onChange={(e) => setFilterType(e.target.value)}
              className="bg-slate-950 border border-slate-700 text-xs text-slate-300 rounded px-2 py-1"
            >
              <option value="all">All Severities ({anomalies.length})</option>
              <option value="high">High Severity Only</option>
              <option value="medium">Medium Severity</option>
            </select>
          </div>
        </div>

        {filteredAnomalies.length === 0 ? (
          <p className="text-xs text-emerald-400 font-mono py-2">
            ✓ No severe RF anomalies detected for the selected filter.
          </p>
        ) : (
          <div className="space-y-3">
            {filteredAnomalies.map((a) => (
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
    </div>
  );
};
