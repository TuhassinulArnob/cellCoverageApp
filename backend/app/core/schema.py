from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field


class MeasurementPoint(BaseModel):
    id: int
    timestamp_raw: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    speed_kmh: Optional[float] = None
    serving_cell_id: Optional[int] = None
    neighbor_cell_id: Optional[int] = None
    rsrp_ie: Optional[int] = None
    rsrp_dbm: Optional[float] = None
    rsrq_ie: Optional[int] = None
    rsrq_db: Optional[float] = None
    cinr_db: Optional[float] = None
    handover_trigger: int = 0
    has_valid_gps: bool = False
    rsrp_class: str = "Unknown"
    rsrq_class: str = "Unknown"
    cinr_class: str = "Unknown"
    tac: Optional[int] = None
    earfcn: Optional[int] = None
    rssi_dbm: Optional[float] = None
    network_type: Optional[str] = None
    network_gen: Optional[str] = None
    operator: Optional[str] = None
    accuracy_m: Optional[float] = None
    heading_deg: Optional[float] = None
    timing_advance: Optional[int] = None
    cqi: Optional[int] = None
    eci: Optional[int] = None
    altitude_m: Optional[float] = None
    anomaly_label: Optional[str] = None
    sinr_source: str = "UNAVAILABLE"
    estimation_method: Optional[str] = None
    estimation_assumptions: Optional[str] = None
    sinr_confidence: Optional[str] = None
    qualitative_diagnostic: Optional[str] = None
    implied_rssi_dbm: Optional[float] = None
    bandwidth_khz: Optional[int] = None


class DistributionStats(BaseModel):
    excellent: int = 0
    good: int = 0
    fair: int = 0
    poor: int = 0
    unknown: int = 0


class GeoBoundingBox(BaseModel):
    min_lat: float
    max_lat: float
    min_lon: float
    max_lon: float
    center_lat: float
    center_lon: float


class PerCellStat(BaseModel):
    pci: int
    sample_count: int
    sample_pct: float
    mean_rsrp_dbm: Optional[float] = None
    min_rsrp_dbm: Optional[float] = None
    max_rsrp_dbm: Optional[float] = None
    mean_rsrq_db: Optional[float] = None
    mean_cinr_db: Optional[float] = None
    handover_count: int = 0
    earfcn: Optional[int] = None
    tac: Optional[int] = None
    network_gen: str = "4G LTE"


class WeakCoverageSegment(BaseModel):
    id: int
    start_index: int
    end_index: int
    sample_count: int
    min_rsrp_dbm: float
    mean_rsrp_dbm: float
    start_lat: Optional[float] = None
    start_lon: Optional[float] = None
    end_lat: Optional[float] = None
    end_lon: Optional[float] = None
    pci: Optional[int] = None
    reason: str


class CellTransitionEvent(BaseModel):
    id: int
    index: int
    timestamp_raw: str
    from_pci: Optional[int] = None
    to_pci: Optional[int] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    is_handover_trigger: bool = False
    event_type: str = "OBSERVED_TRANSITION"
    from_rat: Optional[str] = None
    to_rat: Optional[str] = None
    from_channel: Optional[int] = None
    to_channel: Optional[int] = None
    from_cell_id: Optional[int] = None
    to_cell_id: Optional[int] = None
    from_signal_dbm: Optional[float] = None
    to_signal_dbm: Optional[float] = None
    delta_signal_db: Optional[float] = None
    from_tac: Optional[int] = None
    to_tac: Optional[int] = None
    confidence_label: str = "HIGH"
    confidence_score: Optional[float] = None
    classification_reason: str = ""
    details: str = ""


class AnomalyEvent(BaseModel):
    id: int
    index: int
    timestamp_raw: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    pci: Optional[int] = None
    rsrp_dbm: Optional[float] = None
    rsrq_db: Optional[float] = None
    cinr_db: Optional[float] = None
    anomaly_type: str
    severity: str
    explanation: str


class ServiceHoleEvent(BaseModel):
    id: int
    index: int
    timestamp_raw: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    hole_type: str
    severity: str
    description: str


class DataQualityReport(BaseModel):
    total_records: int
    completeness_pct: float
    missing_gps_count: int
    missing_rsrp_count: int
    missing_rsrq_count: int
    missing_cinr_count: int
    missing_cell_id_count: int
    telemetry_gaps_count: int
    provenance_source: str
    schema_standard: str
    coordinate_system: str = "WGS84 (EPSG:4326)"


class QoeTestRecord(BaseModel):
    id: int
    session_id: Optional[int] = None
    timestamp_raw: str
    timestamp_ms: Optional[int] = None
    test_type: str = "FULL_CYCLE"
    test_status: str = "COMPLETED"
    skipped_reason: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    gps_accuracy_m: Optional[float] = None
    has_valid_gps: bool = False
    serving_rat: Optional[str] = None
    serving_pci: Optional[int] = None
    serving_channel: Optional[int] = None
    serving_rsrp_dbm: Optional[float] = None
    serving_rsrq_db: Optional[float] = None
    serving_sinr_db: Optional[float] = None
    operator: Optional[str] = None
    endpoint_url: Optional[str] = None
    download_mbps: Optional[float] = None
    upload_mbps: Optional[float] = None
    download_bytes: Optional[int] = None
    upload_bytes: Optional[int] = None
    download_duration_ms: Optional[int] = None
    upload_duration_ms: Optional[int] = None
    ping_latency_ms: Optional[float] = None
    rtt_mean_ms: Optional[float] = None
    rtt_p95_ms: Optional[float] = None
    jitter_ms: Optional[float] = None
    packet_loss_pct: Optional[float] = None
    probes_attempted: Optional[int] = None
    probes_successful: Optional[int] = None
    classification: Optional[str] = None
    data_provenance: str = "MEASURED"


