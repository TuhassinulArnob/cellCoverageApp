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
  timing_advance?: number | null;
  cqi?: number | null;
  eci?: number | null;
  altitude_m?: number | null;
  anomaly_label?: string | null;
  sinr_source?: 'MEASURED' | 'ESTIMATED' | 'UNAVAILABLE';
  estimation_method?: string | null;
  estimation_assumptions?: string | null;
  sinr_confidence?: string | null;
  qualitative_diagnostic?: string | null;
  implied_rssi_dbm?: number | null;
  bandwidth_khz?: number | null;
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
  from_rat?: string | null;
  to_rat?: string | null;
  from_channel?: number | null;
  to_channel?: number | null;
  from_cell_id?: number | null;
  to_cell_id?: number | null;
  from_signal_dbm?: number | null;
  to_signal_dbm?: number | null;
  delta_signal_db?: number | null;
  from_tac?: number | null;
  to_tac?: number | null;
  confidence_label?: string | null;
  confidence_score?: number | null;
  classification_reason?: string | null;
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
  sinr_provenance_summary?: Record<string, number>;
  estimated_sinr_count?: number;
  measured_sinr_count?: number;
  unavailable_sinr_count?: number;
  qoe_summary?: QoePerformanceSummary | null;
}

export interface QoeTestRecord {
  id: number;
  session_id?: number | null;
  timestamp_raw: string;
  timestamp_ms?: number | null;
  test_type: string;
  test_status: string;
  skipped_reason?: string | null;
  latitude: number | null;
  longitude: number | null;
  gps_accuracy_m?: number | null;
  has_valid_gps: boolean;
  serving_rat?: string | null;
  serving_pci?: number | null;
  serving_channel?: number | null;
  serving_rsrp_dbm?: number | null;
  serving_rsrq_db?: number | null;
  serving_sinr_db?: number | null;
  operator?: string | null;
  endpoint_url?: string | null;
  download_mbps: number | null;
  upload_mbps: number | null;
  download_bytes?: number | null;
  upload_bytes?: number | null;
  download_duration_ms?: number | null;
  upload_duration_ms?: number | null;
  ping_latency_ms: number | null;
  rtt_mean_ms?: number | null;
  rtt_p95_ms?: number | null;
  jitter_ms: number | null;
  packet_loss_pct: number | null;
  probes_attempted?: number | null;
  probes_successful?: number | null;
  classification?: string | null;
  data_provenance: string;
}

export interface HourlyProfile {
  hour: number;
  hour_label: string;
  test_count: number;
  avg_download_mbps: number | null;
  min_download_mbps: number | null;
  max_download_mbps: number | null;
  avg_upload_mbps: number | null;
  min_upload_mbps: number | null;
  max_upload_mbps: number | null;
  avg_ping_ms: number | null;
}

export interface SummaryInsight {
  category: string;
  title: string;
  description: string;
  severity: 'info' | 'good' | 'warning' | 'critical';
  confidence: string;
}

export interface QoePerformanceSummary {
  total_tests: number;
  completed_tests: number;
  failed_tests: number;
  skipped_tests: number;
  has_speed_data: boolean;
  has_ping_data: boolean;
  avg_ping_ms: number | null;
  min_ping_ms: number | null;
  max_ping_ms: number | null;
  median_ping_ms: number | null;
  rtt_p90_ms?: number | null;
  rtt_p95_ms?: number | null;
  avg_jitter_ms: number | null;
  avg_packet_loss_pct: number | null;
  avg_download_mbps: number | null;
  median_download_mbps?: number | null;
  min_download_mbps: number | null;
  max_download_mbps: number | null;
  avg_upload_mbps: number | null;
  median_upload_mbps?: number | null;
  min_upload_mbps: number | null;
  max_upload_mbps: number | null;
  total_download_bytes: number;
  total_upload_bytes: number;
  hourly_profiles?: HourlyProfile[];
  summary_insights?: SummaryInsight[];
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
  qoe_tests: QoeTestRecord[];
}

export interface IngestResponse {
  dataset_id: string;
  summary: DatasetSummary;
  sample_measurements: MeasurementPoint[];
  qoe_tests?: QoeTestRecord[];
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

export type ActiveMetric = 'rsrp' | 'rsrq' | 'cinr' | 'sinr' | 'pci' | 'ta' | 'tech' | 'speed';

export interface FilterState {
  servingCellId: number | null;
  networkGen: string | null;
  operator: string | null;
  bandEarfcn: number | null;
  rsrpClass: string | null;
  rsrqClass: string | null;
  minCinr: number | null;
  handoversOnly: boolean;
  weakZonesOnly: boolean;
}

export interface MapOverlayState {
  routeTrack: boolean;
  weakZones: boolean;
  handovers: boolean;
  cellSites: boolean;
  taUncertainty: boolean;
  qoeTests: boolean;
}

export interface EstimatedCellSite {
  pci: number;
  eci?: number | null;
  enodeb_id?: number | null;
  tac?: number | null;
  siteKey?: string;
  latitude: number;
  longitude: number;
  sampleCount: number;
  meanRsrp: number;
  maxRsrp: number;
  earfcn: number | null;
  networkGen: string;
  uncertaintyRadiusMeters: number;
}
