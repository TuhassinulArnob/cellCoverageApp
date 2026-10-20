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
    event_type: str = "CELL_CHANGE"
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


class ExtendedDatasetAnalysis(BaseModel):
    dataset_id: str
    summary: DatasetSummary
    transitions: List[CellTransitionEvent] = Field(default_factory=list)
    service_holes: List[ServiceHoleEvent] = Field(default_factory=list)
    anomalies: List[AnomalyEvent] = Field(default_factory=list)
    weak_segments: List[WeakCoverageSegment] = Field(default_factory=list)
    per_cell_stats: List[PerCellStat] = Field(default_factory=list)
    data_quality: DataQualityReport


class IngestResponse(BaseModel):
    dataset_id: str
    summary: DatasetSummary
    sample_measurements: List[MeasurementPoint] = Field(default_factory=list)


class AvailableDatasetInfo(BaseModel):
    id: str
    title: str
    category: str
    rel_path: str
    rows: int
    has_gps: bool
    description: str
