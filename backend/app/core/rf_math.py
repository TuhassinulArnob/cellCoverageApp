"""
RF Engineering Calculations & 3GPP TS 36.133 Conversion Formulas.
"""
from typing import Optional, Tuple
from backend.app.core.config import (
    RSRP_EXCELLENT, RSRP_GOOD, RSRP_FAIR,
    RSRQ_EXCELLENT, RSRQ_GOOD, RSRQ_FAIR,
    CINR_EXCELLENT, CINR_GOOD, CINR_FAIR,
)


def rsrp_ie_to_dbm(ie_value: Optional[float]) -> Optional[float]:
    """
    Convert 3GPP TS 36.133 RSRP Information Element (IE 0..97) to physical dBm.
    Table 9.1.4-1: RSRP measurement report mapping:
      RSRP_00: RSRP < -140 dBm
      RSRP_01: -140 <= RSRP < -139 dBm
      ...
      RSRP_97: -44 <= RSRP < -43 dBm
    Formula: RSRP_dBm = IE - 140 dBm
    """
    if ie_value is None or ie_value != ie_value:  # handles NaN
        return None
    try:
        val = float(ie_value)
        # If the input is already in dBm (e.g. negative value between -145 and -40)
        if -150.0 <= val <= -30.0:
            return round(val, 2)
        # If IE integer (0 to 97 or slightly extended)
        if 0 <= val <= 105:
            return round(val - 140.0, 2)
        return round(val, 2)
    except (ValueError, TypeError):
        return None


def rsrq_ie_to_db(ie_value: Optional[float]) -> Optional[float]:
    """
    Convert 3GPP TS 36.133 RSRQ Information Element (IE 0..34) to physical dB.
    Table 9.1.7-1: RSRQ measurement report mapping:
      RSRQ_00: RSRQ < -19.5 dB
      RSRQ_01: -19.5 <= RSRQ < -19 dB
      ...
      RSRQ_34: RSRQ >= -3 dB
    Formula: RSRQ_dB = (IE * 0.5) - 19.5 dB
    """
    if ie_value is None or ie_value != ie_value:
        return None
    try:
        val = float(ie_value)
        # If the input is strictly negative and within physical RSRQ range (-30 to -1 dB)
        if -30.0 <= val < 0.0:
            return round(val, 2)
        # If IE integer (0 to 50)
        if 0.0 <= val <= 50.0:
            return round((val * 0.5) - 19.5, 2)
        return round(val, 2)
    except (ValueError, TypeError):
        return None


def classify_rsrp(rsrp_dbm: Optional[float]) -> str:
    """Classify RSRP coverage into engineering bands."""
    if rsrp_dbm is None:
        return "Unknown"
    if rsrp_dbm >= RSRP_EXCELLENT:
        return "Excellent"
    if rsrp_dbm >= RSRP_GOOD:
        return "Good"
    if rsrp_dbm >= RSRP_FAIR:
        return "Fair"
    return "Poor"


def classify_rsrq(rsrq_db: Optional[float]) -> str:
    """Classify RSRQ signal quality into engineering bands."""
    if rsrq_db is None:
        return "Unknown"
    if rsrq_db >= RSRQ_EXCELLENT:
        return "Excellent"
    if rsrq_db >= RSRQ_GOOD:
        return "Good"
    if rsrq_db >= RSRQ_FAIR:
        return "Fair"
    return "Poor"


def classify_cinr(cinr_db: Optional[float]) -> str:
    """Classify CINR/SINR interference condition."""
    if cinr_db is None:
        return "Unknown"
    if cinr_db >= CINR_EXCELLENT:
        return "Excellent"
    if cinr_db >= CINR_GOOD:
        return "Good"
    if cinr_db >= CINR_FAIR:
        return "Fair"
    return "Poor"


def get_metric_color(metric_name: str, value: Optional[float]) -> str:
    """
    Returns hex color for Leaflet map markers based on telecom standards:
    Green: Excellent (#10B981)
    Blue/Cyan: Good (#0EA5E9)
    Amber/Yellow: Fair (#F59E0B)
    Red: Poor (#EF4444)
    Gray: Unknown (#6B7280)
    """
    if value is None:
        return "#6B7280"

    metric = metric_name.lower()
    if "rsrp" in metric:
        category = classify_rsrp(value)
    elif "rsrq" in metric:
        category = classify_rsrq(value)
    else:  # cinr / sinr
        category = classify_cinr(value)

    if category == "Excellent":
        return "#10B981"
    if category == "Good":
        return "#0EA5E9"
    if category == "Fair":
        return "#F59E0B"
    if category == "Poor":
        return "#EF4444"
    return "#6B7280"
