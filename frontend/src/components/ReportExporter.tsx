import React, { useState } from 'react';
import type { DatasetSummary, ExtendedDatasetAnalysis } from '../types/cellscope';
import { getCsvExportUrl } from '../services/api';
import {
  FileDown,
  Printer,
  FileSpreadsheet,
  CheckCircle2,
} from 'lucide-react';

interface ReportExporterProps {
  summary: DatasetSummary | null;
  analysis?: ExtendedDatasetAnalysis | null;
}

export const ReportExporter: React.FC<ReportExporterProps> = ({ summary }) => {
  const [reportDate] = useState(() => new Date().toLocaleDateString());

  if (!summary) return null;

  const handlePrint = () => {
    window.print();
  };

  return (
    <div className="space-y-6">
      {/* Export Action Card */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm dark:shadow-none transition-colors">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-6 border-b border-slate-200 dark:border-slate-800">
          <div>
            <h3 className="text-lg font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <FileDown className="w-5 h-5 text-cyan-600 dark:text-cyan-400" /> Telecom Reports & Data Exports
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
              Export survey telemetry complying with canonical 14-column backend schema or print an executive PDF report.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <a
              href={getCsvExportUrl(false)}
              download
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-cyan-600 text-white hover:bg-cyan-500 shadow-sm transition-colors"
            >
              <FileSpreadsheet className="w-4 h-4" /> Download Canonical CSV (14 Cols)
            </a>
            <a
              href={getCsvExportUrl(true)}
              download
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-slate-100 hover:bg-slate-200 text-slate-700 border border-slate-300 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700 dark:border-slate-700 shadow-sm transition-colors"
            >
              <FileSpreadsheet className="w-4 h-4 text-purple-600 dark:text-purple-400" /> Download Extended CSV (18 Cols)
            </a>
            <button
              onClick={handlePrint}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-slate-100 hover:bg-slate-200 text-slate-700 border border-slate-300 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700 dark:border-slate-700 shadow-sm transition-colors"
            >
              <Printer className="w-4 h-4 text-emerald-600 dark:text-emerald-400" /> Print / Save as PDF
            </button>
          </div>
        </div>

        {/* Canonical 14-Column Specification Notice */}
        <div className="mt-4 bg-slate-50 dark:bg-slate-950/70 p-4 rounded-lg border border-slate-200 dark:border-slate-800 text-xs">
          <div className="flex items-center gap-2 text-cyan-700 dark:text-cyan-400 font-semibold mb-2">
            <CheckCircle2 className="w-4 h-4" />
            <span>Strict Preservation of Canonical 14-Column Contract</span>
          </div>
          <p className="text-slate-700 dark:text-slate-400 text-[11px] font-mono leading-relaxed">
            Timestamp, Latitude, Longitude, Speed (km/h), Serving Cell ID, Serving Cell RSRP, Serving Cell RSRQ, Serving Cell CINR, Handover Trigger, TAC, EARFCN, RSSI (dBm), Network Type, Operator
          </p>
          <p className="text-slate-500 dark:text-slate-500 text-[11px] mt-2">
            Guarantees 100% interoperability with existing Python ML pipelines, backend parsers, and external GIS tools (QGIS, MapInfo, MATLAB).
          </p>
        </div>
      </div>

      {/* Printable Telecom Survey Report Preview Card */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 printable-report shadow-sm dark:shadow-none transition-colors">
        <div className="flex items-center justify-between pb-4 border-b border-slate-200 dark:border-slate-800 mb-6">
          <div>
            <span className="text-xs uppercase tracking-widest text-cyan-600 dark:text-cyan-400 font-bold block">CELLSCOPE RAN PLATFORM</span>
            <h2 className="text-xl font-bold text-slate-900 dark:text-white mt-1">Cellular Drive-Test & RF Optimization Survey Report</h2>
            <span className="text-xs text-slate-500 dark:text-slate-400 font-mono">Dataset: {summary.filename}</span>
          </div>
          <div className="text-right text-xs font-mono text-slate-500 dark:text-slate-400">
            <div>Standard: 3GPP TS 36.133 / 38.133</div>
            <div>Datum: WGS84 (EPSG:4326)</div>
            <div>Date: {reportDate}</div>
          </div>
        </div>

        {/* Executive Summary Table */}
        <div className="mb-6">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700 dark:text-slate-300 mb-3">1. Executive Survey KPIs</h4>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono">
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">TOTAL SAMPLES</span>
              <span className="text-slate-900 dark:text-white font-bold text-base">{summary.total_records.toLocaleString()}</span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">ROUTE DISTANCE</span>
              <span className="text-cyan-600 dark:text-cyan-400 font-bold text-base">
                {summary.total_distance_km ? `${summary.total_distance_km} km` : 'N/A'}
              </span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">MEAN RSRP</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-bold text-base">{summary.mean_rsrp_dbm ?? 'N/A'} dBm</span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">MEAN RSRQ</span>
              <span className="text-sky-600 dark:text-sky-400 font-bold text-base">{summary.mean_rsrq_db ?? 'N/A'} dB</span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">MEAN CINR</span>
              <span className="text-amber-600 dark:text-amber-400 font-bold text-base">{summary.mean_cinr_db ?? 'N/A'} dB</span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">HO TRIGGERS</span>
              <span className="text-rose-600 dark:text-rose-400 font-bold text-base">{summary.handover_trigger_count}</span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">SERVING CELLS</span>
              <span className="text-purple-600 dark:text-purple-400 font-bold text-base">{summary.unique_serving_cells} PCIs</span>
            </div>
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">DATA COMPLETENESS</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-bold text-base">{summary.data_completeness_pct ?? 100}%</span>
            </div>
          </div>
        </div>

        {/* 3GPP Quality Breakdown */}
        <div className="mb-6">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700 dark:text-slate-300 mb-3">2. 3GPP Radio Quality Breakdown</h4>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="font-bold text-slate-900 dark:text-white block mb-2">RSRP Coverage</span>
              <div className="space-y-1 text-[11px]">
                <div className="flex justify-between"><span className="text-emerald-600 dark:text-emerald-400 font-medium">Excellent (≥-80 dBm):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrp_distribution.excellent}</span></div>
                <div className="flex justify-between"><span className="text-sky-600 dark:text-sky-400 font-medium">Good (-80 to -90):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrp_distribution.good}</span></div>
                <div className="flex justify-between"><span className="text-amber-600 dark:text-amber-400 font-medium">Fair (-90 to -100):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrp_distribution.fair}</span></div>
                <div className="flex justify-between"><span className="text-rose-600 dark:text-rose-400 font-medium">Poor (&lt;-100 dBm):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrp_distribution.poor}</span></div>
              </div>
            </div>

            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="font-bold text-slate-900 dark:text-white block mb-2">RSRQ Quality</span>
              <div className="space-y-1 text-[11px]">
                <div className="flex justify-between"><span className="text-emerald-600 dark:text-emerald-400 font-medium">Excellent (≥-10 dB):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrq_distribution.excellent}</span></div>
                <div className="flex justify-between"><span className="text-sky-600 dark:text-sky-400 font-medium">Good (-10 to -14):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrq_distribution.good}</span></div>
                <div className="flex justify-between"><span className="text-amber-600 dark:text-amber-400 font-medium">Fair (-14 to -17):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrq_distribution.fair}</span></div>
                <div className="flex justify-between"><span className="text-rose-600 dark:text-rose-400 font-medium">Poor (&lt;-17 dB):</span> <span className="text-slate-800 dark:text-slate-200">{summary.rsrq_distribution.poor}</span></div>
              </div>
            </div>

            <div className="bg-slate-50 dark:bg-slate-950/80 p-3 rounded border border-slate-200 dark:border-slate-800">
              <span className="font-bold text-slate-900 dark:text-white block mb-2">CINR Ratio</span>
              <div className="space-y-1 text-[11px]">
                <div className="flex justify-between"><span className="text-emerald-600 dark:text-emerald-400 font-medium">Excellent (≥20 dB):</span> <span className="text-slate-800 dark:text-slate-200">{summary.cinr_distribution.excellent}</span></div>
                <div className="flex justify-between"><span className="text-sky-600 dark:text-sky-400 font-medium">Good (13 to 20):</span> <span className="text-slate-800 dark:text-slate-200">{summary.cinr_distribution.good}</span></div>
                <div className="flex justify-between"><span className="text-amber-600 dark:text-amber-400 font-medium">Fair (0 to 13):</span> <span className="text-slate-800 dark:text-slate-200">{summary.cinr_distribution.fair}</span></div>
                <div className="flex justify-between"><span className="text-rose-600 dark:text-rose-400 font-medium">Poor (&lt;0 dB):</span> <span className="text-slate-800 dark:text-slate-200">{summary.cinr_distribution.poor}</span></div>
              </div>
            </div>
          </div>
        </div>

        {/* Engineering Audit & Methodology */}
        <div className="text-[11px] text-slate-600 dark:text-slate-400 space-y-2 border-t border-slate-200 dark:border-slate-800 pt-4">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700 dark:text-slate-300">3. Engineering Certification & Audit Notes</h4>
          <p>
            This report represents verified physical cellular field measurements processed according to 3GPP TS 36.133 (E-UTRA requirements for support of radio resource management) and 3GPP TS 38.133 (5G NR). Coordinates are mapped on WGS84 datum. Handover trigger rates reflect Layer-3 measurement report indications.
          </p>
          <p>
            Generated by <strong className="text-slate-900 dark:text-white">CellScope Platform Lead: Sarkar Tuhassinul Arnob</strong> (arnob.ict.iu@gmail.com).
          </p>
        </div>
      </div>
    </div>
  );
};
