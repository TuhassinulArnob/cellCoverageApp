import io
import re
from typing import Dict, List, Optional, Tuple, Any
import pandas as pd
import numpy as np


# Standard canonical field mappings to regex patterns or aliases
CANONICAL_FIELD_PATTERNS = {
    "timestamp": [
        r"^timestamp\s*$", r"^time\s*$", r"^pc\s+timestamp\s*$", r"^date\s*$"
    ],
    "latitude": [
        r"^latitude\s*$", r"^lat\s*$"
    ],
    "longitude": [
        r"^longitude\s*$", r"^lon\s*$", r"^long\s*$"
    ],
    "speed": [
        r"^speed\s*\(\s*km/h\s*\)\s*$", r"^speed\s*$", r"^speed_kmh\s*$"
    ],
    "serving_cell_id": [
        r"^serving\s+cell\s+id\s*$", r"^serving_cell_id\s*$", r"^pci\s*$"
    ],
    "neighbor_cell_id": [
        r"^neighbor\s+cell\s+id\s*$", r"^neighcell\s+id\s*$", r"^neighbor_cell_id\s*$"
    ],
    "rsrp": [
        r"^serving\s+cell\s+rsrp\s*$", r"^rsrp\s*$", r"^serving_cell_rsrp\s*$"
    ],
    "rsrq": [
        r"^serving\s+cell\s+rsrq\s*$", r"^rsrq\s*$", r"^serving_cell_rsrq\s*$", r"^serving\s+cell\s+rsrpq\s*$"
    ],
    "cinr": [
        r"^serving\s+cell\s+cinr\s*$", r"^cinr\s*$", r"^sinr\s*$", r"^serving\s+cell\s+cinr-1\s*$", r"^sinr\s*\(\s*db\s*\)\s*$", r"^cinr\s*\(\s*db\s*\)\s*$"
    ],
    "sinr_source": [
        r"^sinr_source\s*$", r"^sinr\s+source\s*$", r"^cinr_source\s*$"
    ],
    "estimation_method": [
        r"^estimation_method\s*$", r"^sinr_method\s*$"
    ],
    "handover_trigger": [
        r"^handover\s+trigger\s*$", r"^handover_trigger\s*$", r"^ho_trigger\s*$"
    ],
    "tac": [
        r"^tac\s*$", r"^tracking_area_code\s*$"
    ],
    "earfcn": [
        r"^earfcn\s*$", r"^arfcn\s*$", r"^channel\s*$", r"^nr_arfcn\s*$", r"^channel\s+number\s*$"
    ],
    "rssi": [
        r"^rssi\s*\(\s*dbm\s*\)\s*$", r"^rssi\s*$", r"^serving_cell_rssi\s*$"
    ],
    "network_type": [
        r"^network\s+type\s*$", r"^network_type\s*$", r"^tech\s*$", r"^rat\s*$", r"^network_gen\s*$", r"^serving\s+rat\s*$"
    ],
    "operator": [
        r"^operator\s*$", r"^carrier\s*$", r"^network_operator\s*$", r"^operator_alpha\s*$"
    ],
    "accuracy": [
        r"^accuracy\s*\(\s*m\s*\)\s*$", r"^accuracy\s*$", r"^gps_accuracy\s*$", r"^accuracy_m\s*$"
    ],
    "heading": [
        r"^heading\s*\(\s*deg\s*\)\s*$", r"^heading\s*$", r"^bearing\s*$", r"^azimuth\s*$"
    ],
    "timing_advance": [
        r"^timing\s+advance\s*$", r"^timing_advance\s*$", r"^ta\s*$"
    ],
    "cqi": [
        r"^cqi\s*$", r"^channel_quality_indicator\s*$"
    ],
    "eci": [
        r"^eci\s*$", r"^cell_identity\s*$", r"^ci\s*$", r"^nci\s*$"
    ],
    "altitude": [
        r"^altitude\s*\(\s*m\s*\)\s*$", r"^altitude\s*$", r"^alt\s*$"
    ],
    "ping_latency_ms": [
        r"^ping\s*(\(\s*ms\s*\))?$", r"^ping_latency_ms$", r"^latency_ms$", r"^rtt_ms$", r"^rtt_median_ms$", r"^ping[\s_]+latency.*"
    ],
    "download_mbps": [
        r"^download\s*(\(\s*mbps\s*\))?$", r"^download_mbps$", r"^download_throughput_mbps$", r"^dl_mbps$", r"^download[\s_]+speed.*"
    ],
    "upload_mbps": [
        r"^upload\s*(\(\s*mbps\s*\))?$", r"^upload_mbps$", r"^upload_throughput_mbps$", r"^ul_mbps$", r"^upload[\s_]+speed.*"
    ],
    "jitter_ms": [
        r"^jitter\s*(\(\s*ms\s*\))?$", r"^jitter_ms$", r"^jitter[\s_]*.*"
    ],
    "packet_loss_pct": [
        r"^packet[\s_]+loss\s*(\(\s*%\s*\))?$", r"^packet_loss_pct$", r"^probe_loss_pct$", r"^probe[\s_]+loss\s*(\(\s*%\s*\))?$"
    ],
}


