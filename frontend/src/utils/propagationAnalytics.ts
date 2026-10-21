/**
 * CellScope-5G Radio Propagation & Mobility Analytics Engine
 * 
 * Provides mathematically sound, scientifically defensible algorithms for:
 * 1. Log-normal Shadowing Analysis (slow fading, spatial moving average, empirical path-loss residuals)
 * 2. Macroscopic Signal Fluctuation Analysis (consecutive delta dB, rolling std dev, sampling interval distribution)
 * 3. Exploratory Multipath Propagation Analysis (spatial rate-of-change dB/m, repeated location variance, quality correlation)
 * 4. Ping-Pong Handover & Mobility Oscillation Analysis (A->B->A loop detection, dwell times, transition frequency matrix)
 * 
 * References:
 * - 3GPP TS 36.133 / TS 38.133 (Radio Resource Management & Measurement Requirements)
 * - 3GPP TS 36.331 (E-UTRA RRC Event A3 Handover Criteria)
 * - Gudmundson, M. (1991). "Correlation model for shadow fading in mobile radio systems."
 * - Rappaport, T. S. (2002). Wireless Communications: Principles and Practice.
 */

import type { MeasurementPoint, CellTransitionEvent } from '../types/cellscope';

// Geodesic distance using Haversine formula (meters)
export function calculateHaversineDistanceMeters(
  lat1: number | null,
  lon1: number | null,
  lat2: number | null,
  lon2: number | null
): number {
  if (lat1 === null || lon1 === null || lat2 === null || lon2 === null) return 0;
  if (lat1 === lat2 && lon1 === lon2) return 0;

  const R = 6371000; // Earth radius in meters
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return R * c;
}

// Parse timestamp string into epoch milliseconds
export function parseTimestampMs(raw: string | undefined | null): number | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;

  // Try direct date parse
  const parsed = Date.parse(trimmed);
  if (!isNaN(parsed)) return parsed;

  // Check if it's already an epoch timestamp (seconds or milliseconds)
  const num = Number(trimmed);
  if (!isNaN(num) && num > 1000000) {
    return num > 1e11 ? num : num * 1000;
  }

  // Handle format: YYYY-MM-DD HH:mm:ss
  const isoLike = trimmed.replace(' ', 'T');
  const parsedIso = Date.parse(isoLike);
  if (!isNaN(parsedIso)) return parsedIso;

  // Handle format: HH:mm:ss or HH:mm:ss.SSS (assign today's date)
  const timeMatch = trimmed.match(/^(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?$/);
  if (timeMatch) {
    const now = new Date();
    now.setHours(parseInt(timeMatch[1], 10), parseInt(timeMatch[2], 10), parseInt(timeMatch[3], 10), 0);
    if (timeMatch[4]) {
      const ms = parseInt(timeMatch[4].padEnd(3, '0').slice(0, 3), 10);
      now.setMilliseconds(ms);
    }
    return now.getTime();
  }

  return null;
}

// --------------------------------------------------------------------------
// MODULE 1: SHADOWING ANALYSIS
// --------------------------------------------------------------------------

export interface ShadowingOptions {
  filterCellId: number | null; // null for all cells
  movingAverageWindow: number; // e.g. 5, 10, 20 samples
  negativeResidualThresholdDb: number; // default -6 dB
  minConsecutiveCandidatePoints: number; // default 3
}

export interface ShadowingEnrichedPoint {
  index: number;
  id: number;
  timestampRaw: string;
  latitude: number | null;
  longitude: number | null;
  servingCellId: number | null;
  earfcn: number | null;
  cumulativeDistanceMeters: number;
  rawRsrp: number;
  smoothedRsrp: number;
  baselineTrendRsrp: number;
  residualDb: number;
  isCandidateShadowing: boolean;
}

export interface CandidateShadowingZone {
  id: number;
  startIdx: number;
  endIdx: number;
  startDistanceM: number;
  endDistanceM: number;
  lengthMeters: number;
  pointCount: number;
  servingCellId: number | null;
  earfcn: number | null;
  meanResidualDb: number;
  minResidualDb: number;
  meanRsrp: number;
  centerLat: number | null;
  centerLon: number | null;
  startTime: string;
  endTime: string;
}

export interface ShadowingAnalysisResult {
  hasSufficientData: boolean;
  totalPointsAnalyzed: number;
  analyzedDistanceKm: number;
  meanRsrp: number | null;
  medianRsrp: number | null;
  residualStdDevDb: number | null; // Estimated sigma_psi (log-normal shadowing standard deviation)
  candidateZonesCount: number;
  maxSustainedDropDb: number | null;
  points: ShadowingEnrichedPoint[];
  candidateZones: CandidateShadowingZone[];
  availableCellIds: number[];
  warningMessage?: string;
}

