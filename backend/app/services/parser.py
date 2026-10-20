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
        r"^serving\s+cell\s+cinr\s*$", r"^cinr\s*$", r"^sinr\s*$", r"^serving\s+cell\s+cinr-1\s*$"
    ],
    "handover_trigger": [
        r"^handover\s+trigger\s*$", r"^handover_trigger\s*$", r"^ho_trigger\s*$"
    ],
    "tac": [
        r"^tac\s*$", r"^tracking_area_code\s*$"
    ],
    "earfcn": [
        r"^earfcn\s*$", r"^arfcn\s*$", r"^channel\s*$", r"^nr_arfcn\s*$"
    ],
    "rssi": [
        r"^rssi\s*\(\s*dbm\s*\)\s*$", r"^rssi\s*$", r"^serving_cell_rssi\s*$"
    ],
    "network_type": [
        r"^network\s+type\s*$", r"^network_type\s*$", r"^tech\s*$", r"^rat\s*$", r"^network_gen\s*$"
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
    return df, mapping, unmapped
