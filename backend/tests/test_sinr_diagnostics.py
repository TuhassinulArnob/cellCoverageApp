import pytest
import pandas as pd
from backend.app.core.rf_math import (
    bandwidth_to_rb,
    calculate_implied_rssi,
    estimate_lte_sinr,
    resolve_sinr_provenance,
    get_sinr_qualitative_diagnostic,
)
from backend.app.services.analytics import process_and_summarize_dataset
from backend.app.services.parser import detect_column_mapping


def test_bandwidth_to_rb():
    assert bandwidth_to_rb(1400) == 6
    assert bandwidth_to_rb(3000) == 15
    assert bandwidth_to_rb(5000) == 25
    assert bandwidth_to_rb(10000) == 50
    assert bandwidth_to_rb(15000) == 75
    assert bandwidth_to_rb(20000) == 100
    assert bandwidth_to_rb(None) == 50
    assert bandwidth_to_rb(0) == 50
    # Vendor HAL integer MHz mapping
    assert bandwidth_to_rb(1) == 6
    assert bandwidth_to_rb(3) == 15
    assert bandwidth_to_rb(5) == 25
    assert bandwidth_to_rb(10) == 50
    assert bandwidth_to_rb(15) == 75
    assert bandwidth_to_rb(20) == 100


def test_calculate_implied_rssi():
    # RSRP = -90 dBm, RSRQ = -10 dB, BW = 10 MHz (N = 50 RBs)
    # RSSI = -90 + 10 * log10(50) - (-10) = -90 + 16.99 + 10 = -63.01 dBm
    rssi = calculate_implied_rssi(rsrp_dbm=-90.0, rsrq_db=-10.0, bandwidth_khz=10000)
    assert round(rssi, 1) == -63.0


def test_estimate_lte_sinr_valid():
    sinr, implied_rssi = estimate_lte_sinr(rsrp_dbm=-95.0, rsrq_db=-11.0, bandwidth_khz=10000)
    assert sinr is not None
    assert -20.0 <= sinr <= 30.0
    assert implied_rssi is not None
    assert round(implied_rssi, 1) == -67.0


def test_estimate_lte_sinr_invalid_bounds():
    # RSRP too weak (-150 dBm)
    sinr, rssi = estimate_lte_sinr(rsrp_dbm=-150.0, rsrq_db=-10.0)
    assert sinr is None
    assert rssi is None

    # Missing inputs
    sinr, rssi = estimate_lte_sinr(rsrp_dbm=None, rsrq_db=-10.0)
    assert sinr is None


def test_resolve_sinr_provenance_measured():
    res = resolve_sinr_provenance(
        raw_sinr=15.4,
        rsrp_dbm=-85.0,
        rsrq_db=-8.0,
        rssi_dbm=-62.0,
        rat="LTE"
    )
    sinr, source, method, assumptions, conf, qual, implied = res
    assert source == "MEASURED"
    assert sinr == 15.4
    assert method == "ANDROID_RIL_RSSNR_MEASURED"
    assert conf == "HIGH"
    assert implied == -62.0


def test_resolve_sinr_provenance_sentinels_falls_back_to_estimated():
    # Sentinel 99
    res = resolve_sinr_provenance(
        raw_sinr=99,
        rsrp_dbm=-90.0,
        rsrq_db=-10.0,
        bandwidth_khz=20000,
        rat="LTE"
    )
    sinr, source, method, assumptions, conf, qual, implied = res
    assert source == "ESTIMATED"
    assert sinr is not None
    assert method == "3GPP_TS_36_214_RSRQ_LOAD_MODEL"
    assert conf == "CALIBRATED_ESTIMATE"
    assert implied is not None

    # Sentinel 0x7FFFFFFF
    res2 = resolve_sinr_provenance(
        raw_sinr=0x7FFFFFFF,
        rsrp_dbm=-92.0,
        rsrq_db=-12.0,
        rat="LTE"
    )
    assert res2[1] == "ESTIMATED"


def test_resolve_sinr_provenance_nr_measured():
    res = resolve_sinr_provenance(
        raw_sinr=21.0,
        rsrp_dbm=-80.0,
        rsrq_db=-9.0,
        rat="5G_NR"
    )
    assert res[1] == "MEASURED"
    assert res[2] == "NR_SS_SINR_MEASURED"