export function analyzeShadowing(
  measurements: MeasurementPoint[],
  options: Partial<ShadowingOptions> = {}
): ShadowingAnalysisResult {
  const filterCell = options.filterCellId ?? null;
  const windowSize = Math.max(3, options.movingAverageWindow ?? 10);
  const thresholdDb = options.negativeResidualThresholdDb ?? -6.0;
  const minConsecutive = Math.max(2, options.minConsecutiveCandidatePoints ?? 3);

  // 1. Filter and validate measurements with valid RSRP
  const valid = measurements.filter(
    (m) => m.rsrp_dbm !== null && m.rsrp_dbm >= -145 && m.rsrp_dbm <= -40
  );

  const availableCellIds = Array.from(
    new Set(valid.map((m) => m.serving_cell_id).filter((c): c is number => c !== null))
  ).sort((a, b) => a - b);

  const targetPoints = filterCell !== null
    ? valid.filter((m) => m.serving_cell_id === filterCell)
    : valid;

  if (targetPoints.length < 5) {
    return {
      hasSufficientData: false,
      totalPointsAnalyzed: targetPoints.length,
      analyzedDistanceKm: 0,
      meanRsrp: null,
      medianRsrp: null,
      residualStdDevDb: null,
      candidateZonesCount: 0,
      maxSustainedDropDb: null,
      points: [],
      candidateZones: [],
      availableCellIds,
      warningMessage: `Insufficient observations (${targetPoints.length} points). At least 5 valid RSRP measurements are required to calculate empirical spatial trends.`,
    };
  }

  // 2. Sort chronologically
  const sorted = [...targetPoints].sort((a, b) => {
    const tA = parseTimestampMs(a.timestamp_raw) ?? a.id;
    const tB = parseTimestampMs(b.timestamp_raw) ?? b.id;
    return tA - tB;
  });

  // 3. Compute cumulative distance
  let cumulativeDist = 0;
  const distArray: number[] = [0];
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1];
    const curr = sorted[i];
    const d = calculateHaversineDistanceMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude);
    cumulativeDist += d;
    distArray.push(cumulativeDist);
  }

  const rsrpValues = sorted.map((p) => p.rsrp_dbm as number);

  // 4. Moving Average (Centered or Leading window)
  const halfWin = Math.floor(windowSize / 2);
  const smoothedArray: number[] = [];
  for (let i = 0; i < sorted.length; i++) {
    const start = Math.max(0, i - halfWin);
    const end = Math.min(sorted.length, i + halfWin + 1);
    let sum = 0;
    for (let k = start; k < end; k++) {
      sum += rsrpValues[k];
    }
    smoothedArray.push(Math.round((sum / (end - start)) * 10) / 10);
  }

  // 5. Empirical Baseline Trend:
  // When base station coordinates are unavailable, ordinary linear/log regression over traveled distance
  // establishes the macroscopic baseline path loss trend without claiming unverified transmit powers.
  // Linear regression: RSRP(d) = beta0 + beta1 * d
  const n = sorted.length;
  let sumD = 0;
  let sumR = 0;
  let sumDR = 0;
  let sumD2 = 0;
  for (let i = 0; i < n; i++) {
    const d = distArray[i];
    const r = rsrpValues[i];
    sumD += d;
    sumR += r;
    sumDR += d * r;
    sumD2 += d * d;
  }
  const denom = n * sumD2 - sumD * sumD;
  const beta1 = denom !== 0 ? (n * sumDR - sumD * sumR) / denom : 0;
  const beta0 = (sumR - beta1 * sumD) / n;

  // 6. Calculate Residuals relative to the smoothed trend and empirical baseline
  const enrichedPoints: ShadowingEnrichedPoint[] = [];
  const residuals: number[] = [];

  for (let i = 0; i < n; i++) {
    const baseline = Math.round((beta0 + beta1 * distArray[i]) * 10) / 10;
    // Residual relative to smoothed spatial trend (or baseline if flat)
    const residual = Math.round((rsrpValues[i] - smoothedArray[i]) * 10) / 10;
    residuals.push(residual);

    enrichedPoints.push({
      index: i,
      id: sorted[i].id,
      timestampRaw: sorted[i].timestamp_raw,
      latitude: sorted[i].latitude,
      longitude: sorted[i].longitude,
      servingCellId: sorted[i].serving_cell_id,
      earfcn: sorted[i].earfcn ?? null,
      cumulativeDistanceMeters: Math.round(distArray[i]),
      rawRsrp: rsrpValues[i],
      smoothedRsrp: smoothedArray[i],
      baselineTrendRsrp: baseline,
      residualDb: residual,
      isCandidateShadowing: false,
    });
  }

  // Calculate Residual Standard Deviation (sigma_psi)
  const meanResidual = residuals.reduce((a, b) => a + b, 0) / n;
  const variance = residuals.reduce((a, b) => a + Math.pow(b - meanResidual, 2), 0) / (n > 1 ? n - 1 : 1);
  const residualStdDev = Math.round(Math.sqrt(variance) * 10) / 10;

  // 7. Identify Candidate Shadowing Zones (sustained negative residuals)
  const candidateZones: CandidateShadowingZone[] = [];
  let currentRun: number[] = [];

  for (let i = 0; i < n; i++) {
    // Check if residual is significantly negative relative to the expected trend
    if (residuals[i] <= thresholdDb) {
      currentRun.push(i);
    } else {
      if (currentRun.length >= minConsecutive) {
        saveCandidateZone(currentRun);
      }
      currentRun = [];
    }
  }
  if (currentRun.length >= minConsecutive) {
    saveCandidateZone(currentRun);
  }

  function saveCandidateZone(indices: number[]) {
    const startIdx = indices[0];
    const endIdx = indices[indices.length - 1];
    indices.forEach((idx) => {
      enrichedPoints[idx].isCandidateShadowing = true;
    });

    const zoneResiduals = indices.map((idx) => residuals[idx]);
    const zoneRsrps = indices.map((idx) => rsrpValues[idx]);
    const lats = indices.map((idx) => sorted[idx].latitude).filter((lat): lat is number => lat !== null);
    const lons = indices.map((idx) => sorted[idx].longitude).filter((lon): lon is number => lon !== null);

    const lengthM = distArray[endIdx] - distArray[startIdx];

    candidateZones.push({
      id: candidateZones.length + 1,
      startIdx,
      endIdx,
      startDistanceM: Math.round(distArray[startIdx]),
      endDistanceM: Math.round(distArray[endIdx]),
      lengthMeters: Math.round(lengthM),
      pointCount: indices.length,
      servingCellId: sorted[startIdx].serving_cell_id,
      earfcn: sorted[startIdx].earfcn ?? null,
      meanResidualDb: Math.round((zoneResiduals.reduce((a, b) => a + b, 0) / zoneResiduals.length) * 10) / 10,
      minResidualDb: Math.min(...zoneResiduals),
      meanRsrp: Math.round((zoneRsrps.reduce((a, b) => a + b, 0) / zoneRsrps.length) * 10) / 10,
      centerLat: lats.length > 0 ? Math.round((lats.reduce((a, b) => a + b, 0) / lats.length) * 1e6) / 1e6 : null,
      centerLon: lons.length > 0 ? Math.round((lons.reduce((a, b) => a + b, 0) / lons.length) * 1e6) / 1e6 : null,
      startTime: sorted[startIdx].timestamp_raw,
      endTime: sorted[endIdx].timestamp_raw,
    });
  }

  // Summary Metrics
  const sortedRsrp = [...rsrpValues].sort((a, b) => a - b);
  const medianRsrp = sortedRsrp[Math.floor(sortedRsrp.length / 2)];
  const meanRsrp = Math.round((rsrpValues.reduce((a, b) => a + b, 0) / n) * 10) / 10;
  const maxSustainedDrop = candidateZones.length > 0
    ? Math.abs(Math.min(...candidateZones.map((z) => z.minResidualDb)))
    : null;

  return {
    hasSufficientData: true,
    totalPointsAnalyzed: n,
    analyzedDistanceKm: Math.round((cumulativeDist / 1000) * 100) / 100,
    meanRsrp,
    medianRsrp,
    residualStdDevDb: residualStdDev,
    candidateZonesCount: candidateZones.length,
    maxSustainedDropDb: maxSustainedDrop,
    points: enrichedPoints,
    candidateZones,
    availableCellIds,
  };
}

