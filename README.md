# CellScope — LTE & 5G Cellular Drive-Test Analytics & Coverage Intelligence (v1.0.0)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20FastAPI%20%7C%20React-green.svg)](#platform-architecture)
[![Version](https://img.shields.io/badge/Release-v1.0.0-success.svg)](#release-v100)

**CellScope (Cell Coverage App)** is a comprehensive cellular network field-testing and coverage-analysis platform. Designed for Radio Access Network (RAN) engineers, RF planning & optimization specialists, and drive-test technicians, it pairs a **native Kotlin Android field-testing application** with a **web analytics dashboard** for in-depth post-processing.

---

## Key Modules

### 1. Native Android Application (`android/`)
Standalone field application for on-device cellular network measurements and geospatial recording:
* **Real-Time Telephony Subsystem**: Captures physical cell identity (PCI), Tracking Area Code (TAC), E-UTRAN Cell Identifier (ECI), EARFCN, downlink carrier frequency, and bandwidth.
* **RF Measurement Logging**:
  * **RSRP** (Reference Signal Received Power)
  * **RSRQ** (Reference Signal Received Quality)
  * **SINR** (Signal to Interference plus Noise Ratio)
  * **RSSI** (Received Signal Strength Indicator)
  * **CQI** and **Timing Advance (TA)**
* **Geographic Bangladesh Basemap**: Integrated MapLibre Native Android engine running keyless OpenStreetMap tiles with high performance, smooth gestures, and zoom levels covering all administrative divisions and road networks of Bangladesh.
* **Interactive RF Overlays**:
  * Clean route polyline tracks color-coded by 3GPP RSRP quality tiers (Excellent, Good, Fair, Poor).
  * Low-opacity, non-darkening RF coverage heatmap surface.
  * Cell transition / handover pins and estimated tower centroid overlays.
  * Interactive tap-to-inspect route points without cluttering marker shadows.
* **On-Device Analytics Engines**:
  * *Handover & Dwell Analytics*: Detects confirmed handovers, ping-pong transitions, and serving-cell dwell duration.
  * *Weak Zone Planner*: Isolates coverage gaps ($<-105\text{ dBm}$) and generates categorized engineering remediation hypotheses (Antenna tilt, Small Cell, Repeater).
* **Storage Access Framework (SAF) & Export**:
  * Standardized 14-column CSV telemetry export via `ACTION_CREATE_DOCUMENT`.
  * Multi-page, paginated A4 technical PDF report generation with text wrapping, table header continuity, and 3GPP quality distribution charts.
  * Direct Google Drive workflow integration.

### 2. Web Analytics Dashboard (`backend/` & `frontend/`)
* **Backend**: FastAPI (Python 3.10+) service providing 3GPP TS 36.133 unit conversions, CSV ingestion pipelines, coordinate outlier validation, and KPI aggregations.
* **Frontend**: React + Vite + TypeScript web application with interactive Leaflet mapping, summary cards, and paginated measurement tables.

---

## Technical Highlights

| Component | Technology Stack |
| :--- | :--- |
| **Android OS** | Android 8.0+ (API 26 to 36), Native Kotlin |
| **Android Architecture** | MVVM / Clean Architecture with StateFlow & Coroutines |
| **Database** | Android Room Database with Kotlin Symbol Processing (KSP) |
| **Map Rendering** | MapLibre Native Android SDK (11.5.1) |
| **Document Generation**| Android `PdfDocument` paginated canvas engine & SAF |
| **Backend API** | FastAPI, Pydantic, Uvicorn, Pytest |
| **Frontend Web** | React 18, Vite, TypeScript, Tailwind CSS, Leaflet |

---

## Getting Started

### Android App Build
Requirements: Android Studio / JDK 17+ / Android SDK 36
```bash
cd android
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug
```
Debug APK is produced at: `android/app/build/outputs/apk/debug/app-debug.apk`.

### Web Dashboard
```bash
# Backend
python -m pytest backend/tests/ -v
python backend/run.py

# Frontend Development
cd frontend
npm install
npm run dev
```

---

## License

This project is licensed under the [MIT License](LICENSE).
Copyright (c) 2026 Tuhassinul Arnob.
