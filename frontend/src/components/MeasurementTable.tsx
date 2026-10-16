import React, { useState, useMemo } from 'react';
import type { MeasurementPoint } from '../types/cellscope';
import { Download, Search, ChevronLeft, ChevronRight, CheckCircle2, AlertCircle } from 'lucide-react';

interface MeasurementTableProps {
  measurements: MeasurementPoint[];
}

export const MeasurementTable: React.FC<MeasurementTableProps> = ({ measurements }) => {
  const [searchTerm, setSearchTerm] = useState('');
  const [qualityFilter, setQualityFilter] = useState<string>('all');
  const [hoOnly, setHoOnly] = useState<boolean>(false);
  const [currentPage, setCurrentPage] = useState<number>(1);
  const pageSize = 50;

  const filteredMeasurements = useMemo(() => {
    return measurements.filter((m) => {
      if (hoOnly && m.handover_trigger !== 1) return false;
      if (qualityFilter !== 'all' && m.rsrp_class.toLowerCase() !== qualityFilter.toLowerCase()) return false;
      if (searchTerm) {
        const term = searchTerm.toLowerCase();
        const matchesCell = m.serving_cell_id?.toString().includes(term);
        const matchesTime = m.timestamp_raw.toLowerCase().includes(term);
        if (!matchesCell && !matchesTime) return false;
      }
      return true;
    });
  }, [measurements, searchTerm, qualityFilter, hoOnly]);

  const totalPages = Math.ceil(filteredMeasurements.length / pageSize) || 1;
  const paginatedData = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return filteredMeasurements.slice(start, start + pageSize);
  }, [filteredMeasurements, currentPage, pageSize]);

  const handleExportCsv = () => {
    if (measurements.length === 0) return;
    const headers = [
      'id',
      'timestamp',
      'latitude',
      'longitude',
      'speed_kmh',
      'serving_cell_id',
      'neighbor_cell_id',
      'rsrp_dbm',
      'rsrp_ie',
      'rsrq_db',
      'rsrq_ie',
      'cinr_db',
      'handover_trigger',
      'rsrp_class',
    ];
    const rows = filteredMeasurements.map((m) => [
      m.id,
      `"${m.timestamp_raw}"`,
      m.latitude ?? '',
      m.longitude ?? '',
      m.speed_kmh ?? '',
      m.serving_cell_id ?? '',
      m.neighbor_cell_id ?? '',
      m.rsrp_dbm ?? '',
      m.rsrp_ie ?? '',
      m.rsrq_db ?? '',
      m.rsrq_ie ?? '',
      m.cinr_db ?? '',
      m.handover_trigger,
      m.rsrp_class,
    ]);

    const csvContent = [headers.join(','), ...rows.map((r) => r.join(','))].join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `cellscope_measurements_${Date.now()}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const getQualityBadge = (cls: string) => {
    switch (cls) {
      case 'Excellent':
        return 'bg-emerald-950 text-emerald-400 border-emerald-800';
      case 'Good':
        return 'bg-cyan-950 text-cyan-400 border-cyan-800';
      case 'Fair':
        return 'bg-amber-950 text-amber-400 border-amber-800';
      case 'Poor':
        return 'bg-rose-950 text-rose-400 border-rose-800';
      default:
        return 'bg-slate-800 text-slate-400 border-slate-700';
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-lg">
      {/* Controls Bar */}
      <div className="p-4 border-b border-slate-800 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {/* Search */}
          <div className="relative">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Search PCI or timestamp..."
              value={searchTerm}
              onChange={(e) => {
                setSearchTerm(e.target.value);
                setCurrentPage(1);
              }}
              className="bg-slate-950 text-xs font-mono pl-9 pr-3 py-1.5 rounded-lg border border-slate-700 text-slate-200 focus:outline-none focus:ring-1 focus:ring-cyan-500 w-48 sm:w-56"
            />
          </div>

          {/* Quality Filter */}
          <select
            value={qualityFilter}
            onChange={(e) => {
              setQualityFilter(e.target.value);
              setCurrentPage(1);
            }}
            className="bg-slate-950 text-xs text-slate-300 border border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500"
          >
            <option value="all">All RSRP Bands</option>
            <option value="excellent">Excellent (≥ -80 dBm)</option>
            <option value="good">Good (-80 to -90 dBm)</option>
            <option value="fair">Fair (-90 to -100 dBm)</option>
            <option value="poor">Poor (&lt; -100 dBm)</option>
          </select>

          {/* Handover Only Checkbox */}
          <label className="flex items-center gap-1.5 text-xs text-slate-300 cursor-pointer bg-slate-950 px-2.5 py-1.5 rounded-lg border border-slate-700">
            <input
              type="checkbox"
              checked={hoOnly}
              onChange={(e) => {
                setHoOnly(e.target.checked);
                setCurrentPage(1);
              }}
              className="rounded bg-slate-800 border-slate-600 text-cyan-600 focus:ring-0"
            />
            <span>HO Triggers Only</span>
          </label>
        </div>

        {/* Export & Count */}
        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-400 font-mono">
            Showing <strong className="text-white">{filteredMeasurements.length}</strong> of{' '}
            {measurements.length}
          </span>
          <button
            onClick={handleExportCsv}
            className="flex items-center gap-1.5 bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-semibold px-3 py-1.5 rounded-lg transition-colors shadow-sm"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Export Cleaned CSV</span>
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs font-mono">
          <thead className="bg-slate-950 text-slate-400 border-b border-slate-800">
            <tr>
              <th className="py-2.5 px-3">#</th>
              <th className="py-2.5 px-3">Timestamp</th>
              <th className="py-2.5 px-3">Serving PCI</th>
              <th className="py-2.5 px-3">Target PCI</th>
              <th className="py-2.5 px-3">RSRP (dBm)</th>
              <th className="py-2.5 px-3">RSRQ (dB)</th>
              <th className="py-2.5 px-3">CINR (dB)</th>
              <th className="py-2.5 px-3">Speed</th>
              <th className="py-2.5 px-3">Coordinates (Lat, Lon)</th>
              <th className="py-2.5 px-3">HO Trigger</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-800 text-slate-300">
            {paginatedData.map((m) => (
              <tr key={m.id} className="hover:bg-slate-800/40 transition-colors">
                <td className="py-2 px-3 text-slate-500">{m.id}</td>
                <td className="py-2 px-3 text-slate-200 font-semibold">{m.timestamp_raw}</td>
                <td className="py-2 px-3 text-cyan-400 font-bold">{m.serving_cell_id ?? '—'}</td>
                <td className="py-2 px-3 text-slate-400">{m.neighbor_cell_id ?? '—'}</td>
                <td className="py-2 px-3">
                  <div className="flex items-center gap-1.5">
                    <span>{m.rsrp_dbm !== null ? `${m.rsrp_dbm}` : '—'}</span>
                    <span
                      className={`text-[10px] px-1.5 py-0.2 rounded border font-sans ${getQualityBadge(
                        m.rsrp_class
                      )}`}
                    >
                      {m.rsrp_class}
                    </span>
                  </div>
                </td>
                <td className="py-2 px-3">
                  <span>{m.rsrq_db !== null ? `${m.rsrq_db}` : '—'}</span>
                </td>
                <td className="py-2 px-3 font-bold">
                  <span className={m.cinr_db !== null && m.cinr_db < 0 ? 'text-rose-400' : 'text-slate-200'}>
                    {m.cinr_db !== null ? `${m.cinr_db}` : '—'}
                  </span>
                </td>
                <td className="py-2 px-3 text-slate-400">
                  {m.speed_kmh !== null ? `${m.speed_kmh} km/h` : '—'}
                </td>
                <td className="py-2 px-3">
                  {m.has_valid_gps ? (
                    <span className="text-emerald-400 flex items-center gap-1 text-[11px]">
                      <CheckCircle2 className="w-3 h-3" />
                      {m.latitude?.toFixed(4)}, {m.longitude?.toFixed(4)}
                    </span>
                  ) : (
                    <span className="text-slate-500 flex items-center gap-1 text-[11px]">
                      <AlertCircle className="w-3 h-3" />
                      No Fix
                    </span>
                  )}
                </td>
                <td className="py-2 px-3">
                  {m.handover_trigger === 1 ? (
                    <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-bold bg-rose-950 text-rose-400 border border-rose-800">
                      ⚡ Trigger (1)
                    </span>
                  ) : (
                    <span className="text-slate-600">0</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination Footer */}
      <div className="p-3 border-t border-slate-800 bg-slate-950 flex items-center justify-between text-xs text-slate-400">
        <div>
          Page <strong className="text-white">{currentPage}</strong> of <strong>{totalPages}</strong>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
            disabled={currentPage === 1}
            className="p-1 rounded bg-slate-800 hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed text-slate-200"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <button
            onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
            disabled={currentPage === totalPages}
            className="p-1 rounded bg-slate-800 hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed text-slate-200"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  );
};