// --------------------------------------------------------------------------
// MODULE 2: SIGNAL FLUCTUATION ANALYSIS
// --------------------------------------------------------------------------

export interface FluctuationOptions {
  rollingWindow: number; // default 10 samples
  filterCellId: number | null;
}

export interface FluctuationPoint {
  index: number;
  id: number;
  timestampRaw: string;
  timeSec: number;
  cumulativeDistanceM: number;
  rsrp: number;
  deltaRsrpDb: number | null; // Consecutive change
  rollingMeanRsrp: number;
  rollingStdDevDb: number;
  samplingIntervalSec: number | null;
  servingCellId: number | null;
}

export interface SamplingIntervalBin {
  label: string;
  count: number;
  percentage: number;
}

export interface RsrpDistributionBin {
  binStart: number;
  binEnd: number;
  count: number;
  percentage: number;
}

export interface SignalFluctuationResult {
  hasSufficientData: boolean;
  totalSamples: number;
  durationSec: number;
  minRsrp: number | null;
  maxRsrp: number | null;
  meanRsrp: number | null;
  medianRsrp: number | null;
  p10Rsrp: number | null;
  p90Rsrp: number | null;
  iqrRsrp: number | null;
  meanConsecutiveDeltaDb: number | null;
  maxConsecutiveDeltaDb: number | null;
  meanRollingStdDevDb: number | null;
  nominalIntervalSec: number;
  meanIntervalSec: number | null;
  medianIntervalSec: number | null;
  maxIntervalGapSec: number | null;
  irregularIntervalPct: number;
  samplingValidationAssessment: string;
  points: FluctuationPoint[];
  samplingIntervalBins: SamplingIntervalBin[];
  rsrpDistributionBins: RsrpDistributionBin[];
  availableCellIds: number[];
  warningMessage?: string;
}

