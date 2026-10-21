# CellScope-5G: Backend REST API Reference & Data Contracts

**Document Version:** 3.0.0
**Service:** CellScope FastAPI Analytics Engine
**Base URL:** `http://localhost:8000/api`
**Schema Compliance:** OpenAPI 3.1 / Swagger

---

## 1. Overview & Architecture

The CellScope-5G backend is an asynchronous, high-throughput Python FastAPI microservice dedicated to cellular drive-test parsing, 3GPP standards calculations, spatial geometry transformations, and analytics aggregation.

All responses are formatted in strict **JSON** (except for CSV file export endpoints which return streaming `text/csv`). Time formats adhere to **ISO 8601** UTC (`YYYY-MM-DDTHH:mm:ss.sssZ`) or localized timezone offsets (`YYYY-MM-DDTHH:mm:ss.sss+06:00`).

---

## 2. API Endpoints Catalog

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                 FastAPI Endpoints Catalog                               │
├────────┬──────────────────────────────────────────┬─────────────────────────────────────┤
│ Method │ Endpoint Path                            │ Description                         │
├────────┼──────────────────────────────────────────┼─────────────────────────────────────┤
│ GET    │ /api/health                              │ Service health & runtime version    │
│ GET    │ /api/datasets/available                  │ List indexed preset survey datasets │
│ POST   │ /api/datasets/load-preset/{preset_id}    │ Ingest & activate local preset CSV  │
│ POST   │ /api/datasets/upload                     │ Upload & process custom survey CSV  │
│ GET    │ /api/datasets/current/summary            │ Active dataset statistical summary  │
│ GET    │ /api/datasets/current/measurements       │ Filtered 3GPP measurement points    │
│ GET    │ /api/datasets/current/extended-analysis  │ Transitions, anomalies, & quality   │
│ GET    │ /api/datasets/current/qoe-tests          │ Bounded throughput & ping latency   │
│ GET    │ /api/datasets/current/export/csv         │ Export canonical or extended CSV    │
└────────┴──────────────────────────────────────────┴─────────────────────────────────────┘
```

---

## 3. Detailed Endpoint Specifications

### 3.1 Service Health Check
* **Endpoint:** `GET /api/health`
* **Summary:** Returns service health, subsystem status, and build version.
* **Request Parameters:** None.
* **Response `200 OK`:**
```json
{
  "status": "healthy",
  "service": "CellScope LTE Drive-Test Analytics Engine",
  "version": "1.0.0-mvp"
}
```

---

### 3.2 List Available Preset Datasets
* **Endpoint:** `GET /api/datasets/available`
* **Summary:** Returns all pre-indexed real-world drive-test and benchmark surveys found on disk.
* **Request Parameters:** None.
* **Response `200 OK`:**
```json
[
  {
    "id": "bd_fullroute_islampur_kushtia",
    "title": "Full Highway Drive Test: Islampur to Kushtia (Robi 4G LTE)",
    "category": "Field Dataset",
    "rel_path": "docs/fullroute_islampurtokushtia.csv",
    "rows": 9315,
    "has_gps": true,
    "description": "Complete 68.8 km inter-district highway drive test from Islampur to Kushtia with 9,315 telemetry points, 41 active QoE speed/latency tests, and 246 mobile cell transitions on Robi Axiata."
  },
  {
    "id": "bd_survey_qoe_latest",
    "title": "Stationary Field Survey with Active QoE Tests (Islampur)",
    "category": "Field Dataset",
    "rel_path": "docs/cellscope_extended_Survey_20261020_081637_20261020_081637.csv",
    "rows": 98,
    "has_gps": true,
    "description": "Stationary field survey snippet on Robi Axiata at Islampur with bounded active download & upload throughput, RTT latency, RFC 3550 jitter, and probe loss tests."
  }
]
```

---

### 3.3 Load Preset Dataset
* **Endpoint:** `POST /api/datasets/load-preset/{preset_id}`
* **Summary:** Ingests, normalizes, and loads a pre-indexed dataset from the server filesystem into the active session memory.
* **Path Parameters:**
  * `preset_id` (string, required): Unique catalog ID (e.g., `bd_fullroute_islampur_kushtia`, `bd_ds3_v1`).
* **Response `200 OK` (`IngestResponse`):**
```json
{
  "dataset_id": "bd_fullroute_islampur_kushtia",
  "summary": {
    "dataset_id": "bd_fullroute_islampur_kushtia",
    "filename": "Full Highway Drive Test: Islampur to Kushtia (Robi 4G LTE)",
    "total_records": 9315,
    "records_with_gps": 9315,
    "records_invalid_gps": 0,
    "has_gps": true,
    "geo_bounding_box": {
      "min_lat": 23.7501,
      "max_lat": 24.1205,
      "min_lon": 88.8201,
      "max_lon": 90.4102,
      "center_lat": 23.9353,
      "center_lon": 89.6151
    },
    "mean_rsrp_dbm": -92.4,
    "min_rsrp_dbm": -124.0,
    "max_rsrp_dbm": -61.0,
    "mean_rsrq_db": -11.2,
    "mean_cinr_db": 11.8,
    "rsrp_distribution": { "excellent": 820, "good": 3410, "fair": 3200, "poor": 1885, "unknown": 0 },
    "rsrq_distribution": { "excellent": 1120, "good": 5400, "fair": 2100, "poor": 695, "unknown": 0 },
    "cinr_distribution": { "excellent": 1420, "good": 4120, "fair": 2800, "poor": 975, "unknown": 0 },
    "unique_serving_cells": 38,
    "unique_neighbor_cells": 112,
    "handover_trigger_count": 0,
    "handover_trigger_rate_pct": 0.0,
    "total_distance_km": 68.84,
    "mean_speed_kmh": 42.1,
    "max_speed_kmh": 78.5,
    "data_completeness_pct": 100.0,
    "measured_sinr_count": 9315,
    "estimated_sinr_count": 0,
    "unavailable_sinr_count": 0,
    "sinr_provenance_summary": { "MEASURED": 9315, "ESTIMATED": 0, "UNAVAILABLE": 0 }
  },
  "sample_measurements": [ ... ],
  "qoe_tests": [ ... ]
}
```
* **Error Responses:**
  * `404 Not Found`: If `preset_id` is unrecognized or file missing on disk.
  * `500 Internal Server Error`: If parsing fails.

---

### 3.4 Upload Custom Survey CSV
* **Endpoint:** `POST /api/datasets/upload`
* **Summary:** Accepts multipart/form-data CSV uploads (supporting standard single-table or 6-section composite format).
* **Request Body:** `multipart/form-data` with `file: <binary .csv>`.
* **Response `200 OK`:** Returns `IngestResponse` matching schema in Section 3.3.
* **Error Responses:**
  * `400 Bad Request`: If uploaded file is not a `.csv`.
  * `500 Internal Server Error`: If CSV processing fails.

---

### 3.5 Active Dataset Summary
* **Endpoint:** `GET /api/datasets/current/summary`
* **Summary:** Returns aggregated statistical KPIs, bounding box, and 3GPP distributions for the active dataset.
* **Request Parameters:** None.
* **Response `200 OK`:** Returns `DatasetSummary` JSON object.

---

### 3.6 Filtered Measurement Points
* **Endpoint:** `GET /api/datasets/current/measurements`
* **Summary:** Returns discrete physical RF observation samples for map and tabular rendering.
* **Query Parameters:**
  * `cell_id` (integer, optional): Filter by serving cell PCI.
  * `quality` (string, optional): Filter by RSRP quality (`Excellent`, `Good`, `Fair`, `Poor`).
  * `gps_only` (boolean, optional, default `true`): Restrict to valid GPS coordinates.
  * `limit` (integer, optional): Max records to return.
* **Response `200 OK` (`List[MeasurementPoint]`):**
```json
[
  {
    "id": 1,
    "timestamp_raw": "2026-10-20T08:16:00Z",
    "latitude": 24.0751,
    "longitude": 88.8238,
    "speed_kmh": 32.5,
    "serving_cell_id": 177,
    "neighbor_cell_id": 228,
    "rsrp_ie": 55,
    "rsrp_dbm": -85.0,
    "rsrq_ie": 17,
    "rsrq_db": -11.0,
    "cinr_db": 15.2,
    "handover_trigger": 0,
    "has_valid_gps": true,
    "rsrp_class": "Good",
    "rsrq_class": "Good",
    "cinr_class": "Good",
    "tac": 4109,
    "earfcn": 1800,
    "rssi_dbm": -63.0,
    "network_type": "LTE",
    "network_gen": "4G LTE",
    "operator": "Robi Axiata",
    "accuracy_m": 4.1,
    "heading_deg": 142.0,
    "timing_advance": 2,
    "cqi": 12,
    "eci": 179203851,
    "altitude_m": 24.5,
    "sinr_source": "MEASURED",
    "estimation_method": "ANDROID_RIL_RSSNR_MEASURED",
    "estimation_assumptions": "Direct modem baseband measurement",
    "sinr_confidence": "HIGH",
    "qualitative_diagnostic": "Low interference, high spectral efficiency achievable (Good SINR)",
    "implied_rssi_dbm": -63.0
  }
]
```

---

### 3.7 Extended Telecom Analysis
* **Endpoint:** `GET /api/datasets/current/extended-analysis`
* **Summary:** Performs in-depth RAN diagnostics including cell transitions, RF anomalies, coverage holes, and data quality indicators.
* **Request Parameters:** None.
* **Response `200 OK` (`ExtendedDatasetAnalysis`):**
```json
{
  "dataset_id": "bd_fullroute_islampur_kushtia",
  "transitions": [
    {
      "id": 1,
      "index": 142,
      "timestamp_raw": "2026-10-20T08:17:36Z",
      "from_pci": 177,
      "to_pci": 228,
      "latitude": 24.0751,
      "longitude": 88.8238,
      "is_handover_trigger": false,
      "event_type": "INTER_FREQ",
      "from_rat": "4G LTE",
      "to_rat": "4G LTE",
      "from_channel": 1399,
      "to_channel": 1800,
      "from_cell_id": 179203851,
      "to_cell_id": 10266113,
      "from_signal_dbm": -102.0,
      "to_signal_dbm": -88.0,
      "delta_signal_db": 14.0,
      "from_tac": 50034,
      "to_tac": 4109,
      "confidence_label": "MEDIUM",
      "confidence_score": 0.85,
      "classification_reason": "Inter-frequency carrier transition (1399 -> 1800) (TAC 50034 -> 4109)",
      "details": "Transition from PCI 177 to PCI 228: Inter-frequency carrier transition (1399 -> 1800)"
    }
  ],
  "anomalies": [
    {
      "id": 1,
      "index": 482,
      "timestamp_raw": "2026-10-20T08:24:12Z",
      "latitude": 24.0512,
      "longitude": 88.8410,
      "pci": 177,
      "rsrp_dbm": -84.0,
      "rsrq_db": -16.5,
      "cinr_db": -2.1,
      "anomaly_type": "PILOT_POLLUTION_OR_HIGH_LOAD",
      "severity": "HIGH",
      "explanation": "Strong RSRP (-84 dBm) combined with severe RSRQ degradation (-16.5 dB) and sub-zero CINR (-2.1 dB), indicating dense co-channel pilot pollution."
    }
  ],
  "service_holes": [
    {
      "id": 1,
      "index": 1210,
      "timestamp_raw": "2026-10-20T08:35:10Z",
      "latitude": 23.9810,
      "longitude": 88.9102,
      "hole_type": "CRITICAL_COVERAGE_HOLE",
      "severity": "CRITICAL",
      "description": "Signal dropped below receiver threshold (RSRP -118 dBm) along 350m corridor."
    }
  ],
  "weak_segments": [
    {
      "id": 1,
      "start_index": 1205,
      "end_index": 1218,
      "sample_count": 14,
      "min_rsrp_dbm": -118.0,
      "mean_rsrp_dbm": -109.4,
      "start_lat": 23.9821,
      "start_lon": 88.9085,
      "end_lat": 23.9798,
      "end_lon": 88.9122,
      "pci": 177,
      "reason": "Contiguous weak coverage zone below -105 dBm."
    }
  ],
  "data_quality": {
    "total_records": 9315,
    "completeness_pct": 100.0,
    "missing_gps_count": 0,
    "missing_rsrp_count": 0,
    "missing_rsrq_count": 0,
    "missing_cinr_count": 0,
    "missing_cell_id_count": 0,
    "telemetry_gaps_count": 12,
    "provenance_source": "CellScope Field Multi-Section CSV",
    "schema_standard": "3GPP TS 36.133 / WGS84",
    "coordinate_system": "WGS84 (EPSG:4326)"
  }
}
```

---

### 3.8 Active Network QoE Records
* **Endpoint:** `GET /api/datasets/current/qoe-tests`
* **Summary:** Returns individual active network speed and latency test results.
* **Query Parameters:**
  * `rat` (string, optional): Filter by RAT generation (`4G`, `5G`, `2G`).
  * `operator` (string, optional): Filter by carrier name.
  * `pci` (integer, optional): Filter by serving PCI.
  * `status` (string, optional): Filter by status (`COMPLETED`, `FAILED`, `SKIPPED`).
* **Response `200 OK` (`List[QoeTestRecord]`):**
```json
[
  {
    "id": 1,
    "session_id": 1,
    "timestamp_raw": "2026-10-20T08:17:15.102Z",
    "timestamp_ms": 1760948235102,
    "test_type": "AUTOMATIC_SCHEDULED",
    "test_status": "SUCCESS",
    "latitude": 23.810314,
    "longitude": 90.412518,
    "gps_accuracy_m": 4.2,
    "has_valid_gps": true,
    "serving_rat": "LTE",
    "serving_pci": 177,
    "serving_channel": 1850,
    "serving_rsrp_dbm": -94.0,
    "serving_rsrq_db": -12.0,
    "serving_sinr_db": 14.5,
    "operator": "Robi Axiata",
    "download_mbps": 1.20,
    "upload_mbps": 2.03,
    "bytes_downloaded": 1572864,
    "bytes_uploaded": 1048576,
    "download_duration_ms": 10480,
    "upload_duration_ms": 4120,
    "ping_latency_ms": 473.0,
    "min_ping_ms": 465.0,
    "max_ping_ms": 490.0,
    "jitter_ms": 312.30,
    "packet_loss_pct": 0.0,
    "congestion_classification": "MODERATE_CONGESTION"
  }
]
```

---

### 3.9 Export Survey Dataset (CSV)
* **Endpoint:** `GET /api/datasets/current/export/csv`
* **Summary:** Streams a CSV export of the active session.
* **Query Parameters:**
  * `extended` (boolean, optional, default `false`):
    - `false`: Canonical 14-column format.
    - `true`: Extended format including accuracy, heading, and quality classes.
* **Response `200 OK`:** Streaming text file with header `Content-Disposition: attachment; filename="cellscope_...csv"`.
