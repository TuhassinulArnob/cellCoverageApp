// 3GPP TS 36.101 Telecom Engineering Mathematics & Utilities
// Parity with CellScope-5G Android LteBandTable and Telephony Engine

import type { MeasurementPoint, EstimatedCellSite } from '../types/cellscope';

export interface BandDefinition {
  bandNumber: number;
  designation: string;
  duplexMode: 'FDD' | 'TDD';
  dlFreqLowMhz: number;
  dlEarfcnOffset: number;
  dlEarfcnMin: number;
  dlEarfcnMax: number;
  ulFreqLowMhz?: number;
  ulEarfcnOffset?: number;
  bangladeshOperators?: string[];
  notes?: string;
}

export interface FrequencyResult {
  bandNumber: number;
  designation: string;
  duplexMode: 'FDD' | 'TDD';
  earfcn: number;
  dlFrequencyMhz: number;
  ulFrequencyMhz: number | null;
  summaryText: string;
  bangladeshOperators?: string[];
}

export const LTE_BANDS: BandDefinition[] = [
  {
    bandNumber: 1,
    designation: 'IMT 2100',
    duplexMode: 'FDD',
    dlFreqLowMhz: 2110.0,
    dlEarfcnOffset: 0,
    dlEarfcnMin: 0,
    dlEarfcnMax: 599,
    ulFreqLowMhz: 1920.0,
    ulEarfcnOffset: 18000,
    bangladeshOperators: ['Grameenphone', 'Robi', 'Banglalink', 'Teletalk'],
    notes: 'Urban capacity layer',
  },
  {
    bandNumber: 2,
    designation: 'PCS 1900',
    duplexMode: 'FDD',
    dlFreqLowMhz: 1930.0,
    dlEarfcnOffset: 600,
    dlEarfcnMin: 600,
    dlEarfcnMax: 1199,
    ulFreqLowMhz: 1850.0,
    ulEarfcnOffset: 18600,
  },
  {
    bandNumber: 3,
    designation: 'DCS 1800',
    duplexMode: 'FDD',
    dlFreqLowMhz: 1805.0,
    dlEarfcnOffset: 1200,
    dlEarfcnMin: 1200,
    dlEarfcnMax: 1949,
    ulFreqLowMhz: 1710.0,
    ulEarfcnOffset: 19200,
    bangladeshOperators: ['Grameenphone', 'Robi', 'Banglalink', 'Teletalk'],
    notes: 'Primary nationwide LTE coverage & capacity backbone in BD',
  },
  {
    bandNumber: 4,
    designation: 'AWS-1 1700/2100',
    duplexMode: 'FDD',
    dlFreqLowMhz: 2110.0,
    dlEarfcnOffset: 1950,
    dlEarfcnMin: 1950,
    dlEarfcnMax: 2399,
  },
  {
    bandNumber: 5,
    designation: 'CLR 850',
    duplexMode: 'FDD',
    dlFreqLowMhz: 869.0,
    dlEarfcnOffset: 2400,
    dlEarfcnMin: 2400,
    dlEarfcnMax: 2649,
  },
  {
    bandNumber: 7,
    designation: 'IMT-E 2600',
    duplexMode: 'FDD',
    dlFreqLowMhz: 2620.0,
    dlEarfcnOffset: 2750,
    dlEarfcnMin: 2750,
    dlEarfcnMax: 3449,
    ulFreqLowMhz: 2500.0,
    ulEarfcnOffset: 20750,
  },
  {
    bandNumber: 8,
    designation: 'E-GSM 900',
    duplexMode: 'FDD',
    dlFreqLowMhz: 925.0,
    dlEarfcnOffset: 3450,
    dlEarfcnMin: 3450,
    dlEarfcnMax: 3799,
    ulFreqLowMhz: 880.0,
    ulEarfcnOffset: 21450,
    bangladeshOperators: ['Grameenphone', 'Robi', 'Banglalink'],
    notes: 'Sub-1GHz deep indoor penetration in BD',
  },
  {
    bandNumber: 20,
    designation: 'E-GSM 800 (DD)',
    duplexMode: 'FDD',
    dlFreqLowMhz: 791.0,
    dlEarfcnOffset: 6150,
    dlEarfcnMin: 6150,
    dlEarfcnMax: 6449,
  },
  {
    bandNumber: 28,
    designation: 'APT 700',
    duplexMode: 'FDD',
    dlFreqLowMhz: 758.0,
    dlEarfcnOffset: 9210,
    dlEarfcnMin: 9210,
    dlEarfcnMax: 9659,
    ulFreqLowMhz: 703.0,
    ulEarfcnOffset: 27210,
    notes: 'Allocated for nationwide rural 4G/5G rollout',
  },
  {
    bandNumber: 38,
    designation: 'TD 2600',
    duplexMode: 'TDD',
    dlFreqLowMhz: 2570.0,
    dlEarfcnOffset: 37750,
    dlEarfcnMin: 37750,
    dlEarfcnMax: 38249,
  },
  {
    bandNumber: 40,
    designation: 'TD 2300',
    duplexMode: 'TDD',
    dlFreqLowMhz: 2300.0,
    dlEarfcnOffset: 38650,
    dlEarfcnMin: 38650,
    dlEarfcnMax: 39649,
    bangladeshOperators: ['Banglalink', 'Robi'],
    notes: 'High-speed urban TDD capacity layer',
  },
  {
    bandNumber: 41,
    designation: 'TD 2500',
    duplexMode: 'TDD',
    dlFreqLowMhz: 2496.0,
    dlEarfcnOffset: 39650,
    dlEarfcnMin: 39650,
    dlEarfcnMax: 41589,
  },
];

