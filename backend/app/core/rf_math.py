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


def bandwidth_to_rb(bandwidth_khz: Optional[int]) -> int:
    """Map LTE channel bandwidth in kHz to 3GPP TS 36.101 Resource Block count N."""
    if bandwidth_khz is None or bandwidth_khz <= 0:
        return 50  # Default nominal 10 MHz
    if bandwidth_khz <= 1400:
        return 6
    if bandwidth_khz <= 3000:
        return 15
    if bandwidth_khz <= 5000:
        return 25
    if bandwidth_khz <= 10000:
        return 50
    if bandwidth_khz <= 15000:
        return 75
    return 100


def calculate_implied_rssi(rsrp_dbm: float, rsrq_db: float, bandwidth_khz: Optional[int] = None) -> float:
    """
    Calculate implied carrier RSSI using 3GPP TS 36.214 definition:
    RSRQ = N * RSRP / RSSI
    RSSI_linear = N * RSRP_linear / RSRQ_linear
    RSSI_dBm = RSRP_dBm + 10 * log10(N) - RSRQ_dB
    """
    import math
    num_rb = bandwidth_to_rb(bandwidth_khz)
    return round(rsrp_dbm + 10.0 * math.log10(num_rb) - rsrq_db, 2)


def estimate_lte_sinr(
    rsrp_dbm: Optional[float],
    rsrq_db: Optional[float],
    bandwidth_khz: Optional[int] = None,
    load_factor: float = 0.5
) -> Tuple[Optional[float], Optional[float]]:
    """
    Estimate LTE SINR using 3GPP TS 36.214 radio model under nominal cell load.

    Formula:
      offset = 2.0 + 10.0 * load_factor (7.0 for 50% traffic load)
      denom = (1.0 / RSRQ_linear) - offset
      SINR_linear = 12.0 / denom (if denom > 0.001) else clamped to max 30 dB

    Returns (estimated_sinr_db, implied_rssi_dbm).
    """
    import math
    if rsrp_dbm is None or rsrq_db is None:
        return None, None
    if not (-140.0 <= rsrp_dbm <= -40.0 and -25.0 <= rsrq_db <= -3.0):
        return None, None

    implied_rssi = calculate_implied_rssi(rsrp_dbm, rsrq_db, bandwidth_khz)
    rsrq_linear = 10.0 ** (rsrq_db / 10.0)
    offset = 2.0 + (10.0 * load_factor)
    denom = (1.0 / rsrq_linear) - offset

    if denom > 0.001:
        sinr_linear = 12.0 / denom
        sinr_db = 10.0 * math.log10(sinr_linear)
        sinr_db = max(-20.0, min(30.0, sinr_db))
    else:
        sinr_db = 30.0

    return round(sinr_db, 1), implied_rssi


def get_sinr_qualitative_diagnostic(sinr_db: Optional[float], rsrq_db: Optional[float] = None) -> str:
    if sinr_db is not None:
        if sinr_db >= 20.0:
            return "Clean channel, negligible co-channel interference (Excellent SINR)"
        if sinr_db >= 13.0:
            return "Low interference, high spectral efficiency achievable (Good SINR)"
        if sinr_db >= 0.0:
            return "Moderate interference or higher noise floor; lower modulation order (Fair SINR)"
        return "Severe co-channel interference or pilot pollution; cell-edge conditions (Poor SINR)"
    if rsrq_db is not None:
        if rsrq_db >= -10.0:
            return f"Clean RF channel conditions indicated by strong RSRQ ({rsrq_db} dB)"
        if rsrq_db >= -14.0:
            return f"Normal operating cell conditions (RSRQ: {rsrq_db} dB)"
        return f"Noticeable interference or high cell load (RSRQ: {rsrq_db} dB)"
    return "Physical RF parameters insufficient for SINR estimation"


def resolve_sinr_provenance(
    raw_sinr: Optional[float],
    rsrp_dbm: Optional[float],
    rsrq_db: Optional[float],
    rssi_dbm: Optional[float] = None,
    bandwidth_khz: Optional[int] = None,
    rat: str = "LTE",
    has_seen_nonzero_sinr: bool = True
) -> Tuple[Optional[float], str, Optional[str], Optional[str], Optional[str], str, Optional[float]]:
    """
    Standard-compliant SINR provenance resolution.
    Strict priority:
    1. MEASURED
    2. ESTIMATED (LTE only, with validated inputs)
    3. UNAVAILABLE

    Returns tuple:
    (sinr_db, sinr_source, estimation_method, estimation_assumptions, sinr_confidence, qualitative_diagnostic, implied_rssi_dbm)
    """
    rat_upper = (rat or "LTE").upper()
    is_nr = "5G" in rat_upper or "NR" in rat_upper

    # Check for valid measured SINR (Android sentinels excluded)
    # Android RIL reports raw 0 when SINR is unsupported/uninitialized.
    # If raw_sinr is 0.0 and has_seen_nonzero_sinr is False, reject 0.0 as measured.
    is_unsupported_zero = (raw_sinr is not None and abs(raw_sinr) < 1e-6 and not has_seen_nonzero_sinr)
    if raw_sinr is not None and raw_sinr != 99 and raw_sinr != 0x7FFFFFFF and raw_sinr != -1 and not is_unsupported_zero:
        if -23.0 <= raw_sinr <= 40.0:
            method = "NR_SS_SINR_MEASURED" if is_nr else "ANDROID_RIL_RSSNR_MEASURED"
            assump = "Direct 5G NR modem measurement" if is_nr else "Direct modem baseband measurement (CellSignalStrengthLte.rssnr)"
            return (
                round(raw_sinr, 1),
                "MEASURED",
                method,
                assump,
                "HIGH",
                get_sinr_qualitative_diagnostic(raw_sinr),
                rssi_dbm
            )

    # If NR and measured was absent, enforce 3GPP separation
    if is_nr:
        qual = get_sinr_qualitative_diagnostic(None, rsrq_db)
        return (
            None,
            "UNAVAILABLE",
            "NR_MODEL_UNSUPPORTED",
            "3GPP TS 38.215 NR SS-RSRQ definition does not support LTE carrier RSSI equations. Qualitative diagnostic only.",
            None,
            qual,
            None
        )

    # Evaluate LTE estimation
    est_sinr, implied_rssi = estimate_lte_sinr(rsrp_dbm, rsrq_db, bandwidth_khz)
    if est_sinr is not None:
        rb = bandwidth_to_rb(bandwidth_khz)
        bw_label = f"{bandwidth_khz // 1000} MHz" if bandwidth_khz and bandwidth_khz > 0 else "10 MHz (nominal assumption)"
        return (
            est_sinr,
            "ESTIMATED",
            "3GPP_TS_36_214_RSRQ_LOAD_MODEL",
            f"Derived from RSRQ = N*RSRP/RSSI (N={rb} RBs, {bw_label}). Assumes nominal 50% cell traffic load (eta=0.5), flat Rayleigh channel. Estimated uncertainty is +/- 3.5 dB.",
            "CALIBRATED_ESTIMATE",
            get_sinr_qualitative_diagnostic(est_sinr),
            implied_rssi
        )

    # Fallback UNAVAILABLE
    return (
        None,
        "UNAVAILABLE",
        None,
        "Physical inputs insufficient or out of bounds for SINR estimation.",
        None,
        get_sinr_qualitative_diagnostic(None, rsrq_db),
        rssi_dbm
    )
