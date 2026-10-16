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