/**
 * Resolves 3GPP TS 36.101 Band and Downlink/Uplink Frequency from EARFCN.
 */
export function resolveEarfcn(earfcn: number | null | undefined): FrequencyResult | null {
  if (earfcn === null || earfcn === undefined || earfcn < 0) return null;

  const band = LTE_BANDS.find((b) => earfcn >= b.dlEarfcnMin && earfcn <= b.dlEarfcnMax);
  if (!band) return null;

  const delta = earfcn - band.dlEarfcnOffset;
  const dlFreq = band.dlFreqLowMhz + 0.1 * delta;
  const ulFreq =
    band.duplexMode === 'TDD'
      ? dlFreq
      : band.ulFreqLowMhz !== undefined
      ? band.ulFreqLowMhz + 0.1 * delta
      : null;

  return {
    bandNumber: band.bandNumber,
    designation: band.designation,
    duplexMode: band.duplexMode,
    earfcn,
    dlFrequencyMhz: Math.round(dlFreq * 10) / 10,
    ulFrequencyMhz: ulFreq !== null ? Math.round(ulFreq * 10) / 10 : null,
    summaryText: `Band ${band.bandNumber} (${band.designation}) • DL ${dlFreq.toFixed(1)} MHz (${band.duplexMode})`,
    bangladeshOperators: band.bangladeshOperators,
  };
}

/**
 * Converts Timing Advance (TA) index to distance in meters.
 * 1 LTE TA step = 16 * Ts = 16 / (15000 * 2048) sec ≈ 0.52 µs
 * Distance (one-way) = (c * t) / 2 ≈ (3e8 * 0.52e-6) / 2 ≈ 78.12 meters.
 */
export function timingAdvanceToMeters(ta: number | null | undefined): number | null {
  if (ta === null || ta === undefined || ta < 0) return null;
  return Math.round(ta * 78.12);
}

/**
 * Computes PCI modulo-3 shift (v_shift for Cell-specific Reference Signal).
 * 3GPP TS 36.211 Section 6.10.1.2: v_shift = PCI mod 3.
 */
export function getPciMod3Shift(pci: number): number {
  return ((pci % 3) + 3) % 3;
}

export interface PciMod3Collision {
  earfcn: number;
  vShift: number;
  cells: { pci: number; sampleCount: number; meanRsrp: number | null }[];
}

/**
 * Analyzes PCI Reuse and CRS Modulo-3 Collisions on the same carrier frequency.
 */
export function analyzePciMod3Collisions(points: MeasurementPoint[]): PciMod3Collision[] {
  // Group points by EARFCN -> then by PCI
  const carrierMap = new Map<number, Map<number, { count: number; rsrpSum: number }>>();

  for (const p of points) {
    if (p.serving_cell_id === null || p.serving_cell_id === undefined) continue;
    const earfcn = p.earfcn || 0; // 0 for unspecified
    if (!carrierMap.has(earfcn)) {
      carrierMap.set(earfcn, new Map());
    }
    const pciMap = carrierMap.get(earfcn)!;
    const curr = pciMap.get(p.serving_cell_id) || { count: 0, rsrpSum: 0 };
    curr.count++;
    if (p.rsrp_dbm !== null) {
      curr.rsrpSum += p.rsrp_dbm;
    }
    pciMap.set(p.serving_cell_id, curr);
  }

  const collisions: PciMod3Collision[] = [];

  for (const [earfcn, pciMap] of carrierMap.entries()) {
    // Group PCIs by shift (0, 1, 2)
    const shiftGroups = new Map<number, { pci: number; sampleCount: number; meanRsrp: number | null }[]>();
    shiftGroups.set(0, []);
    shiftGroups.set(1, []);
    shiftGroups.set(2, []);

    for (const [pci, stats] of pciMap.entries()) {
      const shift = getPciMod3Shift(pci);
      shiftGroups.get(shift)!.push({
        pci,
        sampleCount: stats.count,
        meanRsrp: stats.count > 0 ? Math.round((stats.rsrpSum / stats.count) * 10) / 10 : null,
      });
    }

    for (const [shift, group] of shiftGroups.entries()) {
      if (group.length > 1) {
        collisions.push({
          earfcn,
          vShift: shift,
          cells: group.sort((a, b) => b.sampleCount - a.sampleCount),
        });
      }
    }
  }

  return collisions;
}

