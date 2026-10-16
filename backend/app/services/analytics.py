import uuid
from typing import List, Dict, Tuple, Optional
import pandas as pd
import numpy as np

from backend.app.core.schema import (
    MeasurementPoint,
    DatasetSummary,
    DistributionStats,
    GeoBoundingBox,
)
from backend.app.core.rf_math import (
    rsrp_ie_to_dbm,
    rsrq_ie_to_db,
    classify_rsrp,
    classify_rsrq,
    classify_cinr,
)
from backend.app.services.parser import clean_numeric_value
from backend.app.services.validator import validate_coordinates, validate_rf_ranges


def process_and_summarize_dataset(
    df: pd.DataFrame,
    mapping: Dict[str, str],
    filename: str,
    dataset_id: Optional[str] = None
) -> Tuple[DatasetSummary, List[MeasurementPoint]]:
    """
    Transforms raw DataFrame using column mapping into canonical MeasurementPoints
    and calculates statistical and RF KPI summaries.
    """
    if dataset_id is None:
        dataset_id = str(uuid.uuid4())

    total_records = len(df)
    warnings: List[str] = []
    
    # Identify which canonical columns were found
    lat_col = mapping.get("latitude")
    lon_col = mapping.get("longitude")
    time_col = mapping.get("timestamp")
    speed_col = mapping.get("speed")
    serving_col = mapping.get("serving_cell_id")
    neighbor_col = mapping.get("neighbor_cell_id")
    rsrp_col = mapping.get("rsrp")
    rsrq_col = mapping.get("rsrq")
    cinr_col = mapping.get("cinr")
    ho_col = mapping.get("handover_trigger")

    has_gps = (lat_col is not None and lon_col is not None)
    if not has_gps:
        warnings.append("No GPS coordinate columns (Latitude/Longitude) detected in dataset.")

    points: List[MeasurementPoint] = []
    valid_gps_count = 0
    invalid_gps_count = 0
    lats_valid: List[float] = []
    lons_valid: List[float] = []

    rsrp_vals: List[float] = []
    rsrq_vals: List[float] = []
    cinr_vals: List[float] = []
    speed_vals: List[float] = []
    serving_cells = set()
    neighbor_cells = set()
    ho_count = 0

    rsrp_dist = DistributionStats()
    rsrq_dist = DistributionStats()
    cinr_dist = DistributionStats()

    for idx, row in df.iterrows():
        # Timestamp
        raw_ts = str(row[time_col]).strip() if time_col else f"Row {idx}"

        # Coordinates
        lat_val = clean_numeric_value(row[lat_col]) if lat_col else None
        lon_val = clean_numeric_value(row[lon_col]) if lon_col else None

        is_valid_gps, geo_err = validate_coordinates(lat_val, lon_val) if has_gps else (False, "NO_GPS")

        if is_valid_gps:
            valid_gps_count += 1
            lats_valid.append(lat_val)
            lons_valid.append(lon_val)
        elif has_gps:
            invalid_gps_count += 1

        # Speed
        speed_val = clean_numeric_value(row[speed_col]) if speed_col else None
        if speed_val is not None:
            speed_vals.append(speed_val)

        # Serving & Neighbor Cells
        serv_val = clean_numeric_value(row[serving_col]) if serving_col else None
        serv_id = int(serv_val) if serv_val is not None and not np.isnan(serv_val) else None
        if serv_id is not None:
            serving_cells.add(serv_id)

        neigh_val = clean_numeric_value(row[neighbor_col]) if neighbor_col else None
        neigh_id = int(neigh_val) if neigh_val is not None and not np.isnan(neigh_val) else None
        if neigh_id is not None:
            neighbor_cells.add(neigh_id)

        # RSRP
        rsrp_raw = clean_numeric_value(row[rsrp_col]) if rsrp_col else None
        rsrp_ie = int(rsrp_raw) if rsrp_raw is not None and 0 <= rsrp_raw <= 100 else None
        rsrp_dbm = rsrp_ie_to_dbm(rsrp_raw)
        rsrp_cls = classify_rsrp(rsrp_dbm)
        if rsrp_dbm is not None:
            rsrp_vals.append(rsrp_dbm)
            if rsrp_cls == "Excellent":
                rsrp_dist.excellent += 1
            elif rsrp_cls == "Good":
                rsrp_dist.good += 1
            elif rsrp_cls == "Fair":
                rsrp_dist.fair += 1
            elif rsrp_cls == "Poor":
                rsrp_dist.poor += 1
        else:
            rsrp_dist.unknown += 1

        # RSRQ
        rsrq_raw = clean_numeric_value(row[rsrq_col]) if rsrq_col else None
        rsrq_ie = int(rsrq_raw) if rsrq_raw is not None and 0 <= rsrq_raw <= 50 else None
        rsrq_db = rsrq_ie_to_db(rsrq_raw)
        rsrq_cls = classify_rsrq(rsrq_db)
        if rsrq_db is not None:
            rsrq_vals.append(rsrq_db)
            if rsrq_cls == "Excellent":
                rsrq_dist.excellent += 1
            elif rsrq_cls == "Good":
                rsrq_dist.good += 1
            elif rsrq_cls == "Fair":
                rsrq_dist.fair += 1
            elif rsrq_cls == "Poor":
                rsrq_dist.poor += 1
        else:
            rsrq_dist.unknown += 1

        # CINR
        cinr_raw = clean_numeric_value(row[cinr_col]) if cinr_col else None
        cinr_cls = classify_cinr(cinr_raw)
        if cinr_raw is not None:
            cinr_vals.append(cinr_raw)
            if cinr_cls == "Excellent":
                cinr_dist.excellent += 1
            elif cinr_cls == "Good":
                cinr_dist.good += 1
            elif cinr_cls == "Fair":
                cinr_dist.fair += 1
            elif cinr_cls == "Poor":
                cinr_dist.poor += 1
        else:
            cinr_dist.unknown += 1

        # Handover trigger
        ho_val = clean_numeric_value(row[ho_col]) if ho_col else 0
        ho_trig = 1 if (ho_val is not None and ho_val > 0.5) else 0
        if ho_trig == 1:
            ho_count += 1

        point = MeasurementPoint(
            id=int(idx),
            timestamp_raw=raw_ts,
            latitude=lat_val if is_valid_gps else None,
            longitude=lon_val if is_valid_gps else None,
            speed_kmh=speed_val,
            serving_cell_id=serv_id,
            neighbor_cell_id=neigh_id,
            rsrp_ie=rsrp_ie,
            rsrp_dbm=rsrp_dbm,
            rsrq_ie=rsrq_ie,
            rsrq_db=rsrq_db,
            cinr_db=cinr_raw,
            handover_trigger=ho_trig,
            has_valid_gps=is_valid_gps,
            rsrp_class=rsrp_cls,
            rsrq_class=rsrq_cls,
            cinr_class=cinr_cls,
        )
        points.append(point)

    if invalid_gps_count > 0:
        warnings.append(f"{invalid_gps_count} rows have invalid or outlier GPS coordinates (filtered out).")

    # Bounding box
    bounding_box = None
    if lats_valid and lons_valid:
        min_lat = float(min(lats_valid))
        max_lat = float(max(lats_valid))
        min_lon = float(min(lons_valid))
        max_lon = float(max(lons_valid))
        bounding_box = GeoBoundingBox(
            min_lat=round(min_lat, 6),
            max_lat=round(max_lat, 6),
            min_lon=round(min_lon, 6),
            max_lon=round(max_lon, 6),
            center_lat=round((min_lat + max_lat) / 2.0, 6),
            center_lon=round((min_lon + max_lon) / 2.0, 6),
        )

    ho_rate = round((ho_count / total_records * 100.0), 2) if total_records > 0 else 0.0

    summary = DatasetSummary(
        dataset_id=dataset_id,
        filename=filename,
        total_records=total_records,
        records_with_gps=valid_gps_count,
        records_invalid_gps=invalid_gps_count,
        has_gps=(valid_gps_count > 0),
        unique_serving_cells=len(serving_cells),
        unique_neighbor_cells=len(neighbor_cells),
        handover_trigger_count=ho_count,
        handover_trigger_rate_pct=ho_rate,
        mean_rsrp_dbm=round(float(np.mean(rsrp_vals)), 2) if rsrp_vals else None,
        min_rsrp_dbm=round(float(min(rsrp_vals)), 2) if rsrp_vals else None,
        max_rsrp_dbm=round(float(max(rsrp_vals)), 2) if rsrp_vals else None,
        mean_rsrq_db=round(float(np.mean(rsrq_vals)), 2) if rsrq_vals else None,
        min_rsrq_db=round(float(min(rsrq_vals)), 2) if rsrq_vals else None,
        max_rsrq_db=round(float(max(rsrq_vals)), 2) if rsrq_vals else None,
        mean_cinr_db=round(float(np.mean(cinr_vals)), 2) if cinr_vals else None,
        min_cinr_db=round(float(min(cinr_vals)), 2) if cinr_vals else None,
        max_cinr_db=round(float(max(cinr_vals)), 2) if cinr_vals else None,
        mean_speed_kmh=round(float(np.mean(speed_vals)), 2) if speed_vals else None,
        rsrp_distribution=rsrp_dist,
        rsrq_distribution=rsrq_dist,
        cinr_distribution=cinr_dist,
        bounding_box=bounding_box,
        detected_schema=mapping,
        warnings=warnings,
    )

    return summary, points
