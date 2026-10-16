import pytest
from backend.app.services.parser import detect_column_mapping, clean_numeric_value
from backend.app.services.validator import validate_coordinates, validate_rf_ranges


def test_column_mapping_with_whitespace_and_typos():
    raw_columns = [
        "Timestamp        ",
        "Latitude",
        "Longitude",
        "Speed (km/h)",
        "Serving Cell ID",
        "Serving Cell RSRP    ",
        "Serving Cell RSRPQ",  # typo in dataset 3 ver 2
        "Serving Cell CINR-1",
        "Neighbor Cell ID",
        "Handover Trigger"
    ]
    mapping, unmapped = detect_column_mapping(raw_columns)

    assert mapping["timestamp"] == "Timestamp        "
    assert mapping["latitude"] == "Latitude"
    assert mapping["longitude"] == "Longitude"
    assert mapping["speed"] == "Speed (km/h)"
    assert mapping["serving_cell_id"] == "Serving Cell ID"
    assert mapping["rsrp"] == "Serving Cell RSRP    "
    assert mapping["rsrq"] == "Serving Cell RSRPQ"
    assert mapping["cinr"] == "Serving Cell CINR-1"
    assert mapping["neighbor_cell_id"] == "Neighbor Cell ID"
    assert mapping["handover_trigger"] == "Handover Trigger"
    assert len(unmapped) == 0


def test_clean_numeric_value():
    assert clean_numeric_value("42") == 42.0
    assert clean_numeric_value(42.5) == 42.5
    assert clean_numeric_value("-") is None
    assert clean_numeric_value("--") is None
    assert clean_numeric_value("NaN") is None
    assert clean_numeric_value(None) is None


def test_validate_coordinates():
    # Valid Dhaka location
    is_valid, err = validate_coordinates(23.8103, 90.4125)
    assert is_valid is True
    assert err is None

    # Longitude outlier (real bug in Dataset 2: 0.116667)
    is_valid, err = validate_coordinates(23.7954, 0.116667)
    assert is_valid is False
    assert "REGIONAL_OUTLIER_LONGITUDE" in err

    # Out of global bounds
    is_valid, err = validate_coordinates(95.0, 90.0)
    assert is_valid is False
    assert "LAT_OUT_OF_GLOBAL_BOUNDS" in err

    # Null Island
    is_valid, err = validate_coordinates(0.0, 0.0)
    assert is_valid is False
    assert err == "NULL_ISLAND_COORDINATES"

    # Missing
    is_valid, err = validate_coordinates(None, 90.0)
    assert is_valid is False
    assert err == "MISSING_COORDINATES"
