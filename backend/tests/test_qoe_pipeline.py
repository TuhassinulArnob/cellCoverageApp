import io
import pytest
from fastapi.testclient import TestClient
from backend.app.main import app

client = TestClient(app)


def test_load_survey_with_qoe():
    # Load the latest genuine survey preset containing active QoE tests
    response = client.post("/api/datasets/load-preset/bd_survey_qoe_latest")
    assert response.status_code == 200
    data = response.json()

    summary = data["summary"]
    assert "qoe_summary" in summary
    qoe_sum = summary["qoe_summary"]
    assert qoe_sum is not None
    assert qoe_sum["total_tests"] >= 1
    assert qoe_sum["completed_tests"] >= 1
    assert qoe_sum["has_speed_data"] is True
    assert qoe_sum["has_ping_data"] is True

    # Real field test values from Robi Axiata survey 20261020_081637:
    # DL: 1.20 Mbps, UL: 2.03 Mbps, Ping: 473 ms, Jitter: 312.30 ms, Loss: 0.0%
    assert qoe_sum["avg_download_mbps"] == 1.20
    assert qoe_sum["avg_upload_mbps"] == 2.03
    assert qoe_sum["avg_ping_ms"] == 473.0
    assert qoe_sum["avg_jitter_ms"] == 312.30
    assert qoe_sum["avg_packet_loss_pct"] == 0.0

    # Check qoe_tests in response
    assert "qoe_tests" in data
    tests = data["qoe_tests"]
    assert len(tests) >= 1
    t0 = tests[0]
    assert t0["id"] == 26
    assert t0["test_status"] == "COMPLETED"
    assert t0["download_mbps"] == 1.20
    assert t0["upload_mbps"] == 2.03
    assert t0["ping_latency_ms"] == 473.0
    assert t0["jitter_ms"] == 312.30
    assert t0["packet_loss_pct"] == 0.0
    assert t0["serving_pci"] == 177
    assert t0["latitude"] == pytest.approx(24.074985, rel=1e-5)
    assert t0["longitude"] == pytest.approx(88.8238916, rel=1e-5)
    assert t0["has_valid_gps"] is True
    assert t0["operator"] == "Robi Axiata (Robi)"


def test_get_qoe_tests_endpoint():
    client.post("/api/datasets/load-preset/bd_survey_qoe_latest")
    response = client.get("/api/datasets/current/qoe-tests")
    assert response.status_code == 200
    tests = response.json()
    assert len(tests) >= 1

    # Filter by RAT
    resp_rat = client.get("/api/datasets/current/qoe-tests?rat=4G")
    assert resp_rat.status_code == 200
    assert len(resp_rat.json()) >= 1

    # Filter by PCI
    resp_pci = client.get("/api/datasets/current/qoe-tests?pci=177")
    assert resp_pci.status_code == 200
    assert len(resp_pci.json()) >= 1

    # Filter by non-existent status
    resp_none = client.get("/api/datasets/current/qoe-tests?status=FAILED")
    assert resp_none.status_code == 200
    assert len(resp_none.json()) == 0


def test_extended_analysis_includes_qoe():
    client.post("/api/datasets/load-preset/bd_survey_qoe_latest")
    response = client.get("/api/datasets/current/extended-analysis")
    assert response.status_code == 200
    data = response.json()
    assert "qoe_tests" in data
    assert len(data["qoe_tests"]) >= 1