def detect_column_mapping(columns: List[str]) -> Tuple[Dict[str, str], List[str]]:
    """
    Given raw CSV header strings, map them to canonical fields.
    Returns:
      mapping: {canonical_name: raw_column_name}
      unmapped: [raw_column_name, ...]
    """
    mapping: Dict[str, str] = {}
    unmapped: List[str] = []

    for raw_col in columns:
        col_clean = raw_col.strip()
        matched = False
        for canon, patterns in CANONICAL_FIELD_PATTERNS.items():
            if canon in mapping:
                continue  # already mapped first best match
            for pat in patterns:
                if re.match(pat, col_clean, re.IGNORECASE):
                    mapping[canon] = raw_col
                    matched = True
                    break
            if matched:
                break
        if not matched:
            unmapped.append(raw_col)

    return mapping, unmapped


def clean_numeric_value(val: Any) -> Optional[float]:
    """Cleans '-' or '--' or NaN string values to None, else returns float."""
    if val is None or pd.isna(val):
        return None
    val_str = str(val).strip()
    if val_str in ["-", "--", "nan", "NaN", "null", "NULL", ""]:
        return None
    try:
        return float(val_str)
    except (ValueError, TypeError):
        return None


def extract_telemetry_lines(raw_text: str) -> str:
    """
    Extracts the telemetry section lines from a single-header or multi-section CSV file.
    Stops before subsequent non-telemetry sections (e.g. QoE, Transitions, Continuity).
    """
    lines = raw_text.splitlines()
    telemetry_lines = []
    in_telemetry = False

    for line in lines:
        s = line.strip()
        if not s:
            continue
        if s.startswith("#"):
            lower = s.lower()
            if "telemetry" in lower:
                in_telemetry = True
                continue
            elif any(k in lower for k in ["active qoe", "cell transition", "network continuity", "service hole", "call reliability"]):
                if in_telemetry:
                    break
                continue
            else:
                continue
        # Direct section header check
        if s.startswith("test_id,") or "Event Type,From RAT" in s or "From Generation,To Generation" in s:
            if in_telemetry:
                break
        in_telemetry = True
        telemetry_lines.append(line)

    return "\n".join(telemetry_lines)


def parse_csv_to_dataframe(
    content_bytes: Optional[bytes] = None,
    file_path: Optional[str] = None
) -> Tuple[pd.DataFrame, Dict[str, str], List[str]]:
    """
    Parse a CSV file (either from raw bytes or file path) and normalize
    headers to canonical field names where possible.
    Section-aware: safely parses multi-section extended CellScope v3.0 exports as well as legacy single-header files.
    """
    df, mapping, unmapped, _ = parse_csv_with_raw_text(content_bytes=content_bytes, file_path=file_path)
    return df, mapping, unmapped


def parse_csv_with_raw_text(
    content_bytes: Optional[bytes] = None,
    file_path: Optional[str] = None
) -> Tuple[pd.DataFrame, Dict[str, str], List[str], str]:
    """
    Parses CSV and returns (df, mapping, unmapped, raw_text) so subsequent section
    extractors (e.g. Active QoE, Cell Transitions) have access to the complete payload.
    """
    if content_bytes is not None:
        raw_text = content_bytes.decode("utf-8", errors="replace")
    elif file_path is not None:
        with open(file_path, "r", encoding="utf-8", errors="replace") as f:
            raw_text = f.read()
    else:
        raise ValueError("Either content_bytes or file_path must be provided.")

    cleaned_csv = extract_telemetry_lines(raw_text)
    df = pd.read_csv(
        io.StringIO(cleaned_csv),
        na_values=["-", "--", "NA", "null"],
        on_bad_lines="skip"
    )

    mapping, unmapped = detect_column_mapping(list(df.columns))
    return df, mapping, unmapped, raw_text