/**
 * Estimates cell site tower centroid locations based on RF power-weighted triangulation.
 */
export function estimateCellSites(points: MeasurementPoint[]): EstimatedCellSite[] {
  const cellGroups = new Map<string, MeasurementPoint[]>();

  for (const p of points) {
    if (p.has_valid_gps && p.latitude !== null && p.longitude !== null && p.serving_cell_id !== null) {
      const pci = p.serving_cell_id;
      let siteKey: string;
      if (p.eci && p.eci > 0) {
        const enodeb = Math.floor(p.eci / 256);
        siteKey = `enb_${enodeb}_pci_${pci}`;
      } else if (p.tac && p.tac > 0) {
        siteKey = `tac_${p.tac}_pci_${pci}`;
      } else {
        siteKey = `pci_${pci}`;
      }

      if (!cellGroups.has(siteKey)) {
        cellGroups.set(siteKey, []);
      }
      cellGroups.get(siteKey)!.push(p);
    }
  }

  const sites: EstimatedCellSite[] = [];

  for (const [siteKey, cellPoints] of cellGroups.entries()) {
    if (cellPoints.length === 0) continue;

    const pci = cellPoints[0].serving_cell_id!;
    const eci = cellPoints.find((p) => p.eci !== null && p.eci !== undefined)?.eci ?? null;
    const enodeb_id = eci !== null ? Math.floor(eci / 256) : null;
    const tac = cellPoints.find((p) => p.tac !== null && p.tac !== undefined)?.tac ?? null;

    let sumWeight = 0;
    let weightedLat = 0;
    let weightedLon = 0;
    let maxRsrp = -999;
    let bestLat = cellPoints[0].latitude!;
    let bestLon = cellPoints[0].longitude!;
    let rsrpSum = 0;

    for (const pt of cellPoints) {
      const r = pt.rsrp_dbm ?? -110;
      rsrpSum += r;
      if (r > maxRsrp) {
        maxRsrp = r;
        bestLat = pt.latitude!;
        bestLon = pt.longitude!;
      }
      // Linear weight with lower bound
      const weight = Math.pow(10, Math.max(r, -130) / 10);
      sumWeight += weight;
      weightedLat += pt.latitude! * weight;
      weightedLon += pt.longitude! * weight;
    }

    const estimatedLat = sumWeight > 0 ? weightedLat / sumWeight : bestLat;
    const estimatedLon = sumWeight > 0 ? weightedLon / sumWeight : bestLon;

    // Compute standard dispersion radius in meters
    let maxDistMeters = 0;
    for (const pt of cellPoints) {
      const dLat = (pt.latitude! - estimatedLat) * 111320;
      const dLon = (pt.longitude! - estimatedLon) * (111320 * Math.cos((estimatedLat * Math.PI) / 180));
      const dist = Math.sqrt(dLat * dLat + dLon * dLon);
      if (dist > maxDistMeters) {
        maxDistMeters = dist;
      }
    }

    const earfcn = cellPoints.find((p) => p.earfcn !== null && p.earfcn !== undefined)?.earfcn ?? null;
    const networkGen = cellPoints[0].network_gen || '4G LTE';
    const uncertaintyRadius = Math.max(150, Math.min(Math.round(maxDistMeters * 0.75), 1800));

    sites.push({
      pci,
      eci,
      enodeb_id,
      tac,
      siteKey,
      latitude: Math.round(estimatedLat * 1e6) / 1e6,
      longitude: Math.round(estimatedLon * 1e6) / 1e6,
      sampleCount: cellPoints.length,
      meanRsrp: Math.round((rsrpSum / cellPoints.length) * 10) / 10,
      maxRsrp,
      earfcn,
      networkGen,
      uncertaintyRadiusMeters: uncertaintyRadius,
    });
  }

  return sites.sort((a, b) => b.sampleCount - a.sampleCount);
}
