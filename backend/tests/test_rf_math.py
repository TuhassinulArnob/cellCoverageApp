import pytest
import numpy as np
from backend.app.core.rf_math import (
    rsrp_ie_to_dbm,
    rsrq_ie_to_db,
    classify_rsrp,
    classify_rsrq,
    classify_cinr,
    get_metric_color,
)


def test_rsrp_ie_to_dbm_standard_values():
    # 3GPP TS 36.133: RSRP_dBm = IE - 140
    assert rsrp_ie_to_dbm(0) == -140.0
    assert rsrp_ie_to_dbm(61) == -79.0
    assert rsrp_ie_to_dbm(97) == -43.0
    assert rsrp_ie_to_dbm(50) == -90.0


def test_rsrp_ie_to_dbm_already_physical():
    # Negative values already in dBm
    assert rsrp_ie_to_dbm(-85.5) == -85.5
    assert rsrp_ie_to_dbm(-105.0) == -105.0


def test_rsrp_ie_to_dbm_null_handling():
    assert rsrp_ie_to_dbm(None) is None
    assert rsrp_ie_to_dbm(float("nan")) is None


def test_rsrq_ie_to_db_standard_values():
    # 3GPP TS 36.133: RSRQ_dB = (IE * 0.5) - 19.5
    assert rsrq_ie_to_db(0) == -19.5
    assert rsrq_ie_to_db(11) == -14.0
    assert rsrq_ie_to_db(34) == -2.5
    assert rsrq_ie_to_db(20) == -9.5


def test_rsrq_ie_to_db_already_physical():
    assert rsrq_ie_to_db(-12.4) == -12.4
    assert rsrq_ie_to_db(None) is None


def test_rf_classifications():
    # RSRP classification
    assert classify_rsrp(-75.0) == "Excellent"
    assert classify_rsrp(-85.0) == "Good"
    assert classify_rsrp(-95.0) == "Fair"
    assert classify_rsrp(-108.0) == "Poor"
    assert classify_rsrp(None) == "Unknown"

    # RSRQ classification
    assert classify_rsrq(-8.0) == "Excellent"
    assert classify_rsrq(-12.0) == "Good"
    assert classify_rsrq(-15.5) == "Fair"
    assert classify_rsrq(-18.5) == "Poor"

    # CINR classification
    assert classify_cinr(22.0) == "Excellent"
    assert classify_cinr(15.0) == "Good"
    assert classify_cinr(5.0) == "Fair"
    assert classify_cinr(-5.0) == "Poor"


def test_metric_colors():
    assert get_metric_color("rsrp", -75.0) == "#10B981"  # green
    assert get_metric_color("rsrp", -85.0) == "#0EA5E9"  # blue
    assert get_metric_color("rsrp", -95.0) == "#F59E0B"  # yellow/amber
    assert get_metric_color("rsrp", -110.0) == "#EF4444" # red
    assert get_metric_color("rsrp", None) == "#6B7280"    # gray
