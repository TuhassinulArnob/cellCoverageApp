import React, { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import L from 'leaflet';
import type {
  MeasurementPoint,
  ActiveMetric,
  DatasetSummary,
  ExtendedDatasetAnalysis,
  MapOverlayState,
  EstimatedCellSite,
  QoeTestRecord,
} from '../types/cellscope';
import { estimateCellSites, resolveEarfcn, timingAdvanceToMeters } from '../utils/telecom';
import { useTheme } from '../context/useTheme';
import {
  Layers,
  AlertCircle,
  Navigation,
  Moon,
  Sun,
  Filter,
  Radio,
  Zap,
  TrendingDown,
  Route,
  Activity,
} from 'lucide-react';

interface MapViewProps {
  measurements: MeasurementPoint[];
  summary: DatasetSummary | null;
  extendedAnalysis: ExtendedDatasetAnalysis | null;
  qoeTests?: QoeTestRecord[];
  selectedCellId: number | null;
  setSelectedCellId: (id: number | null) => void;
  onOpenFilters?: () => void;
  activeFilterCount?: number;
  focusedCoordinate?: { lat: number; lon: number } | null;
}

export const MapView: React.FC<MapViewProps> = ({
  measurements,
  summary,
  extendedAnalysis,
  qoeTests = [],
  selectedCellId,
  setSelectedCellId,
  onOpenFilters,
  activeFilterCount = 0,
  focusedCoordinate,
}) => {
  const { theme } = useTheme();
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapInstanceRef = useRef<L.Map | null>(null);

  // Dedicated layer groups for multi-layer rendering
  const routeTrackLayerRef = useRef<L.LayerGroup | null>(null);
  const weakZonesLayerRef = useRef<L.LayerGroup | null>(null);
  const handoversLayerRef = useRef<L.LayerGroup | null>(null);
  const cellSitesLayerRef = useRef<L.LayerGroup | null>(null);
  const pointsLayerRef = useRef<L.LayerGroup | null>(null);
  const qoeTestsLayerRef = useRef<L.LayerGroup | null>(null);

  const [activeMetric, setActiveMetric] = useState<ActiveMetric>('rsrp');
  const [selectedPoint, setSelectedPoint] = useState<MeasurementPoint | null>(null);
  const [selectedSite, setSelectedSite] = useState<EstimatedCellSite | null>(null);
  const [prevTheme, setPrevTheme] = useState(theme);
  const [basemapOverride, setBasemapOverride] = useState<boolean | null>(null);
  const [tileError, setTileError] = useState<boolean>(false);

  // If the global theme changes, reset the manual basemap override so map syncs with global theme
  if (prevTheme !== theme) {
    setPrevTheme(theme);
    setBasemapOverride(null);
  }

  const isDarkBasemap = basemapOverride !== null ? basemapOverride : (theme === 'dark');

  // Layer Overlay visibility states
  const [overlays, setOverlays] = useState<MapOverlayState>({
    routeTrack: true,
    weakZones: true,
    handovers: true,
    cellSites: true,
    taUncertainty: false,
    qoeTests: true,
  });

  const validGpsPoints = useMemo(
    () => measurements.filter((p) => p.has_valid_gps && p.latitude !== null && p.longitude !== null),
    [measurements]
  );

  // Available unique serving cells for dropdown
  const uniqueCells = useMemo(
    () =>
      Array.from(
        new Set(measurements.map((m) => m.serving_cell_id).filter((c): c is number => c !== null))
      ).sort((a, b) => a - b),
    [measurements]
  );

  // Estimated cell sites computed from GPS measurements
  const estimatedSites = useMemo(() => estimateCellSites(validGpsPoints), [validGpsPoints]);

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
    } else if (metric === 'cinr') {
      const v = point.cinr_db;
      if (v === null) return '#64748b';
      if (v >= 20) return '#10b981';
      if (v >= 13) return '#84cc16';
      if (v >= 0) return '#f59e0b';
      return '#ef4444';
    } else if (metric === 'pci') {
      if (point.serving_cell_id === null) return '#64748b';
      const shift = ((point.serving_cell_id % 3) + 3) % 3;
      if (shift === 0) return '#06b6d4'; // Cyan
      if (shift === 1) return '#a855f7'; // Purple
      return '#f97316'; // Orange
    } else if (metric === 'ta') {
      const ta = point.timing_advance;
      if (ta === null || ta === undefined) return '#64748b';
      if (ta <= 2) return '#10b981';
      if (ta <= 5) return '#84cc16';
      if (ta <= 10) return '#f59e0b';
      return '#ef4444';
    } else if (metric === 'speed') {
      const s = point.speed_kmh;
      if (s === null) return '#64748b';
      if (s < 10) return '#38bdf8';
      if (s < 40) return '#10b981';
      if (s < 70) return '#f59e0b';
      return '#ef4444';
    } else {
      const gen = point.network_gen || '';
      if (gen.includes('5G')) return '#a855f7';
      if (gen.includes('4G')) return '#0ea5e9';
      if (gen.includes('3G')) return '#f59e0b';
      return '#94a3b8';
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

      const tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors',
        maxZoom: 19,
      });

      tileLayer.on('tileerror', (e) => {
        console.warn('Map tile loading error:', e);
        setTileError(true);
      });

      tileLayer.addTo(map);

      // Initialize layer groups in z-order: track -> weak zones -> points -> handovers -> cell sites -> QoE tests
      routeTrackLayerRef.current = L.layerGroup().addTo(map);
      weakZonesLayerRef.current = L.layerGroup().addTo(map);
      pointsLayerRef.current = L.layerGroup().addTo(map);
      handoversLayerRef.current = L.layerGroup().addTo(map);
      cellSitesLayerRef.current = L.layerGroup().addTo(map);
      qoeTestsLayerRef.current = L.layerGroup().addTo(map);

      mapInstanceRef.current = map;
    }

    return () => {
      if (mapInstanceRef.current) {
        mapInstanceRef.current.remove();
        mapInstanceRef.current = null;
      }
    };
  }, []);

  // Update Route Track Polyline Layer
  useEffect(() => {
    const trackLayer = routeTrackLayerRef.current;
    if (!trackLayer) return;
    trackLayer.clearLayers();

    if (!overlays.routeTrack || validGpsPoints.length < 2) return;

    const latLngs = validGpsPoints.map((p) => [p.latitude!, p.longitude!] as [number, number]);

    // Outer subtle glow
    const glowPolyline = L.polyline(latLngs, {
      color: '#0891b2',
      weight: 6,
      opacity: 0.35,
      lineCap: 'round',
      lineJoin: 'round',
    });

    // Inner sharp track
    const mainPolyline = L.polyline(latLngs, {
      color: '#06b6d4',
      weight: 2.5,
      opacity: 0.85,
      dashArray: '4, 4',
      lineCap: 'round',
    });

    mainPolyline.bindTooltip(
      `<div class="text-xs font-mono">
        <strong>Survey Route Track</strong><br/>
        Points: ${validGpsPoints.length}<br/>
        Distance: ${summary?.total_distance_km ? `${summary.total_distance_km} km` : '—'}
      </div>`,
      { sticky: true }
    );

    glowPolyline.addTo(trackLayer);
    mainPolyline.addTo(trackLayer);
  }, [validGpsPoints, overlays.routeTrack, summary?.total_distance_km]);

  // Update Weak Coverage Zones Layer
  useEffect(() => {
    const weakLayer = weakZonesLayerRef.current;
    if (!weakLayer) return;
    weakLayer.clearLayers();

    if (!overlays.weakZones || !extendedAnalysis?.weak_segments) return;

    extendedAnalysis.weak_segments.forEach((seg) => {
      if (seg.start_lat === null || seg.start_lon === null || seg.end_lat === null || seg.end_lon === null) {
        return;
      }

      const corridor = L.polyline(
        [
          [seg.start_lat, seg.start_lon],
          [seg.end_lat, seg.end_lon],
        ],
        {
          color: '#f97316',
          weight: 8,
          opacity: 0.7,
          lineCap: 'round',
        }
      );

      const startCircle = L.circleMarker([seg.start_lat, seg.start_lon], {
        radius: 10,
        fillColor: '#ea580c',
        color: '#ffffff',
        weight: 2,
        opacity: 0.9,
        fillOpacity: 0.8,
      });

      const tooltipContent = `
        <div class="text-xs font-mono p-1">
          <div class="font-bold text-amber-500 dark:text-amber-400 flex items-center gap-1">⚠️ Weak Coverage Zone #${seg.id}</div>
          <div class="mt-1">Samples: <strong>${seg.sample_count}</strong></div>
          <div>Min RSRP: <strong class="text-red-500 dark:text-red-400">${seg.min_rsrp_dbm} dBm</strong></div>
          <div>Mean RSRP: <strong class="text-amber-500 dark:text-amber-300">${seg.mean_rsrp_dbm} dBm</strong></div>
          <div>Serving PCI: <strong>${seg.pci ?? 'N/A'}</strong></div>
          <div class="text-[10px] text-slate-500 dark:text-slate-400 mt-1 italic">${seg.reason}</div>
        </div>
      `;

      corridor.bindTooltip(tooltipContent, { sticky: true });
      startCircle.bindTooltip(tooltipContent, { direction: 'top', offset: [0, -6] });

      corridor.addTo(weakLayer);
      startCircle.addTo(weakLayer);
    });
  }, [extendedAnalysis?.weak_segments, overlays.weakZones]);

  // Update Handover Transition Markers Layer
  useEffect(() => {
    const hoLayer = handoversLayerRef.current;
    if (!hoLayer) return;
    hoLayer.clearLayers();

    if (!overlays.handovers) return;

    const hoPoints = validGpsPoints.filter((p) => p.handover_trigger === 1);

    hoPoints.forEach((point) => {
      const lat = point.latitude!;
      const lon = point.longitude!;

      const hoIcon = L.divIcon({
        className: 'cellscope-ho-marker',
        html: `
          <div style="
            width: 20px;
            height: 20px;
            background: #ef4444;
            border: 2px solid #ffffff;
            box-shadow: 0 0 8px rgba(239, 68, 68, 0.9);
            transform: rotate(45deg);
            display: flex;
            align-items: center;
            justify-content: center;
            border-radius: 3px;
          ">
            <span style="
              transform: rotate(-45deg);
              color: white;
              font-size: 10px;
              font-weight: bold;
              line-height: 1;
            ">⚡</span>
          </div>
        `,
        iconSize: [20, 20],
        iconAnchor: [10, 10],
      });

      const marker = L.marker([lat, lon], { icon: hoIcon });

      marker.bindTooltip(
        `<div class="text-xs font-mono p-1">
          <div class="font-bold text-red-500 dark:text-red-400 flex items-center gap-1">⚡ Handover Event</div>
          <div class="mt-1">Serving PCI: <strong>${point.serving_cell_id ?? 'Unknown'}</strong></div>
          ${point.neighbor_cell_id ? `<div class="text-cyan-600 dark:text-cyan-300">Target/Neighbor PCI: <strong>${point.neighbor_cell_id}</strong></div>` : ''}
          <div>RSRP: <strong>${point.rsrp_dbm ?? 'N/A'} dBm</strong></div>
          <div class="text-slate-500 dark:text-slate-400 text-[10px] mt-1">${point.timestamp_raw}</div>
        </div>`,
        { direction: 'top', offset: [0, -10] }
      );

      marker.on('click', () => {
        setSelectedPoint(point);
      });

      marker.addTo(hoLayer);
    });
  }, [validGpsPoints, overlays.handovers]);

  // Update Estimated Cell Sites Layer
  useEffect(() => {
    const siteLayer = cellSitesLayerRef.current;
    if (!siteLayer) return;
    siteLayer.clearLayers();

    if (!overlays.cellSites) return;

    const isDark = isDarkBasemap;

    estimatedSites.forEach((site) => {
      const towerIcon = L.divIcon({
        className: 'cellscope-tower-marker',
        html: `
          <div style="
            width: 32px;
            height: 32px;
            background: ${isDark ? '#0f172a' : '#ffffff'};
            border: 2px solid ${isDark ? '#06b6d4' : '#0891b2'};
            box-shadow: 0 2px 10px ${isDark ? 'rgba(6, 182, 212, 0.6)' : 'rgba(8, 145, 178, 0.4)'};
            border-radius: 8px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            cursor: pointer;
          ">
            <span style="font-size: 13px; line-height: 1;">📡</span>
            <span style="color: ${isDark ? '#38bdf8' : '#0284c7'}; font-size: 8px; font-weight: bold; font-family: monospace;">${site.pci}</span>
          </div>
        `,
        iconSize: [32, 32],
        iconAnchor: [16, 16],
      });

      const towerMarker = L.marker([site.latitude, site.longitude], { icon: towerIcon });

      const coverageCircle = L.circle([site.latitude, site.longitude], {
        radius: site.uncertaintyRadiusMeters,
        color: isDark ? '#06b6d4' : '#0891b2',
        weight: 1.5,
        dashArray: '6, 6',
        fillColor: isDark ? '#06b6d4' : '#0891b2',
        fillOpacity: isDark ? 0.08 : 0.06,
      });

      const bandRes = resolveEarfcn(site.earfcn);
      const bandLabel = bandRes ? `Band ${bandRes.bandNumber} (${bandRes.dlFrequencyMhz} MHz)` : 'Standard';

      towerMarker.bindTooltip(
        `<div class="text-xs font-mono p-1">
          <div class="font-bold text-cyan-600 dark:text-cyan-400 flex items-center gap-1">📡 Estimated Site: PCI ${site.pci}</div>
          <div class="mt-1">Carrier: <strong>${bandLabel}</strong></div>
          <div>Observed Samples: <strong>${site.sampleCount}</strong></div>
          <div>Peak RSRP: <strong class="text-emerald-600 dark:text-emerald-400">${site.maxRsrp} dBm</strong></div>
          <div>Mean RSRP: <strong>${site.meanRsrp} dBm</strong></div>
          <div class="text-slate-500 dark:text-slate-400 text-[10px] mt-1">Coverage Radius: ~${site.uncertaintyRadiusMeters}m</div>
        </div>`,
        { direction: 'top', offset: [0, -16] }
      );

      towerMarker.on('click', () => {
        setSelectedSite(site);
      });

      coverageCircle.addTo(siteLayer);
      towerMarker.addTo(siteLayer);
    });
  }, [estimatedSites, overlays.cellSites, isDarkBasemap]);

  // Update Measurement Points Layer
  useEffect(() => {
    const pointsLayer = pointsLayerRef.current;
    if (!pointsLayer) return;
    pointsLayer.clearLayers();

    const displayPoints =
      selectedCellId !== null
        ? validGpsPoints.filter((p) => p.serving_cell_id === selectedCellId)
        : validGpsPoints;

    if (displayPoints.length === 0) return;

    displayPoints.forEach((point) => {
      const lat = point.latitude!;
      const lon = point.longitude!;
      const color = getMarkerColor(point, activeMetric);
      const isHo = point.handover_trigger === 1;

      const marker = L.circleMarker([lat, lon], {
        radius: isHo ? 7 : 5,
        fillColor: color,
        color: isHo ? '#ffffff' : color,
        weight: isHo ? 2 : 1,
        opacity: 0.95,
        fillOpacity: 0.85,
      });

      const valStr =
        activeMetric === 'rsrp'
          ? `${point.rsrp_dbm ?? 'N/A'} dBm`
          : activeMetric === 'rsrq'
          ? `${point.rsrq_db ?? 'N/A'} dB`
          : activeMetric === 'cinr'
          ? `${point.cinr_db ?? 'N/A'} dB`
          : activeMetric === 'pci'
          ? `PCI ${point.serving_cell_id ?? 'N/A'} (Shift ${point.serving_cell_id ? ((point.serving_cell_id % 3) + 3) % 3 : '—'})`
          : activeMetric === 'ta'
          ? `TA ${point.timing_advance ?? 'N/A'} (${timingAdvanceToMeters(point.timing_advance) ? `~${timingAdvanceToMeters(point.timing_advance)}m` : 'N/A'})`
          : activeMetric === 'speed'
          ? `${point.speed_kmh ?? '0'} km/h`
          : `${point.network_gen || '4G'}`;

      marker.bindTooltip(
        `<div class="text-xs font-mono">
          <strong>PCI ${point.serving_cell_id ?? 'Unknown'}</strong>: ${valStr}
          ${isHo ? '<br/><span class="text-red-500 font-bold">⚡ HO Trigger</span>' : ''}
        </div>`,
        { direction: 'top', offset: [0, -5] }
      );

      marker.on('click', () => {
        setSelectedPoint(point);
      });

      marker.addTo(pointsLayer);

      if (overlays.taUncertainty && point.timing_advance !== null && point.timing_advance !== undefined) {
        const radiusMeters = timingAdvanceToMeters(point.timing_advance);
        if (radiusMeters && radiusMeters > 0) {
          const taCircle = L.circle([lat, lon], {
            radius: radiusMeters,
            color: '#38bdf8',
            weight: 1,
            dashArray: '3, 3',
            fillColor: '#38bdf8',
            fillOpacity: 0.04,
          });
          taCircle.addTo(pointsLayer);
        }
      }
    });
  }, [validGpsPoints, activeMetric, selectedCellId, overlays.taUncertainty]);

  // Update QoE Test Markers Layer
  useEffect(() => {
    const qoeLayer = qoeTestsLayerRef.current;
    if (!qoeLayer) return;
    qoeLayer.clearLayers();

    if (!overlays.qoeTests || !qoeTests || qoeTests.length === 0) return;

    qoeTests.forEach((test) => {
      if (test.latitude === null || test.longitude === null) return;

      const lat = test.latitude;
      const lon = test.longitude;
      const isSuccess = test.test_status === 'SUCCESS' || (!test.test_status && (test.download_mbps !== null || test.ping_latency_ms !== null));
      const isFailed = test.test_status === 'FAILED' || test.test_status === 'ERROR';

      const badgeColor = isSuccess ? '#10b981' : isFailed ? '#ef4444' : '#06b6d4';
      const qoeIcon = L.divIcon({
        className: 'cellscope-qoe-marker',
        html: `
          <div style="
            width: 26px;
            height: 26px;
            background: ${badgeColor};
            border: 2px solid #ffffff;
            box-shadow: 0 0 8px ${badgeColor}bb;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
          ">
            <span style="color: white; font-size: 11px; font-weight: bold; line-height: 1;">⚡</span>
          </div>
        `,
        iconSize: [26, 26],
        iconAnchor: [13, 13],
      });

      const marker = L.marker([lat, lon], { icon: qoeIcon });

      const dlStr = test.download_mbps !== null ? `${test.download_mbps.toFixed(2)} Mbps` : 'N/A';
      const ulStr = test.upload_mbps !== null ? `${test.upload_mbps.toFixed(2)} Mbps` : 'N/A';
      const pingStr = test.ping_latency_ms !== null ? `${Math.round(test.ping_latency_ms)} ms` : 'N/A';
      const jitterStr = test.jitter_ms !== null ? `${test.jitter_ms.toFixed(1)} ms` : '—';
      const lossStr = test.packet_loss_pct !== null ? `${test.packet_loss_pct.toFixed(1)}%` : '—';

      marker.bindTooltip(
        `<div class="text-xs font-mono p-1">
          <div class="font-bold flex items-center gap-1" style="color: ${badgeColor}">
            ⚡ Active QoE Test #${test.id} (${test.test_type || 'SPEED_TEST'})
          </div>
          <div class="mt-1">Status: <strong class="${isSuccess ? 'text-emerald-500' : 'text-red-500'}">${test.test_status || 'RECORDED'}</strong></div>
          <div class="text-cyan-600 dark:text-cyan-400">Download: <strong>${dlStr}</strong></div>
          <div class="text-indigo-600 dark:text-indigo-400">Upload: <strong>${ulStr}</strong></div>
          <div>Ping RTT: <strong>${pingStr}</strong> | Jitter: <strong>${jitterStr}</strong></div>
          <div>Packet Loss: <strong>${lossStr}</strong></div>
          ${test.serving_rat || test.operator ? `<div class="text-slate-500 dark:text-slate-400 text-[10px] mt-1">${test.serving_rat || ''} ${test.operator || ''} ${test.serving_pci !== null && test.serving_pci !== undefined ? `(PCI ${test.serving_pci})` : ''}</div>` : ''}
          <div class="text-slate-400 text-[9px] mt-0.5">${test.timestamp_raw || ''}</div>
        </div>`,
        { direction: 'top', offset: [0, -14] }
      );

      marker.addTo(qoeLayer);
    });
  }, [qoeTests, overlays.qoeTests]);

  // Center/pan map when a coordinate is selected from external components (e.g. QoE table)
  useEffect(() => {
    if (focusedCoordinate && mapInstanceRef.current) {
      mapInstanceRef.current.setView([focusedCoordinate.lat, focusedCoordinate.lon], 16, { animate: true });
    }
  }, [focusedCoordinate]);

  // Fit bounds when valid GPS points change or reset button is clicked
  const handleFitBounds = useCallback(() => {
    if (!mapInstanceRef.current || validGpsPoints.length === 0) return;
    const latLngs = validGpsPoints.map((p) => [p.latitude!, p.longitude!] as [number, number]);
    mapInstanceRef.current.fitBounds(L.latLngBounds(latLngs), { padding: [40, 40], maxZoom: 16 });
  }, [validGpsPoints]);

  const hasGpsPoints = validGpsPoints.length > 0;
  useEffect(() => {
    if (mapInstanceRef.current && hasGpsPoints) {
      handleFitBounds();
    }
  }, [hasGpsPoints, handleFitBounds]);

  if (!summary?.has_gps && measurements.length > 0) {
    return (
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 text-center max-w-xl mx-auto my-12 shadow-sm transition-colors duration-150">
        <div className="w-12 h-12 bg-amber-500/10 text-amber-500 dark:text-amber-400 rounded-full flex items-center justify-center mx-auto mb-4 border border-amber-500/20">
          <AlertCircle className="w-6 h-6" />
        </div>
        <h3 className="text-lg font-bold text-slate-900 dark:text-white mb-2">No GPS Coordinates in Loaded Dataset</h3>
        <p className="text-sm text-slate-600 dark:text-slate-400 mb-6">
          The current dataset (<span className="text-cyan-600 dark:text-cyan-400 font-mono">{summary?.filename}</span>) contains radio and
          handover event logs without geographic latitude/longitude fields.
        </p>
        <div className="bg-slate-50 dark:bg-slate-800/80 rounded-lg p-4 text-xs text-left text-slate-700 dark:text-slate-300 space-y-2 mb-6 border border-slate-200 dark:border-slate-700">
          <p className="font-semibold text-slate-900 dark:text-slate-200">Recommended Actions:</p>
          <ul className="list-disc list-inside space-y-1 text-slate-600 dark:text-slate-400">
            <li>Inspect RF KPIs, distributions, and histograms in the <strong>RF Analytics</strong> tab.</li>
            <li>Switch to <strong>Dhaka Urban LTE Route 3</strong> in the <strong>Import / Presets</strong> tab to view complete GPS route mapping.</li>
          </ul>
        </div>
      </div>
    );
  }

  return (
    <div
      className={`relative flex flex-col h-[calc(100vh-140px)] min-h-[550px] w-full rounded-xl overflow-hidden border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950 shadow-sm transition-colors duration-150 ${
        isDarkBasemap ? 'cellscope-dark-tiles' : 'cellscope-light-tiles'
      }`}
    >
      {/* Top Map Controls Bar */}
      <div className="absolute top-3 left-3 right-3 z-[1000] flex flex-wrap items-center justify-between gap-2 pointer-events-none">
        {/* Metric Switcher */}
        <div className="pointer-events-auto flex flex-wrap items-center gap-2">
          <div className="flex items-center bg-white/95 dark:bg-slate-900/95 backdrop-blur-md p-1 rounded-lg border border-slate-200 dark:border-slate-700 shadow-xl text-xs font-medium">
            <span className="px-2 text-slate-500 dark:text-slate-400 flex items-center gap-1">
              <Layers className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" /> Metric:
            </span>
            {[
              { id: 'rsrp', label: 'RSRP (dBm)' },
              { id: 'rsrq', label: 'RSRQ (dB)' },
              { id: 'cinr', label: 'CINR / SINR (dB)' },
              { id: 'pci', label: 'PCI Mod-3' },
              { id: 'ta', label: 'TA Range' },
              { id: 'speed', label: 'Speed' },
            ].map((m) => (
              <button
                key={m.id}
                onClick={() => setActiveMetric(m.id as ActiveMetric)}
                className={`px-2.5 py-1.5 rounded-md uppercase font-semibold text-[11px] transition-all ${
                  activeMetric === m.id
                    ? 'bg-cyan-600 text-white shadow-sm'
                    : 'text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800'
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>

          {/* Layer Visibility Toggles Menu */}
          <div className="flex items-center gap-1 bg-white/95 dark:bg-slate-900/95 backdrop-blur-md p-1 rounded-lg border border-slate-200 dark:border-slate-700 shadow-xl text-xs">
            <button
              onClick={() => setOverlays((prev) => ({ ...prev, routeTrack: !prev.routeTrack }))}
              title="Toggle Route Track Polyline"
              className={`px-2 py-1 rounded flex items-center gap-1 transition-colors text-[11px] ${
                overlays.routeTrack
                  ? 'bg-cyan-100 text-cyan-800 border border-cyan-300 dark:bg-cyan-950 dark:text-cyan-300 dark:border-cyan-800'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Route className="w-3 h-3" />
              <span>Route</span>
            </button>

            <button
              onClick={() => setOverlays((prev) => ({ ...prev, weakZones: !prev.weakZones }))}
              title="Toggle Weak Coverage Zones"
              className={`px-2 py-1 rounded flex items-center gap-1 transition-colors text-[11px] ${
                overlays.weakZones
                  ? 'bg-amber-100 text-amber-800 border border-amber-300 dark:bg-amber-950 dark:text-amber-300 dark:border-amber-800'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <TrendingDown className="w-3 h-3 text-amber-500 dark:text-amber-400" />
              <span>Weak Zones</span>
            </button>

            <button
              onClick={() => setOverlays((prev) => ({ ...prev, handovers: !prev.handovers }))}
              title="Toggle Handover Transition Badges"
              className={`px-2 py-1 rounded flex items-center gap-1 transition-colors text-[11px] ${
                overlays.handovers
                  ? 'bg-rose-100 text-rose-800 border border-rose-300 dark:bg-rose-950 dark:text-rose-300 dark:border-rose-800'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Zap className="w-3 h-3 text-rose-500 dark:text-rose-400" />
              <span>Handovers</span>
            </button>

            <button
              onClick={() => setOverlays((prev) => ({ ...prev, cellSites: !prev.cellSites }))}
              title="Toggle Estimated Cell Towers"
              className={`px-2 py-1 rounded flex items-center gap-1 transition-colors text-[11px] ${
                overlays.cellSites
                  ? 'bg-sky-100 text-sky-800 border border-sky-300 dark:bg-sky-950 dark:text-sky-300 dark:border-sky-800'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Radio className="w-3 h-3 text-sky-500 dark:text-sky-400" />
              <span>Cell Sites</span>
            </button>

            <button
              onClick={() => setOverlays((prev) => ({ ...prev, qoeTests: !prev.qoeTests }))}
              title="Toggle Active Speed & QoE Test Markers"
              className={`px-2 py-1 rounded flex items-center gap-1 transition-colors text-[11px] ${
                overlays.qoeTests
                  ? 'bg-emerald-100 text-emerald-800 border border-emerald-300 dark:bg-emerald-950 dark:text-emerald-300 dark:border-emerald-800'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Activity className="w-3 h-3 text-emerald-500 dark:text-emerald-400" />
              <span>Speed Tests {qoeTests.length > 0 ? `(${qoeTests.length})` : ''}</span>
            </button>
          </div>
        </div>

        {/* Action Controls & Basemap Switcher */}
        <div className="pointer-events-auto flex items-center gap-2">
          {/* Quick Serving PCI Filter */}
          <select
            value={selectedCellId ?? ''}
            onChange={(e) => setSelectedCellId(e.target.value ? Number(e.target.value) : null)}
            className="bg-white/95 dark:bg-slate-900/95 backdrop-blur-md text-xs font-medium text-slate-800 dark:text-slate-200 border border-slate-200 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-cyan-500 shadow-xl font-mono"
          >
            <option value="">All Serving PCIs ({uniqueCells.length})</option>
            {uniqueCells.map((cid) => (
              <option key={cid} value={cid}>
                PCI {cid}
              </option>
            ))}
          </select>

          {/* Full Filter Drawer Toggle Button */}
          {onOpenFilters && (
            <button
              onClick={onOpenFilters}
              title="Open Advanced Telecom Filters"
              className={`px-3 py-1.5 rounded-lg border text-xs font-medium flex items-center gap-1.5 shadow-xl transition-all ${
                activeFilterCount > 0
                  ? 'bg-cyan-600 border-cyan-400 text-white font-bold'
                  : 'bg-white/95 dark:bg-slate-900/95 border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
            >
              <Filter className="w-3.5 h-3.5" />
              <span>Filters</span>
              {activeFilterCount > 0 && (
                <span className="w-4 h-4 rounded-full bg-cyan-700 text-white text-[10px] font-bold flex items-center justify-center">
                  {activeFilterCount}
                </span>
              )}
            </button>
          )}

          {/* Basemap Dark/Light Theme Switcher */}
          <button
            onClick={() => setBasemapOverride(!isDarkBasemap)}
            title={isDarkBasemap ? 'Switch to Standard OpenStreetMap' : 'Switch to Dark Basemap'}
            className="p-2 bg-white/95 dark:bg-slate-900/95 backdrop-blur-md text-slate-700 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white border border-slate-200 dark:border-slate-700 rounded-lg shadow-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            {isDarkBasemap ? <Sun className="w-4 h-4 text-amber-500" /> : <Moon className="w-4 h-4 text-slate-700" />}
          </button>

          {/* Reset / Fit Bounds */}
          <button
            onClick={handleFitBounds}
            title="Auto-fit route bounds"
            className="p-2 bg-white/95 dark:bg-slate-900/95 backdrop-blur-md text-slate-700 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white border border-slate-200 dark:border-slate-700 rounded-lg shadow-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <Navigation className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
          </button>
        </div>
      </div>

      {/* Tile Loading Error Notice */}
      {tileError && (
        <div className="absolute top-16 left-3 right-3 z-[1000] bg-amber-50 dark:bg-amber-950/90 backdrop-blur-md border border-amber-300 dark:border-amber-700 text-amber-800 dark:text-amber-300 text-xs px-3 py-2 rounded-lg flex items-center justify-between shadow-xl">
          <div className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0 text-amber-600 dark:text-amber-400" />
            <span>Map tile service delayed or unavailable. LTE measurement points remain fully interactive.</span>
          </div>
          <button
            onClick={() => setTileError(false)}
            className="text-amber-700 dark:text-amber-400 hover:text-amber-900 dark:hover:text-white font-bold ml-2 text-xs"
          >
            ✕
          </button>
        </div>
      )}

      {/* Main Map Container */}
      <div ref={mapContainerRef} className="w-full h-full z-0" />

      {/* Multi-Layer Map Legend (Bottom Left) */}
      <div className="absolute bottom-4 left-4 z-[1000] bg-white/95 dark:bg-slate-900/95 backdrop-blur-md p-3.5 rounded-xl border border-slate-200 dark:border-slate-700 shadow-2xl text-xs max-w-xs text-slate-800 dark:text-slate-200 transition-colors duration-150">
        <div className="font-semibold text-slate-900 dark:text-slate-200 mb-2 flex items-center justify-between">
          <span className="uppercase text-[11px] font-bold text-cyan-700 dark:text-cyan-400">{activeMetric} Scale</span>
          <span className="text-[10px] text-slate-500 dark:text-slate-400 font-mono">3GPP TS 36.133</span>
        </div>

        {activeMetric === 'rsrp' && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#10b981]" />
                <span className="text-slate-700 dark:text-slate-300">Excellent</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">≥ -80 dBm</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#84cc16]" />
                <span className="text-slate-700 dark:text-slate-300">Good</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">-80 to -90</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#f59e0b]" />
                <span className="text-slate-700 dark:text-slate-300">Fair</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">-90 to -100</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#ef4444]" />
                <span className="text-slate-700 dark:text-slate-300">Poor (Edge)</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">&lt; -100 dBm</span>
            </div>
          </div>
        )}

        {activeMetric === 'rsrq' && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#10b981]" />
                <span className="text-slate-700 dark:text-slate-300">Excellent</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">≥ -10 dB</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#84cc16]" />
                <span className="text-slate-700 dark:text-slate-300">Good</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">-10 to -14 dB</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#f59e0b]" />
                <span className="text-slate-700 dark:text-slate-300">Fair</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">-14 to -17 dB</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#ef4444]" />
                <span className="text-slate-700 dark:text-slate-300">Poor (Interference)</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">&lt; -17 dB</span>
            </div>
          </div>
        )}

        {activeMetric === 'cinr' && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#10b981]" />
                <span className="text-slate-700 dark:text-slate-300">Excellent</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">≥ 20 dB</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#84cc16]" />
                <span className="text-slate-700 dark:text-slate-300">Good</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">13 to 20 dB</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#f59e0b]" />
                <span className="text-slate-700 dark:text-slate-300">Fair</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">0 to 13 dB</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#ef4444]" />
                <span className="text-slate-700 dark:text-slate-300">Poor / Noise</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">&lt; 0 dB</span>
            </div>
          </div>
        )}

        {activeMetric === 'pci' && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#06b6d4]" />
                <span className="text-slate-700 dark:text-slate-300">CRS Shift v=0</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">PCI mod 3 = 0</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#a855f7]" />
                <span className="text-slate-700 dark:text-slate-300">CRS Shift v=1</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">PCI mod 3 = 1</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-[#f97316]" />
                <span className="text-slate-700 dark:text-slate-300">CRS Shift v=2</span>
              </div>
              <span className="text-slate-500 dark:text-slate-400 font-mono">PCI mod 3 = 2</span>
            </div>
          </div>
        )}

        {/* Overlay Symbols Legend */}
        <div className="mt-2.5 pt-2 border-t border-slate-200 dark:border-slate-800 space-y-1 text-[11px] text-slate-600 dark:text-slate-400">
          <div className="flex items-center gap-2">
            <span className="w-3 h-0.5 bg-cyan-600 dark:bg-cyan-400 inline-block" />
            <span>Cyan Line: Survey Route Track</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="w-3 h-1.5 bg-orange-500 rounded inline-block" />
            <span>Orange Corridor: Weak Coverage Zone</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 bg-red-500 rotate-45 border border-white inline-block" />
            <span>Red Diamond: Handover Trigger</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[12px]">📡</span>
            <span>Radio Tower: Estimated Cell Site</span>
          </div>
        </div>
      </div>

      {/* Point Inspector Modal / Card (Bottom Right) */}
      {selectedPoint && (
        <div className="absolute bottom-4 right-4 z-[1000] bg-white/95 dark:bg-slate-900/95 backdrop-blur-md p-4 rounded-xl border border-slate-200 dark:border-slate-700 shadow-2xl text-xs w-92 max-w-[92vw] text-slate-800 dark:text-slate-300 transition-colors duration-150">
          <div className="flex items-center justify-between pb-2 border-b border-slate-200 dark:border-slate-800 mb-3">
            <div className="flex items-center gap-2">
              <span className="font-bold text-slate-900 dark:text-white text-sm">Point #{selectedPoint.id}</span>
              <span className="px-1.5 py-0.5 text-[10px] font-semibold bg-cyan-100 text-cyan-800 border border-cyan-300 dark:bg-cyan-950 dark:text-cyan-400 dark:border-cyan-800 rounded">
                {selectedPoint.network_gen || '4G LTE'}
              </span>
            </div>
            <button
              onClick={() => setSelectedPoint(null)}
              className="text-slate-400 hover:text-slate-900 dark:hover:text-white px-1 font-bold"
            >
              ✕
            </button>
          </div>
          <div className="grid grid-cols-2 gap-2 text-slate-700 dark:text-slate-300 font-mono">
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">TIMESTAMP</span>
              <span>{selectedPoint.timestamp_raw}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">SPEED & HEADING</span>
              <span>
                {selectedPoint.speed_kmh !== null ? `${selectedPoint.speed_kmh} km/h` : '0 km/h'}
                {selectedPoint.heading_deg !== null && selectedPoint.heading_deg !== undefined ? ` • ${Math.round(selectedPoint.heading_deg)}°` : ''}
              </span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">SERVING PCI</span>
              <span className="text-cyan-600 dark:text-cyan-400 font-bold">PCI {selectedPoint.serving_cell_id ?? 'N/A'}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">OPERATOR & TAC</span>
              <span className="text-slate-800 dark:text-slate-200">
                {selectedPoint.operator || 'Telecom'}
                {selectedPoint.tac ? ` (TAC ${selectedPoint.tac})` : ''}
              </span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">EARFCN / ARFCN</span>
              <span className="text-slate-700 dark:text-slate-300">{selectedPoint.earfcn ? `CH ${selectedPoint.earfcn}` : 'Standard'}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">NEIGHBOR PCI</span>
              <span className="text-slate-500 dark:text-slate-400">{selectedPoint.neighbor_cell_id ? `PCI ${selectedPoint.neighbor_cell_id}` : 'None'}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">RSRP (POWER)</span>
              <span className="font-bold text-emerald-600 dark:text-emerald-400">
                {selectedPoint.rsrp_dbm !== null ? `${selectedPoint.rsrp_dbm} dBm` : 'N/A'}
              </span>
              <span className="text-slate-500 text-[10px] block">{selectedPoint.rsrp_class}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">RSRQ (QUALITY)</span>
              <span className="font-bold text-sky-600 dark:text-sky-400">
                {selectedPoint.rsrq_db !== null ? `${selectedPoint.rsrq_db} dB` : 'N/A'}
              </span>
              <span className="text-slate-500 text-[10px] block">{selectedPoint.rsrq_class}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">CINR / SINR</span>
              <span className="font-bold text-amber-600 dark:text-amber-400">
                {selectedPoint.cinr_db !== null ? `${selectedPoint.cinr_db} dB` : 'N/A'}
              </span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">HO TRIGGER</span>
              <span className={selectedPoint.handover_trigger ? 'text-red-500 dark:text-red-400 font-bold' : 'text-slate-500 dark:text-slate-400'}>
                {selectedPoint.handover_trigger ? '⚡ YES (Active)' : 'NO (0)'}
              </span>
            </div>
            {selectedPoint.timing_advance !== null && selectedPoint.timing_advance !== undefined && (
              <div>
                <span className="text-slate-500 dark:text-slate-500 text-[10px] block">TIMING ADVANCE</span>
                <span className="text-sky-600 dark:text-sky-300">
                  TA {selectedPoint.timing_advance} ({timingAdvanceToMeters(selectedPoint.timing_advance)}m)
                </span>
              </div>
            )}
            {selectedPoint.cqi !== null && selectedPoint.cqi !== undefined && (
              <div>
                <span className="text-slate-500 dark:text-slate-500 text-[10px] block">CQI (CHANNEL QUALITY)</span>
                <span className="text-cyan-600 dark:text-cyan-300">CQI {selectedPoint.cqi} / 15</span>
              </div>
            )}
          </div>
          <div className="mt-3 pt-2 border-t border-slate-200 dark:border-slate-800 text-[10px] text-slate-500 flex justify-between">
            <span>Lat: {selectedPoint.latitude?.toFixed(5)}</span>
            <span>Lon: {selectedPoint.longitude?.toFixed(5)}</span>
            {selectedPoint.accuracy_m && <span>Acc: ±{Math.round(selectedPoint.accuracy_m)}m</span>}
          </div>
        </div>
      )}

      {/* Estimated Site Inspector Modal (Bottom Right) */}
      {selectedSite && !selectedPoint && (
        <div className="absolute bottom-4 right-4 z-[1000] bg-white/95 dark:bg-slate-900/95 backdrop-blur-md p-4 rounded-xl border border-cyan-400/50 dark:border-cyan-800/80 shadow-2xl text-xs w-88 max-w-[92vw] text-slate-800 dark:text-slate-300 transition-colors duration-150">
          <div className="flex items-center justify-between pb-2 border-b border-slate-200 dark:border-slate-800 mb-3">
            <div className="flex items-center gap-2">
              <span className="text-base">📡</span>
              <span className="font-bold text-slate-900 dark:text-white text-sm">Estimated Tower: PCI {selectedSite.pci}</span>
            </div>
            <button
              onClick={() => setSelectedSite(null)}
              className="text-slate-400 hover:text-slate-900 dark:hover:text-white px-1 font-bold"
            >
              ✕
            </button>
          </div>
          <div className="grid grid-cols-2 gap-2 text-slate-700 dark:text-slate-300 font-mono">
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">OBSERVED SAMPLES</span>
              <span className="font-bold text-slate-900 dark:text-white">{selectedSite.sampleCount}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">NETWORK GEN</span>
              <span className="text-cyan-600 dark:text-cyan-400 font-bold">{selectedSite.networkGen}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">PEAK RSRP</span>
              <span className="font-bold text-emerald-600 dark:text-emerald-400">{selectedSite.maxRsrp} dBm</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">MEAN RSRP</span>
              <span className="text-amber-600 dark:text-amber-300">{selectedSite.meanRsrp} dBm</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">CARRIER CHANNEL</span>
              <span className="text-slate-700 dark:text-slate-300">
                {selectedSite.earfcn ? `EARFCN ${selectedSite.earfcn}` : 'Standard'}
              </span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-500 text-[10px] block">UNCERTAINTY RADIUS</span>
              <span className="text-sky-600 dark:text-sky-300">±{selectedSite.uncertaintyRadiusMeters} meters</span>
            </div>
          </div>
          <div className="mt-3 pt-2 border-t border-slate-200 dark:border-slate-800 text-[10px] text-slate-500">
            Centroid: {selectedSite.latitude.toFixed(6)}, {selectedSite.longitude.toFixed(6)}
          </div>
        </div>
      )}
    </div>
  );
};