def test_resolve_sinr_provenance_nr_rejection():
    # 5G NR without measured SINR should NEVER estimate using LTE equation
    res = resolve_sinr_provenance(
        raw_sinr=None,
        rsrp_dbm=-90.0,
        rsrq_db=-11.0,
        rat="5G_NR"
    )
    sinr, source, method, assumptions, conf, qual, implied = res
    assert source == "UNAVAILABLE"
    assert sinr is None
    assert method == "NR_MODEL_UNSUPPORTED"
    assert "LTE carrier RSSI equations" in assumptions


def test_analytics_process_dataset_sinr_provenance():
    # Create sample DataFrame without explicit SINR/CINR column
    df = pd.DataFrame({
        "Timestamp": ["2026-10-20T10:00:00.000Z", "2026-10-20T10:00:01.000Z"],
        "Latitude": [23.75, 23.751],
        "Longitude": [90.38, 90.381],
        "Speed (km/h)": [25.0, 30.0],
        "Serving Cell ID": [101, 101],
        "Serving Cell RSRP": [-90, -92],
        "Serving Cell RSRQ": [-10, -11],
        "Handover Trigger": [0, 0],
        "Network Type": ["LTE", "LTE"]
    })

    mapping, _ = detect_column_mapping(list(df.columns))
    summary, points, qoe = process_and_summarize_dataset(df, mapping, "test_sinr.csv", "test_id")
    assert summary.total_records == 2
    assert summary.estimated_sinr_count == 2
    assert summary.measured_sinr_count == 0
    assert summary.sinr_provenance_summary["ESTIMATED"] == 2

    # Verify points populated with estimation details
    for p in points:
        assert p.sinr_source == "ESTIMATED"
        assert p.cinr_db is not None
        assert p.estimation_method == "3GPP_TS_36_214_RSRQ_LOAD_MODEL"
        assert p.implied_rssi_dbm is not None
        assert p.sinr_confidence == "CALIBRATED_ESTIMATE"


def test_resolve_sinr_provenance_zero_uninitialized_falls_back():
    # If device reports 0.0 and no non-zero value has been seen, 0.0 is treated as uninitialized sentinel
    res = resolve_sinr_provenance(
        raw_sinr=0.0,
        rsrp_dbm=-90.0,
        rsrq_db=-10.0,
        has_seen_nonzero_sinr=False
    )
    sinr, source, method, assumptions, conf, qual, implied = res
    assert source == "ESTIMATED"
    assert method == "3GPP_TS_36_214_RSRQ_LOAD_MODEL"
    assert sinr is not None
    assert sinr != 0.0


def test_resolve_sinr_provenance_zero_when_nonzero_seen():
    # If modem has proven it can report non-zero values, a genuine 0.0 dB is accepted as MEASURED
    res = resolve_sinr_provenance(
        raw_sinr=0.0,
        rsrp_dbm=-90.0,
        rsrq_db=-10.0,
        has_seen_nonzero_sinr=True
    )
    sinr, source, method, assumptions, conf, qual, implied = res
    assert source == "MEASURED"
    assert sinr == 0.0
    assert method == "ANDROID_RIL_RSSNR_MEASURED"


def test_resolve_sinr_provenance_zero_uninitialized_without_rf_unavailable():
    # 0.0 uninitialized without sufficient RF params -> UNAVAILABLE (never 0.0 measured)
    res = resolve_sinr_provenance(
        raw_sinr=0.0,
        rsrp_dbm=None,
        rsrq_db=None,
        has_seen_nonzero_sinr=False
    )
    sinr, source, method, assumptions, conf, qual, implied = res
    assert source == "UNAVAILABLE"
    assert sinr is None

def test_sinr_estimation_rsrq_matrix():
    # RSRQ = -11 dB -> 3.3 dB (Screenshot sample A, Nominal 50% load model)
    sinr_11, _ = estimate_lte_sinr(rsrp_dbm=-101.0, rsrq_db=-11.0, bandwidth_khz=10000)
    assert sinr_11 == 3.3

    # RSRQ = -10 dB -> 6.0 dB (Screenshot sample B, Nominal 50% load model)
    sinr_10, _ = estimate_lte_sinr(rsrp_dbm=-103.0, rsrq_db=-10.0, bandwidth_khz=10000)
    assert sinr_10 == 6.0

    # User screenshot scenario: RSRP = -97 dBm, RSRQ = -8 dB, Modem RSSI = -89 dBm, Unknown BW
    # Evaluates under 3GPP Bounded-Load Model: eta_max = 43.1%, expected eta = 21.5% -> 7.5 dB
    sinr_8, implied_8 = estimate_lte_sinr(rsrp_dbm=-97.0, rsrq_db=-8.0, bandwidth_khz=None)
    assert sinr_8 == 7.5
    assert implied_8 is not None

    # RSRQ = -7 dB -> 9.0 dB under Bounded-Load Model
    sinr_7, _ = estimate_lte_sinr(rsrp_dbm=-90.0, rsrq_db=-7.0, bandwidth_khz=10000)
    assert sinr_7 == 9.0

    # RSRQ = -3 dB -> 3GPP TS 36.133 Table 9.1.7-1 Index 34 ceiling saturation -> None, NEVER 30.0!
    sinr_3, _ = estimate_lte_sinr(rsrp_dbm=-80.0, rsrq_db=-3.0, bandwidth_khz=10000)
    assert sinr_3 is None


