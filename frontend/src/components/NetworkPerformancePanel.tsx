import React, { useState, useMemo } from 'react';
import type { QoePerformanceSummary, QoeTestRecord } from '../types/cellscope';
import {
  Gauge,
  ArrowDownCircle,
  ArrowUpCircle,
  Activity,
  Radio,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  MapPin,
  Download,
  Filter,
  Clock,
  Layers,
  HelpCircle,
} from 'lucide-react';

interface NetworkPerformancePanelProps {
  summary: QoePerformanceSummary | null | undefined;
  qoeTests: QoeTestRecord[];
  onSelectCoordinate?: (lat: number, lon: number) => void;
}

export const NetworkPerformancePanel: React.FC<NetworkPerformancePanelProps> = ({
  summary,
  qoeTests,
  onSelectCoordinate,
}) => {
  const [filterRat, setFilterRat] = useState<string>('all');
  const [filterOperator, setFilterOperator] = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');
  const [filterPci, setFilterPci] = useState<string>('all');

  // Unique filter values derived from genuine dataset records
  const uniqueRats = useMemo(() => {
    const s = new Set<string>();
    qoeTests.forEach((t) => {
      if (t.serving_rat) s.add(t.serving_rat);
    });
    return Array.from(s).sort();
  }, [qoeTests]);

  const uniqueOperators = useMemo(() => {
    const s = new Set<string>();
    qoeTests.forEach((t) => {
      if (t.operator) s.add(t.operator);
    });
    return Array.from(s).sort();
  }, [qoeTests]);

  const uniquePcis = useMemo(() => {
    const s = new Set<number>();
    qoeTests.forEach((t) => {
      if (t.serving_pci !== null && t.serving_pci !== undefined) s.add(t.serving_pci);
    });
    return Array.from(s).sort((a, b) => a - b);
  }, [qoeTests]);

  // Filtered test records
  const filteredTests = useMemo(() => {
    return qoeTests.filter((t) => {
      if (filterRat !== 'all' && t.serving_rat !== filterRat) return false;
      if (filterOperator !== 'all' && t.operator !== filterOperator) return false;
      if (filterStatus !== 'all' && t.test_status.toUpperCase() !== filterStatus.toUpperCase()) return false;
      if (filterPci !== 'all' && t.serving_pci?.toString() !== filterPci) return false;
      return true;
    });
  }, [qoeTests, filterRat, filterOperator, filterStatus, filterPci]);

  // Export filtered QoE records to CSV
  const handleExportCsv = () => {
    if (filteredTests.length === 0) return;
    const headers = [
      'id',
      'timestamp',
      'test_type',
      'test_status',
      'download_mbps',
      'upload_mbps',
      'ping_latency_ms',
      'jitter_ms',
      'packet_loss_pct',
      'latitude',
      'longitude',
      'serving_rat',
      'serving_pci',
      'serving_channel',
      'serving_rsrp_dbm',
      'operator',
      'endpoint_url',
      'data_provenance',
    ];

    const rows = filteredTests.map((t) => [
      t.id,
      `"${t.timestamp_raw}"`,
      t.test_type,
      t.test_status,
      t.download_mbps ?? '',
      t.upload_mbps ?? '',
      t.ping_latency_ms ?? '',
      t.jitter_ms ?? '',
      t.packet_loss_pct ?? '',
      t.latitude ?? '',
      t.longitude ?? '',
      `"${t.serving_rat ?? ''}"`,
      t.serving_pci ?? '',
      t.serving_channel ?? '',
      t.serving_rsrp_dbm ?? '',
      `"${t.operator ?? ''}"`,
      `"${t.endpoint_url ?? ''}"`,
      t.data_provenance,
    ]);

    const csvContent = [headers.join(','), ...rows.map((r) => r.join(','))].join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `cellscope_qoe_tests_${Date.now()}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  // Status Badge Component
  const getStatusBadge = (status: string, reason?: string | null) => {
    const s = status.toUpperCase();
    if (s === 'COMPLETED') {
      return (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200 dark:bg-emerald-950/80 dark:text-emerald-400 dark:border-emerald-800">
          <CheckCircle2 className="w-3 h-3" /> COMPLETED
        </span>
      );
    }
    if (s === 'SKIPPED') {
      return (
        <span
          title={reason || 'Test skipped'}
          className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-semibold bg-amber-100 text-amber-800 border border-amber-200 dark:bg-amber-950/80 dark:text-amber-400 dark:border-amber-800"
        >
          <AlertTriangle className="w-3 h-3" /> SKIPPED
        </span>
      );
    }
    return (
      <span
        title={reason || 'Test failed'}
        className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-semibold bg-rose-100 text-rose-800 border border-rose-200 dark:bg-rose-950/80 dark:text-rose-400 dark:border-rose-800"
      >
        <XCircle className="w-3 h-3" /> FAILED
      </span>
    );
  };

  // If the dataset contains 0 QoE test records, show genuine informative unavailable state
  if (!qoeTests || qoeTests.length === 0) {
    return (
      <div className="space-y-6">
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 shadow-sm dark:shadow-none transition-colors">
          <div className="flex items-center gap-3 pb-4 border-b border-slate-200 dark:border-slate-800">
            <div className="p-3 bg-cyan-100 dark:bg-cyan-950/80 border border-cyan-200 dark:border-cyan-800 rounded-xl text-cyan-600 dark:text-cyan-400">
              <Gauge className="w-6 h-6" />
            </div>
            <div>
              <h2 className="text-xl font-bold text-slate-900 dark:text-white">Active Network QoE & Speed Performance</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                End-to-end ping latency (RTT), jitter, probe loss, and bounded HTTP download/upload throughput analytics.
              </p>
            </div>
          </div>

          <div className="py-12 flex flex-col items-center justify-center text-center max-w-xl mx-auto space-y-4">
            <div className="p-4 bg-slate-100 dark:bg-slate-800/60 rounded-full text-slate-400 dark:text-slate-500">
              <Activity className="w-10 h-10" />
            </div>
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-200">
              No Network Performance Tests Found in Current Dataset
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
              The currently loaded CSV contains physical RF telemetry (RSRP, RSRQ, CINR, GPS), but does not contain an{' '}
              <code className="text-cyan-600 dark:text-cyan-400 font-mono font-semibold"># Active QoE & Congestion Section</code> or speed test columns.
            </p>
            <div className="p-4 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-lg text-left text-xs font-mono space-y-2 w-full text-slate-600 dark:text-slate-400">
              <div className="flex items-center gap-1.5 text-cyan-700 dark:text-cyan-400 font-bold">
                <HelpCircle className="w-4 h-4" />
                <span>How to include Active QoE measurements:</span>
              </div>
              <p className="text-[11px] leading-relaxed">
                1. In the CellScope Android app, tap <strong>START SURVEY</strong> with <em>QoE Auto-Testing</em> enabled (5m or 10m interval), or tap <strong>RUN TEST NOW ⚡</strong>.
              </p>
              <p className="text-[11px] leading-relaxed">
                2. Export survey session to <strong>Extended CSV</strong>. CellScope automatically embeds the 32-column Active QoE section.
              </p>
              <p className="text-[11px] leading-relaxed">
                3. Or switch to the verified preset:{' '}
                <strong className="text-slate-800 dark:text-slate-200">Live Field Survey with Active Network QoE & Speed Tests (20261020)</strong> under Dataset Ingestion.
              </p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Overview Header */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm dark:shadow-none transition-colors">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-6 border-b border-slate-200 dark:border-slate-800">
          <div className="flex items-center gap-3">
            <div className="p-3 bg-cyan-100 dark:bg-cyan-950/80 border border-cyan-200 dark:border-cyan-800 rounded-xl text-cyan-600 dark:text-cyan-400">
              <Gauge className="w-6 h-6" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-xl font-bold text-slate-900 dark:text-white">Active Network QoE & Speed Performance</h2>
                <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-cyan-100 text-cyan-800 border border-cyan-200 dark:bg-cyan-950 dark:text-cyan-400 dark:border-cyan-800">
                  {qoeTests.length} TEST {qoeTests.length === 1 ? 'RUN' : 'RUNS'}
                </span>
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                Physical field measurements: Bounded HTTP streaming throughput, multi-probe RTT, RFC 3550 jitter, and probe loss.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleExportCsv}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-cyan-600 hover:bg-cyan-500 text-white shadow-sm transition-colors"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Export QoE CSV</span>
            </button>
          </div>
        </div>

        {/* Top KPI Scorecards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-6">
          {/* Download Speed */}
          <div className="bg-slate-50 dark:bg-slate-950/80 border border-slate-200 dark:border-slate-800 rounded-xl p-4 transition-colors">
            <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 mb-2">
              <span className="text-xs font-semibold uppercase tracking-wider flex items-center gap-1.5">
                <ArrowDownCircle className="w-4 h-4 text-emerald-600 dark:text-emerald-400" /> Download Speed
              </span>
              <span className="text-[10px] font-mono text-emerald-700 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-950/80 px-1.5 py-0.5 rounded border border-emerald-200 dark:border-emerald-800 font-bold">
                HTTP Stream
              </span>
            </div>
            <div className="flex items-baseline gap-1.5">
              <span className="text-2xl font-bold font-mono text-slate-900 dark:text-white">
                {summary?.avg_download_mbps !== null && summary?.avg_download_mbps !== undefined
                  ? summary.avg_download_mbps.toFixed(2)
                  : '—'}
              </span>
              <span className="text-xs text-slate-500 dark:text-slate-400 font-mono font-medium">Mbps (Avg)</span>
            </div>
            <div className="mt-2 pt-2 border-t border-slate-200 dark:border-slate-800/80 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400 font-mono">
              <span>
                Peak: <strong className="text-slate-800 dark:text-slate-200">{summary?.max_download_mbps?.toFixed(2) ?? '—'}</strong> Mbps
              </span>
              <span>
                Min: <strong className="text-slate-800 dark:text-slate-200">{summary?.min_download_mbps?.toFixed(2) ?? '—'}</strong> Mbps
              </span>
            </div>
          </div>

          {/* Upload Speed */}
          <div className="bg-slate-50 dark:bg-slate-950/80 border border-slate-200 dark:border-slate-800 rounded-xl p-4 transition-colors">
            <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 mb-2">
              <span className="text-xs font-semibold uppercase tracking-wider flex items-center gap-1.5">
                <ArrowUpCircle className="w-4 h-4 text-cyan-600 dark:text-cyan-400" /> Upload Speed
              </span>
              <span className="text-[10px] font-mono text-cyan-700 dark:text-cyan-400 bg-cyan-100 dark:bg-cyan-950/80 px-1.5 py-0.5 rounded border border-cyan-200 dark:border-cyan-800 font-bold">
                HTTP POST
              </span>
            </div>
            <div className="flex items-baseline gap-1.5">
              <span className="text-2xl font-bold font-mono text-slate-900 dark:text-white">
                {summary?.avg_upload_mbps !== null && summary?.avg_upload_mbps !== undefined
                  ? summary.avg_upload_mbps.toFixed(2)
                  : '—'}
              </span>
              <span className="text-xs text-slate-500 dark:text-slate-400 font-mono font-medium">Mbps (Avg)</span>
            </div>
            <div className="mt-2 pt-2 border-t border-slate-200 dark:border-slate-800/80 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400 font-mono">
              <span>
                Peak: <strong className="text-slate-800 dark:text-slate-200">{summary?.max_upload_mbps?.toFixed(2) ?? '—'}</strong> Mbps
              </span>
              <span>
                Min: <strong className="text-slate-800 dark:text-slate-200">{summary?.min_upload_mbps?.toFixed(2) ?? '—'}</strong> Mbps
              </span>
            </div>
          </div>

          {/* Ping Latency (RTT) */}
          <div className="bg-slate-50 dark:bg-slate-950/80 border border-slate-200 dark:border-slate-800 rounded-xl p-4 transition-colors">
            <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 mb-2">
              <span className="text-xs font-semibold uppercase tracking-wider flex items-center gap-1.5">
                <Activity className="w-4 h-4 text-amber-600 dark:text-amber-400" /> Ping Latency (RTT)
              </span>
              <span className="text-[10px] font-mono text-amber-700 dark:text-amber-400 bg-amber-100 dark:bg-amber-950/80 px-1.5 py-0.5 rounded border border-amber-200 dark:border-amber-800 font-bold">
                Multi-Probe
              </span>
            </div>
            <div className="flex items-baseline gap-1.5">
              <span className="text-2xl font-bold font-mono text-slate-900 dark:text-white">
                {summary?.median_ping_ms !== null && summary?.median_ping_ms !== undefined
                  ? summary.median_ping_ms.toFixed(0)
                  : summary?.avg_ping_ms !== null && summary?.avg_ping_ms !== undefined
                  ? summary.avg_ping_ms.toFixed(0)
                  : '—'}
              </span>
              <span className="text-xs text-slate-500 dark:text-slate-400 font-mono font-medium">ms (Median)</span>
            </div>
            <div className="mt-2 pt-2 border-t border-slate-200 dark:border-slate-800/80 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400 font-mono">
              <span>
                Mean: <strong className="text-slate-800 dark:text-slate-200">{summary?.avg_ping_ms?.toFixed(1) ?? '—'}</strong> ms
              </span>
              <span>
                Min: <strong className="text-slate-800 dark:text-slate-200">{summary?.min_ping_ms?.toFixed(0) ?? '—'}</strong> ms
              </span>
            </div>
          </div>

          {/* Jitter & Reliability */}
          <div className="bg-slate-50 dark:bg-slate-950/80 border border-slate-200 dark:border-slate-800 rounded-xl p-4 transition-colors">
            <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 mb-2">
              <span className="text-xs font-semibold uppercase tracking-wider flex items-center gap-1.5">
                <Radio className="w-4 h-4 text-purple-600 dark:text-purple-400" /> Jitter & Probe Loss
              </span>
              <span className="text-[10px] font-mono text-purple-700 dark:text-purple-400 bg-purple-100 dark:bg-purple-950/80 px-1.5 py-0.5 rounded border border-purple-200 dark:border-purple-800 font-bold">
                RFC 3550
              </span>
            </div>
            <div className="flex items-baseline gap-1.5">
              <span className="text-2xl font-bold font-mono text-slate-900 dark:text-white">
                {summary?.avg_jitter_ms !== null && summary?.avg_jitter_ms !== undefined
                  ? summary.avg_jitter_ms.toFixed(1)
                  : '—'}
              </span>
              <span className="text-xs text-slate-500 dark:text-slate-400 font-mono font-medium">ms Jitter</span>
            </div>
            <div className="mt-2 pt-2 border-t border-slate-200 dark:border-slate-800/80 flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400 font-mono">
              <span>
                Loss:{' '}
                <strong className={summary?.avg_packet_loss_pct && summary.avg_packet_loss_pct > 0 ? 'text-rose-600 dark:text-rose-400' : 'text-emerald-600 dark:text-emerald-400'}>
                  {summary?.avg_packet_loss_pct !== null && summary?.avg_packet_loss_pct !== undefined
                    ? `${summary.avg_packet_loss_pct.toFixed(1)}%`
                    : '0%'}
                </strong>
              </span>
              <span>
                Completed: <strong className="text-slate-800 dark:text-slate-200">{summary?.completed_tests ?? 0}/{summary?.total_tests ?? 0}</strong>
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm dark:shadow-none flex flex-wrap items-center justify-between gap-3 transition-colors">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold flex items-center gap-1.5">
            <Filter className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" /> Filter Tests:
          </span>

          {/* RAT Filter */}
          <select
            value={filterRat}
            onChange={(e) => setFilterRat(e.target.value)}
            className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500"
          >
            <option value="all">All RATs</option>
            {uniqueRats.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>

          {/* Operator Filter */}
          {uniqueOperators.length > 0 && (
            <select
              value={filterOperator}
              onChange={(e) => setFilterOperator(e.target.value)}
              className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500"
            >
              <option value="all">All Operators</option>
              {uniqueOperators.map((op) => (
                <option key={op} value={op}>
                  {op}
                </option>
              ))}
            </select>
          )}

          {/* Serving PCI Filter */}
          {uniquePcis.length > 0 && (
            <select
              value={filterPci}
              onChange={(e) => setFilterPci(e.target.value)}
              className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500"
            >
              <option value="all">All PCIs</option>
              {uniquePcis.map((pci) => (
                <option key={pci} value={pci.toString()}>
                  PCI {pci}
                </option>
              ))}
            </select>
          )}

          {/* Status Filter */}
          <select
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value)}
            className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500"
          >
            <option value="all">All Statuses</option>
            <option value="COMPLETED">Completed</option>
            <option value="FAILED">Failed</option>
            <option value="SKIPPED">Skipped</option>
          </select>
        </div>

        <div className="text-xs text-slate-500 dark:text-slate-400 font-mono">
          Showing <strong className="text-slate-900 dark:text-white">{filteredTests.length}</strong> of{' '}
          {qoeTests.length} tests
        </div>
      </div>

      {/* Time-Series Charts Section */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Throughput Bar Chart */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm dark:shadow-none transition-colors">
          <div className="flex items-center justify-between pb-4 border-b border-slate-200 dark:border-slate-800 mb-4">
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                <Gauge className="w-4 h-4 text-emerald-600 dark:text-emerald-400" /> Throughput Run Comparison (Mbps)
              </h3>
              <p className="text-[11px] text-slate-500 dark:text-slate-400">
                Download (green) vs Upload (blue) throughput per test run
              </p>
            </div>
            <div className="flex items-center gap-3 text-[11px] font-mono">
              <span className="flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400">
                <span className="w-2.5 h-2.5 rounded-sm bg-emerald-500" /> DL
              </span>
              <span className="flex items-center gap-1.5 text-cyan-600 dark:text-cyan-400">
                <span className="w-2.5 h-2.5 rounded-sm bg-cyan-500" /> UL
              </span>
            </div>
          </div>

          <div className="space-y-3">
            {filteredTests.map((t, idx) => {
              const maxVal = Math.max(
                ...filteredTests.map((r) => Math.max(r.download_mbps || 0, r.upload_mbps || 0)),
                5.0
              );
              const dlPct = t.download_mbps ? (t.download_mbps / maxVal) * 100 : 0;
              const ulPct = t.upload_mbps ? (t.upload_mbps / maxVal) * 100 : 0;

              return (
                <div key={t.id || idx} className="space-y-1">
                  <div className="flex items-center justify-between text-[11px] font-mono text-slate-600 dark:text-slate-400">
                    <span className="flex items-center gap-1.5">
                      <span className="font-bold text-slate-800 dark:text-slate-200">Test #{t.id}</span>
                      <span className="text-[10px] text-slate-400">({t.timestamp_raw.slice(11, 19) || t.timestamp_raw})</span>
                      {t.serving_rat && (
                        <span className="text-[9px] px-1 py-0.2 rounded bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300">
                          {t.serving_rat}
                        </span>
                      )}
                    </span>
                    <span className="flex items-center gap-2">
                      <span className="text-emerald-600 dark:text-emerald-400 font-bold">
                        DL: {t.download_mbps !== null ? `${t.download_mbps.toFixed(2)} M` : '—'}
                      </span>
                      <span className="text-cyan-600 dark:text-cyan-400 font-bold">
                        UL: {t.upload_mbps !== null ? `${t.upload_mbps.toFixed(2)} M` : '—'}
                      </span>
                    </span>
                  </div>

                  {/* Dual bar track */}
                  <div className="space-y-1">
                    <div className="h-2 w-full bg-slate-100 dark:bg-slate-800/80 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-emerald-500 dark:bg-emerald-400 rounded-full transition-all duration-300"
                        style={{ width: `${Math.min(100, Math.max(3, dlPct))}%` }}
                      />
                    </div>
                    <div className="h-2 w-full bg-slate-100 dark:bg-slate-800/80 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-cyan-500 dark:bg-cyan-400 rounded-full transition-all duration-300"
                        style={{ width: `${Math.min(100, Math.max(3, ulPct))}%` }}
                      />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Latency & Jitter Chart */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm dark:shadow-none transition-colors">
          <div className="flex items-center justify-between pb-4 border-b border-slate-200 dark:border-slate-800 mb-4">
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                <Activity className="w-4 h-4 text-amber-600 dark:text-amber-400" /> Latency (RTT) & Jitter Profile (ms)
              </h3>
              <p className="text-[11px] text-slate-500 dark:text-slate-400">
                RTT ping latency (amber) vs RFC 3550 jitter (purple)
              </p>
            </div>
            <div className="flex items-center gap-3 text-[11px] font-mono">
              <span className="flex items-center gap-1.5 text-amber-600 dark:text-amber-400">
                <span className="w-2.5 h-2.5 rounded-sm bg-amber-500" /> Ping RTT
              </span>
              <span className="flex items-center gap-1.5 text-purple-600 dark:text-purple-400">
                <span className="w-2.5 h-2.5 rounded-sm bg-purple-500" /> Jitter
              </span>
            </div>
          </div>

          <div className="space-y-3">
            {filteredTests.map((t, idx) => {
              const maxPing = Math.max(
                ...filteredTests.map((r) => Math.max(r.ping_latency_ms || 0, r.jitter_ms || 0)),
                100.0
              );
              const pingPct = t.ping_latency_ms ? (t.ping_latency_ms / maxPing) * 100 : 0;
              const jitPct = t.jitter_ms ? (t.jitter_ms / maxPing) * 100 : 0;

              return (
                <div key={t.id || idx} className="space-y-1">
                  <div className="flex items-center justify-between text-[11px] font-mono text-slate-600 dark:text-slate-400">
                    <span className="flex items-center gap-1.5">
                      <span className="font-bold text-slate-800 dark:text-slate-200">Test #{t.id}</span>
                      <span className="text-[10px] text-slate-400">({t.timestamp_raw.slice(11, 19) || t.timestamp_raw})</span>
                      {t.packet_loss_pct !== null && t.packet_loss_pct !== undefined && t.packet_loss_pct > 0 && (
                        <span className="text-[9px] px-1 py-0.2 rounded bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-400 font-bold">
                          {t.packet_loss_pct.toFixed(0)}% Loss
                        </span>
                      )}
                    </span>
                    <span className="flex items-center gap-2">
                      <span className="text-amber-600 dark:text-amber-400 font-bold">
                        Ping: {t.ping_latency_ms !== null ? `${t.ping_latency_ms.toFixed(0)} ms` : '—'}
                      </span>
                      <span className="text-purple-600 dark:text-purple-400 font-bold">
                        Jitter: {t.jitter_ms !== null ? `${t.jitter_ms.toFixed(1)} ms` : '—'}
                      </span>
                    </span>
                  </div>

                  {/* Dual bar track */}
                  <div className="space-y-1">
                    <div className="h-2 w-full bg-slate-100 dark:bg-slate-800/80 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-amber-500 dark:bg-amber-400 rounded-full transition-all duration-300"
                        style={{ width: `${Math.min(100, Math.max(3, pingPct))}%` }}
                      />
                    </div>
                    <div className="h-2 w-full bg-slate-100 dark:bg-slate-800/80 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-purple-500 dark:bg-purple-400 rounded-full transition-all duration-300"
                        style={{ width: `${Math.min(100, Math.max(3, jitPct))}%` }}
                      />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* Individual Test Results Table */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl overflow-hidden shadow-sm dark:shadow-none transition-colors">
        <div className="p-4 border-b border-slate-200 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-950 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Layers className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
            <h3 className="text-sm font-bold text-slate-900 dark:text-white">Individual QoE Test Runs & Diagnostics</h3>
          </div>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">
            Standard: 3GPP TS 36.133 / RFC 3550
          </span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-slate-100 dark:bg-slate-950 text-slate-600 dark:text-slate-400 border-b border-slate-200 dark:border-slate-800">
              <tr>
                <th className="py-2.5 px-3">#</th>
                <th className="py-2.5 px-3">Timestamp</th>
                <th className="py-2.5 px-3">Type</th>
                <th className="py-2.5 px-3">Status</th>
                <th className="py-2.5 px-3">Download</th>
                <th className="py-2.5 px-3">Upload</th>
                <th className="py-2.5 px-3">Ping (RTT)</th>
                <th className="py-2.5 px-3">Jitter</th>
                <th className="py-2.5 px-3">Loss</th>
                <th className="py-2.5 px-3">Cellular Context</th>
                <th className="py-2.5 px-3">GPS / Location</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800 text-slate-700 dark:text-slate-300">
              {filteredTests.map((t) => (
                <tr key={t.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors">
                  <td className="py-2.5 px-3 text-slate-400 dark:text-slate-500 font-bold">{t.id}</td>
                  <td className="py-2.5 px-3 font-semibold text-slate-900 dark:text-white">
                    <div className="flex items-center gap-1.5">
                      <Clock className="w-3.5 h-3.5 text-slate-400" />
                      <span>{t.timestamp_raw}</span>
                    </div>
                  </td>
                  <td className="py-2.5 px-3">
                    <span className="px-1.5 py-0.5 rounded text-[10px] bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 font-semibold text-slate-700 dark:text-slate-300">
                      {t.test_type}
                    </span>
                  </td>
                  <td className="py-2.5 px-3">{getStatusBadge(t.test_status, t.skipped_reason)}</td>
                  <td className="py-2.5 px-3">
                    {t.download_mbps !== null ? (
                      <span className="font-bold text-emerald-600 dark:text-emerald-400">
                        {t.download_mbps.toFixed(2)} Mbps
                      </span>
                    ) : (
                      <span className="text-slate-400">—</span>
                    )}
                  </td>
                  <td className="py-2.5 px-3">
                    {t.upload_mbps !== null ? (
                      <span className="font-bold text-cyan-600 dark:text-cyan-400">
                        {t.upload_mbps.toFixed(2)} Mbps
                      </span>
                    ) : (
                      <span className="text-slate-400">—</span>
                    )}
                  </td>
                  <td className="py-2.5 px-3">
                    {t.ping_latency_ms !== null ? (
                      <span className="font-bold text-amber-600 dark:text-amber-400">
                        {t.ping_latency_ms.toFixed(0)} ms
                      </span>
                    ) : (
                      <span className="text-slate-400">—</span>
                    )}
                  </td>
                  <td className="py-2.5 px-3">
                    {t.jitter_ms !== null ? (
                      <span className="text-purple-600 dark:text-purple-400">
                        {t.jitter_ms.toFixed(1)} ms
                      </span>
                    ) : (
                      <span className="text-slate-400">—</span>
                    )}
                  </td>
                  <td className="py-2.5 px-3">
                    {t.packet_loss_pct !== null ? (
                      <span
                        className={
                          t.packet_loss_pct > 0
                            ? 'text-rose-600 dark:text-rose-400 font-bold'
                            : 'text-emerald-600 dark:text-emerald-400'
                        }
                      >
                        {t.packet_loss_pct.toFixed(0)}%
                      </span>
                    ) : (
                      <span className="text-slate-400">0%</span>
                    )}
                  </td>
                  <td className="py-2.5 px-3">
                    <div className="space-y-0.5 text-[11px]">
                      <div className="flex items-center gap-1.5">
                        <span className="text-cyan-700 dark:text-cyan-400 font-bold">
                          PCI {t.serving_pci ?? '—'}
                        </span>
                        {t.serving_rat && (
                          <span className="text-[9px] px-1 py-0.2 rounded bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300">
                            {t.serving_rat}
                          </span>
                        )}
                        {t.serving_rsrp_dbm !== null && (
                          <span className="text-slate-500 dark:text-slate-400">
                            {t.serving_rsrp_dbm} dBm
                          </span>
                        )}
                      </div>
                      {t.operator && (
                        <div className="text-[10px] text-slate-400 dark:text-slate-500 truncate max-w-[150px]">
                          {t.operator}
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="py-2.5 px-3">
                    {t.has_valid_gps && t.latitude !== null && t.longitude !== null ? (
                      <button
                        onClick={() => onSelectCoordinate?.(t.latitude!, t.longitude!)}
                        className="inline-flex items-center gap-1 text-[11px] text-emerald-600 dark:text-emerald-400 hover:underline"
                        title="Focus on map"
                      >
                        <MapPin className="w-3.5 h-3.5" />
                        <span>
                          {t.latitude.toFixed(4)}, {t.longitude.toFixed(4)}
                        </span>
                      </button>
                    ) : (
                      <span className="text-slate-400 dark:text-slate-500 text-[11px]">No GPS Fix</span>
                    )}
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