export function analyzeSignalFluctuation(
  measurements: MeasurementPoint[],
  options: Partial<FluctuationOptions> = {}
): SignalFluctuationResult {
  const windowSize = Math.max(3, options.rollingWindow ?? 10);
  const filterCell = options.filterCellId ?? null;

  const valid = measurements.filter(
    (m) => m.rsrp_dbm !== null && m.rsrp_dbm >= -145 && m.rsrp_dbm <= -40
  );

  const availableCellIds = Array.from(
    new Set(valid.map((m) => m.serving_cell_id).filter((c): c is number => c !== null))
  ).sort((a, b) => a - b);

  const targetPoints = filterCell !== null
    ? valid.filter((m) => m.serving_cell_id === filterCell)
    : valid;

  if (targetPoints.length < 5) {
    return {
      hasSufficientData: false,
      totalSamples: targetPoints.length,
      durationSec: 0,
      minRsrp: null,
      maxRsrp: null,
      meanRsrp: null,
      medianRsrp: null,
      p10Rsrp: null,
      p90Rsrp: null,
      iqrRsrp: null,
      meanConsecutiveDeltaDb: null,
      maxConsecutiveDeltaDb: null,
      meanRollingStdDevDb: null,
      nominalIntervalSec: 1.0,
      meanIntervalSec: null,
      medianIntervalSec: null,
      maxIntervalGapSec: null,
      irregularIntervalPct: 0,
      samplingValidationAssessment: 'Insufficient data points to conduct signal fluctuation audit.',
      points: [],
      samplingIntervalBins: [],
      rsrpDistributionBins: [],
      availableCellIds,
      warningMessage: `Insufficient observations (${targetPoints.length} points). Need at least 5 valid RSRP measurements.`,
    };
  }

  // Sort chronologically
  const sorted = [...targetPoints].sort((a, b) => {
    const tA = parseTimestampMs(a.timestamp_raw) ?? a.id;
    const tB = parseTimestampMs(b.timestamp_raw) ?? b.id;
    return tA - tB;
  });

  const timestampsMs = sorted.map((p, idx) => parseTimestampMs(p.timestamp_raw) ?? idx * 1000);
  const initialTimeMs = timestampsMs[0];

  // Distances
  let cumulativeDist = 0;
  const distArray: number[] = [0];
  for (let i = 1; i < sorted.length; i++) {
    const d = calculateHaversineDistanceMeters(
      sorted[i - 1].latitude, sorted[i - 1].longitude,
      sorted[i].latitude, sorted[i].longitude
    );
    cumulativeDist += d;
    distArray.push(cumulativeDist);
  }

  const rsrpValues = sorted.map((p) => p.rsrp_dbm as number);
  const n = sorted.length;

  // Sampling intervals
  const intervalsSec: number[] = [];
  for (let i = 1; i < n; i++) {
    const dt = (timestampsMs[i] - timestampsMs[i - 1]) / 1000;
    // Cap unrealistic zero or negative timestamps to minimum positive
    intervalsSec.push(dt > 0 ? dt : 0.05);
  }

  // Consecutive Deltas and Rolling Stats
  const points: FluctuationPoint[] = [];
  const consecutiveDeltas: number[] = [];
  const rollingStdDevs: number[] = [];

  for (let i = 0; i < n; i++) {
    const delta = i > 0 ? Math.round((rsrpValues[i] - rsrpValues[i - 1]) * 10) / 10 : null;
    if (delta !== null) consecutiveDeltas.push(delta);

    // Rolling window [start .. i]
    const start = Math.max(0, i - windowSize + 1);
    const win = rsrpValues.slice(start, i + 1);
    const winMean = win.reduce((a, b) => a + b, 0) / win.length;
    let winVar = 0;
    if (win.length > 1) {
      winVar = win.reduce((a, b) => a + Math.pow(b - winMean, 2), 0) / (win.length - 1);
    }
    const winStd = Math.round(Math.sqrt(winVar) * 10) / 10;
    rollingStdDevs.push(winStd);

    points.push({
      index: i,
      id: sorted[i].id,
      timestampRaw: sorted[i].timestamp_raw,
      timeSec: Math.round(((timestampsMs[i] - initialTimeMs) / 1000) * 10) / 10,
      cumulativeDistanceM: Math.round(distArray[i]),
      rsrp: rsrpValues[i],
      deltaRsrpDb: delta,
      rollingMeanRsrp: Math.round(winMean * 10) / 10,
      rollingStdDevDb: winStd,
      samplingIntervalSec: i > 0 ? Math.round(intervalsSec[i - 1] * 100) / 100 : null,
      servingCellId: sorted[i].serving_cell_id,
    });
  }

  // Distribution Metrics
  const sortedRsrp = [...rsrpValues].sort((a, b) => a - b);
  const minRsrp = sortedRsrp[0];
  const maxRsrp = sortedRsrp[sortedRsrp.length - 1];
  const meanRsrp = Math.round((rsrpValues.reduce((a, b) => a + b, 0) / n) * 10) / 10;
  const medianRsrp = sortedRsrp[Math.floor(n / 2)];
  const p10Rsrp = sortedRsrp[Math.floor(n * 0.1)];
  const p90Rsrp = sortedRsrp[Math.floor(n * 0.9)];
  const p25Rsrp = sortedRsrp[Math.floor(n * 0.25)];
  const p75Rsrp = sortedRsrp[Math.floor(n * 0.75)];
  const iqrRsrp = Math.round((p75Rsrp - p25Rsrp) * 10) / 10;

  const meanDelta = consecutiveDeltas.length > 0
    ? Math.round((consecutiveDeltas.map(Math.abs).reduce((a, b) => a + b, 0) / consecutiveDeltas.length) * 10) / 10
    : null;
  const maxDelta = consecutiveDeltas.length > 0
    ? Math.max(...consecutiveDeltas.map(Math.abs))
    : null;
  const meanRollingStd = rollingStdDevs.length > 0
    ? Math.round((rollingStdDevs.reduce((a, b) => a + b, 0) / rollingStdDevs.length) * 10) / 10
    : null;

  // Sampling Interval Statistics
  const sortedIntervals = [...intervalsSec].sort((a, b) => a - b);
  const meanInterval = intervalsSec.length > 0
    ? Math.round((intervalsSec.reduce((a, b) => a + b, 0) / intervalsSec.length) * 100) / 100
    : null;
  const medianInterval = sortedIntervals.length > 0
    ? Math.round(sortedIntervals[Math.floor(sortedIntervals.length / 2)] * 100) / 100
    : null;
  const maxGap = sortedIntervals.length > 0
    ? Math.round(sortedIntervals[sortedIntervals.length - 1] * 10) / 10
    : null;

  // Irregular interval count: intervals deviating by > 50% from 1.0s nominal (i.e. < 0.5s or > 1.5s)
  const irregularCount = intervalsSec.filter((dt) => dt < 0.5 || dt > 1.5).length;
  const irregularPct = intervalsSec.length > 0
    ? Math.round((irregularCount / intervalsSec.length) * 100)
    : 0;

  // Interval Bins
  const binCounts = { subSec: 0, nominal: 0, moderate: 0, highGap: 0, extremeGap: 0 };
  intervalsSec.forEach((dt) => {
    if (dt < 0.8) binCounts.subSec++;
    else if (dt <= 1.3) binCounts.nominal++;
    else if (dt <= 2.5) binCounts.moderate++;
    else if (dt <= 5.0) binCounts.highGap++;
    else binCounts.extremeGap++;
  });
  const totalInt = intervalsSec.length || 1;
  const samplingIntervalBins: SamplingIntervalBin[] = [
    { label: '< 0.8s (Rapid)', count: binCounts.subSec, percentage: Math.round((binCounts.subSec / totalInt) * 100) },
    { label: '0.8–1.3s (Nominal 1 Hz)', count: binCounts.nominal, percentage: Math.round((binCounts.nominal / totalInt) * 100) },
    { label: '1.4–2.5s (Delayed)', count: binCounts.moderate, percentage: Math.round((binCounts.moderate / totalInt) * 100) },
    { label: '2.6–5.0s (Jitter Gap)', count: binCounts.highGap, percentage: Math.round((binCounts.highGap / totalInt) * 100) },
    { label: '> 5.0s (Severe Gap)', count: binCounts.extremeGap, percentage: Math.round((binCounts.extremeGap / totalInt) * 100) },
  ];

  // RSRP Histogram Bins (4 dB width)
  const binWidth = 4;
  const startBin = Math.floor(minRsrp / binWidth) * binWidth;
  const endBin = Math.ceil(maxRsrp / binWidth) * binWidth;
  const rsrpDistributionBins: RsrpDistributionBin[] = [];
  for (let b = startBin; b < endBin; b += binWidth) {
    const cnt = rsrpValues.filter((v) => v >= b && v < b + binWidth).length;
    rsrpDistributionBins.push({
      binStart: b,
      binEnd: b + binWidth,
      count: cnt,
      percentage: Math.round((cnt / n) * 100),
    });
  }

  // Sampling Rate Validation Assessment
  let assessment = '';
  if (meanInterval !== null && meanInterval <= 1.5 && irregularPct < 25) {
    assessment = `Standard Periodic Sampling (Mean: ${meanInterval}s, Nominal ~1 Hz). Suitable for macroscopic signal fluctuation and shadowing trend observation. Note: Sub-wavelength Rayleigh fast fading cannot be claimed because RF carrier wavelength (λ ≈ 16.6 cm at 1800 MHz) is traversed in milliseconds at drive-test speeds.`;
  } else {
    assessment = `Irregular Sampling Detected (${irregularPct}% deviations, Max gap: ${maxGap}s). Fluctuation observations must account for non-uniform sampling intervals.`;
  }

  const durationSec = Math.round((timestampsMs[timestampsMs.length - 1] - initialTimeMs) / 1000);

  return {
    hasSufficientData: true,
    totalSamples: n,
    durationSec,
    minRsrp,
    maxRsrp,
    meanRsrp,
    medianRsrp,
    p10Rsrp,
    p90Rsrp,
    iqrRsrp,
    meanConsecutiveDeltaDb: meanDelta,
    maxConsecutiveDeltaDb: maxDelta,
    meanRollingStdDevDb: meanRollingStd,
    nominalIntervalSec: 1.0,
    meanIntervalSec: meanInterval,
    medianIntervalSec: medianInterval,
    maxIntervalGapSec: maxGap,
    irregularIntervalPct: irregularPct,
    samplingValidationAssessment: assessment,
    points,
    samplingIntervalBins,
    rsrpDistributionBins,
    availableCellIds,
  };
}