def test_upload_single_sheet_csv_with_speed_columns():
    csv_text = """Timestamp,Latitude,Longitude,Serving Cell ID,Serving Cell RSRP,Serving Cell RSRQ,Serving Cell CINR,Handover Trigger,Network Type,Operator,Download (Mbps),Upload (Mbps),Ping (ms),Jitter (ms),Packet Loss (%)
2026-10-20T10:00:00Z,23.8103,90.4125,101,-85,-10,18.0,0,4G LTE,Banglalink,45.5,15.2,32.0,4.5,0.0
2026-10-20T10:05:00Z,23.8120,90.4140,102,-95,-14,8.0,1,4G LTE,Banglalink,12.0,3.5,65.0,12.0,2.5
2026-10-20T10:10:00Z,23.8140,90.4160,102,-105,-18,1.0,0,4G LTE,Banglalink,,,,,
"""
    files = {"file": ("speed_test_survey.csv", io.BytesIO(csv_text.encode("utf-8")), "text/csv")}
    response = client.post("/api/datasets/upload", files=files)
    assert response.status_code == 200
    data = response.json()

    # 3 total telemetry rows
    assert data["summary"]["total_records"] == 3

    # 2 rows had speed test values
    qoe_tests = data["qoe_tests"]
    assert len(qoe_tests) == 2
    assert qoe_tests[0]["download_mbps"] == 45.5
    assert qoe_tests[0]["upload_mbps"] == 15.2
    assert qoe_tests[0]["ping_latency_ms"] == 32.0
    assert qoe_tests[0]["jitter_ms"] == 4.5
    assert qoe_tests[0]["packet_loss_pct"] == 0.0

    assert qoe_tests[1]["download_mbps"] == 12.0
    assert qoe_tests[1]["upload_mbps"] == 3.5
    assert qoe_tests[1]["ping_latency_ms"] == 65.0

    # Summary
    qsum = data["summary"]["qoe_summary"]
    assert qsum is not None
    assert qsum["total_tests"] == 2
    assert qsum["avg_download_mbps"] == round((45.5 + 12.0) / 2.0, 2)
    assert qsum["avg_upload_mbps"] == round((15.2 + 3.5) / 2.0, 2)
    assert qsum["avg_ping_ms"] == round((32.0 + 65.0) / 2.0, 2)


def test_missing_measurements_are_not_zero_filled():
    # If a test fails or is skipped, missing values must be null/None, never 0.0
    csv_multi_section = """# CellScope Extended Telemetry Format v3.0
Timestamp,Latitude,Longitude,Speed (km/h),Serving Cell ID,Serving Cell RSRP,Serving Cell RSRQ,Serving Cell CINR,Handover Trigger,TAC,EARFCN,RSSI (dBm),Network Type,Operator
2026-10-20T10:00:00Z,23.8103,90.4125,10.0,101,-85,-10,18.0,0,50001,1399,-75,LTE,Grameenphone

# Active QoE & Congestion Section
test_id,session_id,timestamp_iso,timestamp_epoch_ms,test_trigger,test_status,skipped_reason,scheduled_epoch_ms,execution_delay_ms,latitude,longitude,gps_accuracy_m,serving_rat,serving_pci,serving_channel,serving_rsrp_dbm,endpoint_url,download_throughput_mbps,upload_throughput_mbps,download_bytes_transferred,upload_bytes_transferred,download_duration_ms,upload_duration_ms,rtt_median_ms,rtt_mean_ms,rtt_p95_ms,jitter_ms,probe_loss_pct,probes_attempted,probes_successful,classification,data_provenance
1,1,2026-10-20T10:00:00Z,1792460000000,FULL_CYCLE,FAILED,Connection refused,1792460000000,0,23.8103,90.4125,5.0,4G LTE,101,1399,-85,https://test.com,,,,,,,,,,,100.0,4,0,SERVER_UNREACHABLE,MEASURED
"""
    files = {"file": ("failed_test_survey.csv", io.BytesIO(csv_multi_section.encode("utf-8")), "text/csv")}
    response = client.post("/api/datasets/upload", files=files)
    assert response.status_code == 200
    data = response.json()

    tests = data["qoe_tests"]
    assert len(tests) == 1
    t = tests[0]
    assert t["test_status"] == "FAILED"
    assert t["skipped_reason"] == "Connection refused"
    assert t["download_mbps"] is None  # MUST BE NONE, NOT 0.0!
    assert t["upload_mbps"] is None    # MUST BE NONE, NOT 0.0!
    assert t["ping_latency_ms"] is None  # MUST BE NONE, NOT 0.0!
    assert t["packet_loss_pct"] == 100.0


