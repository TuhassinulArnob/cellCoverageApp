from typing import List, Tuple, Optional
import pandas as pd
import numpy as np
from backend.app.core.config import BD_LAT_MIN, BD_LAT_MAX, BD_LON_MIN, BD_LON_MAX


def validate_coordinates(
    lat: Optional[float],
    lon: Optional[float],
    regional_check: bool = True
) -> Tuple[bool, Optional[str]]:
    """
    Validates latitude and longitude.
    Returns:
        (is_valid: bool, error_reason: Optional[str])
    """
    if lat is None or lon is None:
        return False, "MISSING_COORDINATES"
    if pd.isna(lat) or pd.isna(lon):
        return False, "NAN_COORDINATES"
    
    # Global bounds check
    if not (-90.0 <= lat <= 90.0):
        return False, f"LAT_OUT_OF_GLOBAL_BOUNDS: {lat}"
    if not (-180.0 <= lon <= 180.0):
        return False, f"LON_OUT_OF_GLOBAL_BOUNDS: {lon}"

    # Null island / 0,0 check
    if abs(lat) < 0.001 and abs(lon) < 0.001:
        return False, "NULL_ISLAND_COORDINATES"

    # Regional sanity check for Bangladesh dataset
    if regional_check:
        # Check if latitude is roughly in BD, but longitude is an outlier (like 0.116667)
        if (BD_LAT_MIN - 1.0 <= lat <= BD_LAT_MAX + 1.0):
            if not (BD_LON_MIN - 2.0 <= lon <= BD_LON_MAX + 2.0):
                return False, f"REGIONAL_OUTLIER_LONGITUDE: {lon}"

    return True, None


def validate_rf_ranges(
    rsrp_dbm: Optional[float],
    rsrq_db: Optional[float],
    cinr_db: Optional[float]
) -> List[str]:
    """Generates warnings for any extreme or out-of-spec RF values."""
    warnings = []
    if rsrp_dbm is not None:
        if rsrp_dbm < -145.0 or rsrp_dbm > -40.0:
            warnings.append(f"Unusual RSRP value: {rsrp_dbm} dBm")
    if rsrq_db is not None:
        if rsrq_db < -25.0 or rsrq_db > 2.0:
            warnings.append(f"Unusual RSRQ value: {rsrq_db} dB")
    if cinr_db is not None:
        if cinr_db < -35.0 or cinr_db > 45.0:
            warnings.append(f"Unusual CINR value: {cinr_db} dB")
    return warnings