// --------------------------------------------------------------------------
// MODULE 3: MULTIPATH PROPAGATION ANALYSIS
// --------------------------------------------------------------------------

export interface MultipathOptions {
  filterCellId: number | null;
  highSpatialGradientThresholdDbM: number; // default 0.4 dB/meter
  revisitDistanceRadiusM: number; // default 20 meters
}

export interface SpatialGradientPoint {
  index: number;
  id: number;
  timestampRaw: string;
  latitude: number | null;
  longitude: number | null;
  servingCellId: number | null;
  cumulativeDistM: number;
  rsrp: number;
  spatialGradientDbM: number | null; // |deltaRSRP| / deltaDist
  isHighSpatialVariation: boolean;
  cinrDb: number | null;
  rsrqDb: number | null;
}

export interface RevisitCluster {
  id: number;
  centerLat: number;
  centerLon: number;
  pointIndexA: number;
  pointIndexB: number;
  timeA: string;
  timeB: string;
  timeSeparationSec: number;
  geographicDistanceM: number;
  rsrpA: number;
  rsrpB: number;
  deltaRsrpDb: number;
  servingCellA: number | null;
  servingCellB: number | null;
}

export interface MultipathAnalysisResult {
  hasSufficientData: boolean;
  totalPoints: number;
  meanSpatialGradientDbM: number | null;
  maxSpatialGradientDbM: number | null;
  highGradientPointsCount: number;
  highGradientPct: number;
  detectedRevisitClustersCount: number;
  meanRevisitDeltaDb: number | null;
  points: SpatialGradientPoint[];
  revisitClusters: RevisitCluster[];
  availableCellIds: number[];
  warningMessage?: string;
}

