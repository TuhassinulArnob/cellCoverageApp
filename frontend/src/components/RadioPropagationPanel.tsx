import React, { useState, useMemo, useEffect, useRef } from 'react';
import L from 'leaflet';
import type { MeasurementPoint, CellTransitionEvent } from '../types/cellscope';
import {
  analyzeShadowing,
  analyzeSignalFluctuation,
  analyzeMultipathPropagation,
  analyzePingPongHandover,
} from '../utils/propagationAnalytics';
import { useTheme } from '../context/useTheme';
import {
  Radio,
  Activity,
  Zap,
  ShieldAlert,
  AlertTriangle,
  MapPin,
  Info,
  Clock,
  ArrowDownUp,
  RefreshCw,
  BarChart3,
  Waves,
  Shuffle,
  Sliders,
  ExternalLink,
} from 'lucide-react';

interface RadioPropagationPanelProps {
  measurements: MeasurementPoint[];
  transitions?: CellTransitionEvent[];
  onNavigateToMap?: (lat: number, lon: number) => void;
}

type SubTab = 'shadowing' | 'fluctuation' | 'multipath' | 'pingpong';

export const RadioPropagationPanel: React.FC<RadioPropagationPanelProps> = ({
  measurements,
  transitions = [],
  onNavigateToMap,
}) => {
  const { theme } = useTheme();
  const isDark = theme === 'dark';

  const [activeSubTab, setActiveSubTab] = useState<SubTab>('shadowing');

  // Module 1 (Shadowing) Options
  const [shadowCellFilter, setShadowCellFilter] = useState<number | null>(null);
  const [shadowWindow, setShadowWindow] = useState<number>(10);
  const [shadowThresholdDb, setShadowThresholdDb] = useState<number>(-6.0);

  // Module 2 (Fluctuation) Options
  const [fluctCellFilter, setFluctCellFilter] = useState<number | null>(null);
  const [fluctWindow, setFluctWindow] = useState<number>(10);

  // Module 3 (Multipath) Options
  const [multipathCellFilter, setMultipathCellFilter] = useState<number | null>(null);
  const [multipathGradientThreshold, setMultipathGradientThreshold] = useState<number>(0.4);

  // Module 4 (Ping-Pong) Options
  const [pingPongWindowSec, setPingPongWindowSec] = useState<number>(15);
  const [pingPongConfirmedOnly, setPingPongConfirmedOnly] = useState<boolean>(false);

  // ------------------------------------------------------------------------
  // Computations
  // ------------------------------------------------------------------------

  const shadowingData = useMemo(() => {
    return analyzeShadowing(measurements, {
      filterCellId: shadowCellFilter,
      movingAverageWindow: shadowWindow,
      negativeResidualThresholdDb: shadowThresholdDb,
    });
  }, [measurements, shadowCellFilter, shadowWindow, shadowThresholdDb]);

  const fluctuationData = useMemo(() => {
    return analyzeSignalFluctuation(measurements, {
      filterCellId: fluctCellFilter,
      rollingWindow: fluctWindow,
    });
  }, [measurements, fluctCellFilter, fluctWindow]);

  const multipathData = useMemo(() => {
    return analyzeMultipathPropagation(measurements, {
      filterCellId: multipathCellFilter,
      highSpatialGradientThresholdDbM: multipathGradientThreshold,
    });
  }, [measurements, multipathCellFilter, multipathGradientThreshold]);

  const pingPongData = useMemo(() => {
    return analyzePingPongHandover(measurements, transitions, {
      timeWindowSec: pingPongWindowSec,
      confirmedOnly: pingPongConfirmedOnly,
    });
  }, [measurements, transitions, pingPongWindowSec, pingPongConfirmedOnly]);

  // Leaflet Map Refs
  const shadowMapContainerRef = useRef<HTMLDivElement>(null);
  const shadowMapInstanceRef = useRef<L.Map | null>(null);

  const multipathMapContainerRef = useRef<HTMLDivElement>(null);
  const multipathMapInstanceRef = useRef<L.Map | null>(null);

  const pingPongMapContainerRef = useRef<HTMLDivElement>(null);
  const pingPongMapInstanceRef = useRef<L.Map | null>(null);

  // ------------------------------------------------------------------------
  // Leaflet Map 1: Shadowing Candidate Zones Map
  // ------------------------------------------------------------------------
  useEffect(() => {
    if (activeSubTab !== 'shadowing' || !shadowMapContainerRef.current) return;

    if (shadowMapInstanceRef.current) {
      shadowMapInstanceRef.current.remove();
      shadowMapInstanceRef.current = null;
    }

    const validPts = shadowingData.points.filter((p) => p.latitude !== null && p.longitude !== null);
    if (validPts.length === 0) return;

    const map = L.map(shadowMapContainerRef.current, {
      attributionControl: true,
      zoomControl: true,
      maxZoom: 18,
    });
    shadowMapInstanceRef.current = map;

    const tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors',
    });
    tileLayer.on('tileerror', (e) => {
      console.warn('Shadowing map tile loading error:', e);
    });
    tileLayer.addTo(map);

    // Route track
    const latLngs = validPts.map((p) => [p.latitude as number, p.longitude as number] as [number, number]);
    const routeLine = L.polyline(latLngs, {
      color: isDark ? '#38bdf8' : '#0284c7',
      weight: 3,
      opacity: 0.7,
    }).addTo(map);

    // Highlight candidate shadowing zones
    shadowingData.candidateZones.forEach((z) => {
      if (z.centerLat !== null && z.centerLon !== null) {
        const circle = L.circle([z.centerLat, z.centerLon], {
          radius: Math.max(z.lengthMeters / 2, 25),
          color: '#e11d48',
          fillColor: '#f43f5e',
          fillOpacity: 0.35,
          weight: 2,
        }).addTo(map);

        circle.bindPopup(`
          <div style="font-family: sans-serif; font-size: 12px; line-height: 1.4;">
            <strong style="color: #e11d48;">Candidate Shadowing Zone #${z.id}</strong><br/>
            <strong>Cell PCI:</strong> ${z.servingCellId ?? 'N/A'}<br/>
            <strong>Sustained Drop:</strong> ${z.minResidualDb} dB<br/>
            <strong>Mean Residual:</strong> ${z.meanResidualDb} dB<br/>
            <strong>Length:</strong> ${z.lengthMeters} m (${z.pointCount} points)<br/>
            <strong>Span:</strong> ${z.startTime.slice(11, 19)} &rarr; ${z.endTime.slice(11, 19)}
          </div>
        `);
      }
    });

    map.fitBounds(routeLine.getBounds(), { padding: [25, 25] });

    // Invalidate map size to handle container transitions and visibility
    const invalidateTimer = setTimeout(() => {
      map.invalidateSize();
    }, 100);

    let resizeObserver: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined' && shadowMapContainerRef.current) {
      resizeObserver = new ResizeObserver(() => {
        map.invalidateSize();
      });
      resizeObserver.observe(shadowMapContainerRef.current);
    }

    return () => {
      clearTimeout(invalidateTimer);
      if (resizeObserver) {
        resizeObserver.disconnect();
      }
      if (shadowMapInstanceRef.current) {
        shadowMapInstanceRef.current.remove();
        shadowMapInstanceRef.current = null;
      }
    };
  }, [activeSubTab, shadowingData, isDark]);

  // ------------------------------------------------------------------------
  // Leaflet Map 2: Multipath Spatial Gradient Map
  // ------------------------------------------------------------------------
  useEffect(() => {
    if (activeSubTab !== 'multipath' || !multipathMapContainerRef.current) return;

    if (multipathMapInstanceRef.current) {
      multipathMapInstanceRef.current.remove();
      multipathMapInstanceRef.current = null;
    }

    const validPts = multipathData.points.filter((p) => p.latitude !== null && p.longitude !== null);
    if (validPts.length === 0) return;

    const map = L.map(multipathMapContainerRef.current, {
      attributionControl: true,
      zoomControl: true,
      maxZoom: 18,
    });
    multipathMapInstanceRef.current = map;

    const tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors',
    });
    tileLayer.on('tileerror', (e) => {
      console.warn('Multipath map tile loading error:', e);
    });
    tileLayer.addTo(map);

    const latLngs = validPts.map((p) => [p.latitude as number, p.longitude as number] as [number, number]);
    const routeLine = L.polyline(latLngs, {
      color: isDark ? '#64748b' : '#94a3b8',
      weight: 2,
      opacity: 0.5,
    }).addTo(map);

    // Color code points by spatial gradient dB/m
    validPts.forEach((p) => {
      const g = p.spatialGradientDbM ?? 0;
      let color = '#10b981'; // smooth (< 0.1)
      if (g > 0.4) {
        color = '#ef4444'; // rapid variation (> 0.4 dB/m)
      } else if (g > 0.15) {
        color = '#f59e0b'; // moderate
      }

      const marker = L.circleMarker([p.latitude as number, p.longitude as number], {
        radius: g > 0.4 ? 5 : 3.5,
        fillColor: color,
        color: isDark ? '#0f172a' : '#ffffff',
        weight: 1,
        fillOpacity: 0.85,
      }).addTo(map);

      marker.bindPopup(`
        <div style="font-family: sans-serif; font-size: 12px; line-height: 1.4;">
          <strong>Spatial Rate of Change:</strong> ${p.spatialGradientDbM !== null ? `${p.spatialGradientDbM} dB/m` : 'N/A'}<br/>
          <strong>RSRP:</strong> ${p.rsrp} dBm<br/>
          <strong>CINR:</strong> ${p.cinrDb !== null ? `${p.cinrDb} dB` : 'N/A'}<br/>
          <strong>PCI:</strong> ${p.servingCellId ?? 'N/A'}<br/>
          <strong>Time:</strong> ${p.timestampRaw.slice(11, 19)}
        </div>
      `);
    });

    // Mark revisited location clusters
    multipathData.revisitClusters.forEach((c) => {
      const clusterMarker = L.circle([c.centerLat, c.centerLon], {
        radius: 20,
        color: '#8b5cf6',
        fillColor: '#a78bfa',
        fillOpacity: 0.35,
        weight: 2,
      }).addTo(map);

      clusterMarker.bindPopup(`
        <div style="font-family: sans-serif; font-size: 12px; line-height: 1.4;">
          <strong style="color: #8b5cf6;">Revisited Location Cluster #${c.id}</strong><br/>
          <strong>Distance Radius:</strong> ${c.geographicDistanceM} m<br/>
          <strong>Time Gap:</strong> ${c.timeSeparationSec} s<br/>
          <strong>Signal Delta:</strong> ${c.deltaRsrpDb} dB (A: ${c.rsrpA} vs B: ${c.rsrpB} dBm)<br/>
          <strong>PCI:</strong> ${c.servingCellA ?? 'N/A'} &rarr; ${c.servingCellB ?? 'N/A'}
        </div>
      `);
    });

    map.fitBounds(routeLine.getBounds(), { padding: [25, 25] });

    // Invalidate map size to handle container transitions and visibility
    const invalidateTimer = setTimeout(() => {
      map.invalidateSize();
    }, 100);

    let resizeObserver: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined' && multipathMapContainerRef.current) {
      resizeObserver = new ResizeObserver(() => {
        map.invalidateSize();
      });
      resizeObserver.observe(multipathMapContainerRef.current);
    }

    return () => {
      clearTimeout(invalidateTimer);
      if (resizeObserver) {
        resizeObserver.disconnect();
      }
      if (multipathMapInstanceRef.current) {
        multipathMapInstanceRef.current.remove();
        multipathMapInstanceRef.current = null;
      }
    };
  }, [activeSubTab, multipathData, isDark]);

  // ------------------------------------------------------------------------
  // Leaflet Map 3: Ping-Pong Transitions & Movement Vectors Map
  // ------------------------------------------------------------------------
  useEffect(() => {
    if (activeSubTab !== 'pingpong' || !pingPongMapContainerRef.current) return;

    if (pingPongMapInstanceRef.current) {
      pingPongMapInstanceRef.current.remove();
      pingPongMapInstanceRef.current = null;
    }

    const validPts = measurements.filter((m) => m.latitude !== null && m.longitude !== null);
    if (validPts.length === 0) return;

    const map = L.map(pingPongMapContainerRef.current, {
      attributionControl: true,
      zoomControl: true,
      maxZoom: 18,
    });
    pingPongMapInstanceRef.current = map;

    const tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors',
    });
    tileLayer.on('tileerror', (e) => {
      console.warn('Ping-Pong map tile loading error:', e);
    });
    tileLayer.addTo(map);

    const latLngs = validPts.map((p) => [p.latitude as number, p.longitude as number] as [number, number]);
    const routeLine = L.polyline(latLngs, {
      color: isDark ? '#475569' : '#cbd5e1',
      weight: 2,
      opacity: 0.6,
    }).addTo(map);

    // Plot Ping-Pong loops
    pingPongData.loops.forEach((lp) => {
      if (lp.startLat !== null && lp.startLon !== null && lp.returnLat !== null && lp.returnLon !== null) {
        // Connecting vector line
        L.polyline(
          [
            [lp.startLat, lp.startLon],
            [lp.returnLat, lp.returnLon],
          ],
          {
            color: lp.isConfirmedHandover ? '#ef4444' : '#f59e0b',
            weight: 3,
            dashArray: '5, 8',
          }
        ).addTo(map);

        const markerA = L.circleMarker([lp.startLat, lp.startLon], {
          radius: 6,
          fillColor: '#06b6d4',
          color: '#ffffff',
          weight: 1.5,
          fillOpacity: 0.9,
        }).addTo(map);

        markerA.bindPopup(`
          <div style="font-family: sans-serif; font-size: 12px; line-height: 1.4;">
            <strong style="color: ${lp.isConfirmedHandover ? '#ef4444' : '#f59e0b'};">
              Ping-Pong Loop #${lp.id} (${lp.classification})
            </strong><br/>
            <strong>Loop:</strong> PCI ${lp.sourceCell} &rarr; PCI ${lp.intermediateCell} &rarr; PCI ${lp.returnCell}<br/>
            <strong>Intermediate Dwell:</strong> ${lp.intermediateDwellSec} s<br/>
            <strong>Total Loop Duration:</strong> ${lp.totalElapsedSec} s<br/>
            <strong>Departure Signal:</strong> ${lp.rsrpBeforeDeparture ?? '—'} dBm &rarr; ${lp.rsrpOnIntermediate ?? '—'} dBm<br/>
            <strong>Distance:</strong> ${lp.distanceBetweenTransitionsM ?? 0} m<br/>
            <strong>Time:</strong> ${lp.initialTransitionTime.slice(11, 19)} &rarr; ${lp.returnTransitionTime.slice(11, 19)}
          </div>
        `);
      }
    });

    map.fitBounds(routeLine.getBounds(), { padding: [25, 25] });

    // Invalidate map size to handle container transitions and visibility
    const invalidateTimer = setTimeout(() => {
      map.invalidateSize();
    }, 100);

    let resizeObserver: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined' && pingPongMapContainerRef.current) {
      resizeObserver = new ResizeObserver(() => {
        map.invalidateSize();
      });
      resizeObserver.observe(pingPongMapContainerRef.current);
    }

    return () => {
      clearTimeout(invalidateTimer);
      if (resizeObserver) {
        resizeObserver.disconnect();
      }
      if (pingPongMapInstanceRef.current) {
        pingPongMapInstanceRef.current.remove();
        pingPongMapInstanceRef.current = null;
      }
    };
  }, [activeSubTab, measurements, pingPongData, isDark]);

  // ------------------------------------------------------------------------
  // Helper: SVG Line / Scatter Plot Generator
  // ------------------------------------------------------------------------
  const renderSvgLineChart = (
    title: string,
    subtitle: string,
    data: { x: number; y: number; label?: string; rawY?: number; secondaryY?: number }[],
    xLabel: string,
    yLabel: string,
    yDomain?: [number, number],
    referenceLineY?: number,
    secondaryLineName?: string
  ) => {
    if (data.length === 0) {
      return (
        <div className="h-64 flex items-center justify-center text-xs text-slate-400">
          No observations available to render chart.
        </div>
      );
    }

    const width = 800;
    const height = 280;
    const padding = { top: 20, right: 30, bottom: 40, left: 50 };
    const chartW = width - padding.left - padding.right;
    const chartH = height - padding.top - padding.bottom;

    const minX = data[0].x;
    const maxX = data[data.length - 1].x || 1;
    const spanX = Math.max(maxX - minX, 0.001);

    const yVals = data.map((d) => d.y).concat(data.map((d) => d.rawY ?? d.y));
    const minY = yDomain ? yDomain[0] : Math.floor(Math.min(...yVals) / 5) * 5;
    const maxY = yDomain ? yDomain[1] : Math.ceil(Math.max(...yVals) / 5) * 5;
    const spanY = Math.max(maxY - minY, 1);

    const getX = (x: number) => padding.left + ((x - minX) / spanX) * chartW;
    const getY = (y: number) => padding.top + chartH - ((y - minY) / spanY) * chartH;

    // Build SVG path string for primary line (smoothed/trend)
    const pointsStr = data.map((d) => `${getX(d.x).toFixed(1)},${getY(d.y).toFixed(1)}`).join(' ');

    // Build SVG path string for secondary line if present
    const secondaryStr = data[0].secondaryY !== undefined
      ? data.map((d) => `${getX(d.x).toFixed(1)},${getY(d.secondaryY as number).toFixed(1)}`).join(' ')
      : null;

    // Y ticks (5 ticks)
    const yTicks = [0, 0.25, 0.5, 0.75, 1].map((pct) => minY + pct * spanY);
    // X ticks (5 ticks)
    const xTicks = [0, 0.25, 0.5, 0.75, 1].map((pct) => minX + pct * spanX);

    return (
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm transition-colors duration-150">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3 gap-2">
          <div>
            <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <Activity className="w-4 h-4 text-cyan-600 dark:text-cyan-400" /> {title}
            </h4>
            <p className="text-xs text-slate-500 dark:text-slate-400">{subtitle}</p>
          </div>
          <div className="flex items-center gap-3 text-[11px] font-mono">
            {data[0].rawY !== undefined && (
              <span className="flex items-center gap-1.5 text-slate-500 dark:text-slate-400">
                <span className="w-2.5 h-2.5 rounded-full bg-cyan-400 opacity-60 inline-block" /> Raw Points
              </span>
            )}
            <span className="flex items-center gap-1.5 text-cyan-600 dark:text-cyan-400 font-semibold">
              <span className="w-3 h-0.5 bg-cyan-600 dark:bg-cyan-400 inline-block" /> Smoothed Trend
            </span>
            {secondaryStr && (
              <span className="flex items-center gap-1.5 text-amber-500 font-semibold">
                <span className="w-3 h-0.5 bg-amber-500 inline-block stroke-dasharray" /> {secondaryLineName || 'Baseline'}
              </span>
            )}
          </div>
        </div>

        <div className="overflow-x-auto">
          <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-auto min-w-[550px]">
            {/* Grid Lines */}
            {yTicks.map((tick, idx) => (
              <g key={idx}>
                <line
                  x1={padding.left}
                  y1={getY(tick)}
                  x2={width - padding.right}
                  y2={getY(tick)}
                  stroke={isDark ? '#1e293b' : '#e2e8f0'}
                  strokeDasharray="3 3"
                />
                <text
                  x={padding.left - 8}
                  y={getY(tick) + 3}
                  textAnchor="end"
                  className="text-[10px] fill-slate-400 font-mono"
                >
                  {tick.toFixed(0)}
                </text>
              </g>
            ))}

            {/* Zero or Reference line */}
            {referenceLineY !== undefined && (
              <line
                x1={padding.left}
                y1={getY(referenceLineY)}
                x2={width - padding.right}
                y2={getY(referenceLineY)}
                stroke="#f43f5e"
                strokeWidth="1.5"
                strokeDasharray="4 4"
              />
            )}

            {/* X Ticks */}
            {xTicks.map((tick, idx) => (
              <g key={idx}>
                <line
                  x1={getX(tick)}
                  y1={padding.top + chartH}
                  x2={getX(tick)}
                  y2={padding.top + chartH + 5}
                  stroke={isDark ? '#475569' : '#94a3b8'}
                />
                <text
                  x={getX(tick)}
                  y={padding.top + chartH + 18}
                  textAnchor="middle"
                  className="text-[10px] fill-slate-400 font-mono"
                >
                  {tick.toFixed(1)}
                </text>
              </g>
            ))}

            {/* Raw points scatter */}
            {data[0].rawY !== undefined &&
              data.map((d, idx) => (
                <circle
                  key={idx}
                  cx={getX(d.x)}
                  cy={getY(d.rawY as number)}
                  r={1.8}
                  className="fill-cyan-400 dark:fill-cyan-500 opacity-40 hover:opacity-100 hover:r-3 transition-all"
                >
                  <title>{`${d.label || ''} Raw: ${d.rawY} dBm`}</title>
                </circle>
              ))}

            {/* Secondary line (e.g. Empirical Path Loss Baseline) */}
            {secondaryStr && (
              <polyline
                fill="none"
                stroke="#f59e0b"
                strokeWidth="2"
                strokeDasharray="4 3"
                points={secondaryStr}
              />
            )}

            {/* Primary line (Smoothed / Moving Avg) */}
            <polyline
              fill="none"
              stroke="#06b6d4"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeLinejoin="round"
              points={pointsStr}
            />

            {/* Axis Titles */}
            <text
              x={width / 2}
              y={height - 5}
              textAnchor="middle"
              className="text-[11px] fill-slate-500 font-medium"
            >
              {xLabel}
            </text>
            <text
              transform={`rotate(-90)`}
              x={-height / 2}
              y={14}
              textAnchor="middle"
              className="text-[11px] fill-slate-500 font-medium"
            >
              {yLabel}
            </text>
          </svg>
        </div>
      </div>
    );
  };

  // ------------------------------------------------------------------------
  // Helper: Detailed 4-Subheadings Explanation Box
  // ------------------------------------------------------------------------
  const renderDetailedExplanation = (
    whatItShows: string,
    telecomInterpretation: string,
    whyItMatters: string,
    limitationsAndConfidence: string
  ) => {
    return (
      <div className="bg-slate-50 dark:bg-slate-900/90 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm space-y-4 text-xs transition-colors duration-150">
        <div className="flex items-center gap-2 pb-2.5 border-b border-slate-200 dark:border-slate-800 text-slate-800 dark:text-slate-200 font-bold uppercase tracking-wider text-[11px]">
          <Info className="w-4 h-4 text-cyan-600 dark:text-cyan-400" /> Scientific & Telecom Interpretation
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <h5 className="font-bold text-slate-800 dark:text-slate-200 flex items-center gap-1.5 text-xs">
              <span className="w-2 h-2 rounded-full bg-cyan-500 inline-block" /> What the graph shows
            </h5>
            <p className="text-slate-600 dark:text-slate-400 leading-relaxed pl-3.5 border-l-2 border-cyan-500/40">
              {whatItShows}
            </p>
          </div>

          <div className="space-y-1.5">
            <h5 className="font-bold text-slate-800 dark:text-slate-200 flex items-center gap-1.5 text-xs">
              <span className="w-2 h-2 rounded-full bg-emerald-500 inline-block" /> Telecom interpretation
            </h5>
            <p className="text-slate-600 dark:text-slate-400 leading-relaxed pl-3.5 border-l-2 border-emerald-500/40">
              {telecomInterpretation}
            </p>
          </div>

          <div className="space-y-1.5">
            <h5 className="font-bold text-slate-800 dark:text-slate-200 flex items-center gap-1.5 text-xs">
              <span className="w-2 h-2 rounded-full bg-amber-500 inline-block" /> Why it matters
            </h5>
            <p className="text-slate-600 dark:text-slate-400 leading-relaxed pl-3.5 border-l-2 border-amber-500/40">
              {whyItMatters}
            </p>
          </div>

          <div className="space-y-1.5">
            <h5 className="font-bold text-slate-800 dark:text-slate-200 flex items-center gap-1.5 text-xs">
              <span className="w-2 h-2 rounded-full bg-rose-500 inline-block" /> Limitations and confidence
            </h5>
            <p className="text-slate-600 dark:text-slate-400 leading-relaxed pl-3.5 border-l-2 border-rose-500/40">
              {limitationsAndConfidence}
            </p>
          </div>
        </div>
      </div>
    );
  };

  // ------------------------------------------------------------------------
  // RENDER: MODULE 1 (SHADOWING ANALYSIS)
  // ------------------------------------------------------------------------
  const renderShadowingTab = () => {
    if (!shadowingData.hasSufficientData) {
      return (
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 text-center text-slate-500 dark:text-slate-400 shadow-sm">
          <AlertTriangle className="w-8 h-8 text-amber-500 mx-auto mb-2 opacity-80" />
          <h4 className="font-semibold text-sm text-slate-800 dark:text-slate-200">Insufficient Shadowing Data</h4>
          <p className="text-xs mt-1 max-w-md mx-auto">{shadowingData.warningMessage}</p>
        </div>
      );
    }

    // Prepare line data: RSRP vs Distance
    const rsrpVsDist = shadowingData.points.map((p) => ({
      x: Math.round((p.cumulativeDistanceMeters / 1000) * 100) / 100, // km
      y: p.smoothedRsrp,
      rawY: p.rawRsrp,
      secondaryY: p.baselineTrendRsrp,
      label: `km ${Math.round((p.cumulativeDistanceMeters / 1000) * 100) / 100}`,
    }));

    // Residuals vs Distance
    const residualVsDist = shadowingData.points.map((p) => ({
      x: Math.round((p.cumulativeDistanceMeters / 1000) * 100) / 100,
      y: p.residualDb,
      label: `km ${Math.round((p.cumulativeDistanceMeters / 1000) * 100) / 100}`,
    }));

    return (
      <div className="space-y-6">
        {/* Physical Concept Banner */}
        <div className="bg-gradient-to-r from-cyan-500/10 via-blue-500/5 to-transparent border border-cyan-500/20 rounded-xl p-4 text-xs text-slate-700 dark:text-slate-300">
          <div className="flex items-start gap-2.5">
            <Radio className="w-5 h-5 text-cyan-600 dark:text-cyan-400 flex-shrink-0 mt-0.5" />
            <div>
              <strong className="font-bold text-slate-900 dark:text-white">Physical Phenomenon: Log-Normal Shadowing (Slow Fading)</strong>
              <p className="mt-1 text-slate-600 dark:text-slate-400 leading-relaxed">
                Shadowing occurs when large macroscopic obstructions (high-rise structures, topographical hills, vegetation, overpasses) attenuate the radio wavefront between eNodeB and UE. 
                According to empirical propagation theory (Gudmundson &amp; Rappaport models), shadowing exhibits log-normal statistical variance with a standard deviation &sigma;<sub>&psi;</sub> typically ranging between 4 to 10 dB in urban environments. 
                This analysis computes spatial moving averages to filter fast ripple and isolates sustained negative residuals relative to empirical baseline path loss.
              </p>
            </div>
          </div>
        </div>

        {/* Filter Toolbar */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" /> Controls:
            </span>

            {/* Serving Cell Filter */}
            <select
              value={shadowCellFilter ?? 'all'}
              onChange={(e) => setShadowCellFilter(e.target.value === 'all' ? null : Number(e.target.value))}
              className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none"
            >
              <option value="all">All Serving Cells (Entire Drive)</option>
              {shadowingData.availableCellIds.map((c) => (
                <option key={c} value={c}>
                  PCI {c}
                </option>
              ))}
            </select>

            {/* Smoothing Window */}
            <div className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
              <span>Window:</span>
              <select
                value={shadowWindow}
                onChange={(e) => setShadowWindow(Number(e.target.value))}
                className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2 py-1.5 focus:outline-none"
              >
                <option value={5}>5 Samples (Fast)</option>
                <option value={10}>10 Samples (Nominal)</option>
                <option value={20}>20 Samples (Heavy Spatial Filter)</option>
              </select>
            </div>

            {/* Negative Residual Threshold */}
            <div className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
              <span>Threshold:</span>
              <select
                value={shadowThresholdDb}
                onChange={(e) => setShadowThresholdDb(Number(e.target.value))}
                className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2 py-1.5 focus:outline-none"
              >
                <option value={-4}>-4 dB (Sensitive)</option>
                <option value={-6}>-6 dB (Standard Candidate)</option>
                <option value={-8}>-8 dB (Severe Obstruction)</option>
              </select>
            </div>
          </div>

          <div className="text-xs text-slate-500 font-mono">
            Analyzed: <strong className="text-slate-900 dark:text-white">{shadowingData.totalPointsAnalyzed}</strong> points ({shadowingData.analyzedDistanceKm} km)
          </div>
        </div>

        {/* KPI Cards */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Mean RSRP</span>
            <span className="text-2xl font-bold font-mono text-cyan-600 dark:text-cyan-400">{shadowingData.meanRsrp} <span className="text-xs font-normal">dBm</span></span>
            <span className="text-[10px] text-slate-400 block">Median: {shadowingData.medianRsrp} dBm</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Shadow Spread (&sigma;<sub>&psi;</sub>)</span>
            <span className="text-2xl font-bold font-mono text-purple-600 dark:text-purple-400">{shadowingData.residualStdDevDb} <span className="text-xs font-normal">dB</span></span>
            <span className="text-[10px] text-slate-400 block">Log-normal deviation</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Distance Traveled</span>
            <span className="text-2xl font-bold font-mono text-slate-800 dark:text-slate-200">{shadowingData.analyzedDistanceKm} <span className="text-xs font-normal">km</span></span>
            <span className="text-[10px] text-slate-400 block">Geodesic path</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Candidate Zones</span>
            <span className="text-2xl font-bold font-mono text-rose-600 dark:text-rose-400">{shadowingData.candidateZonesCount}</span>
            <span className="text-[10px] text-slate-400 block">Sustained negative residual</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Max Sustained Drop</span>
            <span className="text-2xl font-bold font-mono text-amber-600 dark:text-amber-400">{shadowingData.maxSustainedDropDb ? `-${shadowingData.maxSustainedDropDb} dB` : '—'}</span>
            <span className="text-[10px] text-slate-400 block">Relative to trend</span>
          </div>
        </div>

        {/* Primary Chart: RSRP vs Distance */}
        {renderSvgLineChart(
          'RSRP vs Traveled Distance & Spatial Trend',
          'Cyan curve depicts spatial moving average; amber dashed line represents empirical linear distance trend.',
          rsrpVsDist,
          'Cumulative Distance (km)',
          'Signal Strength RSRP (dBm)',
          [-130, -50],
          undefined,
          'Empirical Baseline'
        )}

        {/* Detailed 4-Subheadings Explanation Box */}
        {renderDetailedExplanation(
          `The graph plots raw cellular RSRP measurements (cyan points) together with a ${shadowWindow}-sample spatial moving average (cyan solid line) and an empirical linear path-loss regression trend (amber dashed line) across ${shadowingData.analyzedDistanceKm} km of route.`,
          `Gradual downwards decay of the smoothed curve over distance reflects macroscopic propagation path loss. Departures where the smoothed curve sags persistently beneath the empirical baseline indicate localized shadowing, where physical barriers obstruct line-of-sight propagation.`,
          `Distinguishing large-scale shadowing from short-term ripple enables RF engineers to pinpoint physical coverage deficits (such as building blockage corridors or terrain dead spots) rather than adjusting antenna tilt or power unnecessarily.`,
          `This analysis employs an empirical regression baseline because exact base station coordinates, effective radiated power (EIRP), and antenna down-tilt are not broadcast over commercial LTE air interfaces. A negative residual indicates candidate shadowing, but cannot definitively prove obstruction without physical terrain elevation or LiDAR clutter models.`
        )}

        {/* Secondary Visualizations: Residuals vs Distance & Candidate Zones Map */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Residual Chart */}
          <div>
            {renderSvgLineChart(
              'Shadowing Residual Variation (dB)',
              'Deviation of measured RSRP from the smoothed spatial trend. Dashed line marks zero baseline.',
              residualVsDist,
              'Cumulative Distance (km)',
              'Residual (dB)',
              [-20, 20],
              0
            )}
          </div>

          {/* Map of Candidate Zones */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <MapPin className="w-4 h-4 text-rose-500" /> Candidate Shadowing Regions on Map
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Red highlighted circles indicate candidate localized obstacle attenuation zones.
                </p>
              </div>
              <span className="text-xs font-mono text-rose-600 dark:text-rose-400 font-bold">
                {shadowingData.candidateZonesCount} zones
              </span>
            </div>
            <div
              ref={shadowMapContainerRef}
              className={`w-full h-72 rounded-lg overflow-hidden border border-slate-200 dark:border-slate-800 ${
                isDark ? 'cellscope-dark-tiles' : 'cellscope-light-tiles'
              }`}
            />
          </div>
        </div>

        {/* Candidate Zones Table */}
        {shadowingData.candidateZones.length > 0 && (
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <h4 className="text-sm font-bold text-slate-900 dark:text-white mb-3 flex items-center gap-2">
              <ShieldAlert className="w-4 h-4 text-rose-500" /> Detected Candidate Shadowing Corridors
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 font-mono">
                    <th className="py-2 px-3">Zone ID</th>
                    <th className="py-2 px-3">PCI</th>
                    <th className="py-2 px-3">Distance Span</th>
                    <th className="py-2 px-3">Corridor Length</th>
                    <th className="py-2 px-3">Mean Residual</th>
                    <th className="py-2 px-3">Worst Drop</th>
                    <th className="py-2 px-3">Duration</th>
                    {onNavigateToMap && <th className="py-2 px-3 text-right">Action</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800 font-mono">
                  {shadowingData.candidateZones.map((z) => (
                    <tr key={z.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/50">
                      <td className="py-2 px-3 font-bold text-rose-600 dark:text-rose-400">#{z.id}</td>
                      <td className="py-2 px-3 text-cyan-600 dark:text-cyan-400">PCI {z.servingCellId ?? '—'}</td>
                      <td className="py-2 px-3">{z.startDistanceM}m &rarr; {z.endDistanceM}m</td>
                      <td className="py-2 px-3 font-semibold">{z.lengthMeters} m ({z.pointCount} pts)</td>
                      <td className="py-2 px-3 text-rose-600">{z.meanResidualDb} dB</td>
                      <td className="py-2 px-3 text-rose-700 font-bold">{z.minResidualDb} dB</td>
                      <td className="py-2 px-3 text-slate-400">{z.startTime.slice(11, 19)} - {z.endTime.slice(11, 19)}</td>
                      {onNavigateToMap && (
                        <td className="py-2 px-3 text-right">
                          {z.centerLat !== null && z.centerLon !== null && (
                            <button
                              onClick={() => onNavigateToMap(z.centerLat!, z.centerLon!)}
                              className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-sans font-medium rounded bg-slate-100 dark:bg-slate-800 hover:bg-cyan-50 dark:hover:bg-cyan-950 text-slate-700 dark:text-slate-300 hover:text-cyan-700 dark:hover:text-cyan-300 transition-colors"
                              title="Focus coordinate on interactive coverage map"
                            >
                              <ExternalLink className="w-3 h-3" /> Map
                            </button>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    );
  };

  // ------------------------------------------------------------------------
  // RENDER: MODULE 2 (SIGNAL FLUCTUATION ANALYSIS)
  // ------------------------------------------------------------------------
  const renderFluctuationTab = () => {
    if (!fluctuationData.hasSufficientData) {
      return (
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 text-center text-slate-500 dark:text-slate-400 shadow-sm">
          <AlertTriangle className="w-8 h-8 text-amber-500 mx-auto mb-2 opacity-80" />
          <h4 className="font-semibold text-sm text-slate-800 dark:text-slate-200">Insufficient Fluctuation Data</h4>
          <p className="text-xs mt-1 max-w-md mx-auto">{fluctuationData.warningMessage}</p>
        </div>
      );
    }

    // Prepare line data: RSRP vs Time
    const rsrpVsTime = fluctuationData.points.map((p) => ({
      x: p.timeSec,
      y: p.rollingMeanRsrp,
      rawY: p.rsrp,
      label: `${p.timeSec}s`,
    }));

    // Consecutive Delta Chart
    const deltaVsTime = fluctuationData.points.filter((p) => p.deltaRsrpDb !== null).map((p) => ({
      x: p.timeSec,
      y: p.deltaRsrpDb as number,
      label: `${p.timeSec}s`,
    }));

    // Rolling Std Dev Chart
    const stdVsTime = fluctuationData.points.map((p) => ({
      x: p.timeSec,
      y: p.rollingStdDevDb,
      label: `${p.timeSec}s`,
    }));

    return (
      <div className="space-y-6">
        {/* Physical Concept Banner */}
        <div className="bg-gradient-to-r from-purple-500/10 via-pink-500/5 to-transparent border border-purple-500/20 rounded-xl p-4 text-xs text-slate-700 dark:text-slate-300">
          <div className="flex items-start gap-2.5">
            <Waves className="w-5 h-5 text-purple-600 dark:text-purple-400 flex-shrink-0 mt-0.5" />
            <div>
              <strong className="font-bold text-slate-900 dark:text-white">Physical Phenomenon: Macroscopic Signal Fluctuation &amp; Sampling Rate Audit</strong>
              <p className="mt-1 text-slate-600 dark:text-slate-400 leading-relaxed">
                Cellular signal levels exhibit continuous variation due to vehicle mobility, Doppler shifts, shadow transitions, and measurement noise. 
                This module analyzes sample-to-sample deltas (&Delta;dB), rolling variance, and empirical distribution percentiles.
                Crucially, this module performs an <strong>active sampling rate validation</strong>: commercial Android drive tests reporting at ~1 Hz (nominal 1-second interval) 
                span multiple carrier wavelengths (&lambda; &approx; 16.6 cm at 1.8 GHz) per sample; therefore, this feature scientifically characterizes <strong>macroscopic signal fluctuations</strong> rather than microscopic Rayleigh fast-fading nulls.
              </p>
            </div>
          </div>
        </div>

        {/* Filter Toolbar */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5 text-purple-600 dark:text-purple-400" /> Controls:
            </span>

            {/* Serving Cell Filter */}
            <select
              value={fluctCellFilter ?? 'all'}
              onChange={(e) => setFluctCellFilter(e.target.value === 'all' ? null : Number(e.target.value))}
              className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none"
            >
              <option value="all">All Serving Cells</option>
              {fluctuationData.availableCellIds.map((c) => (
                <option key={c} value={c}>
                  PCI {c}
                </option>
              ))}
            </select>

            {/* Rolling Window */}
            <div className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
              <span>Rolling Window:</span>
              <select
                value={fluctWindow}
                onChange={(e) => setFluctWindow(Number(e.target.value))}
                className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2 py-1.5 focus:outline-none"
              >
                <option value={5}>5 Samples (Short-Term)</option>
                <option value={10}>10 Samples (Nominal ~10s)</option>
                <option value={20}>20 Samples (Macro Variability)</option>
              </select>
            </div>
          </div>

          <div className="text-xs text-slate-500 font-mono">
            Duration: <strong className="text-slate-900 dark:text-white">{fluctuationData.durationSec}s</strong> | Samples: <strong className="text-slate-900 dark:text-white">{fluctuationData.totalSamples}</strong>
          </div>
        </div>

        {/* Sampling Rate Validation Card */}
        <div className="bg-amber-50 dark:bg-amber-950/40 border border-amber-300 dark:border-amber-800/60 rounded-xl p-4 text-xs">
          <div className="flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
            <div>
              <strong className="font-semibold text-amber-900 dark:text-amber-300">Sampling Rate Validation &amp; Scientific Disclosure:</strong>
              <p className="mt-0.5 text-amber-800 dark:text-amber-400 leading-relaxed">
                {fluctuationData.samplingValidationAssessment}
              </p>
            </div>
          </div>
        </div>

        {/* KPI Cards */}
        <div className="grid grid-cols-2 sm:grid-cols-6 gap-3">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Mean &Delta;RSRP</span>
            <span className="text-2xl font-bold font-mono text-cyan-600 dark:text-cyan-400">{fluctuationData.meanConsecutiveDeltaDb} <span className="text-xs font-normal">dB</span></span>
            <span className="text-[10px] text-slate-400 block">Sample-to-sample</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Max &Delta;RSRP</span>
            <span className="text-2xl font-bold font-mono text-rose-600 dark:text-rose-400">{fluctuationData.maxConsecutiveDeltaDb} <span className="text-xs font-normal">dB</span></span>
            <span className="text-[10px] text-slate-400 block">Largest step cliff</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Rolling Std Dev (&sigma;)</span>
            <span className="text-2xl font-bold font-mono text-purple-600 dark:text-purple-400">{fluctuationData.meanRollingStdDevDb} <span className="text-xs font-normal">dB</span></span>
            <span className="text-[10px] text-slate-400 block">Mean local variance</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">P10 / P90 Spread</span>
            <span className="text-2xl font-bold font-mono text-slate-800 dark:text-slate-200">{fluctuationData.p90Rsrp && fluctuationData.p10Rsrp ? `${Math.abs(fluctuationData.p90Rsrp - fluctuationData.p10Rsrp)}` : '—'} <span className="text-xs font-normal">dB</span></span>
            <span className="text-[10px] text-slate-400 block">P10: {fluctuationData.p10Rsrp} | P90: {fluctuationData.p90Rsrp}</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Median Interval (&Delta;t)</span>
            <span className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">{fluctuationData.medianIntervalSec} <span className="text-xs font-normal">s</span></span>
            <span className="text-[10px] text-slate-400 block">Mean: {fluctuationData.meanIntervalSec}s</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Irregular Jitter</span>
            <span className="text-2xl font-bold font-mono text-amber-600 dark:text-amber-400">{fluctuationData.irregularIntervalPct}%</span>
            <span className="text-[10px] text-slate-400 block">Max gap: {fluctuationData.maxIntervalGapSec}s</span>
          </div>
        </div>

        {/* Primary Chart: RSRP vs Time */}
        {renderSvgLineChart(
          'RSRP vs Measurement Time (Chronological Fluctuation Profile)',
          `Raw observations (scatter dots) vs ${fluctWindow}-sample rolling mean (cyan curve).`,
          rsrpVsTime,
          'Elapsed Drive Time (seconds)',
          'Signal Strength RSRP (dBm)',
          [-135, -50]
        )}

        {/* Detailed 4-Subheadings Explanation Box */}
        {renderDetailedExplanation(
          `The graph displays continuous RSRP measurements and a ${fluctWindow}-sample moving average across ${fluctuationData.durationSec} seconds of drive testing. Observed signal spans from ${fluctuationData.minRsrp} dBm (minimum) to ${fluctuationData.maxRsrp} dBm (maximum), with a median of ${fluctuationData.medianRsrp} dBm.`,
          `Signal fluctuations reflect the dynamic interplay between receiver displacement, changing multipath phase combinations, and shadowing boundaries. A high rolling standard deviation (&sigma; > 4 dB) highlights corridors of intense RF variability, typically occurring in dense urban clutter or cell overlap zones.`,
          `Monitoring signal fluctuation magnitude is critical for tuning Handover Time-to-Trigger (TTT) and Handover Margin (HOM). If signal fluctuation standard deviation exceeds the handover hysteresis, the mobile station risks premature or ping-pong handovers.`,
          `Commercial Android smartphones report TelephonyManager cell telemetry at approximately 1 Hz (nominal median interval: ${fluctuationData.medianIntervalSec}s). At driving speeds (10 m/s), 1 second corresponds to ~60 carrier wavelengths at 1.8 GHz. Consequently, constructive/destructive Rayleigh multipath nulls are spatially sub-sampled; this analysis measures macroscopic signal fluctuations rather than confirmed fast fading.`
        )}

        {/* Secondary Visualizations: Consecutive Delta & Rolling Variance */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Consecutive Delta Chart */}
          <div>
            {renderSvgLineChart(
              'Consecutive Sample-to-Sample &Delta;RSRP (dB)',
              'Step changes between adjacent timestamps. Sudden spikes (> 6 dB) denote rapid clutter transitions or cell changes.',
              deltaVsTime,
              'Elapsed Time (seconds)',
              '&Delta;RSRP (dB)',
              [-20, 20],
              0
            )}
          </div>

          {/* Rolling Std Dev Chart */}
          <div>
            {renderSvgLineChart(
              `Rolling Standard Deviation &sigma; (Window = ${fluctWindow})`,
              'Local variability metric. Elevated peaks (> 4 dB) identify unstable RF reception corridors.',
              stdVsTime,
              'Elapsed Time (seconds)',
              'Rolling &sigma; (dB)',
              [0, 15],
              4.0 // Alert threshold line at 4 dB
            )}
          </div>
        </div>

        {/* Statistical Histograms: RSRP Distribution & Sampling Intervals */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* RSRP Histogram */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <BarChart3 className="w-4 h-4 text-cyan-600 dark:text-cyan-400" /> RSRP Empirical Signal Distribution
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Sample count frequency across 4 dB quantization bins.
                </p>
              </div>
              <span className="text-xs font-mono text-cyan-600 dark:text-cyan-400 font-bold">
                IQR: {fluctuationData.iqrRsrp} dB
              </span>
            </div>

            <div className="space-y-2 mt-3">
              {fluctuationData.rsrpDistributionBins.map((bin, idx) => (
                <div key={idx} className="space-y-1">
                  <div className="flex justify-between text-[11px] font-mono text-slate-600 dark:text-slate-400">
                    <span>{bin.binStart} to {bin.binEnd} dBm</span>
                    <span className="font-semibold text-slate-800 dark:text-slate-200">{bin.count} pts ({bin.percentage}%)</span>
                  </div>
                  <div className="h-2 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-cyan-500 dark:bg-cyan-400 rounded-full transition-all duration-300"
                      style={{ width: `${Math.min(100, Math.max(2, bin.percentage))}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Sampling Interval Distribution */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <Clock className="w-4 h-4 text-purple-600 dark:text-purple-400" /> Telemetry Sampling Interval (&Delta;t) Profile
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Verification of underlying Android logging periodicity.
                </p>
              </div>
              <span className="text-xs font-mono text-purple-600 dark:text-purple-400 font-bold">
                Nominal: ~1.0s
              </span>
            </div>

            <div className="space-y-2 mt-3">
              {fluctuationData.samplingIntervalBins.map((bin, idx) => (
                <div key={idx} className="space-y-1">
                  <div className="flex justify-between text-[11px] font-mono text-slate-600 dark:text-slate-400">
                    <span>{bin.label}</span>
                    <span className="font-semibold text-slate-800 dark:text-slate-200">{bin.count} intervals ({bin.percentage}%)</span>
                  </div>
                  <div className="h-2 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-purple-500 dark:bg-purple-400 rounded-full transition-all duration-300"
                      style={{ width: `${Math.min(100, Math.max(2, bin.percentage))}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  };

  // ------------------------------------------------------------------------
  // RENDER: MODULE 3 (MULTIPATH PROPAGATION ANALYSIS)
  // ------------------------------------------------------------------------
  const renderMultipathTab = () => {
    if (!multipathData.hasSufficientData) {
      return (
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 text-center text-slate-500 dark:text-slate-400 shadow-sm">
          <AlertTriangle className="w-8 h-8 text-amber-500 mx-auto mb-2 opacity-80" />
          <h4 className="font-semibold text-sm text-slate-800 dark:text-slate-200">Insufficient Multipath Data</h4>
          <p className="text-xs mt-1 max-w-md mx-auto">{multipathData.warningMessage}</p>
        </div>
      );
    }

    // Spatial gradient chart data
    const gradVsDist = multipathData.points.filter((p) => p.spatialGradientDbM !== null).map((p) => ({
      x: Math.round((p.cumulativeDistM / 1000) * 100) / 100,
      y: p.spatialGradientDbM as number,
      label: `km ${Math.round((p.cumulativeDistM / 1000) * 100) / 100}`,
    }));

    return (
      <div className="space-y-6">
        {/* Physical Concept Banner */}
        <div className="bg-gradient-to-r from-emerald-500/10 via-teal-500/5 to-transparent border border-emerald-500/20 rounded-xl p-4 text-xs text-slate-700 dark:text-slate-300">
          <div className="flex items-start gap-2.5">
            <Shuffle className="w-5 h-5 text-emerald-600 dark:text-emerald-400 flex-shrink-0 mt-0.5" />
            <div>
              <strong className="font-bold text-slate-900 dark:text-white">Physical Phenomenon: Exploratory Multipath Propagation &amp; Spatial Rate of Change</strong>
              <p className="mt-1 text-slate-600 dark:text-slate-400 leading-relaxed">
                Multipath propagation occurs when radio waves reflect off structures, ground surfaces, and vehicles, arriving at the receiver with differing delays, amplitudes, and phase offsets. 
                In commercial drive testing, individual ray paths and Channel Impulse Responses (CIR) cannot be directly measured via Android APIs. 
                Instead, this module computes the <strong>spatial gradient</strong> (|&Delta;RSRP| / &Delta;distance in dB/m) along the vehicle path and analyzes <strong>revisited spatial locations</strong> (points within 20m revisited across time) 
                to explore localized scattering, constructive/destructive spatial variations, and delay-spread interference correlations.
              </p>
            </div>
          </div>
        </div>

        {/* Filter Toolbar */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400" /> Controls:
            </span>

            {/* Serving Cell Filter */}
            <select
              value={multipathCellFilter ?? 'all'}
              onChange={(e) => setMultipathCellFilter(e.target.value === 'all' ? null : Number(e.target.value))}
              className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none"
            >
              <option value="all">All Serving Cells</option>
              {multipathData.availableCellIds.map((c) => (
                <option key={c} value={c}>
                  PCI {c}
                </option>
              ))}
            </select>

            {/* Gradient Threshold */}
            <div className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
              <span>High Gradient Threshold:</span>
              <select
                value={multipathGradientThreshold}
                onChange={(e) => setMultipathGradientThreshold(Number(e.target.value))}
                className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2 py-1.5 focus:outline-none"
              >
                <option value={0.3}>0.3 dB/m (Sensitive)</option>
                <option value={0.4}>0.4 dB/m (Nominal)</option>
                <option value={0.6}>0.6 dB/m (Extreme Spatial Gradient)</option>
              </select>
            </div>
          </div>

          <div className="text-xs text-slate-500 font-mono">
            Spatial Points: <strong className="text-slate-900 dark:text-white">{multipathData.totalPoints}</strong> | High Variation: <strong className="text-emerald-600 dark:text-emerald-400">{multipathData.highGradientPct}%</strong>
          </div>
        </div>

        {/* KPI Cards */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Mean Spatial Gradient</span>
            <span className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">{multipathData.meanSpatialGradientDbM} <span className="text-xs font-normal">dB/m</span></span>
            <span className="text-[10px] text-slate-400 block">Spatial rate of change</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Max Spatial Gradient</span>
            <span className="text-2xl font-bold font-mono text-rose-600 dark:text-rose-400">{multipathData.maxSpatialGradientDbM} <span className="text-xs font-normal">dB/m</span></span>
            <span className="text-[10px] text-slate-400 block">Sharpest spatial delta</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">High Variation Points</span>
            <span className="text-2xl font-bold font-mono text-amber-600 dark:text-amber-400">{multipathData.highGradientPointsCount}</span>
            <span className="text-[10px] text-slate-400 block">{multipathData.highGradientPct}% of total fixes</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Revisited Clusters</span>
            <span className="text-2xl font-bold font-mono text-purple-600 dark:text-purple-400">{multipathData.detectedRevisitClustersCount}</span>
            <span className="text-[10px] text-slate-400 block">Within 20m radius</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Mean Revisit &Delta;RSRP</span>
            <span className="text-2xl font-bold font-mono text-cyan-600 dark:text-cyan-400">{multipathData.meanRevisitDeltaDb !== null ? `${multipathData.meanRevisitDeltaDb} dB` : '—'}</span>
            <span className="text-[10px] text-slate-400 block">Identical location delta</span>
          </div>
        </div>

        {/* Primary Chart: Spatial Rate of Change vs Distance */}
        {renderSvgLineChart(
          'Spatial Rate of Change (|&Delta;RSRP| / &Delta;Distance in dB/meter)',
          'Measures signal gradient per meter moved. Values above 0.4 dB/m represent rapid spatial variation.',
          gradVsDist,
          'Cumulative Distance (km)',
          'Spatial Gradient (dB/meter)',
          [0, 2.0],
          multipathGradientThreshold // Threshold line
        )}

        {/* Detailed 4-Subheadings Explanation Box */}
        {renderDetailedExplanation(
          `The graph plots the spatial gradient |&Delta;RSRP| / &Delta;d in dB per meter of vehicle travel across the route. Average spatial gradient is ${multipathData.meanSpatialGradientDbM} dB/m, with peak spatial shifts reaching ${multipathData.maxSpatialGradientDbM} dB/m.`,
          `When a mobile station remains connected to the same serving cell without a physical obstacle, sudden high spatial gradients (> 0.4 dB/m) are consistent with localized multipath standing waves and phase-combining ripples, where scattered reflection paths construct and destruct over short physical distances.`,
          `Rapid spatial signal variation degrades demodulation efficiency and increases RSRQ/CINR fluctuation. In dense urban microcells and urban canyons, high spatial gradients cause the UE to report erratic CQI and trigger unwarranted handover measurement reports.`,
          `Android smartphones cannot directly measure channel impulse response (CIR), power delay profile (PDP), delay spread (&tau;<sub>rms</sub>), or angle of arrival (AoA). Therefore, this feature is presented as an exploratory spatial variation analysis. We strictly do not claim direct multipath ray detection without dedicated channel sounders.`
        )}

        {/* Secondary Visualizations: Spatial Map & Repeated Clusters Table */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Spatial Variation Map */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <MapPin className="w-4 h-4 text-emerald-500" /> Spatial Signal Variation &amp; Revisit Clusters Map
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Green: Smooth (&le; 0.15 dB/m) | Yellow: Moderate | Red: Rapid Spatial Variation (&gt; 0.4 dB/m). Purple circles: Revisited locations (&le; 20m).
                </p>
              </div>
            </div>
            <div
              ref={multipathMapContainerRef}
              className={`w-full h-80 rounded-lg overflow-hidden border border-slate-200 dark:border-slate-800 ${
                isDark ? 'cellscope-dark-tiles' : 'cellscope-light-tiles'
              }`}
            />
          </div>

          {/* Repeated Location Comparison Table */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm flex flex-col">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <RefreshCw className="w-4 h-4 text-purple-500" /> Repeated Location Signal Consistency Audit
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Compares signal levels recorded when passing the same geographic coordinate (&le; 20m radius) at different times.
                </p>
              </div>
              <span className="text-xs font-mono text-purple-600 dark:text-purple-400 font-bold">
                {multipathData.detectedRevisitClustersCount} matches
              </span>
            </div>

            <div className="overflow-y-auto max-h-72">
              {multipathData.revisitClusters.length === 0 ? (
                <div className="py-12 text-center text-xs text-slate-400">
                  No repeated passes at the same geographic coordinate (&le; 20m) detected along this route.
                </div>
              ) : (
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 font-mono">
                      <th className="py-2 px-2">Cluster</th>
                      <th className="py-2 px-2">Dist (m)</th>
                      <th className="py-2 px-2">Time Gap</th>
                      <th className="py-2 px-2">Initial RSRP</th>
                      <th className="py-2 px-2">Revisit RSRP</th>
                      <th className="py-2 px-2">&Delta;RSRP</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 dark:divide-slate-800 font-mono">
                    {multipathData.revisitClusters.map((c) => (
                      <tr key={c.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/50">
                        <td className="py-2 px-2 font-bold text-purple-600 dark:text-purple-400">#{c.id}</td>
                        <td className="py-2 px-2">{c.geographicDistanceM}m</td>
                        <td className="py-2 px-2 text-slate-400">{c.timeSeparationSec}s</td>
                        <td className="py-2 px-2 text-cyan-600">{c.rsrpA} dBm</td>
                        <td className="py-2 px-2 text-cyan-600">{c.rsrpB} dBm</td>
                        <td className="py-2 px-2 font-bold text-amber-600">{c.deltaRsrpDb} dB</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      </div>
    );
  };

  // ------------------------------------------------------------------------
  // RENDER: MODULE 4 (PING-PONG HANDOVER ANALYSIS)
  // ------------------------------------------------------------------------
  const renderPingPongTab = () => {
    if (!pingPongData.hasSufficientData) {
      return (
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-8 text-center text-slate-500 dark:text-slate-400 shadow-sm">
          <AlertTriangle className="w-8 h-8 text-amber-500 mx-auto mb-2 opacity-80" />
          <h4 className="font-semibold text-sm text-slate-800 dark:text-slate-200">Insufficient Handover Data</h4>
          <p className="text-xs mt-1 max-w-md mx-auto">{pingPongData.warningMessage}</p>
        </div>
      );
    }

    // Step chart data: Cell PCI vs Time
    const pciVsTime = pingPongData.timelinePoints.map((p) => ({
      x: p.timeSec,
      y: p.cellId,
      label: `Time ${p.timeSec}s | PCI ${p.cellId}`,
    }));

    return (
      <div className="space-y-6">
        {/* Physical Concept Banner */}
        <div className="bg-gradient-to-r from-rose-500/10 via-amber-500/5 to-transparent border border-rose-500/20 rounded-xl p-4 text-xs text-slate-700 dark:text-slate-300">
          <div className="flex items-start gap-2.5">
            <Zap className="w-5 h-5 text-rose-600 dark:text-rose-400 flex-shrink-0 mt-0.5" />
            <div>
              <strong className="font-bold text-slate-900 dark:text-white">Physical &amp; Network Phenomenon: Ping-Pong Handover Oscillation (3GPP TS 36.331 Event A3)</strong>
              <p className="mt-1 text-slate-600 dark:text-slate-400 leading-relaxed">
                A ping-pong handover occurs when a user equipment (UE) transitions from serving cell A to cell B and rapidly returns to cell A within a short evaluation window (typically &le; 15 seconds). 
                In 3GPP cellular networks, handovers are governed by Event A3 (Neighbor becomes offset better than PCell). 
                If the Handover Margin (HOM / hysteresis) is calibrated too narrow or Time-to-Trigger (TTT) is set too short relative to local shadow fading fluctuations, the mobile connection oscillates back and forth. 
                This causes severe signaling overhead on the MME/eNodeB, packet buffer latency spikes, and increased call drop risk. 
                This module analyzes serving cell sequences, intermediate dwell times, pre/post handover signal deltas, and distinguishes confirmed network handovers from periodic snapshot oscillations.
              </p>
            </div>
          </div>
        </div>

        {/* Filter Toolbar */}
        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5 text-rose-600 dark:text-rose-400" /> Ping-Pong Criteria:
            </span>

            {/* Configurable Time Window */}
            <div className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
              <span>Time Window (&Delta;t):</span>
              <select
                value={pingPongWindowSec}
                onChange={(e) => setPingPongWindowSec(Number(e.target.value))}
                className="bg-slate-50 dark:bg-slate-950 text-xs text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-slate-700 rounded-lg px-2.5 py-1.5 focus:outline-none"
              >
                <option value={5}>&le; 5 seconds (Extremely Rapid)</option>
                <option value={10}>&le; 10 seconds (Strict)</option>
                <option value={15}>&le; 15 seconds (Standard 3GPP Benchmark)</option>
                <option value={30}>&le; 30 seconds (Extended Mobility Window)</option>
                <option value={60}>&le; 60 seconds (Macro Oscillation)</option>
              </select>
            </div>

            {/* Confirmed Only Toggle */}
            <label className="flex items-center gap-1.5 text-xs text-slate-700 dark:text-slate-300 cursor-pointer">
              <input
                type="checkbox"
                checked={pingPongConfirmedOnly}
                onChange={(e) => setPingPongConfirmedOnly(e.target.checked)}
                className="rounded border-slate-300 text-rose-600 focus:ring-rose-500"
              />
              <span>Confirmed Handovers Only</span>
            </label>
          </div>

          <div className="text-xs text-slate-500 font-mono">
            Total Transitions: <strong className="text-slate-900 dark:text-white">{pingPongData.totalServingTransitions}</strong> | Active Loops: <strong className="text-rose-600 dark:text-rose-400">{pingPongData.candidatePingPongLoopsCount}</strong>
          </div>
        </div>

        {/* KPI Cards */}
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Total Cell Transitions</span>
            <span className="text-2xl font-bold font-mono text-cyan-600 dark:text-cyan-400">{pingPongData.totalServingTransitions}</span>
            <span className="text-[10px] text-slate-400 block">All serving cell switches</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Ping-Pong Loops (A&rarr;B&rarr;A)</span>
            <span className="text-2xl font-bold font-mono text-rose-600 dark:text-rose-400">{pingPongData.candidatePingPongLoopsCount}</span>
            <span className="text-[10px] text-slate-400 block">Within &le; {pingPongWindowSec}s window</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Confirmed Loops</span>
            <span className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">{pingPongData.confirmedPingPongLoopsCount}</span>
            <span className="text-[10px] text-slate-400 block">With explicit network HO trigger</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Inferred Oscillations</span>
            <span className="text-2xl font-bold font-mono text-amber-600 dark:text-amber-400">{pingPongData.inferredPingPongLoopsCount}</span>
            <span className="text-[10px] text-slate-400 block">Periodic snapshot reselection</span>
          </div>
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
            <span className="text-xs text-slate-500 dark:text-slate-400 block">Mean Intermediate Dwell</span>
            <span className="text-2xl font-bold font-mono text-purple-600 dark:text-purple-400">{pingPongData.meanDwellTimeSec !== null ? `${pingPongData.meanDwellTimeSec}s` : '—'}</span>
            <span className="text-[10px] text-slate-400 block">Min: {pingPongData.minDwellTimeSec ?? '—'}s | Max: {pingPongData.maxDwellTimeSec ?? '—'}s</span>
          </div>
        </div>

        {/* Primary Chart: Serving Cell PCI vs Time Step Chart */}
        {renderSvgLineChart(
          'Chronological Serving Cell Identity (PCI) vs Elapsed Time',
          `Step trajectory tracking the active serving cell. Rapid oscillations between two adjacent PCI horizontal levels identify ping-pong loops (Window: ${pingPongWindowSec}s).`,
          pciVsTime,
          'Elapsed Drive Time (seconds)',
          'Serving Physical Cell ID (PCI)'
        )}

        {/* Detailed 4-Subheadings Explanation Box */}
        {renderDetailedExplanation(
          `The graph plots the active serving Physical Cell ID (PCI) across elapsed drive-test time. A total of ${pingPongData.totalServingTransitions} serving cell switches occurred along the route, with ${pingPongData.candidatePingPongLoopsCount} satisfying the configured &le; ${pingPongWindowSec}-second ping-pong threshold.`,
          `Alternating step patterns between two identical cell identities (e.g. PCI A &rarr; PCI B &rarr; PCI A) within short dwell times (mean: ${pingPongData.meanDwellTimeSec ?? 0}s) indicate that the mobile receiver lingered in an overlapping coverage boundary with balanced signal levels. In the absence of sufficient Handover Margin (HOM) or Time-to-Trigger (TTT), small shadow variations induce rapid ping-pong switching.`,
          `Ping-pong transitions trigger excessive RRC connection reconfigurations, consume core network signaling bandwidth, induce momentary user-plane throughput throttling during buffer forwarding, and elevate call drop risk if handover commands collide with radio link degradation.`,
          `This analysis explicitly classifies events into Confirmed Ping-Pong (${pingPongData.confirmedPingPongLoopsCount}) versus Inferred Snapshot Oscillations (${pingPongData.inferredPingPongLoopsCount}). If measurement rows represent periodic polling snapshots without explicit modem handover status events, intermediate sub-second transitions may be aliased.`
        )}

        {/* Secondary Visualizations: Transition Map & Cell-Pair Frequency Matrix */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Transition & Ping-Pong Map */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <MapPin className="w-4 h-4 text-rose-500" /> Ping-Pong Transition Vectors on Map
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Dashed vector lines connect the departure and return locations of rapid ping-pong loops.
                </p>
              </div>
              <span className="text-xs font-mono text-rose-600 dark:text-rose-400 font-bold">
                {pingPongData.candidatePingPongLoopsCount} loops
              </span>
            </div>
            <div
              ref={pingPongMapContainerRef}
              className={`w-full h-80 rounded-lg overflow-hidden border border-slate-200 dark:border-slate-800 ${
                isDark ? 'cellscope-dark-tiles' : 'cellscope-light-tiles'
              }`}
            />
          </div>

          {/* Cell-Pair Transition Frequency Matrix */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm flex flex-col">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 mb-3">
              <div>
                <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <ArrowDownUp className="w-4 h-4 text-amber-500" /> Cell-Pair Transition Frequency Matrix
                </h4>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Ranking of cell pairs involved most frequently in mobility transitions and oscillatory loops.
                </p>
              </div>
            </div>

            <div className="overflow-y-auto max-h-72">
              {pingPongData.cellPairTransitions.length === 0 ? (
                <div className="py-12 text-center text-xs text-slate-400">
                  No cell transitions recorded in the active dataset.
                </div>
              ) : (
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 font-mono">
                      <th className="py-2 px-2">Cell Pair</th>
                      <th className="py-2 px-2">Total Switches</th>
                      <th className="py-2 px-2">Forward</th>
                      <th className="py-2 px-2">Reverse</th>
                      <th className="py-2 px-2">Ping-Pong Loops</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 dark:divide-slate-800 font-mono">
                    {pingPongData.cellPairTransitions.map((pair, idx) => (
                      <tr key={idx} className="hover:bg-slate-50 dark:hover:bg-slate-800/50">
                        <td className="py-2 px-2 font-bold text-cyan-600 dark:text-cyan-400">{pair.pairKey}</td>
                        <td className="py-2 px-2 font-semibold text-slate-800 dark:text-slate-200">{pair.totalTransitions}</td>
                        <td className="py-2 px-2 text-slate-500">{pair.forwardCount} &rarr;</td>
                        <td className="py-2 px-2 text-slate-500">&larr; {pair.reverseCount}</td>
                        <td className="py-2 px-2 font-bold text-rose-600 dark:text-rose-400">
                          {pair.pingPongLoopsCount > 0 ? `${pair.pingPongLoopsCount} loops` : '0'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>

        {/* Candidate Ping-Pong Loops Table */}
        {pingPongData.loops.length > 0 && (
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
            <h4 className="text-sm font-bold text-slate-900 dark:text-white mb-3 flex items-center gap-2">
              <Zap className="w-4 h-4 text-rose-500" /> Candidate Ping-Pong Handover Events Log
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 font-mono">
                    <th className="py-2 px-3">Loop ID</th>
                    <th className="py-2 px-3">Transition Sequence</th>
                    <th className="py-2 px-3">Dwell Time</th>
                    <th className="py-2 px-3">Total Loop Elapsed</th>
                    <th className="py-2 px-3">RSRP Sequence</th>
                    <th className="py-2 px-3">&Delta;Signal</th>
                    <th className="py-2 px-3">Classification</th>
                    <th className="py-2 px-3">Timestamp Span</th>
                    {onNavigateToMap && <th className="py-2 px-3 text-right">Action</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800 font-mono">
                  {pingPongData.loops.map((lp) => (
                    <tr key={lp.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/50">
                      <td className="py-2 px-3 font-bold text-rose-600 dark:text-rose-400">#{lp.id}</td>
                      <td className="py-2 px-3 font-semibold text-slate-800 dark:text-slate-200">
                        PCI {lp.sourceCell} &rarr; <span className="text-rose-600 dark:text-rose-400">PCI {lp.intermediateCell}</span> &rarr; PCI {lp.returnCell}
                      </td>
                      <td className="py-2 px-3 font-bold text-purple-600 dark:text-purple-400">{lp.intermediateDwellSec}s</td>
                      <td className="py-2 px-3">{lp.totalElapsedSec}s</td>
                      <td className="py-2 px-3 text-cyan-600 dark:text-cyan-400">
                        {lp.rsrpBeforeDeparture ?? '—'} &rarr; {lp.rsrpOnIntermediate ?? '—'} &rarr; {lp.rsrpOnReturn ?? '—'} dBm
                      </td>
                      <td className="py-2 px-3 font-medium text-amber-600">
                        {lp.deltaSignalDepartureDb ?? 0} dB / {lp.deltaSignalReturnDb ?? 0} dB
                      </td>
                      <td className="py-2 px-3">
                        <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium ${
                          lp.isConfirmedHandover
                            ? 'bg-emerald-100 text-emerald-800 border border-emerald-300 dark:bg-emerald-950 dark:text-emerald-400'
                            : 'bg-amber-100 text-amber-800 border border-amber-300 dark:bg-amber-950 dark:text-amber-400'
                        }`}>
                          {lp.classification}
                        </span>
                      </td>
                      <td className="py-2 px-3 text-slate-400">
                        {lp.initialTransitionTime.slice(11, 19)} &rarr; {lp.returnTransitionTime.slice(11, 19)}
                      </td>
                      {onNavigateToMap && (
                        <td className="py-2 px-3 text-right">
                          {lp.startLat !== null && lp.startLon !== null && (
                            <button
                              onClick={() => onNavigateToMap(lp.startLat!, lp.startLon!)}
                              className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-sans font-medium rounded bg-slate-100 dark:bg-slate-800 hover:bg-cyan-50 dark:hover:bg-cyan-950 text-slate-700 dark:text-slate-300 hover:text-cyan-700 dark:hover:text-cyan-300 transition-colors"
                              title="Focus transition coordinate on interactive coverage map"
                            >
                              <ExternalLink className="w-3 h-3" /> Map
                            </button>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    );
  };

  // ------------------------------------------------------------------------
  // MAIN PANEL RENDER
  // ------------------------------------------------------------------------
  return (
    <div className="space-y-6 transition-colors duration-150">
      {/* Top Header & Sub-Tab Navigation */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-4 border-b border-slate-200 dark:border-slate-800">
          <div>
            <div className="flex items-center gap-2">
              <div className="bg-cyan-500/10 p-2 rounded-lg text-cyan-600 dark:text-cyan-400">
                <Radio className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  Radio Propagation &amp; Mobility Analysis
                  <span className="text-[10px] uppercase font-semibold px-2 py-0.5 bg-cyan-100 text-cyan-800 dark:bg-cyan-950 dark:text-cyan-400 rounded-full border border-cyan-300 dark:border-cyan-800">
                    BSc Project Suite
                  </span>
                </h2>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Comprehensive empirical analysis of slow fading (shadowing), signal variability, spatial multipath indicators, and ping-pong handovers.
                </p>
              </div>
            </div>
          </div>

          {/* Sub-Nav Tabs */}
          <div className="flex items-center gap-1.5 p-1 bg-slate-100 dark:bg-slate-950 rounded-lg border border-slate-200 dark:border-slate-800 self-start md:self-auto overflow-x-auto max-w-full">
            <button
              onClick={() => setActiveSubTab('shadowing')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all whitespace-nowrap ${
                activeSubTab === 'shadowing'
                  ? 'bg-cyan-600 text-white shadow-sm'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Radio className="w-3.5 h-3.5" />
              <span>Shadowing Analysis</span>
            </button>
            <button
              onClick={() => setActiveSubTab('fluctuation')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all whitespace-nowrap ${
                activeSubTab === 'fluctuation'
                  ? 'bg-purple-600 text-white shadow-sm'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Activity className="w-3.5 h-3.5" />
              <span>Signal Fluctuation</span>
            </button>
            <button
              onClick={() => setActiveSubTab('multipath')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all whitespace-nowrap ${
                activeSubTab === 'multipath'
                  ? 'bg-emerald-600 text-white shadow-sm'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Shuffle className="w-3.5 h-3.5" />
              <span>Multipath Propagation</span>
            </button>
            <button
              onClick={() => setActiveSubTab('pingpong')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all whitespace-nowrap ${
                activeSubTab === 'pingpong'
                  ? 'bg-rose-600 text-white shadow-sm'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
              }`}
            >
              <Zap className="w-3.5 h-3.5" />
              <span>Ping-Pong Handover</span>
            </button>
          </div>
        </div>

        {/* Sub-Tab Content Rendering */}
        <div className="mt-5">
          {activeSubTab === 'shadowing' && renderShadowingTab()}
          {activeSubTab === 'fluctuation' && renderFluctuationTab()}
          {activeSubTab === 'multipath' && renderMultipathTab()}
          {activeSubTab === 'pingpong' && renderPingPongTab()}
        </div>
      </div>
    </div>
  );
};
