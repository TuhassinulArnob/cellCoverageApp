export interface MeasurementPoint {
  id: number;
  timestamp_raw: string;
  latitude: number | null;
  longitude: number | null;
  speed_kmh: number | null;
  serving_cell_id: number | null;
  neighbor_cell_id: number | null;
  rsrp_ie: number | null;
  rsrp_dbm: number | null;
  rsrq_ie: number | null;
  rsrq_db: number | null;
  cinr_db: number | null;
  handover_trigger: number;
  has_valid_gps: boolean;
  rsrp_class: 'Excellent' | 'Good' | 'Fair' | 'Poor' | 'Unknown';
  rsrq_class: 'Excellent' | 'Good' | 'Fair' | 'Poor' | 'Unknown';
  cinr_class: 'Excellent' | 'Good' | 'Fair' | 'Poor' | 'Unknown';
  tac?: number | null;
  earfcn?: number | null;
  rssi_dbm?: number | null;
  network_type?: string | null;
  network_gen?: string | null;
  operator?: string | null;
  accuracy_m?: number | null;
  heading_deg?: number | null;
  anomaly_label?: string | null;
}

export interface DistributionStats {
  excellent: number;
  good: number;
  fair: number;
  poor: number;
  unknown: number;
}

export interface GeoBoundingBox {
  min_lat: number;
  max_lat: number;
  min_lon: number;
  max_lon: number;
  center_lat: number;
  center_lon: number;
}

export interface PerCellStat {
  pci: number;
  sample_count: number;
  sample_pct: number;
  mean_rsrp_dbm: number | null;
  min_rsrp_dbm: number | null;
  max_rsrp_dbm: number | null;
  mean_rsrq_db: number | null;
  mean_cinr_db: number | null;
  handover_count: number;
  earfcn?: number | null;
  tac?: number | null;
  network_gen: string;
}

export interface WeakCoverageSegment {
  id: number;
  start_index: number;
  end_index: number;
  sample_count: number;
  min_rsrp_dbm: number;
  mean_rsrp_dbm: number;
  start_lat: number | null;
  start_lon: number | null;
  end_lat: number | null;
  end_lon: number | null;
  pci: number | null;
  reason: string;
}

export interface CellTransitionEvent {
  id: number;
  index: number;
  timestamp_raw: string;
  from_pci: number | null;
  to_pci: number | null;
  latitude: number | null;
  longitude: number | null;
  is_handover_trigger: boolean;
  event_type: string;
  details: string;
}

export interface AnomalyEvent {
  id: number;
  index: number;
  timestamp_raw: string;
  latitude: number | null;
  longitude: number | null;
  pci: number | null;
  rsrp_dbm: number | null;
  rsrq_db: number | null;
  cinr_db: number | null;
  anomaly_type: string;
  severity: string;
  explanation: string;
}

export interface ServiceHoleEvent {
  id: number;
  index: number;
  timestamp_raw: string;
  latitude: number | null;
  longitude: number | null;
  hole_type: string;
  severity: string;
  description: string;
}

export interface DataQualityReport {
  total_records: number;
  completeness_pct: number;
  missing_gps_count: number;
  missing_rsrp_count: number;
  missing_rsrq_count: number;
  missing_cinr_count: number;
  missing_cell_id_count: number;
  telemetry_gaps_count: number;
  provenance_source: string;
  schema_standard: string;
  coordinate_system: string;
}

export interface DatasetSummary {
  dataset_id: string;
  filename: string;
  total_records: number;
  records_with_gps: number;
  records_invalid_gps: number;
  has_gps: boolean;
  unique_serving_cells: number;
  unique_neighbor_cells: number;
  handover_trigger_count: number;
  handover_trigger_rate_pct: number;
  mean_rsrp_dbm: number | null;
  min_rsrp_dbm: number | null;
  max_rsrp_dbm: number | null;
  mean_rsrq_db: number | null;
  min_rsrq_db: number | null;
  max_rsrq_db: number | null;
  mean_cinr_db: number | null;
  min_cinr_db: number | null;
  max_cinr_db: number | null;
  mean_speed_kmh: number | null;
  rsrp_distribution: DistributionStats;
  rsrq_distribution: DistributionStats;
  cinr_distribution: DistributionStats;
  bounding_box: GeoBoundingBox | null;
  detected_schema: Record<string, string>;
  warnings: string[];
  total_distance_km?: number | null;
  duration_sec?: number | null;
  data_completeness_pct?: number;
  mean_accuracy_m?: number | null;
  network_gen_distribution?: Record<string, number>;
  per_cell_stats?: PerCellStat[];
  weak_coverage_segments_count?: number;
  service_hole_count?: number;
  telemetry_gaps_count?: number;
  missing_values_summary?: Record<string, number>;
  measurement_provenance?: Record<string, any>;
}

export interface ExtendedDatasetAnalysis {
  dataset_id: string;
  summary: DatasetSummary;
  transitions: CellTransitionEvent[];
  service_holes: ServiceHoleEvent[];
  anomalies: AnomalyEvent[];
  weak_segments: WeakCoverageSegment[];
  per_cell_stats: PerCellStat[];
  data_quality: DataQualityReport;
}

export interface IngestResponse {
  dataset_id: string;
  summary: DatasetSummary;
  sample_measurements: MeasurementPoint[];
}

export interface AvailableDatasetInfo {
  id: string;
  title: string;
  category: string;
  rel_path: string;
  rows: number;
  has_gps: boolean;
  description: string;
}

export type ActiveMetric = 'rsrp' | 'rsrq' | 'cinr';
