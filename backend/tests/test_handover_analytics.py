import pytest
import pandas as pd
from backend.app.services.parser import extract_cell_transition_records
from backend.app.services.analytics import process_and_summarize_dataset, compute_extended_analysis


def test_extract_cell_transition_records_tac_crossing_does_not_promote():
    # TAC crossing alone is NOT a confirmed handover - preserves actual classification
    sample_csv = """# Cell Transitions (Mobility & Handover) Section
Timestamp,Latitude,Longitude,Event Type,From RAT,To RAT,From PCI,To PCI,From Channel,To Channel,From Cell ID,To Cell ID,From Signal (dBm),To Signal (dBm),From TAC/LAC,To TAC/LAC,Confidence Score,Details
2026-10-20T08:17:36.809+06:00,24.0751,88.8238,INTER_FREQ,4G,4G,177,228,1399,1800,179203851,10266113,-123,-115,50034,4109,0.85,Inter-frequency transition on 4G: Ch 1399 -> 1800 (PCI: 177 -> 228)
"""
    records = extract_cell_transition_records(sample_csv)
    assert len(records) == 1
    rec = records[0]
    assert rec["event_type"] == "INTER_FREQ"
    assert rec["confidence_label"] == "MEDIUM"
    assert rec["from_tac"] == 50034
    assert rec["to_tac"] == 4109
    assert rec["is_handover_trigger"] is False


def test_extract_cell_transition_records_explicit_confirmed():
    sample_csv = """# Cell Transitions (Mobility & Handover) Section
Timestamp,Latitude,Longitude,Event Type,From RAT,To RAT,From PCI,To PCI,From Channel,To Channel,From Cell ID,To Cell ID,From Signal (dBm),To Signal (dBm),From TAC/LAC,To TAC/LAC,Confidence Score,Details
2026-10-20T08:17:36.809+06:00,24.0751,88.8238,CONFIRMED_HANDOVER,4G,4G,177,228,1800,1800,179203851,10266113,-90,-85,4109,4109,1.0,Confirmed serving-cell handover with Handover Trigger asserted
"""
    records = extract_cell_transition_records(sample_csv)
    assert len(records) == 1
    rec = records[0]
    assert rec["event_type"] == "CONFIRMED_HANDOVER"
    assert rec["confidence_label"] == "CONFIRMED"
    assert rec["is_handover_trigger"] is True


def test_process_telemetry_df_explicit_handover_trigger():
    df = pd.DataFrame([
        {
            "timestamp": "2026-10-20T08:16:00Z",
            "latitude": 24.075,
            "longitude": 88.823,
            "pci": 177,
            "tac": 4109,
            "earfcn": 1800,
            "rsrp": -85,
            "handover_trigger": 0,
            "speed": 30.0
        },
        {
            "timestamp": "2026-10-20T08:16:02Z",
            "latitude": 24.076,
            "longitude": 88.824,
            "pci": 228,
            "tac": 4109,
            "earfcn": 1800,
            "rsrp": -80,
            "handover_trigger": 1,
            "speed": 30.0
        }
    ])
    mapping = {
        "timestamp": "timestamp",
        "latitude": "latitude",
        "longitude": "longitude",
        "serving_cell_id": "pci",
        "tac": "tac",
        "earfcn": "earfcn",
        "rsrp": "rsrp",
        "handover_trigger": "handover_trigger",
        "speed": "speed"
    }
    summary, points, qoe = process_and_summarize_dataset(df, mapping, "test.csv", "sess_1")
    analysis = compute_extended_analysis(summary, points)
    assert len(analysis.transitions) == 1
    t = analysis.transitions[0]
    assert t.event_type == "CONFIRMED_HANDOVER"
    assert t.confidence_label == "CONFIRMED"
    assert t.from_pci == 177
    assert t.to_pci == 228
    assert t.is_handover_trigger is True
    assert "Confirmed intra-frequency handover" in t.classification_reason


def test_process_telemetry_df_unconfirmed_serving_cell_change():
    # Without handover trigger or TAC crossing
    df = pd.DataFrame([
        {
            "timestamp": "2026-10-20T08:16:00Z",
            "latitude": 24.075,
            "longitude": 88.823,
            "pci": 177,
            "tac": 4109,
            "earfcn": 1800,
            "rsrp": -85,
            "handover_trigger": 0,
            "speed": 30.0
        },
        {
            "timestamp": "2026-10-20T08:16:02Z",
            "latitude": 24.076,
            "longitude": 88.824,
            "pci": 228,
            "tac": 4109,
            "earfcn": 1800,
            "rsrp": -80,
            "handover_trigger": 0,
            "speed": 30.0
        }
    ])
    mapping = {
        "timestamp": "timestamp",
        "latitude": "latitude",
        "longitude": "longitude",
        "serving_cell_id": "pci",
        "tac": "tac",
        "earfcn": "earfcn",
        "rsrp": "rsrp",
        "handover_trigger": "handover_trigger",
        "speed": "speed"
    }
    summary, points, qoe = process_and_summarize_dataset(df, mapping, "test.csv", "sess_2")
    analysis = compute_extended_analysis(summary, points)
    assert len(analysis.transitions) == 1
    t = analysis.transitions[0]
    assert t.event_type == "INTRA_FREQ"
    assert t.confidence_label == "HIGH"
    assert t.is_handover_trigger is False
