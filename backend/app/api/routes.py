import os
import glob
from pathlib import Path
from typing import Dict, List, Optional
from fastapi import APIRouter, UploadFile, File, HTTPException, Query
from pydantic import BaseModel

from backend.app.core.config import DATA_RAW_DIR, BASE_DIR
from backend.app.core.schema import (
    DatasetSummary,
    MeasurementPoint,
    IngestResponse,
    AvailableDatasetInfo,
    ExtendedDatasetAnalysis,
    QoeTestRecord,
)
from backend.app.services.parser import parse_csv_to_dataframe, parse_csv_with_raw_text
from backend.app.services.analytics import process_and_summarize_dataset

router = APIRouter(prefix="/api", tags=["Drive-Test Analytics"])

# In-memory store for loaded sessions
_DATASET_STORE: Dict[str, Dict] = {}
_CURRENT_DATASET_ID: Optional[str] = None


def resolve_preset_path(rel_path: str) -> Optional[Path]:
    p1 = DATA_RAW_DIR / Path(rel_path)
    if p1.exists():
        return p1
    p2 = BASE_DIR / Path(rel_path)
    if p2.exists():
        return p2
    return None


PRESET_CATALOG = [
    {
        "id": "bd_fullroute_islampur_kushtia",
        "title": "Full Highway Drive Test: Islampur to Kushtia (Robi 4G LTE)",
        "category": "Field Dataset",
        "rel_path": "docs/fullroute_islampurtokushtia.csv",
        "rows": 9315,
        "has_gps": True,
        "description": "Complete 68.8 km inter-district highway drive test from Islampur to Kushtia with 9,315 telemetry points, 41 active QoE speed/latency tests, and 246 mobile cell transitions on Robi Axiata."
    },
    {
        "id": "bd_survey_qoe_latest",
        "title": "Stationary Field Survey with Active QoE Tests (Islampur)",
        "category": "Field Dataset",
        "rel_path": "docs/cellscope_extended_Survey_20261020_081637_20261020_081637.csv",
        "rows": 98,
        "has_gps": True,
        "description": "Stationary field survey snippet on Robi Axiata at Islampur with bounded active download & upload throughput, RTT latency, RFC 3550 jitter, and probe loss tests."
    },
    {
        "id": "bd_ds3_v1",
        "title": "Dhaka Urban LTE Route 3 (ver 1) - Complete GPS",
        "category": "Processed Dataset",
        "rel_path": "Handover Dataset/Handover Dataset (ver 2)/Processed Dataset/Dataset_3_ver_1.csv",
        "rows": 640,
        "has_gps": True,
        "description": "Recommended benchmark: Clean urban drive test in Dhaka, Bangladesh with 100% valid GPS coordinates, speed, RSRP, RSRQ, CINR, and handover triggers."
    },
    {
        "id": "bd_ds2_v1",
        "title": "Dhaka LTE Route 2 (ver 1) - With Coordinate Outliers",
        "category": "Processed Dataset",
        "rel_path": "Handover Dataset/Handover Dataset (ver 2)/Processed Dataset/Dataset_2_ver_1.csv",
        "rows": 592,
        "has_gps": True,
        "description": "Commercial drive test containing GPS antenna glitch (longitude 0.116667) to test real-world outlier detection and filtering."
    },
    {
        "id": "bd_ds2_parent_f1",
        "title": "Dhaka LTE Route 2 Raw Parent Log (File 1)",
        "category": "Parent Dataset",
        "rel_path": "Handover Dataset/Handover Dataset (ver 2)/Parent Dataset/Dataset_2/File 1.csv",
        "rows": 723,
        "has_gps": True,
        "description": "Raw extracted XCAL-M parent log containing 92 unacquired GPS fixes (NaN coordinates) and whitespace column names."
    },
    {
        "id": "bd_ds1_v1",
        "title": "Dhaka LTE Route 1 (Non-GPS Radio Metrics)",
        "category": "Processed Dataset",
        "rel_path": "Handover Dataset/Handover Dataset (ver 2)/Processed Dataset/Dataset_1_ver_1.csv",
        "rows": 922,
        "has_gps": False,
        "description": "Handover and RF measurement log without GPS coordinates to test tabular KPI analytics when coordinates are unavailable."
    },
    {
        "id": "bd_iu_kushtia",
        "title": "Suburban Highway Drive Test (Kushtia Sector)",
        "category": "Field Dataset",
        "rel_path": "docs/IUtoKushtia.csv",
        "rows": 2131,
        "has_gps": True,
        "description": "Real suburban highway drive test along Kushtia highway corridor (2131 samples, 37 min duration, 23.4 km route)."
    },
    {
        "id": "bd_kushtia_veramara",
        "title": "Kushtia to Bheramara / Veramara Drive Test",
        "category": "Field Dataset",
        "rel_path": "docs/kushtia to veramara .csv",
        "rows": 2023,
        "has_gps": True,
        "description": "Real inter-district highway drive test between Kushtia and Bheramara (2023 samples, 100% GPS, 35 min duration, 21.6 km route)."
    }
]


