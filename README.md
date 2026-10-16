# CellScope: Mobile-First LTE Drive-Test Analytics & RF Optimization

CellScope is a cellular network engineering platform designed for RAN engineers, RF planning & optimization specialists, and drive-test technicians.

---

## Milestone 1 Status: Working LTE Analysis MVP

- [x] **Verified LTE Dataset:** Complete inspection and characterization of the Bangladesh urban LTE drive-test dataset (Version 2.0).
- [x] **3GPP Standards Compliance:** Implemented exact 3GPP TS 36.133 conversions for RSRP ($IE - 140\text{ dBm}$) and RSRQ ($(IE \times 0.5) - 19.5\text{ dB}$).
- [x] **Flexible CSV Ingestion Pipeline:** Auto-detects whitespace-padded columns, header typos (`Serving Cell RSRPQ`), and non-standard timestamps.
- [x] **Geospatial Coordinate Validation:** Detects and isolates invalid GPS fixes and longitude antenna glitches (e.g. lon 0.116667) using regional bounding-box sanity checks.
- [x] **Interactive GPS Map:** Leaflet map with color-coded RSRP, RSRQ, and CINR layers, auto-fit route bounds, serving cell filter, and hover/click inspection.
- [x] **Telecom KPI Summary & Distributions:** Computes mean/min/max metrics and 4-tier RF quality distributions (Excellent, Good, Fair, Poor).
- [x] **Cleaned Data Export:** Filterable and paginated measurement table with CSV export.
- [x] **Automated Test Suite:** 15 unit and integration tests passing with `pytest`.

---

## Quick Start Guide

### Prerequisites
* Python 3.10+ (Tested on Python 3.14)
* Node.js v18+ & npm (Tested on Node v24)

### 1. Run Automated Tests
```bash
python -m pytest backend/tests/ -v
```

### 2. Start the Application

#### Option A: Unified Server (FastAPI serves both API and built React SPA)
```bash
python backend/run.py
```
Open your browser at: **`http://127.0.0.1:8000`**

#### Option B: Development Mode (Vite hot-reloading + FastAPI API)
**Terminal 1 (Backend API):**
```bash
python backend/run.py
```
**Terminal 2 (Frontend Dev Server):**
```bash
cd frontend
npm.cmd run dev
```
Open your browser at: **`http://localhost:5173`**

---

## Benchmark Datasets Included

* **Dhaka Urban LTE Route 3 (ver 1)**: 640 rows with 100% valid Dhaka GPS coordinates, speed, RSRP, RSRQ, CINR, and handover triggers.
* **Dhaka LTE Route 2 (ver 1)**: 592 rows containing an antenna glitch outlier (lon 0.116667) demonstrating outlier isolation.
* **Dhaka LTE Route 2 Raw Parent Log**: 723 rows containing unacquired GPS fixes (NaN coordinates).
* **Dhaka LTE Route 1**: 922 rows of non-GPS tabular RF records for evaluating non-geospatial mode.

---

## Project Structure

```text
cellscope-5g/
├── backend/
│   ├── app/
│   │   ├── main.py             # FastAPI entrypoint & SPA static mount
│   │   ├── core/
│   │   │   ├── config.py       # RF thresholds & BD geo bounding box
│   │   │   ├── rf_math.py      # 3GPP TS 36.133 conversion formulas
│   │   │   └── schema.py       # Pydantic canonical measurement models
│   │   ├── services/
│   │   │   ├── parser.py       # CSV ingestion & column auto-detection
│   │   │   ├── validator.py    # Coordinate outlier & RF bounds checks
│   │   │   └── analytics.py    # KPI summaries & distribution calculator
│   │   └── api/
│   │       └── routes.py       # REST API endpoints
│   ├── tests/
│   │   ├── test_rf_math.py     # 3GPP RF unit tests
│   │   ├── test_schema_validator.py # Schema & coordinate tests
│   │   └── test_api.py         # REST endpoint integration tests
│   ├── requirements.txt
│   └── run.py
├── frontend/
│   ├── src/
│   │   ├── App.tsx             # Root application component
│   │   ├── components/
│   │   │   ├── MapView.tsx     # Leaflet interactive map with RF layers
│   │   │   ├── SummaryStats.tsx# Telecom KPI cards & distribution bars
│   │   │   ├── MeasurementTable.tsx # Paginated grid & CSV export
│   │   │   ├── FileUpload.tsx  # Preset selector & custom CSV uploader
│   │   │   ├── Navbar.tsx      # Header & navigation
│   │   │   └── KpiCard.tsx     # Reusable KPI card
│   │   └── services/api.ts     # Frontend API client
│   └── dist/                   # Production-optimized SPA bundle
├── docs/
│   ├── ARCHITECTURE.md         # Full system architecture
│   └── DATA_DICTIONARY.md      # Detailed dataset verification report
└── data/raw/                   # Extracted real LTE drive-test dataset
```
