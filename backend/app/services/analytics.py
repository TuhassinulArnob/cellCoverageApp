
import uuid
import math
from datetime import datetime
from typing import List, Dict, Tuple, Optional, Any
import pandas as pd
import numpy as np

from backend.app.core.schema import (
    MeasurementPoint,
    DatasetSummary,
    DistributionStats,
    GeoBoundingBox,
    PerCellStat,
    WeakCoverageSegment,
    CellTransitionEvent,
    AnomalyEvent,
    ServiceHoleEvent,
    DataQualityReport,
    ExtendedDatasetAnalysis,
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


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate the great-circle distance between two points on the Earth (km)."""
    r = 6371.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = math.sin(delta_phi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return r * c


def infer_network_generation(net_type: Optional[str], earfcn: Optional[int], rsrp_dbm: Optional[float]) -> str:
    if net_type:
        nt = net_type.upper()
        if "NR" in nt or "5G" in nt:
            return "5G NR"
        if "LTE" in nt or "4G" in nt:
            return "4G LTE"
        if "UMTS" in nt or "HSPA" in nt or "3G" in nt or "WCDMA" in nt:
            return "3G UMTS"
        if "GSM" in nt or "EDGE" in nt or "GPRS" in nt or "2G" in nt:
            return "2G GSM"
    if earfcn is not None and earfcn > 100000:
        return "5G NR"
    if rsrp_dbm is not None:
        return "4G LTE"
    return "4G LTE"


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
    
    # Identify canonical columns
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
    tac_col = mapping.get("tac")
    earfcn_col = mapping.get("earfcn")
    rssi_col = mapping.get("rssi")
    net_col = mapping.get("network_type")
    op_col = mapping.get("operator")
    acc_col = mapping.get("accuracy")
    heading_col = mapping.get("heading")

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
    accuracy_vals: List[float] = []
    serving_cells = set()
    neighbor_cells = set()
    ho_count = 0

    rsrp_dist = DistributionStats()
    rsrq_dist = DistributionStats()
    cinr_dist = DistributionStats()
    gen_dist: Dict[str, int] = {}

    prev_lat: Optional[float] = None
    prev_lon: Optional[float] = None
    total_distance_km = 0.0

    missing_gps_count = 0
    missing_rsrp_count = 0
    missing_rsrq_count = 0
    missing_cinr_count = 0
    missing_cell_count = 0
    telemetry_gaps = 0

    parsed_timestamps: List[float] = []

    for idx, row in df.iterrows():
        # Timestamp
        raw_ts = str(row[time_col]).strip() if time_col else f"Row {idx}"
        if time_col and pd.notna(row[time_col]):
            try:
                dt = pd.to_datetime(raw_ts)
                if pd.notna(dt):
                    parsed_timestamps.append(dt.timestamp())
            except Exception:
                pass

        # Coordinates
        lat_val = clean_numeric_value(row[lat_col]) if lat_col else None
        lon_val = clean_numeric_value(row[lon_col]) if lon_col else None

        is_valid_gps, geo_err = validate_coordinates(lat_val, lon_val) if has_gps else (False, "NO_GPS")

        if is_valid_gps:
            valid_gps_count += 1
            lats_valid.append(lat_val)
            lons_valid.append(lon_val)

            # Cumulative distance
            if prev_lat is not None and prev_lon is not None:
                d_step = haversine_km(prev_lat, prev_lon, lat_val, lon_val)
                if d_step > 0.150:  # Gap > 150m
                    telemetry_gaps += 1
                if d_step < 5.0:  # Ignore wild jumps > 5km
                    total_distance_km += d_step
            prev_lat = lat_val
            prev_lon = lon_val
        else:
            missing_gps_count += 1
            if has_gps:
                invalid_gps_count += 1

        # Speed
        speed_val = clean_numeric_value(row[speed_col]) if speed_col else None
        if speed_val is not None:
            speed_vals.append(speed_val)

        # Accuracy & Heading
        acc_val = clean_numeric_value(row[acc_col]) if acc_col else None
        if acc_val is not None:
            accuracy_vals.append(acc_val)
        heading_val = clean_numeric_value(row[heading_col]) if heading_col else None

        # TAC, EARFCN, RSSI, Operator, Network Type
        tac_val = int(clean_numeric_value(row[tac_col])) if tac_col and clean_numeric_value(row[tac_col]) is not None else None
        earfcn_val = int(clean_numeric_value(row[earfcn_col])) if earfcn_col and clean_numeric_value(row[earfcn_col]) is not None else None
        rssi_val = clean_numeric_value(row[rssi_col]) if rssi_col else None
        net_type_val = str(row[net_col]).strip() if net_col and pd.notna(row[net_col]) else None
        op_val = str(row[op_col]).strip() if op_col and pd.notna(row[op_col]) else None

        # Serving & Neighbor Cells
        serv_val = clean_numeric_value(row[serving_col]) if serving_col else None
        serv_id = int(serv_val) if serv_val is not None and not np.isnan(serv_val) else None
        if serv_id is not None:
            serving_cells.add(serv_id)
        else:
            missing_cell_count += 1

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
            missing_rsrp_count += 1
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
            missing_rsrq_count += 1
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
            missing_cinr_count += 1
            cinr_dist.unknown += 1

        # Network Gen
        gen_val = infer_network_generation(net_type_val, earfcn_val, rsrp_dbm)
        gen_dist[gen_val] = gen_dist.get(gen_val, 0) + 1

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
            tac=tac_val,
            earfcn=earfcn_val,
            rssi_dbm=rssi_val,
            network_type=net_type_val or gen_val,
            network_gen=gen_val,
            operator=op_val,
            accuracy_m=acc_val,
            heading_deg=heading_val,
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

    # Per-cell stats
    per_cell_list: List[PerCellStat] = []
    cell_groups: Dict[int, List[MeasurementPoint]] = {}
    for p in points:
        if p.serving_cell_id is not None:
            cell_groups.setdefault(p.serving_cell_id, []).append(p)

    for cid, cpoints in sorted(cell_groups.items(), key=lambda x: len(x[1]), reverse=True):
        c_rsrps = [p.rsrp_dbm for p in cpoints if p.rsrp_dbm is not None]
        c_rsrqs = [p.rsrq_db for p in cpoints if p.rsrq_db is not None]
        c_cinrs = [p.cinr_db for p in cpoints if p.cinr_db is not None]
        c_hos = sum(1 for p in cpoints if p.handover_trigger == 1)
        c_earfcn = next((p.earfcn for p in cpoints if p.earfcn is not None), None)
        c_tac = next((p.tac for p in cpoints if p.tac is not None), None)
        c_gen = next((p.network_gen for p in cpoints if p.network_gen), "4G LTE")

        per_cell_list.append(
            PerCellStat(
                pci=cid,
                sample_count=len(cpoints),
                sample_pct=round(len(cpoints) / total_records * 100.0, 1) if total_records > 0 else 0.0,
                mean_rsrp_dbm=round(float(np.mean(c_rsrps)), 1) if c_rsrps else None,
                min_rsrp_dbm=round(float(min(c_rsrps)), 1) if c_rsrps else None,
                max_rsrp_dbm=round(float(max(c_rsrps)), 1) if c_rsrps else None,
                mean_rsrq_db=round(float(np.mean(c_rsrqs)), 1) if c_rsrqs else None,
                mean_cinr_db=round(float(np.mean(c_cinrs)), 1) if c_cinrs else None,
                handover_count=c_hos,
                earfcn=c_earfcn,
                tac=c_tac,
                network_gen=c_gen,
            )
        )

    # Completeness calculation
    total_expected_cells = total_records * 5  # GPS, RSRP, RSRQ, CINR, CellID
    missing_sum = missing_gps_count + missing_rsrp_count + missing_rsrq_count + missing_cinr_count + missing_cell_count
    data_completeness = max(0.0, round((1.0 - (missing_sum / max(1, total_expected_cells))) * 100.0, 1))

    # Duration calculation: exact delta from valid start and end timestamps (fallback to 1s/sample)
    if len(parsed_timestamps) >= 2:
        duration_sec = float(max(parsed_timestamps) - min(parsed_timestamps))
        if duration_sec <= 0:
            duration_sec = float(total_records)
    else:
        duration_sec = float(total_records)

    # Weak coverage segments count (< -105 dBm)
    weak_count = sum(1 for p in points if p.rsrp_dbm is not None and p.rsrp_dbm < -105)
    service_hole_count = sum(1 for p in points if p.rsrp_dbm is not None and p.rsrp_dbm < -115)

    missing_values_summary = {
        "gps": missing_gps_count,
        "rsrp": missing_rsrp_count,
        "rsrq": missing_rsrq_count,
        "cinr": missing_cinr_count,
        "serving_cell": missing_cell_count,
    }

    measurement_provenance = {
        "source_filename": filename,
        "parser_standard": "3GPP TS 36.133 / TS 38.133",
        "coordinate_datum": "WGS84",
        "processed_samples": total_records,
        "device_profile": "CellScope Telecom Field Testing Engine",
    }

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
        total_distance_km=round(total_distance_km, 2) if has_gps else None,
        duration_sec=duration_sec,
        data_completeness_pct=data_completeness,
        mean_accuracy_m=round(float(np.mean(accuracy_vals)), 1) if accuracy_vals else None,
        network_gen_distribution=gen_dist,
        per_cell_stats=per_cell_list,
        weak_coverage_segments_count=weak_count,
        service_hole_count=service_hole_count,
        telemetry_gaps_count=telemetry_gaps,
        missing_values_summary=missing_values_summary,
        measurement_provenance=measurement_provenance,
    )

    return summary, points


def compute_extended_analysis(summary: DatasetSummary, points: List[MeasurementPoint]) -> ExtendedDatasetAnalysis:
    """Computes transitions, explainable anomalies, service holes, and weak coverage segments."""
    transitions: List[CellTransitionEvent] = []
    service_holes: List[ServiceHoleEvent] = []
    anomalies: List[AnomalyEvent] = []
    weak_segments: List[WeakCoverageSegment] = []

    prev_pci: Optional[int] = None
    prev_rsrp: Optional[float] = None
    recent_pcis: List[Tuple[int, int]] = []  # (pci, index)

    # 1. Transitions & Anomaly detection pass
    for idx, p in enumerate(points):
        # Transitions
        if p.serving_cell_id is not None:
            if prev_pci is not None and p.serving_cell_id != prev_pci:
                is_ho = (p.handover_trigger == 1)
                transitions.append(
                    CellTransitionEvent(
                        id=len(transitions) + 1,
                        index=idx,
                        timestamp_raw=p.timestamp_raw,
                        from_pci=prev_pci,
                        to_pci=p.serving_cell_id,
                        latitude=p.latitude,
                        longitude=p.longitude,
                        is_handover_trigger=is_ho,
                        event_type="HANDOVER_TRIGGER" if is_ho else "SERVING_CELL_CHANGE",
                        details=f"Switched from PCI {prev_pci} to PCI {p.serving_cell_id}{' (Handover Trigger flag set)' if is_ho else ''}",
                    )
                )

                # Ping-pong check: did we switch back to a cell we were on within 10 samples?
                past_matches = [past_idx for past_pci, past_idx in recent_pcis if past_pci == p.serving_cell_id]
                if past_matches and (idx - past_matches[-1] <= 10):
                    anomalies.append(
                        AnomalyEvent(
                            id=len(anomalies) + 1,
                            index=idx,
                            timestamp_raw=p.timestamp_raw,
                            latitude=p.latitude,
                            longitude=p.longitude,
                            pci=p.serving_cell_id,
                            rsrp_dbm=p.rsrp_dbm,
                            rsrq_db=p.rsrq_db,
                            cinr_db=p.cinr_db,
                            anomaly_type="PING_PONG_HANDOVER",
                            severity="HIGH",
                            explanation=f"Rapid ping-pong oscillation: Returned to PCI {p.serving_cell_id} within {idx - past_matches[-1]} samples of leaving it.",
                        )
                    )

            recent_pcis.append((p.serving_cell_id, idx))
            if len(recent_pcis) > 20:
                recent_pcis.pop(0)
            prev_pci = p.serving_cell_id

        # Pilot pollution / severe interference: Good RSRP but terrible RSRQ or CINR
        if p.rsrp_dbm is not None and p.rsrp_dbm >= -95:
            if (p.rsrq_db is not None and p.rsrq_db < -16) or (p.cinr_db is not None and p.cinr_db < -3):
                anomalies.append(
                    AnomalyEvent(
                        id=len(anomalies) + 1,
                        index=idx,
                        timestamp_raw=p.timestamp_raw,
                        latitude=p.latitude,
                        longitude=p.longitude,
                        pci=p.serving_cell_id,
                        rsrp_dbm=p.rsrp_dbm,
                        rsrq_db=p.rsrq_db,
                        cinr_db=p.cinr_db,
                        anomaly_type="PILOT_POLLUTION_INTERFERENCE",
                        severity="MEDIUM",
                        explanation=f"Strong serving signal ({p.rsrp_dbm} dBm) contaminated by low quality (RSRQ: {p.rsrq_db} dB, CINR: {p.cinr_db} dB), indicating overlapping co-channel interferers or pilot pollution.",
                    )
                )

        # Sudden signal cliff: RSRP drops > 20 dB in consecutive fixes
        if prev_rsrp is not None and p.rsrp_dbm is not None:
            if (prev_rsrp - p.rsrp_dbm) >= 20.0:
                anomalies.append(
                    AnomalyEvent(
                        id=len(anomalies) + 1,
                        index=idx,
                        timestamp_raw=p.timestamp_raw,
                        latitude=p.latitude,
                        longitude=p.longitude,
                        pci=p.serving_cell_id,
                        rsrp_dbm=p.rsrp_dbm,
                        rsrq_db=p.rsrq_db,
                        cinr_db=p.cinr_db,
                        anomaly_type="SUDDEN_SIGNAL_CLIFF",
                        severity="HIGH",
                        explanation=f"Sudden RSRP drop of {round(prev_rsrp - p.rsrp_dbm, 1)} dB (from {prev_rsrp} dBm to {p.rsrp_dbm} dBm), indicating building entry or rapid shadow fading.",
                    )
                )

        if p.rsrp_dbm is not None:
            prev_rsrp = p.rsrp_dbm

        # Service hole detection (< -115 dBm or no signal)
        if p.rsrp_dbm is not None and p.rsrp_dbm < -115:
            service_holes.append(
                ServiceHoleEvent(
                    id=len(service_holes) + 1,
                    index=idx,
                    timestamp_raw=p.timestamp_raw,
                    latitude=p.latitude,
                    longitude=p.longitude,
                    hole_type="WEAK_RADIO_SIGNAL",
                    severity="CRITICAL",
                    description=f"Serving RSRP is critically degraded at {p.rsrp_dbm} dBm (below -115 dBm 3GPP threshold).",
                )
            )

    # 2. Contiguous weak coverage segments (< -105 dBm)
    in_weak = False
    start_idx = 0
    seg_rsrps: List[float] = []

    for idx, p in enumerate(points):
        is_pt_weak = (p.rsrp_dbm is not None and p.rsrp_dbm < -105)
        if is_pt_weak:
            if not in_weak:
                in_weak = True
                start_idx = idx
                seg_rsrps = [p.rsrp_dbm]
            else:
                seg_rsrps.append(p.rsrp_dbm)
        else:
            if in_weak:
                in_weak = False
                if len(seg_rsrps) >= 2:  # at least 2 consecutive weak samples
                    p_start = points[start_idx]
                    p_end = points[idx - 1]
                    weak_segments.append(
                        WeakCoverageSegment(
                            id=len(weak_segments) + 1,
                            start_index=start_idx,
                            end_index=idx - 1,
                            sample_count=len(seg_rsrps),
                            min_rsrp_dbm=round(float(min(seg_rsrps)), 1),
                            mean_rsrp_dbm=round(float(np.mean(seg_rsrps)), 1),
                            start_lat=p_start.latitude,
                            start_lon=p_start.longitude,
                            end_lat=p_end.latitude,
                            end_lon=p_end.longitude,
                            pci=p_start.serving_cell_id,
                            reason="Cell edge attenuation or obstruction",
                        )
                    )

    if in_weak and len(seg_rsrps) >= 2:
        p_start = points[start_idx]
        p_end = points[-1]
        weak_segments.append(
            WeakCoverageSegment(
                id=len(weak_segments) + 1,
                start_index=start_idx,
                end_index=len(points) - 1,
                sample_count=len(seg_rsrps),
                min_rsrp_dbm=round(float(min(seg_rsrps)), 1),
                mean_rsrp_dbm=round(float(np.mean(seg_rsrps)), 1),
                start_lat=p_start.latitude,
                start_lon=p_start.longitude,
                end_lat=p_end.latitude,
                end_lon=p_end.longitude,
                pci=p_start.serving_cell_id,
                reason="Cell edge attenuation or obstruction",
            )
        )

    data_quality = DataQualityReport(
        total_records=summary.total_records,
        completeness_pct=summary.data_completeness_pct,
        missing_gps_count=summary.missing_values_summary.get("gps", 0),
        missing_rsrp_count=summary.missing_values_summary.get("rsrp", 0),
        missing_rsrq_count=summary.missing_values_summary.get("rsrq", 0),
        missing_cinr_count=summary.missing_values_summary.get("cinr", 0),
        missing_cell_id_count=summary.missing_values_summary.get("serving_cell", 0),
        telemetry_gaps_count=summary.telemetry_gaps_count,
        provenance_source=summary.filename,
        schema_standard="3GPP TS 36.133 (LTE) / TS 38.133 (5G NR)",
        coordinate_system="WGS84 (EPSG:4326)",
    )

    return ExtendedDatasetAnalysis(
        dataset_id=summary.dataset_id,
        summary=summary,
        transitions=transitions,
        service_holes=service_holes,
        anomalies=anomalies,
        weak_segments=weak_segments,
        per_cell_stats=summary.per_cell_stats,
        data_quality=data_quality,
    )