@router.get("/health")
def health_check():
    return {
        "status": "healthy",
        "service": "CellScope LTE Drive-Test Analytics Engine",
        "version": "1.0.0-mvp",
    }


@router.get("/datasets/available", response_model=List[AvailableDatasetInfo])
def get_available_datasets():
    """Returns available pre-indexed drive-test datasets from data/raw/ and docs/."""
    available = []
    for item in PRESET_CATALOG:
        full_path = resolve_preset_path(item["rel_path"])
        if full_path and full_path.exists():
            available.append(AvailableDatasetInfo(**item))
    return available


@router.post("/datasets/load-preset/{preset_id}", response_model=IngestResponse)
def load_preset_dataset(preset_id: str):
    """Loads a pre-indexed dataset from the local repository."""
    preset = next((p for p in PRESET_CATALOG if p["id"] == preset_id), None)
    if not preset:
        raise HTTPException(status_code=404, detail=f"Preset {preset_id} not found.")

    file_path = resolve_preset_path(preset["rel_path"])
    if not file_path or not file_path.exists():
        raise HTTPException(status_code=404, detail=f"File not found at {preset['rel_path']}")

    try:
        df, mapping, unmapped, raw_text = parse_csv_with_raw_text(file_path=str(file_path))
        summary, points, qoe_tests = process_and_summarize_dataset(
            df=df,
            mapping=mapping,
            filename=preset["title"],
            dataset_id=preset_id,
            raw_text=raw_text
        )

        _DATASET_STORE[preset_id] = {
            "summary": summary,
            "points": points,
            "qoe_tests": qoe_tests,
            "raw_filename": preset["title"],
            "raw_text": raw_text,
        }
        global _CURRENT_DATASET_ID
        _CURRENT_DATASET_ID = preset_id

        return IngestResponse(
            dataset_id=preset_id,
            summary=summary,
            sample_measurements=points[:100],
            qoe_tests=qoe_tests
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error loading preset: {str(e)}")


@router.post("/datasets/upload", response_model=IngestResponse)
async def upload_csv_file(file: UploadFile = File(...)):
    """Upload and process an arbitrary drive-test CSV file."""
    if not file.filename.lower().endswith(".csv"):
        raise HTTPException(status_code=400, detail="Only CSV files are supported.")

    try:
        content = await file.read()
        df, mapping, unmapped, raw_text = parse_csv_with_raw_text(content_bytes=content)
        dataset_id = f"upload_{file.filename}_{len(df)}"
        summary, points, qoe_tests = process_and_summarize_dataset(
            df=df,
            mapping=mapping,
            filename=file.filename,
            dataset_id=dataset_id,
            raw_text=raw_text
        )

        _DATASET_STORE[dataset_id] = {
            "summary": summary,
            "points": points,
            "qoe_tests": qoe_tests,
            "raw_filename": file.filename,
            "raw_text": raw_text,
        }
        global _CURRENT_DATASET_ID
        _CURRENT_DATASET_ID = dataset_id

        return IngestResponse(
            dataset_id=dataset_id,
            summary=summary,
            sample_measurements=points[:100],
            qoe_tests=qoe_tests
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to process CSV: {str(e)}")


@router.get("/datasets/current/summary", response_model=DatasetSummary)
def get_current_summary():
    """Gets the active dataset summary."""
    if not _CURRENT_DATASET_ID or _CURRENT_DATASET_ID not in _DATASET_STORE:
        # If none loaded, automatically load default benchmark
        default_id = "bd_fullroute_islampur_kushtia"
        load_preset_dataset(default_id)

    return _DATASET_STORE[_CURRENT_DATASET_ID]["summary"]


@router.get("/datasets/current/measurements", response_model=List[MeasurementPoint])
def get_current_measurements(
    cell_id: Optional[int] = Query(None, description="Filter by serving cell ID"),
    quality: Optional[str] = Query(None, description="Filter by RSRP quality (Excellent, Good, Fair, Poor)"),
    gps_only: bool = Query(True, description="Only return points with valid GPS"),
    limit: Optional[int] = Query(None, description="Maximum number of points to return"),
):
    """Gets measurement points for the active dataset with optional filters."""
    if not _CURRENT_DATASET_ID or _CURRENT_DATASET_ID not in _DATASET_STORE:
        load_preset_dataset("bd_fullroute_islampur_kushtia")

    all_points: List[MeasurementPoint] = _DATASET_STORE[_CURRENT_DATASET_ID]["points"]

    filtered = all_points
    if gps_only:
        filtered = [p for p in filtered if p.has_valid_gps]
    if cell_id is not None:
        filtered = [p for p in filtered if p.serving_cell_id == cell_id]
    if quality:
        q_lower = quality.lower()
        filtered = [p for p in filtered if p.rsrp_class.lower() == q_lower]

    if limit is not None and limit > 0:
        filtered = filtered[:limit]

    return filtered


@router.get("/datasets/current/extended-analysis", response_model=ExtendedDatasetAnalysis)
def get_extended_analysis():
    """Gets comprehensive telecom analysis including transitions, anomalies, service holes, and data quality."""
    if not _CURRENT_DATASET_ID or _CURRENT_DATASET_ID not in _DATASET_STORE:
        load_preset_dataset("bd_fullroute_islampur_kushtia")

    dataset_entry = _DATASET_STORE[_CURRENT_DATASET_ID]
    summary = dataset_entry["summary"]
    points = dataset_entry["points"]
    qoe_tests = dataset_entry.get("qoe_tests", [])
    raw_text = dataset_entry.get("raw_text")

    from backend.app.services.analytics import compute_extended_analysis
    return compute_extended_analysis(summary=summary, points=points, qoe_tests=qoe_tests, raw_text=raw_text)


@router.get("/datasets/current/qoe-tests", response_model=List[QoeTestRecord])
def get_current_qoe_tests(
    rat: Optional[str] = Query(None, description="Filter by RAT generation (e.g. 4G, 5G, 2G)"),
    operator: Optional[str] = Query(None, description="Filter by operator name"),
    pci: Optional[int] = Query(None, description="Filter by serving PCI"),
    status: Optional[str] = Query(None, description="Filter by test status (COMPLETED, FAILED, SKIPPED)"),
):
    """Gets individual network QoE ping, download, upload, jitter, and loss test records."""
    if not _CURRENT_DATASET_ID or _CURRENT_DATASET_ID not in _DATASET_STORE:
        load_preset_dataset("bd_ds3_v1")

    dataset_entry = _DATASET_STORE[_CURRENT_DATASET_ID]
    qoe_tests: List[QoeTestRecord] = dataset_entry.get("qoe_tests", [])

    filtered = qoe_tests
    if rat:
        r_clean = rat.strip().lower()
        filtered = [t for t in filtered if t.serving_rat and r_clean in t.serving_rat.lower()]
    if operator:
        op_clean = operator.strip().lower()
        filtered = [t for t in filtered if t.operator and op_clean in t.operator.lower()]
    if pci is not None:
        filtered = [t for t in filtered if t.serving_pci == pci]
    if status:
        st_clean = status.strip().upper()
        filtered = [t for t in filtered if t.test_status.upper() == st_clean]

    return filtered


@router.get("/datasets/current/export/csv")
def export_current_dataset_csv(extended: bool = Query(False, description="Export with extended columns")):
    """
    Exports the current dataset. Defaults to canonical 14-column CSV format
    guaranteeing 100% interoperability with external tools and dashboard ingestion.
    """
    from fastapi.responses import Response
    import io
    import csv

    if not _CURRENT_DATASET_ID or _CURRENT_DATASET_ID not in _DATASET_STORE:
        load_preset_dataset("bd_ds3_v1")

    dataset_entry = _DATASET_STORE[_CURRENT_DATASET_ID]
    points: List[MeasurementPoint] = dataset_entry["points"]

    output = io.StringIO()
    writer = csv.writer(output)

    if not extended:
        # Canonical 14-column CSV
        headers = [
            "Timestamp", "Latitude", "Longitude", "Speed (km/h)",
            "Serving Cell ID", "Serving Cell RSRP", "Serving Cell RSRQ",
            "Serving Cell CINR", "Handover Trigger", "TAC", "EARFCN",
            "RSSI (dBm)", "Network Type", "Operator"
        ]
        writer.writerow(headers)
        for p in points:
            writer.writerow([
                p.timestamp_raw,
                p.latitude if p.latitude is not None else "",
                p.longitude if p.longitude is not None else "",
                p.speed_kmh if p.speed_kmh is not None else "",
                p.serving_cell_id if p.serving_cell_id is not None else "",
                p.rsrp_dbm if p.rsrp_dbm is not None else "",
                p.rsrq_db if p.rsrq_db is not None else "",
                p.cinr_db if p.cinr_db is not None else "",
                p.handover_trigger,
                p.tac if p.tac is not None else "",
                p.earfcn if p.earfcn is not None else "",
                p.rssi_dbm if p.rssi_dbm is not None else "",
                p.network_type or p.network_gen or "LTE",
                p.operator or ""
            ])
    else:
        # Extended 20-column CSV with accuracy, heading, quality classes, and SINR provenance
        headers = [
            "Timestamp", "Latitude", "Longitude", "Accuracy (m)", "Heading (deg)",
            "Speed (km/h)", "Serving Cell ID", "Neighbor Cell ID",
            "Serving Cell RSRP", "RSRP Class", "Serving Cell RSRQ", "RSRQ Class",
            "Serving Cell CINR", "SINR Source", "Estimation Method", "CINR Class", "Handover Trigger",
            "TAC", "EARFCN", "Network Gen"
        ]
        writer.writerow(headers)
        for p in points:
            writer.writerow([
                p.timestamp_raw,
                p.latitude if p.latitude is not None else "",
                p.longitude if p.longitude is not None else "",
                p.accuracy_m if p.accuracy_m is not None else "",
                p.heading_deg if p.heading_deg is not None else "",
                p.speed_kmh if p.speed_kmh is not None else "",
                p.serving_cell_id if p.serving_cell_id is not None else "",
                p.neighbor_cell_id if p.neighbor_cell_id is not None else "",
                p.rsrp_dbm if p.rsrp_dbm is not None else "",
                p.rsrp_class,
                p.rsrq_db if p.rsrq_db is not None else "",
                p.rsrq_class,
                p.cinr_db if p.cinr_db is not None else "",
                p.sinr_source,
                p.estimation_method or "",
                p.cinr_class,
                p.handover_trigger,
                p.tac if p.tac is not None else "",
                p.earfcn if p.earfcn is not None else "",
                p.network_gen or "4G LTE"
            ])

    filename = f"cellscope_{_CURRENT_DATASET_ID}_{'extended' if extended else 'canonical'}.csv"
    return Response(
        content=output.getvalue(),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'}
    )

