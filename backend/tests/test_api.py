import io
import pytest
from fastapi.testclient import TestClient
from backend.app.main import app

client = TestClient(app)


def test_health_check():
    response = client.get("/api/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"


def test_get_available_datasets():
    response = client.get("/api/datasets/available")
    assert response.status_code == 200
    presets = response.json()
    assert len(presets) >= 1
    # Check that bd_ds3_v1 is listed
    ds3 = next((p for p in presets if p["id"] == "bd_ds3_v1"), None)
    assert ds3 is not None
    assert ds3["has_gps"] is True


def test_load_preset_dataset():
    response = client.post("/api/datasets/load-preset/bd_ds3_v1")
    assert response.status_code == 200
    data = response.json()
    summary = data["summary"]
    assert summary["total_records"] == 640
    assert summary["records_with_gps"] == 640
    assert summary["has_gps"] is True
    assert summary["mean_rsrp_dbm"] is not None
    assert summary["bounding_box"] is not None


def test_filter_measurements():
    # Load dataset first
    client.post("/api/datasets/load-preset/bd_ds3_v1")

    # Fetch measurements
    response = client.get("/api/datasets/current/measurements?limit=10")
    assert response.status_code == 200
    points = response.json()
    assert len(points) == 10
    assert points[0]["has_valid_gps"] is True
    assert points[0]["rsrp_dbm"] is not None

    # Filter by quality
    resp_qual = client.get("/api/datasets/current/measurements?quality=Good")
    assert resp_qual.status_code == 200
    good_points = resp_qual.json()
    for p in good_points:
        assert p["rsrp_class"] == "Good"


def test_upload_custom_csv():
    csv_content = """Timestamp,Latitude,Longitude,Serving Cell ID,Serving Cell RSRP,Serving Cell RSRQ,Serving Cell CINR,Handover Trigger
12:00:01,23.8103,90.4125,101,65,12,14.5,0
12:00:02,23.8105,90.4128,101,62,11,12.0,1
"""
    files = {"file": ("test_drive.csv", io.BytesIO(csv_content.encode("utf-8")), "text/csv")}
    response = client.post("/api/datasets/upload", files=files)
    assert response.status_code == 200
    data = response.json()
    summary = data["summary"]
    assert summary["total_records"] == 2
    assert summary["records_with_gps"] == 2
    assert summary["handover_trigger_count"] == 1
    # 65 - 140 = -75 dBm
    assert summary["mean_rsrp_dbm"] == -76.5


def test_get_extended_analysis():
    client.post("/api/datasets/load-preset/bd_ds3_v1")
    response = client.get("/api/datasets/current/extended-analysis")
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "per_cell_stats" in data
    assert "data_quality" in data
    assert data["data_quality"]["provenance_source"] is not None
    assert data["summary"]["total_distance_km"] is not None
    assert data["summary"]["total_distance_km"] > 0


def test_export_csv_canonical():
    client.post("/api/datasets/load-preset/bd_ds3_v1")
    response = client.get("/api/datasets/current/export/csv?extended=false")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/csv")
    lines = response.text.strip().split("\r\n")
    if len(lines) == 1:
        lines = response.text.strip().split("\n")
    header = lines[0]
    expected_headers = [
        "Timestamp", "Latitude", "Longitude", "Speed (km/h)",
        "Serving Cell ID", "Serving Cell RSRP", "Serving Cell RSRQ",
        "Serving Cell CINR", "Handover Trigger", "TAC", "EARFCN",
        "RSSI (dBm)", "Network Type", "Operator"
    ]
    assert header == ",".join(expected_headers)
    assert len(lines) > 100


def test_export_csv_extended():
    client.post("/api/datasets/load-preset/bd_ds3_v1")
    response = client.get("/api/datasets/current/export/csv?extended=true")
    assert response.status_code == 200
    assert "Heading (deg)" in response.text



def test_upload_and_parse_extended_telemetry():
    csv_content = """Timestamp,Latitude,Longitude,Speed (km/h),Serving Cell ID,Serving Cell RSRP,Serving Cell RSRQ,Serving Cell CINR,Handover Trigger,TAC,EARFCN,RSSI (dBm),Network Type,Operator,Altitude (m),Accuracy (m),Bearing (deg),ECI,CQI,Timing Advance,GPS Valid
2026-10-20T05:06:57.100+06:00,23.810331,90.412518,12.50,4,-85,-8,14.5,0,50030,1399,-80,LTE,Robi,15.2,3.5,120.0,268435457,12,1,1
"""
    files = {"file": ("extended_test.csv", io.BytesIO(csv_content.encode("utf-8")), "text/csv")}
    response = client.post("/api/datasets/upload", files=files)
    assert response.status_code == 200
    data = response.json()
    assert data["summary"]["total_records"] == 1

    resp_m = client.get("/api/datasets/current/measurements")
    assert resp_m.status_code == 200
    points = resp_m.json()
    assert len(points) == 1
    pt = points[0]
    assert pt["timing_advance"] == 1
    assert pt["cqi"] == 12
    assert pt["eci"] == 268435457
    assert pt["altitude_m"] == 15.2
    assert pt["earfcn"] == 1399
    assert pt["rsrp_dbm"] == -85.0