def test_bandwidth_validation_and_sentinel_rejection():
    # Missing / None
    assert bandwidth_to_rb(None) == 50
    # 0 or negative
    assert bandwidth_to_rb(0) == 50
    assert bandwidth_to_rb(-1000) == 50
    # Integer.MAX_VALUE / CellInfo.UNAVAILABLE sentinel (0x7FFFFFFF = 2147483647)
    assert bandwidth_to_rb(2147483647) == 50
    assert bandwidth_to_rb(0x7FFFFFFF) == 50
    # Implausible bandwidth > 100 MHz
    assert bandwidth_to_rb(500000) == 50
    # Valid bandwidths
    assert bandwidth_to_rb(1400) == 6
    assert bandwidth_to_rb(3000) == 15
    assert bandwidth_to_rb(5000) == 25
    assert bandwidth_to_rb(10000) == 50
    assert bandwidth_to_rb(15000) == 75
    assert bandwidth_to_rb(20000) == 100

    # Bandwidth independence for estimated SINR:
    # Channel bandwidth N cancels out of the SINR equation; it only affects implied RSSI.
    sinr_bw10, _ = estimate_lte_sinr(rsrp_dbm=-97.0, rsrq_db=-8.0, bandwidth_khz=10000)
    sinr_bw20, _ = estimate_lte_sinr(rsrp_dbm=-97.0, rsrq_db=-8.0, bandwidth_khz=20000)
    sinr_bwnone, _ = estimate_lte_sinr(rsrp_dbm=-97.0, rsrq_db=-8.0, bandwidth_khz=None)
    assert sinr_bw10 == 7.5
    assert sinr_bw20 == 7.5
    assert sinr_bwnone == 7.5


def test_resolve_provenance_clean_conditions_never_30db():
    # RSRQ = -7 dB with no modem SINR: evaluates under Bounded-Load Model to 9.0 dB (never 30.0 dB)
    res_7 = resolve_sinr_provenance(
        raw_sinr=None,
        rsrp_dbm=-90.0,
        rsrq_db=-7.0,
        bandwidth_khz=2147483647,
        rat='LTE'
    )
    sinr7, source7, method7, assumptions7, conf7, qual7, implied7 = res_7
    assert source7 == 'ESTIMATED'
    assert sinr7 == 9.0
    assert method7 == "3GPP_TS_36_214_BOUNDED_LOAD_MODEL"
    assert conf7 == "BOUNDED_LOAD_ESTIMATE"
    assert "bounds traffic load" in assumptions7
    assert implied7 is not None

    # RSRQ = -3 dB: at 3GPP saturation ceiling, mathematical inversion is indeterminate
    res_3 = resolve_sinr_provenance(
        raw_sinr=None,
        rsrp_dbm=-85.0,
        rsrq_db=-3.0,
        bandwidth_khz=10000,
        rat='LTE'
    )
    sinr3, source3, method3, assumptions3, conf3, qual3, implied3 = res_3
    assert source3 == 'UNAVAILABLE'
    assert sinr3 is None
    assert 'saturation ceiling' in qual3
    assert 'theoretical ceiling' in assumptions3


def test_suspect_clamp_audit_valid_vs_suspicious_minus44():
    def is_suspect_clamp(rsrp, rsrq, rssi=None, rat='LTE'):
        if '2G' in rat or 'GSM' in rat:
            return False
        return rsrp == -44.0 and rsrq == -3.0 and (rssi is None or rssi <= -100.0)

    assert is_suspect_clamp(-44.0, -3.0, None) is True
    assert is_suspect_clamp(-44.0, -3.0, -105.0) is True
    assert is_suspect_clamp(-44.0, -8.0, -25.0) is False
    assert is_suspect_clamp(-44.0, -10.0, -30.0) is False
    assert is_suspect_clamp(-44.0, -3.0, -105.0, rat='2G') is False
