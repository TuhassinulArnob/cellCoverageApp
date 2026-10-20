import React from 'react';
import type { ExtendedDatasetAnalysis } from '../types/cellscope';
import {
  ShieldCheck,
  AlertCircle,
  FileText,
  MapPin,
  Database,
} from 'lucide-react';

interface DataQualityPanelProps {
  analysis: ExtendedDatasetAnalysis | null;
}

export const DataQualityPanel: React.FC<DataQualityPanelProps> = ({ analysis }) => {
  if (!analysis) {
    return (
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 text-center text-slate-500 dark:text-slate-400 shadow-sm transition-colors duration-150">
        Loading data quality diagnostics...
      </div>
    );
  }

  const { data_quality, summary } = analysis;
  const missing = summary.missing_values_summary || {};

  return (
    <div className="space-y-6 transition-colors duration-150">
      {/* Header Metric Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">Dataset Completeness</span>
            <ShieldCheck className="w-5 h-5 text-emerald-600 dark:text-emerald-400" />
          </div>
          <div className="text-3xl font-bold font-mono text-emerald-600 dark:text-emerald-400">{data_quality.completeness_pct}%</div>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-2">
            Evaluates presence of required RF metrics (RSRP, RSRQ, CINR), serving cell identity, and coordinates across {data_quality.total_records.toLocaleString()} rows.
          </p>
          <div className="w-full bg-slate-100 dark:bg-slate-800 h-2 rounded-full mt-3 overflow-hidden">
            <div
              className={`h-full ${data_quality.completeness_pct >= 90 ? 'bg-emerald-500' : 'bg-amber-500'}`}
              style={{ width: `${data_quality.completeness_pct}%` }}
            />
          </div>
        </div>

        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">Telemetry Gaps</span>
            <AlertCircle className="w-5 h-5 text-amber-600 dark:text-amber-400" />
          </div>
          <div className="text-3xl font-bold font-mono text-amber-600 dark:text-amber-400">{data_quality.telemetry_gaps_count}</div>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-2">
            Consecutive fixes separated by &gt; 150 meters or time gaps &gt; 10 seconds. Splitting polyline trajectory avoids drawing false straight lines across tunnels or blackouts.
          </p>
        </div>

        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">GPS Coordinate Fixes</span>
            <MapPin className="w-5 h-5 text-cyan-600 dark:text-cyan-400" />
          </div>
          <div className="text-3xl font-bold font-mono text-cyan-700 dark:text-cyan-400">
            {summary.records_with_gps} <span className="text-sm font-normal text-slate-400 dark:text-slate-500">/ {summary.total_records}</span>
          </div>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-2">
            {summary.records_invalid_gps > 0
              ? `${summary.records_invalid_gps} invalid/outlier coordinate fixes filtered out safely.`
              : '100% of recorded coordinate samples fall within valid geographic bounds.'}
          </p>
        </div>
      </div>

      {/* Missing Values Audit Table */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Database className="w-5 h-5 text-cyan-600 dark:text-cyan-400" />
            <h3 className="font-semibold text-base text-slate-900 dark:text-slate-100">Telemetry Fields Missing Value Audit</h3>
          </div>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">{summary.total_records.toLocaleString()} total sample rows</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-400 font-mono">
                <th className="pb-2">Field</th>
                <th className="pb-2">Type</th>
                <th className="pb-2">Missing Count</th>
                <th className="pb-2">Availability</th>
                <th className="pb-2">Impact Assessment</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800/60 text-slate-800 dark:text-slate-300 font-mono">
              <tr className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                <td className="py-2.5 font-bold text-slate-900 dark:text-white">Latitude / Longitude</td>
                <td className="py-2.5 text-slate-500 dark:text-slate-400">WGS84 Coordinates</td>
                <td className="py-2.5 text-amber-600 dark:text-amber-400">{missing.gps ?? 0}</td>
                <td className="py-2.5">
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                    {Math.round(((summary.total_records - (missing.gps ?? 0)) / summary.total_records) * 100)}%
                  </span>
                </td>
                <td className="py-2.5 text-slate-600 dark:text-slate-400 font-sans">Required for spatial route mapping and coverage heatmaps.</td>
              </tr>
              <tr className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                <td className="py-2.5 font-bold text-slate-900 dark:text-white">Serving Cell RSRP</td>
                <td className="py-2.5 text-slate-500 dark:text-slate-400">Signal Power (dBm)</td>
                <td className="py-2.5 text-amber-600 dark:text-amber-400">{missing.rsrp ?? 0}</td>
                <td className="py-2.5">
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                    {Math.round(((summary.total_records - (missing.rsrp ?? 0)) / summary.total_records) * 100)}%
                  </span>
                </td>
                <td className="py-2.5 text-slate-600 dark:text-slate-400 font-sans">Primary 3GPP signal strength metric for coverage hole detection.</td>
              </tr>
              <tr className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                <td className="py-2.5 font-bold text-slate-900 dark:text-white">Serving Cell RSRQ</td>
                <td className="py-2.5 text-slate-500 dark:text-slate-400">Signal Quality (dB)</td>
                <td className="py-2.5 text-amber-600 dark:text-amber-400">{missing.rsrq ?? 0}</td>
                <td className="py-2.5">
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                    {Math.round(((summary.total_records - (missing.rsrq ?? 0)) / summary.total_records) * 100)}%
                  </span>
                </td>
                <td className="py-2.5 text-slate-600 dark:text-slate-400 font-sans">Carrier RSSI ratio indicating channel loading and interference.</td>
              </tr>
              <tr className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                <td className="py-2.5 font-bold text-slate-900 dark:text-white">Serving Cell CINR / SINR</td>
                <td className="py-2.5 text-slate-500 dark:text-slate-400">Carrier to Noise (dB)</td>
                <td className="py-2.5 text-amber-600 dark:text-amber-400">{missing.cinr ?? 0}</td>
                <td className="py-2.5">
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                    {Math.round(((summary.total_records - (missing.cinr ?? 0)) / summary.total_records) * 100)}%
                  </span>
                </td>
                <td className="py-2.5 text-slate-600 dark:text-slate-400 font-sans">Physical layer throughput and modulation indicator.</td>
              </tr>
              <tr className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
                <td className="py-2.5 font-bold text-slate-900 dark:text-white">Serving Cell ID (PCI)</td>
                <td className="py-2.5 text-slate-500 dark:text-slate-400">Physical Cell Identity</td>
                <td className="py-2.5 text-amber-600 dark:text-amber-400">{missing.serving_cell ?? 0}</td>
                <td className="py-2.5">
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                    {Math.round(((summary.total_records - (missing.serving_cell ?? 0)) / summary.total_records) * 100)}%
                  </span>
                </td>
                <td className="py-2.5 text-slate-600 dark:text-slate-400 font-sans">Required for handover tracking, PCI collision and pilot pollution audits.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* Measurement Provenance Box */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <FileText className="w-5 h-5 text-cyan-600 dark:text-cyan-400" />
          <h3 className="font-semibold text-base text-slate-900 dark:text-slate-100">Measurement Provenance & Standards Compliance</h3>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 text-xs font-mono">
          <div className="bg-slate-50 dark:bg-slate-950/60 p-3 rounded-lg border border-slate-200 dark:border-slate-800">
            <span className="text-slate-500 block text-[10px]">SOURCE DATASET</span>
            <span className="text-slate-900 dark:text-white font-bold truncate block">{data_quality.provenance_source}</span>
          </div>
          <div className="bg-slate-50 dark:bg-slate-950/60 p-3 rounded-lg border border-slate-200 dark:border-slate-800">
            <span className="text-slate-500 block text-[10px]">PARSER STANDARD</span>
            <span className="text-cyan-700 dark:text-cyan-400 font-bold block">{data_quality.schema_standard}</span>
          </div>
          <div className="bg-slate-50 dark:bg-slate-950/60 p-3 rounded-lg border border-slate-200 dark:border-slate-800">
            <span className="text-slate-500 block text-[10px]">COORDINATE DATUM</span>
            <span className="text-emerald-700 dark:text-emerald-400 font-bold block">{data_quality.coordinate_system}</span>
          </div>
          <div className="bg-slate-50 dark:bg-slate-950/60 p-3 rounded-lg border border-slate-200 dark:border-slate-800">
            <span className="text-slate-500 block text-[10px]">PROCESSING ENGINE</span>
            <span className="text-purple-700 dark:text-purple-400 font-bold block">CellScope RAN Core v3.0</span>
          </div>
        </div>
      </div>
    </div>
  );
};