def extract_qoe_records(
    raw_text: str,
    df: Optional[pd.DataFrame] = None,
    mapping: Optional[Dict[str, str]] = None
) -> List[Dict[str, Any]]:
    """
    Extracts active network QoE speed, ping, latency, and reliability test records.
    Supports both multi-section CellScope v3.0 exports (with '# Active QoE & Congestion Section')
    and single-sheet tabular CSVs that contain speed/ping columns.
    Never fabricates measurements or replaces missing values with zero.
    """
    import csv

    records: List[Dict[str, Any]] = []

    # 1. Look for # Active QoE section in raw_text
    lines = raw_text.splitlines()
    qoe_lines: List[str] = []
    in_qoe = False

    for line in lines:
        s = line.strip()
        if not s:
            continue
        if s.startswith("#"):
            lower = s.lower()
            if "active qoe" in lower or "qoe & congestion" in lower:
                in_qoe = True
                continue
            elif in_qoe:
                break
        elif in_qoe:
            qoe_lines.append(line)

    if qoe_lines:
        reader = csv.DictReader(io.StringIO("\n".join(qoe_lines)))
        row_idx = 1
        for row in reader:
            if not row:
                continue

            # Skip malformed lines (e.g. malformed single-token lines or rows without test_id / timestamp)
            raw_id = row.get("test_id")
            if not raw_id or "MALFORMED" in str(raw_id).upper():
                continue

            try:
                test_id = int(float(str(raw_id).strip()))
            except (ValueError, TypeError):
                test_id = row_idx

            row_idx += 1

            # Extract timestamps
            ts_raw = str(row.get("timestamp_iso") or row.get("timestamp") or "").strip()
            ts_ms_val = clean_numeric_value(row.get("timestamp_epoch_ms") or row.get("timestamp_ms"))
            ts_ms = int(ts_ms_val) if ts_ms_val is not None else None

            # Test metadata
            test_type = str(row.get("test_trigger") or row.get("test_type") or "FULL_CYCLE").strip()
            test_status = str(row.get("test_status") or "COMPLETED").strip().upper()
            skipped_reason = row.get("skipped_reason")
            if skipped_reason:
                skipped_reason = str(skipped_reason).strip() or None

            # Location
            lat = clean_numeric_value(row.get("latitude"))
            lon = clean_numeric_value(row.get("longitude"))
            acc = clean_numeric_value(row.get("gps_accuracy_m") or row.get("accuracy"))
            has_valid_gps = bool(lat is not None and lon is not None and (abs(lat) > 0.0001 or abs(lon) > 0.0001))

            # Radio / Cellular context
            serving_rat = row.get("serving_rat")
            if serving_rat:
                serving_rat = str(serving_rat).strip() or None

            pci_val = clean_numeric_value(row.get("serving_pci") or row.get("pci"))
            serving_pci = int(pci_val) if pci_val is not None else None

            ch_val = clean_numeric_value(row.get("serving_channel") or row.get("earfcn") or row.get("channel"))
            serving_ch = int(ch_val) if ch_val is not None else None

            rsrp = clean_numeric_value(row.get("serving_rsrp_dbm") or row.get("rsrp_dbm") or row.get("rsrp"))
            rsrq = clean_numeric_value(row.get("serving_rsrq_db") or row.get("rsrq_db") or row.get("rsrq"))
            sinr = clean_numeric_value(row.get("serving_sinr_db") or row.get("sinr_db") or row.get("cinr_db") or row.get("cinr"))
            operator = row.get("operator") or row.get("network_operator")
            if operator:
                operator = str(operator).strip() or None

            endpoint = row.get("endpoint_url")
            if endpoint:
                endpoint = str(endpoint).strip() or None

            # Throughput
            dl_mbps = clean_numeric_value(row.get("download_throughput_mbps") or row.get("download_mbps") or row.get("dl_mbps"))
            ul_mbps = clean_numeric_value(row.get("upload_throughput_mbps") or row.get("upload_mbps") or row.get("ul_mbps"))

            dl_bytes_val = clean_numeric_value(row.get("download_bytes_transferred") or row.get("download_bytes") or row.get("dl_bytes"))
            dl_bytes = int(dl_bytes_val) if dl_bytes_val is not None else None

            ul_bytes_val = clean_numeric_value(row.get("upload_bytes_transferred") or row.get("upload_bytes") or row.get("ul_bytes"))
            ul_bytes = int(ul_bytes_val) if ul_bytes_val is not None else None

            dl_dur_val = clean_numeric_value(row.get("download_duration_ms") or row.get("dl_duration_ms"))
            dl_duration_ms = int(dl_dur_val) if dl_dur_val is not None else None

            ul_dur_val = clean_numeric_value(row.get("upload_duration_ms") or row.get("ul_duration_ms"))
            ul_duration_ms = int(ul_dur_val) if ul_dur_val is not None else None

            # Latency & Reliability
            ping_ms = clean_numeric_value(row.get("rtt_median_ms") or row.get("latency_ms") or row.get("ping_latency_ms") or row.get("rtt_ms") or row.get("ping_ms"))
            rtt_mean = clean_numeric_value(row.get("rtt_mean_ms"))
            rtt_p95 = clean_numeric_value(row.get("rtt_p95_ms"))
            jitter = clean_numeric_value(row.get("jitter_ms"))
            loss_pct = clean_numeric_value(row.get("probe_loss_pct") or row.get("packet_loss_pct"))

            probes_att_val = clean_numeric_value(row.get("probes_attempted"))
            probes_att = int(probes_att_val) if probes_att_val is not None else None

            probes_succ_val = clean_numeric_value(row.get("probes_successful"))
            probes_succ = int(probes_succ_val) if probes_succ_val is not None else None

            classification = row.get("classification")
            if classification:
                classification = str(classification).strip() or None

            data_prov = str(row.get("data_provenance") or "MEASURED").strip()

            records.append({
                "id": test_id,
                "session_id": int(clean_numeric_value(row.get("session_id")) or 1),
                "timestamp_raw": ts_raw,
                "timestamp_ms": ts_ms,
                "test_type": test_type,
                "test_status": test_status,
                "skipped_reason": skipped_reason,
                "latitude": lat,
                "longitude": lon,
                "gps_accuracy_m": acc,
                "has_valid_gps": has_valid_gps,
                "serving_rat": serving_rat,
                "serving_pci": serving_pci,
                "serving_channel": serving_ch,
                "serving_rsrp_dbm": rsrp,
                "serving_rsrq_db": rsrq,
                "serving_sinr_db": sinr,
                "operator": operator,
                "endpoint_url": endpoint,
                "download_mbps": dl_mbps,
                "upload_mbps": ul_mbps,
                "download_bytes": dl_bytes,
                "upload_bytes": ul_bytes,
                "download_duration_ms": dl_duration_ms,
                "upload_duration_ms": ul_duration_ms,
                "ping_latency_ms": ping_ms,
                "rtt_mean_ms": rtt_mean,
                "rtt_p95_ms": rtt_p95,
                "jitter_ms": jitter,
                "packet_loss_pct": loss_pct,
                "probes_attempted": probes_att,
                "probes_successful": probes_succ,
                "classification": classification,
                "data_provenance": data_prov
            })

    # 2. If no QoE records found from section, check if main dataframe has speed/ping columns
    elif df is not None and mapping is not None:
        dl_col = mapping.get("download_mbps")
        ul_col = mapping.get("upload_mbps")
        ping_col = mapping.get("ping_latency_ms")
        jitter_col = mapping.get("jitter_ms")
        loss_col = mapping.get("packet_loss_pct")

        if any([dl_col, ul_col, ping_col]):
            time_col = mapping.get("timestamp")
            lat_col = mapping.get("latitude")
            lon_col = mapping.get("longitude")
            pci_col = mapping.get("serving_cell_id")
            rsrp_col = mapping.get("rsrp")
            rsrq_col = mapping.get("rsrq")
            cinr_col = mapping.get("cinr")
            rat_col = mapping.get("network_type")
            op_col = mapping.get("operator")

            for idx, row in df.iterrows():
                dl_val = clean_numeric_value(row[dl_col]) if dl_col and dl_col in row else None
                ul_val = clean_numeric_value(row[ul_col]) if ul_col and ul_col in row else None
                ping_val = clean_numeric_value(row[ping_col]) if ping_col and ping_col in row else None

                # Only create a record if at least one measurement is present
                if dl_val is not None or ul_val is not None or ping_val is not None:
                    lat_val = clean_numeric_value(row[lat_col]) if lat_col and lat_col in row else None
                    lon_val = clean_numeric_value(row[lon_col]) if lon_col and lon_col in row else None
                    has_valid_gps = bool(lat_val is not None and lon_val is not None and (abs(lat_val) > 0.0001 or abs(lon_val) > 0.0001))

                    records.append({
                        "id": int(idx) + 1,
                        "session_id": 1,
                        "timestamp_raw": str(row[time_col]) if time_col and time_col in row and not pd.isna(row[time_col]) else "",
                        "timestamp_ms": None,
                        "test_type": "SPEED_TEST",
                        "test_status": "COMPLETED",
                        "skipped_reason": None,
                        "latitude": lat_val,
                        "longitude": lon_val,
                        "gps_accuracy_m": None,
                        "has_valid_gps": has_valid_gps,
                        "serving_rat": str(row[rat_col]).strip() if rat_col and rat_col in row and not pd.isna(row[rat_col]) else None,
                        "serving_pci": int(clean_numeric_value(row[pci_col])) if pci_col and pci_col in row and clean_numeric_value(row[pci_col]) is not None else None,
                        "serving_channel": None,
                        "serving_rsrp_dbm": clean_numeric_value(row[rsrp_col]) if rsrp_col and rsrp_col in row else None,
                        "serving_rsrq_db": clean_numeric_value(row[rsrq_col]) if rsrq_col and rsrq_col in row else None,
                        "serving_sinr_db": clean_numeric_value(row[cinr_col]) if cinr_col and cinr_col in row else None,
                        "operator": str(row[op_col]).strip() if op_col and op_col in row and not pd.isna(row[op_col]) else None,
                        "endpoint_url": None,
                        "download_mbps": dl_val,
                        "upload_mbps": ul_val,
                        "download_bytes": None,
                        "upload_bytes": None,
                        "download_duration_ms": None,
                        "upload_duration_ms": None,
                        "ping_latency_ms": ping_val,
                        "rtt_mean_ms": ping_val,
                        "rtt_p95_ms": None,
                        "jitter_ms": clean_numeric_value(row[jitter_col]) if jitter_col and jitter_col in row else None,
                        "packet_loss_pct": clean_numeric_value(row[loss_col]) if loss_col and loss_col in row else None,
                        "probes_attempted": None,
                        "probes_successful": None,
                        "classification": None,
                        "data_provenance": "MEASURED"
                    })

    return records