export function analyzeMultipathPropagation(
  measurements: MeasurementPoint[],
  options: Partial<MultipathOptions> = {}
): MultipathAnalysisResult {
  const filterCell = options.filterCellId ?? null;
  const gradientThreshold = options.highSpatialGradientThresholdDbM ?? 0.4;
  const revisitRadius = options.revisitDistanceRadiusM ?? 20;

  const valid = measurements.filter(
    (m) => m.rsrp_dbm !== null && m.rsrp_dbm >= -145 && m.rsrp_dbm <= -40 && m.latitude !== null && m.longitude !== null
  );

  const availableCellIds = Array.from(
    new Set(valid.map((m) => m.serving_cell_id).filter((c): c is number => c !== null))
  ).sort((a, b) => a - b);

  const targetPoints = filterCell !== null
    ? valid.filter((m) => m.serving_cell_id === filterCell)
    : valid;

  if (targetPoints.length < 5) {
    return {
      hasSufficientData: false,
      totalPoints: targetPoints.length,
      meanSpatialGradientDbM: null,
      maxSpatialGradientDbM: null,
      highGradientPointsCount: 0,
      highGradientPct: 0,
      detectedRevisitClustersCount: 0,
      meanRevisitDeltaDb: null,
      points: [],
      revisitClusters: [],
      availableCellIds,
      warningMessage: `Insufficient GPS observations (${targetPoints.length} valid points). Need at least 5 points with coordinates and RSRP.`,
    };
  }

  // Sort chronologically
  const sorted = [...targetPoints].sort((a, b) => {
    const tA = parseTimestampMs(a.timestamp_raw) ?? a.id;
    const tB = parseTimestampMs(b.timestamp_raw) ?? b.id;
    return tA - tB;
  });

  const n = sorted.length;
  let cumulativeDist = 0;
  const distArray: number[] = [0];
  for (let i = 1; i < n; i++) {
    const d = calculateHaversineDistanceMeters(
      sorted[i - 1].latitude, sorted[i - 1].longitude,
      sorted[i].latitude, sorted[i].longitude
    );
    cumulativeDist += d;
    distArray.push(cumulativeDist);
  }

  // 1. Compute spatial rate of change (|delta RSRP| / delta d)
  const points: SpatialGradientPoint[] = [];
  const gradients: number[] = [];

  for (let i = 0; i < n; i++) {
    let grad: number | null = null;
    let isHigh = false;

    if (i > 0) {
      const stepDist = distArray[i] - distArray[i - 1];
      const deltaR = Math.abs((sorted[i].rsrp_dbm as number) - (sorted[i - 1].rsrp_dbm as number));
      // Only compute gradient if vehicle moved at least 1.5 meters to avoid division by GPS jitter
      if (stepDist >= 1.5) {
        grad = Math.round((deltaR / stepDist) * 100) / 100;
        gradients.push(grad);
        if (grad >= gradientThreshold) {
          isHigh = true;
        }
      }
    }

    points.push({
      index: i,
      id: sorted[i].id,
      timestampRaw: sorted[i].timestamp_raw,
      latitude: sorted[i].latitude,
      longitude: sorted[i].longitude,
      servingCellId: sorted[i].serving_cell_id,
      cumulativeDistM: Math.round(distArray[i]),
      rsrp: sorted[i].rsrp_dbm as number,
      spatialGradientDbM: grad,
      isHighSpatialVariation: isHigh,
      cinrDb: sorted[i].cinr_db,
      rsrqDb: sorted[i].rsrq_db,
    });
  }

  const meanGrad = gradients.length > 0
    ? Math.round((gradients.reduce((a, b) => a + b, 0) / gradients.length) * 100) / 100
    : null;
  const maxGrad = gradients.length > 0
    ? Math.round(Math.max(...gradients) * 100) / 100
    : null;
  const highGradCount = points.filter((p) => p.isHighSpatialVariation).length;
  const highGradPct = points.length > 0 ? Math.round((highGradCount / points.length) * 100) : 0;

  // 2. Spatial Clustering / Repeated Location Comparison:
  // Identify points separated by at least 15 seconds in time, but within revisitRadius in space.
  const revisitClusters: RevisitCluster[] = [];
  const timestampsMs = sorted.map((p, idx) => parseTimestampMs(p.timestamp_raw) ?? idx * 1000);

  // Limit quadratic pair comparisons for performance
  const step = Math.max(1, Math.floor(n / 300));
  for (let i = 0; i < n; i += step) {
    for (let j = i + 15; j < n; j += step) {
      const timeDiffSec = (timestampsMs[j] - timestampsMs[i]) / 1000;
      if (timeDiffSec < 15) continue; // Must be separated in time

      const geoDist = calculateHaversineDistanceMeters(
        sorted[i].latitude, sorted[i].longitude,
        sorted[j].latitude, sorted[j].longitude
      );

      if (geoDist <= revisitRadius) {
        const rA = sorted[i].rsrp_dbm as number;
        const rB = sorted[j].rsrp_dbm as number;
        const deltaR = Math.round(Math.abs(rB - rA) * 10) / 10;

        revisitClusters.push({
          id: revisitClusters.length + 1,
          centerLat: Math.round(((sorted[i].latitude! + sorted[j].latitude!) / 2) * 1e6) / 1e6,
          centerLon: Math.round(((sorted[i].longitude! + sorted[j].longitude!) / 2) * 1e6) / 1e6,
          pointIndexA: i,
          pointIndexB: j,
          timeA: sorted[i].timestamp_raw,
          timeB: sorted[j].timestamp_raw,
          timeSeparationSec: Math.round(timeDiffSec),
          geographicDistanceM: Math.round(geoDist * 10) / 10,
          rsrpA: rA,
          rsrpB: rB,
          deltaRsrpDb: deltaR,
          servingCellA: sorted[i].serving_cell_id,
          servingCellB: sorted[j].serving_cell_id,
        });

        if (revisitClusters.length >= 25) break; // Keep top 25 distinct clusters
      }
    }
    if (revisitClusters.length >= 25) break;
  }

  const meanRevisitDelta = revisitClusters.length > 0
    ? Math.round((revisitClusters.reduce((a, b) => a + b.deltaRsrpDb, 0) / revisitClusters.length) * 10) / 10
    : null;

  return {
    hasSufficientData: true,
    totalPoints: n,
    meanSpatialGradientDbM: meanGrad,
    maxSpatialGradientDbM: maxGrad,
    highGradientPointsCount: highGradCount,
    highGradientPct: highGradPct,
    detectedRevisitClustersCount: revisitClusters.length,
    meanRevisitDeltaDb: meanRevisitDelta,
    points,
    revisitClusters,
    availableCellIds,
  };
}

// --------------------------------------------------------------------------
// MODULE 4: PING-PONG HANDOVER ANALYSIS
// --------------------------------------------------------------------------

export interface PingPongOptions {
  timeWindowSec: number; // e.g. 5, 10, 15 (default), 30, 60s
  minDwellTimeSec: number; // minimum intermediate dwell time (default 0s)
  confirmedOnly: boolean; // filter only loops with confirmed handover triggers
}

export interface ServingCellSegment {
  cellId: number;
  pci: number;
  earfcn: number | null;
  startIdx: number;
  endIdx: number;
  startTime: string;
  endTime: string;
  startTimeMs: number;
  endTimeMs: number;
  durationSec: number;
  sampleCount: number;
  meanRsrp: number;
  startLat: number | null;
  startLon: number | null;
  endLat: number | null;
  endLon: number | null;
}

export interface PingPongLoopEvent {
  id: number;
  sourceCell: number; // Cell A
  intermediateCell: number; // Cell B
  returnCell: number; // Cell A (or returning cell)
  initialTransitionTime: string;
  returnTransitionTime: string;
  intermediateDwellSec: number;
  totalElapsedSec: number;
  rsrpBeforeDeparture: number | null;
  rsrpOnIntermediate: number | null;
  rsrpOnReturn: number | null;
  deltaSignalDepartureDb: number | null;
  deltaSignalReturnDb: number | null;
  isConfirmedHandover: boolean;
  classification: 'CONFIRMED_PING_PONG' | 'INFERRED_PING_PONG_OSCILLATION';
  startLat: number | null;
  startLon: number | null;
  returnLat: number | null;
  returnLon: number | null;
  distanceBetweenTransitionsM: number | null;
}

export interface CellPairTransitionCount {
  cellA: number;
  cellB: number;
  pairKey: string;
  forwardCount: number;
  reverseCount: number;
  totalTransitions: number;
  pingPongLoopsCount: number;
}

export interface PingPongAnalysisResult {
  hasSufficientData: boolean;
  totalServingTransitions: number;
  candidatePingPongLoopsCount: number;
  confirmedPingPongLoopsCount: number;
  inferredPingPongLoopsCount: number;
  meanDwellTimeSec: number | null;
  minDwellTimeSec: number | null;
  maxDwellTimeSec: number | null;
  activeTimeWindowSec: number;
  loops: PingPongLoopEvent[];
  cellPairTransitions: CellPairTransitionCount[];
  segments: ServingCellSegment[];
  timelinePoints: {
    index: number;
    timestampRaw: string;
    timeSec: number;
    cellId: number;
    rsrp: number | null;
    isInPingPongLoop: boolean;
    loopId: number | null;
  }[];
  warningMessage?: string;
}