class HourlyProfile(BaseModel):
    hour: int
    hour_label: str
    test_count: int
    avg_download_mbps: Optional[float] = None
    min_download_mbps: Optional[float] = None
    max_download_mbps: Optional[float] = None
    avg_upload_mbps: Optional[float] = None
    min_upload_mbps: Optional[float] = None
    max_upload_mbps: Optional[float] = None
    avg_ping_ms: Optional[float] = None


class SummaryInsight(BaseModel):
    category: str
    title: str
    description: str
    severity: str = "info"  # info, good, warning, critical
    confidence: str = "HIGH"


class QoePerformanceSummary(BaseModel):
    total_tests: int = 0
    completed_tests: int = 0
    failed_tests: int = 0
    skipped_tests: int = 0
    has_speed_data: bool = False
    has_ping_data: bool = False
    avg_ping_ms: Optional[float] = None
    min_ping_ms: Optional[float] = None
    max_ping_ms: Optional[float] = None
    median_ping_ms: Optional[float] = None
    rtt_p90_ms: Optional[float] = None
    rtt_p95_ms: Optional[float] = None
    avg_jitter_ms: Optional[float] = None
    avg_packet_loss_pct: Optional[float] = None
    avg_download_mbps: Optional[float] = None
    median_download_mbps: Optional[float] = None
    min_download_mbps: Optional[float] = None
    max_download_mbps: Optional[float] = None
    avg_upload_mbps: Optional[float] = None
    median_upload_mbps: Optional[float] = None
    min_upload_mbps: Optional[float] = None
    max_upload_mbps: Optional[float] = None
    total_download_bytes: int = 0
    total_upload_bytes: int = 0
    hourly_profiles: List[HourlyProfile] = Field(default_factory=list)
    summary_insights: List[SummaryInsight] = Field(default_factory=list)


class DatasetSummary(BaseModel):
    dataset_id: str
    filename: str
    total_records: int
    records_with_gps: int
    records_invalid_gps: int
    has_gps: bool
    unique_serving_cells: int
    unique_neighbor_cells: int
    handover_trigger_count: int
    handover_trigger_rate_pct: float
    mean_rsrp_dbm: Optional[float] = None
    min_rsrp_dbm: Optional[float] = None
    max_rsrp_dbm: Optional[float] = None
    mean_rsrq_db: Optional[float] = None
    min_rsrq_db: Optional[float] = None
    max_rsrq_db: Optional[float] = None
    mean_cinr_db: Optional[float] = None
    min_cinr_db: Optional[float] = None
    max_cinr_db: Optional[float] = None
    mean_speed_kmh: Optional[float] = None
    rsrp_distribution: DistributionStats = Field(default_factory=DistributionStats)
    rsrq_distribution: DistributionStats = Field(default_factory=DistributionStats)
    cinr_distribution: DistributionStats = Field(default_factory=DistributionStats)
    bounding_box: Optional[GeoBoundingBox] = None
    detected_schema: Dict[str, str] = Field(default_factory=dict)
    warnings: List[str] = Field(default_factory=list)
    total_distance_km: Optional[float] = None
    duration_sec: Optional[float] = None
    data_completeness_pct: float = 100.0
    mean_accuracy_m: Optional[float] = None
    network_gen_distribution: Dict[str, int] = Field(default_factory=dict)
    per_cell_stats: List[PerCellStat] = Field(default_factory=list)
    weak_coverage_segments_count: int = 0
    service_hole_count: int = 0
    telemetry_gaps_count: int = 0
    missing_values_summary: Dict[str, int] = Field(default_factory=dict)
    measurement_provenance: Dict[str, Any] = Field(default_factory=dict)
    sinr_provenance_summary: Dict[str, int] = Field(default_factory=dict)
    estimated_sinr_count: int = 0
    measured_sinr_count: int = 0
    unavailable_sinr_count: int = 0
    qoe_summary: Optional[QoePerformanceSummary] = None


class ExtendedDatasetAnalysis(BaseModel):
    dataset_id: str
    summary: DatasetSummary
    transitions: List[CellTransitionEvent] = Field(default_factory=list)
    service_holes: List[ServiceHoleEvent] = Field(default_factory=list)
    anomalies: List[AnomalyEvent] = Field(default_factory=list)
    weak_segments: List[WeakCoverageSegment] = Field(default_factory=list)
    per_cell_stats: List[PerCellStat] = Field(default_factory=list)
    data_quality: DataQualityReport
    qoe_tests: List[QoeTestRecord] = Field(default_factory=list)


class IngestResponse(BaseModel):
    dataset_id: str
    summary: DatasetSummary
    sample_measurements: List[MeasurementPoint] = Field(default_factory=list)
    qoe_tests: List[QoeTestRecord] = Field(default_factory=list)


class AvailableDatasetInfo(BaseModel):
    id: str
    title: str
    category: str
    rel_path: str
    rows: int
    has_gps: bool
    description: str