def extract_cell_transition_records(raw_text: str) -> List[Dict[str, Any]]:
    """
    Extracts recorded mobility and cell transition events from multi-section exports
    containing '# Cell Transitions (Mobility & Handover) Section'.
    """
    import csv

    records: List[Dict[str, Any]] = []
    lines = raw_text.splitlines()
    trans_lines: List[str] = []
    in_trans = False

    for line in lines:
        s = line.strip()
        if not s:
            continue
        if s.startswith("#"):
            lower = s.lower()
            if "cell transition" in lower or "mobility & handover" in lower:
                in_trans = True
                continue
            elif in_trans:
                break
        elif in_trans:
            trans_lines.append(line)

    if not trans_lines:
        return []

    reader = csv.DictReader(io.StringIO("\n".join(trans_lines)))
    for idx, row in enumerate(reader):
        if not row:
            continue
        ts_raw = str(row.get("Timestamp") or row.get("timestamp") or "").strip()
        if not ts_raw or "MALFORMED" in ts_raw.upper():
            continue

        lat = clean_numeric_value(row.get("Latitude") or row.get("latitude"))
        lon = clean_numeric_value(row.get("Longitude") or row.get("longitude"))

        raw_ev = str(row.get("Event Type") or row.get("event_type") or "OBSERVED_TRANSITION").strip().upper()
        # Normalize legacy INTRA_LTE -> INTRA_FREQ
        if raw_ev == "INTRA_LTE":
            ev_type = "INTRA_FREQ"
        else:
            ev_type = raw_ev

        from_rat = row.get("From RAT") or row.get("from_rat")
        to_rat = row.get("To RAT") or row.get("to_rat")

        from_pci = clean_numeric_value(row.get("From PCI") or row.get("from_pci"))
        to_pci = clean_numeric_value(row.get("To PCI") or row.get("to_pci"))

        from_ch = clean_numeric_value(row.get("From Channel") or row.get("from_channel"))
        to_ch = clean_numeric_value(row.get("To Channel") or row.get("to_channel"))

        from_cid = clean_numeric_value(row.get("From Cell ID") or row.get("from_cell_id"))
        to_cid = clean_numeric_value(row.get("To Cell ID") or row.get("to_cell_id"))

        from_sig = clean_numeric_value(row.get("From Signal (dBm)") or row.get("from_signal_dbm"))
        to_sig = clean_numeric_value(row.get("To Signal (dBm)") or row.get("to_signal_dbm"))
        delta_sig = round(to_sig - from_sig, 1) if (to_sig is not None and from_sig is not None) else None

        from_tac = clean_numeric_value(row.get("From TAC/LAC") or row.get("from_tac"))
        to_tac = clean_numeric_value(row.get("To TAC/LAC") or row.get("to_tac"))

        conf_score = clean_numeric_value(row.get("Confidence Score") or row.get("confidence_score"))
        details = str(row.get("Details") or row.get("details") or "").strip()

        # Grounded handover evidence check (TAC change or cell change alone is NOT a confirmed handover)
        is_tac_change = (from_tac is not None and to_tac is not None and from_tac != to_tac)
        is_explicit_confirmed = (raw_ev == "CONFIRMED_HANDOVER")

        # Evidence-based confidence classification
        if is_explicit_confirmed:
            ev_type = "CONFIRMED_HANDOVER"
            conf_label = "CONFIRMED"
            reason = details if details else "Direct confirmed handover event"
        elif ev_type in ("INTRA_FREQ", "INTRA_FREQUENCY"):
            ev_type = "INTRA_FREQ"
            conf_label = "HIGH"
            reason = details if details else "Intra-frequency transition on matching carrier frequency"
        elif ev_type in ("INTER_FREQ", "INTER_FREQUENCY"):
            ev_type = "INTER_FREQ"
            conf_label = "MEDIUM"
            reason = details if details else "Inter-frequency carrier transition on distinct channel frequencies"
        elif ev_type in ("INTER_RAT", "RAT_MOBILITY"):
            ev_type = "INTER_RAT"
            conf_label = "CONFIRMED_RAT"
            reason = details if details else "Radio access technology change"
        elif ev_type == "CELL_RESELECTION":
            ev_type = "CELL_RESELECTION"
            conf_label = "MEDIUM"
            reason = details if details else "Cell reselection transition"
        elif "PING_PONG" in ev_type:
            ev_type = "PING_PONG"
            conf_label = "HIGH"
            reason = details if details else "Rapid return to previously serving cell"
        elif ev_type == "SIGNAL_LOSS":
            ev_type = "SIGNAL_LOSS"
            conf_label = "HIGH"
            reason = details if details else "Serving cell signal loss"
        else:
            ev_type = "OBSERVED_CELL_CHANGE"
            conf_label = "EMPIRICAL"
            reason = details if details else "Observed serving-cell change"

        is_ho = (ev_type == "CONFIRMED_HANDOVER")

        records.append({
            "id": idx + 1,
            "index": idx,
            "timestamp_raw": ts_raw,
            "latitude": lat,
            "longitude": lon,
            "event_type": ev_type,
            "from_rat": str(from_rat).strip() if from_rat else None,
            "to_rat": str(to_rat).strip() if to_rat else None,
            "from_pci": int(from_pci) if from_pci is not None else None,
            "to_pci": int(to_pci) if to_pci is not None else None,
            "from_channel": int(from_ch) if from_ch is not None else None,
            "to_channel": int(to_ch) if to_ch is not None else None,
            "from_cell_id": int(from_cid) if from_cid is not None else None,
            "to_cell_id": int(to_cid) if to_cid is not None else None,
            "from_signal_dbm": from_sig,
            "to_signal_dbm": to_sig,
            "delta_signal_db": delta_sig,
            "from_tac": int(from_tac) if from_tac is not None else None,
            "to_tac": int(to_tac) if to_tac is not None else None,
            "confidence_label": conf_label,
            "confidence_score": conf_score,
            "classification_reason": reason,
            "details": details,
            "is_handover_trigger": is_ho,
        })

    return records
