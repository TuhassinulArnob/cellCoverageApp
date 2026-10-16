# CellScope: System Architecture Specification

## 1. Overview
CellScope is a mobile-first cellular network engineering platform designed for LTE/5G drive-test analytics, RF quality diagnosis, and mobility optimization.

The system is organized into modular phases:
* **Phase 1 (Active Milestone):** Mobile-first Web/PWA Analytics Platform with multi-schema CSV ingestion, 3GPP TS 36.133 conversion, GPS coordinate anomaly filtering, interactive Leaflet map layers, and KPI statistics.
* **Phase 2 (Next Milestone):** Native Android companion application in Kotlin for live GPS & cellular TelephonyManager collection.
* **Phase 3:** Advanced RF & Mobility Analytics (ping-pong detection, route segmentation, coverage hole identification).
* **Phase 4:** AI-assisted handover risk prediction and anomaly classification.
* **Phase 5:** 5G NR and Open RAN xApp-inspired decision engine.

---

## 2. Milestone 1 Architecture

```text
┌────────────────────────────────────────────────────────┐
│             CellScope Frontend (React + TS)            │
│  - Leaflet Map (RSRP / RSRQ / CINR color-coded layers) │
│  - KPI Overview Cards & Distribution Bars              │
│  - Measurement Grid (Search, Filter, Export)           │
│  - Ingestion Hub (Preset Catalog & Custom Upload)      │
└───────────────────────────▲────────────────────────────┘
                            │ REST / JSON (or unified SPA)
┌───────────────────────────▼────────────────────────────┐
│              FastAPI Analytics Backend                 │
│  - Ingestion Service: Header normalization & regex map │
│  - Validator: Bounding box GPS & RF outlier detection  │
│  - 3GPP Math Engine: TS 36.133 IE to dBm/dB formulas   │
│  - Aggregator: KPI distributions & bounding box calc   │
└───────────────────────────▲────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                    Data Storage Layer                  │
│  - data/raw/ (Verified Bangladesh LTE Drive Tests)     │
│  - In-Memory & SQLite Session Repository               │
└────────────────────────────────────────────────────────┘
```

---

## 3. API Specification

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/health` | GET | Health check and version status |
| `/api/datasets/available` | GET | List indexed drive-test datasets with metadata |
| `/api/datasets/load-preset/{id}` | POST | Load pre-indexed dataset by ID |
| `/api/datasets/upload` | POST | Upload and process custom drive-test CSV |
| `/api/datasets/current/summary` | GET | Fetch statistical summary of active dataset |
| `/api/datasets/current/measurements` | GET | Fetch filtered measurement points (by PCI, quality, limit) |
