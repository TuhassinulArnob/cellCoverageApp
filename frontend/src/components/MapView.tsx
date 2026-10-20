import React, { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import type { MeasurementPoint, ActiveMetric, DatasetSummary } from '../types/cellscope';
import { Layers, AlertCircle, Navigation, Moon, Sun } from 'lucide-react';

interface MapViewProps {
  measurements: MeasurementPoint[];
  summary: DatasetSummary | null;
  selectedCellId: number | null;
  setSelectedCellId: (id: number | null) => void;
}

export const MapView: React.FC<MapViewProps> = ({
  measurements,
  summary,
  selectedCellId,
  setSelectedCellId,
}) => {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<L.Map | null>(null);
  const markersLayerRef = useRef<L.LayerGroup | null>(null);

  const [activeMetric, setActiveMetric] = useState<ActiveMetric>('rsrp');
  const [selectedPoint, setSelectedPoint] = useState<MeasurementPoint | null>(null);
  const [isDarkBasemap, setIsDarkBasemap] = useState<boolean>(true);
  const [tileError, setTileError] = useState<boolean>(false);

  const validGpsPoints = measurements.filter((p) => p.has_valid_gps && p.latitude !== null && p.longitude !== null);

  // Available unique serving cells for dropdown
  const uniqueCells = Array.from(
    new Set(measurements.map((m) => m.serving_cell_id).filter((c): c is number => c !== null))
  ).sort((a, b) => a - b);

  // Determine marker color according to active metric
  const getMarkerColor = (point: MeasurementPoint, metric: ActiveMetric): string => {
    if (metric === 'rsrp') {
      const v = point.rsrp_dbm;
      if (v === null) return '#64748b';
      if (v >= -80) return '#10b981'; // Excellent (Green, >= -80 dBm)
      if (v >= -90) return '#84cc16'; // Good (Lime, -90 to -81 dBm)
      if (v >= -100) return '#f59e0b'; // Fair (Yellow/Amber, -100 to -91 dBm)
      return '#ef4444'; // Poor (Red, < -100 dBm)
    } else if (metric === 'rsrq') {
      const v = point.rsrq_db;
      if (v === null) return '#64748b';
      if (v >= -10) return '#10b981';
      if (v >= -14) return '#84cc16';
      if (v >= -17) return '#f59e0b';
      return '#ef4444';
    } else {
      const v = point.cinr_db;
      if (v === null) return '#64748b';
      if (v >= 20) return '#10b981';
      if (v >= 13) return '#84cc16';
      if (v >= 0) return '#f59e0b';
      return '#ef4444';
    }
  };

  // Initialize Leaflet map with standard OpenStreetMap tile endpoint
  useEffect(() => {
    if (!mapContainerRef.current) return;

    if (!mapInstanceRef.current) {
      const map = L.map(mapContainerRef.current, {
        center: [23.8103, 90.4125], // Dhaka, Bangladesh default
        zoom: 13,
        zoomControl: true,
      });

      // Standard OpenStreetMap tile endpoint complying with OSM usage policy
      const tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors',
        maxZoom: 19,
      });

      // Gracefully handle tile loading issues
      tileLayer.on('tileerror', (e) => {
        console.warn('Map tile loading error:', e);
        setTileError(true);
      });

      tileLayer.addTo(map);

      const markersGroup = L.layerGroup().addTo(map);
      markersLayerRef.current = markersGroup;
      mapInstanceRef.current = map;
    }

    return () => {
      if (mapInstanceRef.current) {
        mapInstanceRef.current.remove();
        mapInstanceRef.current = null;
      }
    };
  }, []);

  // Update markers when points, activeMetric, or selectedCellId change
  useEffect(() => {
    const map = mapInstanceRef.current;
    const layer = markersLayerRef.current;
    if (!map || !layer) return;

    layer.clearLayers();

    const displayPoints = selectedCellId !== null
      ? validGpsPoints.filter((p) => p.serving_cell_id === selectedCellId)
      : validGpsPoints;

    if (displayPoints.length === 0) return;

    const latLngs: L.LatLngExpression[] = [];

    displayPoints.forEach((point) => {
      const lat = point.latitude!;
      const lon = point.longitude!;
      latLngs.push([lat, lon]);

      const color = getMarkerColor(point, activeMetric);
      const isHo = point.handover_trigger === 1;

      const marker = L.circleMarker([lat, lon], {
        radius: isHo ? 8 : 6,
        fillColor: color,
        color: isHo ? '#ffffff' : color,
        weight: isHo ? 2 : 1,
        opacity: 0.95,
        fillOpacity: 0.85,
      });

      // Tooltip
      const valStr =
        activeMetric === 'rsrp'
          ? `${point.rsrp_dbm ?? 'N/A'} dBm`
          : activeMetric === 'rsrq'
          ? `${point.rsrq_db ?? 'N/A'} dB`
          : `${point.cinr_db ?? 'N/A'} dB`;

      marker.bindTooltip(
        `<div class="text-xs font-mono">
          <strong>PCI ${point.serving_cell_id ?? 'Unknown'}</strong>: ${valStr}
          ${isHo ? '<br/><span class="text-red-500 font-bold">⚡ HO Trigger</span>' : ''}
        </div>`,
        { direction: 'top', offset: [0, -5] }
      );

      // On click
      marker.on('click', () => {
        setSelectedPoint(point);
      });

      marker.addTo(layer);
    });

    // Auto-fit bounds
    if (latLngs.length > 0) {
      const bounds = L.latLngBounds(latLngs);
      map.fitBounds(bounds, { padding: [30, 30], maxZoom: 16 });
    }
  }, [validGpsPoints.length, activeMetric, selectedCellId]);

  const handleFitBounds = () => {
    if (!mapInstanceRef.current || validGpsPoints.length === 0) return;
    const latLngs = validGpsPoints.map((p) => [p.latitude!, p.longitude!] as [number, number]);
    mapInstanceRef.current.fitBounds(L.latLngBounds(latLngs), { padding: [30, 30] });
  };

  if (!summary?.has_gps && measurements.length > 0) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-8 text-center max-w-xl mx-auto my-12">
        <div className="w-12 h-12 bg-amber-500/10 text-amber-400 rounded-full flex items-center justify-center mx-auto mb-4 border border-amber-500/20">
          <AlertCircle className="w-6 h-6" />
        </div>
        <h3 className="text-lg font-bold text-white mb-2">No GPS Coordinates in Loaded Dataset</h3>
        <p className="text-sm text-slate-400 mb-6">
          The current dataset (<span className="text-cyan-400 font-mono">{summary?.filename}</span>) contains radio and
          handover event logs without geographic latitude/longitude fields.
        </p>
        <div className="bg-slate-800/80 rounded-lg p-4 text-xs text-left text-slate-300 space-y-2 mb-6 border border-slate-700">
          <p className="font-semibold text-slate-200">Recommended Actions:</p>
          <ul className="list-disc list-inside space-y-1 text-slate-400">
            <li>Inspect RF KPIs, distributions, and histograms in the <strong>RF Analytics</strong> tab.</li>
            <li>Switch to <strong>Dhaka Urban LTE Route 3</strong> in the <strong>Import / Presets</strong> tab to view complete GPS route mapping.</li>
          </ul>
        </div>
      </div>
    );
  }

  return (
    <div
      className={`relative flex flex-col h-[calc(100vh-140px)] min-h-[500px] w-full rounded-xl overflow-hidden border border-slate-800 bg-slate-950 ${
        isDarkBasemap ? 'cellscope-dark-tiles' : ''
      }`}
    >
      {/* Top Map Controls Bar */}
      <div className="absolute top-3 left-3 right-3 z-[1000] flex flex-wrap items-center justify-between gap-2 pointer-events-none">
        {/* Metric Switcher & KPI Badges */}
        <div className="pointer-events-auto flex flex-wrap items-center gap-2">
          <div className="flex items-center bg-slate-900/90 backdrop-blur-md p-1 rounded-lg border border-slate-700 shadow-lg text-xs font-medium">
            <span className="px-2 text-slate-400 flex items-center gap-1">
              <Layers className="w-3.5 h-3.5 text-cyan-400" /> Layer:
            </span>
            {(['rsrp', 'rsrq', 'cinr'] as ActiveMetric[]).map((m) => (
              <button
                key={m}
                onClick={() => setActiveMetric(m)}
                className={`px-3 py-1.5 rounded-md uppercase font-semibold transition-all ${
                  activeMetric === m
                    ? 'bg-cyan-600 text-white shadow-sm'
                    : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
              >
                {m === 'rsrp' ? 'RSRP (dBm)' : m === 'rsrq' ? 'RSRQ (dB)' : 'CINR (dB)'}
              </button>
            ))}
          </div>

          {/* Quick Route KPIs */}
          {summary && (
            <div className="hidden lg:flex items-center gap-3 bg-slate-900/90 backdrop-blur-md px-3 py-1.5 rounded-lg border border-slate-700 text-xs font-mono text-slate-300 shadow-lg">
              {summary.total_distance_km !== null && summary.total_distance_km !== undefined && (
                <div>
                  <span className="text-slate-500">DIST: </span>
                  <span className="text-cyan-400 font-bold">{summary.total_distance_km} km</span>
                </div>
              )}
              <div>
                <span className="text-slate-500">SAMPLES: </span>
                <span className="text-white font-bold">{validGpsPoints.length}</span>
              </div>
              {summary.mean_accuracy_m && (
                <div>
                  <span className="text-slate-500">ACC: </span>
                  <span className="text-emerald-400 font-bold">±{summary.mean_accuracy_m}m</span>
                </div>
              )}
              <div>
                <span className="text-slate-500">COMPLETENESS: </span>
                <span className="text-amber-400 font-bold">{summary.data_completeness_pct ?? 100}%</span>
              </div>
            </div>
          )}
        </div>

        {/* Filter Controls & Basemap Switcher */}
        <div className="pointer-events-auto flex items-center gap-2">
          {/* Cell Filter */}
          <select
            value={selectedCellId ?? ''}
            onChange={(e) => setSelectedCellId(e.target.value ? Number(e.target.value) : null)}
            className="bg-slate-900/90 backdrop-blur-md text-xs font-medium text-slate-200 border border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500 shadow-lg"
          >
            <option value="">All Serving Cells ({uniqueCells.length} PCIs)</option>
            {uniqueCells.map((cid) => (
              <option key={cid} value={cid}>
                PCI {cid}
              </option>
            ))}
          </select>

          {/* Basemap Dark/Light Theme Switcher */}
          <button
            onClick={() => setIsDarkBasemap(!isDarkBasemap)}
            title={isDarkBasemap ? 'Switch to Standard OpenStreetMap' : 'Switch to Dark Basemap'}
            className="p-2 bg-slate-900/90 backdrop-blur-md text-slate-300 hover:text-white border border-slate-700 rounded-lg shadow-lg hover:bg-slate-800 transition-colors"
          >
            {isDarkBasemap ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-cyan-400" />}
          </button>

          {/* Reset / Fit Bounds */}
          <button
            onClick={handleFitBounds}
            title="Auto-fit route bounds"
            className="p-2 bg-slate-900/90 backdrop-blur-md text-slate-300 hover:text-white border border-slate-700 rounded-lg shadow-lg hover:bg-slate-800 transition-colors"
          >
            <Navigation className="w-4 h-4 text-cyan-400" />
          </button>
        </div>
      </div>

      {/* Tile Loading Error Notice */}
      {tileError && (
        <div className="absolute top-16 left-3 right-3 z-[1000] bg-amber-950/90 backdrop-blur-md border border-amber-700 text-amber-300 text-xs px-3 py-2 rounded-lg flex items-center justify-between shadow-xl">
          <div className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0 text-amber-400" />
            <span>Map tile service delayed or unavailable. LTE measurement points remain fully interactive.</span>
          </div>
          <button
            onClick={() => setTileError(false)}
            className="text-amber-400 hover:text-white font-bold ml-2 text-xs"
          >
            ✕
          </button>
        </div>
      )}

      {/* Main Map Container */}
      <div ref={mapContainerRef} className="w-full h-full z-0" />

      {/* Map Legend (Bottom Left) */}
      <div className="absolute bottom-4 left-4 z-[1000] bg-slate-900/90 backdrop-blur-md p-3 rounded-lg border border-slate-700 shadow-xl text-xs max-w-xs">
        <div className="font-semibold text-slate-200 mb-2 flex items-center justify-between">
          <span>{activeMetric.toUpperCase()} Quality Bands</span>
          <span className="text-[10px] text-slate-400 font-mono">3GPP TS 36.133</span>
        </div>
        <div className="space-y-1.5">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full bg-[#10b981]" />
              <span className="text-slate-300">Excellent</span>
            </div>
            <span className="text-slate-400 font-mono">
              {activeMetric === 'rsrp' ? '≥ -80 dBm' : activeMetric === 'rsrq' ? '≥ -10 dB' : '≥ 20 dB'}
            </span>
          </div>
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full bg-[#84cc16]" />
              <span className="text-slate-300">Good</span>
            </div>
            <span className="text-slate-400 font-mono">
              {activeMetric === 'rsrp' ? '-80 to -90' : activeMetric === 'rsrq' ? '-10 to -14' : '13 to 20'}
            </span>
          </div>
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full bg-[#f59e0b]" />
              <span className="text-slate-300">Fair</span>
            </div>
            <span className="text-slate-400 font-mono">
              {activeMetric === 'rsrp' ? '-90 to -100' : activeMetric === 'rsrq' ? '-14 to -17' : '0 to 13'}
            </span>
          </div>
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full bg-[#ef4444]" />
              <span className="text-slate-300">Poor (Edge)</span>
            </div>
            <span className="text-slate-400 font-mono">
              {activeMetric === 'rsrp' ? '< -100 dBm' : activeMetric === 'rsrq' ? '< -17 dB' : '< 0 dB'}
            </span>
          </div>
        </div>
        <div className="mt-2 pt-2 border-t border-slate-800 space-y-1 text-[11px] text-slate-400">
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full border border-cyan-400 bg-cyan-400/20 inline-block" />
            <span>Cyan: Spatial Uncertainty / TA Range (Not RSRP)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full border-2 border-white bg-red-500 inline-block" />
            <span>White rim: Handover Trigger event</span>
          </div>
        </div>
      </div>

      {/* Point Inspector Modal / Card (Bottom Right) */}
      {selectedPoint && (
        <div className="absolute bottom-4 right-4 z-[1000] bg-slate-900/95 backdrop-blur-md p-4 rounded-xl border border-slate-700 shadow-2xl text-xs w-88 max-w-[92vw]">
          <div className="flex items-center justify-between pb-2 border-b border-slate-800 mb-3">
            <div className="flex items-center gap-2">
              <span className="font-bold text-white text-sm">Point #{selectedPoint.id}</span>
              <span className="px-1.5 py-0.5 text-[10px] font-semibold bg-cyan-950 text-cyan-400 border border-cyan-800 rounded">
                {selectedPoint.network_gen || '4G LTE'}
              </span>
            </div>
            <button
              onClick={() => setSelectedPoint(null)}
              className="text-slate-400 hover:text-white px-1 font-bold"
            >
              ✕
            </button>
          </div>
          <div className="grid grid-cols-2 gap-2 text-slate-300 font-mono">
            <div>
              <span className="text-slate-500 text-[10px] block">TIMESTAMP</span>
              <span>{selectedPoint.timestamp_raw}</span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">SPEED & HEADING</span>
              <span>
                {selectedPoint.speed_kmh !== null ? `${selectedPoint.speed_kmh} km/h` : '0 km/h'}
                {selectedPoint.heading_deg !== null && selectedPoint.heading_deg !== undefined ? ` • ${Math.round(selectedPoint.heading_deg)}°` : ''}
              </span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">SERVING PCI</span>
              <span className="text-cyan-400 font-bold">PCI {selectedPoint.serving_cell_id ?? 'N/A'}</span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">OPERATOR & TAC</span>
              <span className="text-slate-200">
                {selectedPoint.operator || 'Telecom'}
                {selectedPoint.tac ? ` (TAC ${selectedPoint.tac})` : ''}
              </span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">EARFCN / ARFCN</span>
              <span className="text-slate-300">{selectedPoint.earfcn ? `CH ${selectedPoint.earfcn}` : 'Standard'}</span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">NEIGHBOR PCI</span>
              <span className="text-slate-400">{selectedPoint.neighbor_cell_id ? `PCI ${selectedPoint.neighbor_cell_id}` : 'None'}</span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">RSRP (POWER)</span>
              <span className="font-bold text-emerald-400">
                {selectedPoint.rsrp_dbm !== null ? `${selectedPoint.rsrp_dbm} dBm` : 'N/A'}
              </span>
              <span className="text-slate-500 text-[10px] block">{selectedPoint.rsrp_class}</span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">RSRQ (QUALITY)</span>
              <span className="font-bold text-sky-400">
                {selectedPoint.rsrq_db !== null ? `${selectedPoint.rsrq_db} dB` : 'N/A'}
              </span>
              <span className="text-slate-500 text-[10px] block">{selectedPoint.rsrq_class}</span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">CINR / SINR</span>
              <span className="font-bold text-amber-400">
                {selectedPoint.cinr_db !== null ? `${selectedPoint.cinr_db} dB` : 'N/A'}
              </span>
            </div>
            <div>
              <span className="text-slate-500 text-[10px] block">HO TRIGGER</span>
              <span className={selectedPoint.handover_trigger ? 'text-red-400 font-bold' : 'text-slate-400'}>
                {selectedPoint.handover_trigger ? '⚡ YES (Active)' : 'NO (0)'}
              </span>
            </div>
          </div>
          <div className="mt-3 pt-2 border-t border-slate-800 text-[10px] text-slate-500 flex justify-between">
            <span>Lat: {selectedPoint.latitude?.toFixed(5)}</span>
            <span>Lon: {selectedPoint.longitude?.toFixed(5)}</span>
            {selectedPoint.accuracy_m && <span>Acc: ±{Math.round(selectedPoint.accuracy_m)}m</span>}
          </div>
        </div>
      )}
    </div>
  );
};