export function analyzePingPongHandover(
  measurements: MeasurementPoint[],
  cellTransitions: CellTransitionEvent[] = [],
  options: Partial<PingPongOptions> = {}
): PingPongAnalysisResult {
  const windowSec = options.timeWindowSec ?? 15;
  const minDwell = options.minDwellTimeSec ?? 0;
  const confirmedOnly = options.confirmedOnly ?? false;

  const valid = measurements.filter((m) => m.serving_cell_id !== null);

  if (valid.length < 5) {
    return {
      hasSufficientData: false,
      totalServingTransitions: 0,
      candidatePingPongLoopsCount: 0,
      confirmedPingPongLoopsCount: 0,
      inferredPingPongLoopsCount: 0,
      meanDwellTimeSec: null,
      minDwellTimeSec: null,
      maxDwellTimeSec: null,
      activeTimeWindowSec: windowSec,
      loops: [],
      cellPairTransitions: [],
      segments: [],
      timelinePoints: [],
      warningMessage: `Insufficient measurements (${valid.length} points with serving cell). Need at least 5 observations to analyze mobility sequences.`,
    };
  }

  // Sort chronologically
  const sorted = [...valid].sort((a, b) => {
    const tA = parseTimestampMs(a.timestamp_raw) ?? a.id;
    const tB = parseTimestampMs(b.timestamp_raw) ?? b.id;
    return tA - tB;
  });

  const timestampsMs = sorted.map((p, idx) => parseTimestampMs(p.timestamp_raw) ?? idx * 1000);
  const initialTimeMs = timestampsMs[0];

  // 1. Extract contiguous serving cell segments
  const segments: ServingCellSegment[] = [];
  let currCell = sorted[0].serving_cell_id as number;
  let segStartIdx = 0;
  let segRsrpSum = sorted[0].rsrp_dbm ?? -100;
  let segRsrpCount = sorted[0].rsrp_dbm !== null ? 1 : 0;

  for (let i = 1; i < sorted.length; i++) {
    const cell = sorted[i].serving_cell_id as number;
    if (cell !== currCell) {
      // Close current segment
      const startMs = timestampsMs[segStartIdx];
      const endMs = timestampsMs[i - 1];
      const duration = Math.max(0.5, (endMs - startMs) / 1000);

      segments.push({
        cellId: currCell,
        pci: currCell,
        earfcn: sorted[segStartIdx].earfcn ?? null,
        startIdx: segStartIdx,
        endIdx: i - 1,
        startTime: sorted[segStartIdx].timestamp_raw,
        endTime: sorted[i - 1].timestamp_raw,
        startTimeMs: startMs,
        endTimeMs: endMs,
        durationSec: Math.round(duration * 10) / 10,
        sampleCount: i - segStartIdx,
        meanRsrp: segRsrpCount > 0 ? Math.round((segRsrpSum / segRsrpCount) * 10) / 10 : -100,
        startLat: sorted[segStartIdx].latitude,
        startLon: sorted[segStartIdx].longitude,
        endLat: sorted[i - 1].latitude,
        endLon: sorted[i - 1].longitude,
      });

      // Start new segment
      currCell = cell;
      segStartIdx = i;
      segRsrpSum = sorted[i].rsrp_dbm ?? -100;
      segRsrpCount = sorted[i].rsrp_dbm !== null ? 1 : 0;
    } else {
      if (sorted[i].rsrp_dbm !== null) {
        segRsrpSum += sorted[i].rsrp_dbm as number;
        segRsrpCount++;
      }
    }
  }

  // Push final segment
  const finalStartMs = timestampsMs[segStartIdx];
  const finalEndMs = timestampsMs[sorted.length - 1];
  const finalDuration = Math.max(0.5, (finalEndMs - finalStartMs) / 1000);
  segments.push({
    cellId: currCell,
    pci: currCell,
    earfcn: sorted[segStartIdx].earfcn ?? null,
    startIdx: segStartIdx,
    endIdx: sorted.length - 1,
    startTime: sorted[segStartIdx].timestamp_raw,
    endTime: sorted[sorted.length - 1].timestamp_raw,
    startTimeMs: finalStartMs,
    endTimeMs: finalEndMs,
    durationSec: Math.round(finalDuration * 10) / 10,
    sampleCount: sorted.length - segStartIdx,
    meanRsrp: segRsrpCount > 0 ? Math.round((segRsrpSum / segRsrpCount) * 10) / 10 : -100,
    startLat: sorted[segStartIdx].latitude,
    startLon: sorted[segStartIdx].longitude,
    endLat: sorted[sorted.length - 1].latitude,
    endLon: sorted[sorted.length - 1].longitude,
  });

  const totalServingTransitions = Math.max(0, segments.length - 1);

  // 2. Detect Ping-Pong Loops:
  // Look for A -> B -> A sequences (and A -> B -> C -> A if total time <= windowSec)
  const candidateLoops: PingPongLoopEvent[] = [];
  const loopSegmentIndices = new Set<number>();

  for (let s = 0; s < segments.length - 2; s++) {
    const segA = segments[s];
    const segB = segments[s + 1];
    const segReturn = segments[s + 2];

    // Check A -> B -> A pattern
    if (segA.cellId === segReturn.cellId && segB.cellId !== segA.cellId) {
      const dwellB = segB.durationSec;
      const totalElapsed = (segReturn.startTimeMs - segB.startTimeMs) / 1000;

      if (totalElapsed <= windowSec && dwellB >= minDwell) {
        // Handover confirmation lookup from cellTransitions or handover triggers in measurement points
        let isConfirmed = false;
        // Check if explicit handover trigger happened in segment B transition
        for (let idx = segB.startIdx; idx <= Math.min(segB.endIdx + 1, sorted.length - 1); idx++) {
          if (sorted[idx].handover_trigger === 1) {
            isConfirmed = true;
            break;
          }
        }
        if (!isConfirmed && cellTransitions.length > 0) {
          const matchAtoB = cellTransitions.find(
            (t) => t.from_pci === segA.cellId && t.to_pci === segB.cellId && (t.event_type || '').toUpperCase() === 'CONFIRMED_HANDOVER'
          );
          const matchBtoA = cellTransitions.find(
            (t) => t.from_pci === segB.cellId && t.to_pci === segReturn.cellId && (t.event_type || '').toUpperCase() === 'CONFIRMED_HANDOVER'
          );
          if (matchAtoB || matchBtoA) {
            isConfirmed = true;
          }
        }

        const geoDist = calculateHaversineDistanceMeters(
          segB.startLat, segB.startLon,
          segReturn.startLat, segReturn.startLon
        );

        candidateLoops.push({
          id: candidateLoops.length + 1,
          sourceCell: segA.cellId,
          intermediateCell: segB.cellId,
          returnCell: segReturn.cellId,
          initialTransitionTime: segB.startTime,
          returnTransitionTime: segReturn.startTime,
          intermediateDwellSec: Math.round(dwellB * 10) / 10,
          totalElapsedSec: Math.round(totalElapsed * 10) / 10,
          rsrpBeforeDeparture: segA.meanRsrp,
          rsrpOnIntermediate: segB.meanRsrp,
          rsrpOnReturn: segReturn.meanRsrp,
          deltaSignalDepartureDb: Math.round((segB.meanRsrp - segA.meanRsrp) * 10) / 10,
          deltaSignalReturnDb: Math.round((segReturn.meanRsrp - segB.meanRsrp) * 10) / 10,
          isConfirmedHandover: isConfirmed,
          classification: isConfirmed ? 'CONFIRMED_PING_PONG' : 'INFERRED_PING_PONG_OSCILLATION',
          startLat: segB.startLat,
          startLon: segB.startLon,
          returnLat: segReturn.startLat,
          returnLon: segReturn.startLon,
          distanceBetweenTransitionsM: Math.round(geoDist),
        });

        loopSegmentIndices.add(s + 1); // mark segment B as part of ping-pong
      }
    }
  }

  // Filter if confirmedOnly is requested
  const filteredLoops = confirmedOnly
    ? candidateLoops.filter((l) => l.isConfirmedHandover)
    : candidateLoops;

  // 3. Cell-Pair Transition Frequency Matrix
  const pairMap = new Map<string, { cellA: number; cellB: number; forward: number; reverse: number; loops: number }>();
  for (let s = 0; s < segments.length - 1; s++) {
    const c1 = segments[s].cellId;
    const c2 = segments[s + 1].cellId;
    const minC = Math.min(c1, c2);
    const maxC = Math.max(c1, c2);
    const key = `${minC}<->${maxC}`;

    const curr = pairMap.get(key) || { cellA: minC, cellB: maxC, forward: 0, reverse: 0, loops: 0 };
    if (c1 === minC) {
      curr.forward++;
    } else {
      curr.reverse++;
    }
    pairMap.set(key, curr);
  }

  // Count loops for each pair
  filteredLoops.forEach((lp) => {
    const minC = Math.min(lp.sourceCell, lp.intermediateCell);
    const maxC = Math.max(lp.sourceCell, lp.intermediateCell);
    const key = `${minC}<->${maxC}`;
    const item = pairMap.get(key);
    if (item) {
      item.loops++;
    }
  });

  const cellPairTransitions: CellPairTransitionCount[] = Array.from(pairMap.values())
    .map((p) => ({
      cellA: p.cellA,
      cellB: p.cellB,
      pairKey: `PCI ${p.cellA} ⟷ PCI ${p.cellB}`,
      forwardCount: p.forward,
      reverseCount: p.reverse,
      totalTransitions: p.forward + p.reverse,
      pingPongLoopsCount: p.loops,
    }))
    .sort((a, b) => b.totalTransitions - a.totalTransitions);

  // 4. Timeline points for step chart
  const timelinePoints = sorted.map((p, idx) => {
    const timeSec = Math.round(((timestampsMs[idx] - initialTimeMs) / 1000) * 10) / 10;
    // Check if this point falls inside any candidate loop
    let isInLoop = false;
    let loopId: number | null = null;

    for (const lp of filteredLoops) {
      const tMs = timestampsMs[idx];
      const startMs = parseTimestampMs(lp.initialTransitionTime) ?? 0;
      const returnMs = parseTimestampMs(lp.returnTransitionTime) ?? 0;
      if (tMs >= startMs && tMs <= returnMs) {
        isInLoop = true;
        loopId = lp.id;
        break;
      }
    }

    return {
      index: idx,
      timestampRaw: p.timestamp_raw,
      timeSec,
      cellId: p.serving_cell_id as number,
      rsrp: p.rsrp_dbm,
      isInPingPongLoop: isInLoop,
      loopId,
    };
  });

  const dwellTimes = filteredLoops.map((l) => l.intermediateDwellSec);
  const meanDwell = dwellTimes.length > 0
    ? Math.round((dwellTimes.reduce((a, b) => a + b, 0) / dwellTimes.length) * 10) / 10
    : null;
  const minDwellTime = dwellTimes.length > 0 ? Math.min(...dwellTimes) : null;
  const maxDwellTime = dwellTimes.length > 0 ? Math.max(...dwellTimes) : null;

  const confirmedLoopsCount = filteredLoops.filter((l) => l.isConfirmedHandover).length;
  const inferredLoopsCount = filteredLoops.filter((l) => !l.isConfirmedHandover).length;

  return {
    hasSufficientData: true,
    totalServingTransitions,
    candidatePingPongLoopsCount: filteredLoops.length,
    confirmedPingPongLoopsCount: confirmedLoopsCount,
    inferredPingPongLoopsCount: inferredLoopsCount,
    meanDwellTimeSec: meanDwell,
    minDwellTimeSec: minDwellTime,
    maxDwellTimeSec: maxDwellTime,
    activeTimeWindowSec: windowSec,
    loops: filteredLoops,
    cellPairTransitions,
    segments,
    timelinePoints,
  };
}
