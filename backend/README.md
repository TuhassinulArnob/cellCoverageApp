# CellScope-5G: FastAPI Analytics Microservice

**Subsystem:** Backend Analytics & Processing Engine
**Language & Runtime:** Python 3.10+ / Python 3.11+
**Framework:** FastAPI 0.136, Pydantic v2, AnyIO, Uvicorn
**Testing Suite:** Pytest 9.x
**Service Port:** `8000` (Default Base URL: `http://localhost:8000/api`)

---

## 1. Overview & Objectives

The **CellScope-5G Backend** is an asynchronous microservice responsible for ingesting, validating, and performing deep 3GPP standards calculations on cellular drive-test surveys. It powers both the interactive web GIS dashboard and external analytical integrations by transforming raw mobile telemetry logs into structured telecom KPIs, spatial mobility transitions, pilot pollution alerts, and active QoE performance metrics.

---

## 2. Directory Structure

```
backend/
├── app/
│   ├── main.py                  # FastAPI app factory, CORS middleware, & lifecycle
│   ├── api/
│   │   └── routes.py            # REST API endpoints (summary, points, transitions, qoe)
│   ├── core/
│   │   ├── config.py            # Telecom engineering threshold constants & paths
│   │   ├── rf_math.py           # 3GPP TS 36.133, TS 36.214, & TS 38.101 formulas
│   │   └── schema.py            # Pydantic v2 data models & request/response contracts
│   └── services/
│       ├── parser.py            # 6-Section composite CSV parser & regex header mapper
│       └── analytics.py         # Summary statistics, transitions, & anomaly detection
├── tests/                       # Automated Pytest test suite (43 tests)
│   ├── test_api.py              # Endpoint integration tests
│   ├── test_handover_analytics.py # Transition classification & TAC crossing tests
│   ├── test_qoe_pipeline.py     # Active QoE parsing & transition synthesis
│   ├── test_rf_math.py          # 3GPP IE conversion & frequency raster tests
│   ├── test_schema_validator.py # Kinematic coordinate & bounding box tests
│   └── test_sinr_diagnostics.py # SINR provenance, zero sentinel rejection, & 3GPP load model
└── requirements.txt             # Python dependency manifest
```

---

## 3. Core Analytical Capabilities

### 3.1 Resilient Multi-Section CSV Parser (`services/parser.py`)
* **Multi-Section Ingestion:** Independently identifies and processes `[SURVEY_METADATA]`, `[TELEMETRY_SAMPLES]`, `[ACTIVE_QOE_TESTS]`, `[MOBILITY_EVENTS]`, `[SERVICE_HOLES]`, and `[ESTIMATED_TOWERS]`.
* **Header Normalization:** Employs regex aliasing across 25+ canonical fields to support heterogeneous logging formats (e.g. Innowireless XCAL-M, Mendeley Data, and CellScope Native CSVs).
* **Fault Tolerance:** Isolates malformed lines without aborting dataset ingestion.

### 3.2 Standard 3GPP RF Calculations (`core/rf_math.py`)
* **Physical Conversions:** 3GPP TS 36.133 formulas for RSRP ($\text{IE} - 140\text{ dBm}$) and RSRQ ($(\text{IE} \times 0.5) - 19.5\text{ dB}$).
* **SINR Provenance Resolution:** Implements standard-compliant provenance resolution:
  - Valid modem baseband readings marked `MEASURED`.
  - Uninitialized zeros detected via `has_seen_nonzero_sinr` and rejected from `MEASURED`.
  - 4G LTE signals with valid bounds estimated via 3GPP TS 36.214 RSRQ load model (`ESTIMATED`).
  - 5G NR without measured baseband signals marked `UNAVAILABLE` (preventing uncalibrated estimation).

### 3.3 Analytics & Anomaly Detection (`services/analytics.py`)
* **Mobility Transitions:** Synthesizes `INTRA_FREQ`, `INTER_FREQ`, `INTER_RAT`, `CELL_RESELECTION`, `PING_PONG`, and `CONFIRMED_HANDOVER` without false TAC promotions.
* **RF Anomalies:** Detects pilot pollution ($\text{RSRP} \ge -85\text{ dBm}, \text{RSRQ} \le -15\text{ dB}, \text{CINR} < 0\text{ dB}$) and sector congestion.
* **Coverage Holes:** Segments contiguous weak zones below $-105\text{ dBm}$ and critical service holes below $-115\text{ dBm}$.
* **Tower Centroids:** Estimates cell site locations using linear RF power weighting.

---

## 4. REST API Endpoint Reference

See [`docs/API_DOCUMENTATION.md`](../docs/API_DOCUMENTATION.md) for full request/response schemas.

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/health` | Health check & microservice status |
| `GET` | `/api/datasets/available` | List indexed pre-processed survey datasets |
| `POST` | `/api/datasets/load-preset/{id}` | Ingest and activate a disk preset dataset |
| `POST` | `/api/datasets/upload` | Upload and process custom survey CSV |
| `GET` | `/api/datasets/current/summary` | Statistical summary of active dataset |
| `GET` | `/api/datasets/current/measurements` | Filtered measurement points (by cell, quality, GPS) |
| `GET` | `/api/datasets/current/extended-analysis` | Transitions, anomalies, service holes, and data quality |
| `GET` | `/api/datasets/current/qoe-tests` | Active speed, ping, jitter, and loss test records |
| `GET` | `/api/datasets/current/export/csv` | Export active dataset as canonical or extended CSV |

---

## 5. Setup & Execution

### 5.1 Environment Setup
```powershell
# Create & activate Python virtual environment
python -m venv venv
.\venv\Scripts\Activate.ps1

# Install requirements
pip install -r backend/requirements.txt
```

### 5.2 Executing Automated Pytest Suite
```powershell
$env:PYTHONPATH = "."
pytest backend/tests
```
*Expected Result:* `43 passed, 1 warning` in ~10 seconds.

### 5.3 Starting the Uvicorn Server
```powershell
$env:PYTHONPATH = "."
uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --reload
```
Interactive Swagger documentation is available at: `http://localhost:8000/docs`.