def test_load_fullroute_islampur_kushtia():
    # Load the comprehensive 68.8 km drive test dataset
    response = client.post("/api/datasets/load-preset/bd_fullroute_islampur_kushtia")
    assert response.status_code == 200
    data = response.json()

    summary = data["summary"]
    assert summary["total_records"] == 9315
    assert summary["records_with_gps"] == 9269
    assert summary["records_invalid_gps"] == 46
    assert summary["has_gps"] is True
    assert summary["unique_serving_cells"] > 20

    # QoE metrics verification
    qsum = summary["qoe_summary"]
    assert qsum is not None
    assert qsum["total_tests"] == 41
    assert qsum["completed_tests"] == 41
    assert qsum["has_speed_data"] is True
    assert qsum["has_ping_data"] is True
    assert qsum["median_download_mbps"] is not None
    assert qsum["median_upload_mbps"] is not None
    assert qsum["rtt_p90_ms"] is not None
    assert qsum["rtt_p95_ms"] is not None
    assert len(qsum["hourly_profiles"]) >= 1
    assert len(qsum["summary_insights"]) >= 1

    # Extended analysis with transitions
    resp_ext = client.get("/api/datasets/current/extended-analysis")
    assert resp_ext.status_code == 200
    ext_data = resp_ext.json()
    assert len(ext_data["transitions"]) == 246

    # Check transition attributes
    t0 = ext_data["transitions"][0]
    assert "event_type" in t0
    assert "confidence_label" in t0
    assert "classification_reason" in t0
    assert t0["to_pci"] is not None


def test_handover_transition_synthesizer():
    from backend.app.core.schema import DatasetSummary, MeasurementPoint
    from backend.app.services.analytics import compute_extended_analysis

    points = [
        MeasurementPoint(id=1, timestamp_raw="2026-10-20 10:00:00", serving_cell_id=101, earfcn=1399, network_gen="4G LTE", rsrp_dbm=-85.0, tac=5001),
        # Intra-frequency: same earfcn 1399
        MeasurementPoint(id=2, timestamp_raw="2026-10-20 10:00:01", serving_cell_id=102, earfcn=1399, network_gen="4G LTE", rsrp_dbm=-88.0, tac=5001),
        # Inter-frequency: earfcn 1399 -> 1650
        MeasurementPoint(id=3, timestamp_raw="2026-10-20 10:00:02", serving_cell_id=205, earfcn=1650, network_gen="4G LTE", rsrp_dbm=-92.0, tac=5001),
        # Inter-RAT: 4G LTE -> 5G NR
        MeasurementPoint(id=4, timestamp_raw="2026-10-20 10:00:03", serving_cell_id=301, earfcn=630000, network_gen="5G NR", rsrp_dbm=-95.0, tac=5001),
        # Missing channel: should be SERVING_CELL_CHANGE, NOT guessed as inter-frequency
        MeasurementPoint(id=5, timestamp_raw="2026-10-20 10:00:04", serving_cell_id=401, earfcn=None, network_gen="5G NR", rsrp_dbm=-98.0, tac=5001),
    ]

    dummy_summary = DatasetSummary(
        dataset_id="test",
        filename="test.csv",
        total_records=len(points),
        records_with_gps=0,
        records_invalid_gps=0,
        has_gps=False,
        unique_serving_cells=5,
        unique_neighbor_cells=0,
        handover_trigger_count=0,
        handover_trigger_rate_pct=0.0
    )

    result = compute_extended_analysis(dummy_summary, points)
    assert len(result.transitions) == 4

    # 1: Intra-frequency
    assert result.transitions[0].event_type == "INTRA_FREQ"
    assert result.transitions[0].confidence_label == "HIGH"
    assert "Intra-frequency" in result.transitions[0].classification_reason

    # 2: Inter-frequency
    assert result.transitions[1].event_type == "INTER_FREQ"
    assert result.transitions[1].confidence_label == "MEDIUM"
    assert "Inter-frequency" in result.transitions[1].classification_reason

    # 3: Inter-RAT
    assert result.transitions[2].event_type == "INTER_RAT"
    assert result.transitions[2].confidence_label == "CONFIRMED_RAT"
    assert "5G NR" in result.transitions[2].classification_reason

    # 4: Missing earfcn -> SERVING_CELL_CHANGE (not guessed as inter-freq)
    assert result.transitions[3].event_type == "SERVING_CELL_CHANGE"
    assert result.transitions[3].confidence_label == "EMPIRICAL"
