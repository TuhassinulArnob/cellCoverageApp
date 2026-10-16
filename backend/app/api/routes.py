import os
import glob
from pathlib import Path
from typing import Dict, List, Optional
from fastapi import APIRouter, UploadFile, File, HTTPException, Query
from pydantic import BaseModel

from backend.app.core.config import DATA_RAW_DIR
from backend.app.core.schema import (
    DatasetSummary,
    MeasurementPoint,
    IngestResponse,
    AvailableDatasetInfo,
)
from backend.app.services.parser import parse_csv_to_dataframe
from backend.app.services.analytics import process_and_summarize_dataset

router = APIRouter(prefix="/api", tags=["Drive-Test Analytics"])

# In-memory store for loaded sessions
_DATASET_STORE: Dict[str, Dict] = {}
_CURRENT_DATASET_ID: Optional[str] = None


PRESET_CATALOG = [
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
    """Returns available pre-indexed drive-test datasets from data/raw/."""
    available = []
    for item in PRESET_CATALOG:
        full_path = DATA_RAW_DIR / Path(item["rel_path"])
        if full_path.exists():
            available.append(AvailableDatasetInfo(**item))
    return available


@router.post("/datasets/load-preset/{preset_id}", response_model=IngestResponse)
def load_preset_dataset(preset_id: str):
    """Loads a pre-indexed dataset from the local repository."""
    preset = next((p for p in PRESET_CATALOG if p["id"] == preset_id), None)
    if not preset:
        raise HTTPException(status_code=404, detail=f"Preset {preset_id} not found.")

    file_path = DATA_RAW_DIR / Path(preset["rel_path"])
    if not file_path.exists():
        raise HTTPException(status_code=404, detail=f"File not found at {file_path}")

    try:
        df, mapping, unmapped = parse_csv_to_dataframe(file_path=str(file_path))
        summary, points = process_and_summarize_dataset(
            df=df,
            mapping=mapping,
            filename=preset["title"],
            dataset_id=preset_id
        )

        _DATASET_STORE[preset_id] = {
            "summary": summary,
            "points": points,
            "raw_filename": preset["title"]
        }
        global _CURRENT_DATASET_ID
        _CURRENT_DATASET_ID = preset_id

        return IngestResponse(
            dataset_id=preset_id,
            summary=summary,
            sample_measurements=points[:100]
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
        df, mapping, unmapped = parse_csv_to_dataframe(content_bytes=content)
        dataset_id = f"upload_{file.filename}_{len(df)}"
        summary, points = process_and_summarize_dataset(
            df=df,
            mapping=mapping,
            filename=file.filename,
            dataset_id=dataset_id
        )

        _DATASET_STORE[dataset_id] = {
            "summary": summary,
            "points": points,
            "raw_filename": file.filename
        }
        global _CURRENT_DATASET_ID
        _CURRENT_DATASET_ID = dataset_id

        return IngestResponse(
            dataset_id=dataset_id,
            summary=summary,
            sample_measurements=points[:100]
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to process CSV: {str(e)}")


@router.get("/datasets/current/summary", response_model=DatasetSummary)
def get_current_summary():
    """Gets the active dataset summary."""
    if not _CURRENT_DATASET_ID or _CURRENT_DATASET_ID not in _DATASET_STORE:
        # If none loaded, automatically load default benchmark
        default_id = "bd_ds3_v1"
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
        load_preset_dataset("bd_ds3_v1")

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
