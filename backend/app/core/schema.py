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
