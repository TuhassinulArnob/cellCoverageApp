# CellScope-5G: End-to-End System Architecture Specification

**Document Version:** 3.0.0 (Production)
**Status:** Approved & Verified
**Author:** Sarkar Tuhassinul Arnob (Lead System Architect)

---

## 1. Executive Architecture Summary

CellScope-5G is a multi-tier telecommunications RAN (Radio Access Network) drive-testing, physical-layer RF optimization, and active QoE intelligence platform. The system operates across three tightly integrated tiers:

1. **Mobile Field Capture Client (`android/`):**
   - Native Android application built in Kotlin 2.0 targeting API 26 to 35.
   - Leverages `TelephonyManager`, `TelephonyCallback` / `PhoneStateListener`, and `FusedLocationProviderClient`.
   - Runs background survey recording and autonomous QoE testing using Android Foreground Services.
   - Persists data locally in an ACID-compliant Room SQLite Database (Version 6).
   - Generates publication-grade on-device PDF reports and 6-section composite CSV survey logs.

2. **Analytical Post-Processing Microservice (`backend/`):**
   - High-throughput asynchronous REST microservice built with Python 3.11+ and FastAPI.
   - Multi-format ingestion engine parsing canonical single-table and 6-section composite CSVs.
   - Grounded mathematical engine executing 3GPP TS 36.133, TS 36.214, TS 38.101, and TS 45.005 RF formulas.
   - Kinematic trajectory validator with spatial bounding box and GPS jitter rejection.
   - Analytical synthesizer generating mobility transitions, RF anomalies, and coverage hole segments.

3. **Interactive Web GIS & Engineering Dashboard (`frontend/`):**
   - Single-Page Application (SPA) built with React 19, TypeScript, and Vite 8.
   - Leaflet / MapLibre multi-layer vector map with dynamic tile styling (Dark & Light modes).
   - Real-time slide-over telecom filter drawer with instant multi-parameter re-aggregation.
   - 3GPP carrier & band inventory, LTE CRS modulo-3 collision analysis, and active QoE performance metrics.

---

## 2. Platform Architecture Diagram

```mermaid
flowchart TD
    subgraph Tier1["Tier 1: Android Field Application (Kotlin 2.0 / Room v6)"]
        Sensors[GPS & Orientation Sensors] -->|Filtered Fixes| JitterFilter[GpsJitterFilter]
        RIL[Modem RIL TelephonyManager] -->|CellInfo Polling| ServingClassifier[ServingCellClassifier]
        NetConn[ConnectivityManager] --> Continuity[NetworkContinuityAnalyzer]

        Recorder[DriveTestRecorder Core Engine]
        JitterFilter --> Recorder
        ServingClassifier --> Recorder
        Continuity --> Recorder

        Recorder -->|Autonomous Trigger| QoeSched[NetworkQoeScheduler]
        QoeSched --> QoeService[QoeForegroundService]
        QoeService -->|HTTP/TCP Probes| QoeTestMgr[QoeTestManager]
        QoeTestMgr -->|Throughput & RTT| Recorder

        ServingClassifier --> RFGraph[RfChannelGraphView<br/>Bell-Shaped Envelopes & Touch Hit-Testing]

        Recorder --> RoomDB[(Room SQLite DB v6)]
        RoomDB --> CsvEngine[CsvExporter<br/>6-Section Composite CSV]
        RoomDB --> AndroidPdf[PdfReportExporter<br/>Paginated Canvas Engine]
    end

    subgraph Tier2["Tier 2: FastAPI Analytics Microservice (Python 3.11)"]
        CsvEngine -->|File Upload / HTTP POST| RouteUpload["POST /api/datasets/upload"]
        DiskPresets[(Disk Presets data/raw/)] -->|Catalog Ingestion| RoutePreset["POST /api/datasets/load-preset/{id}"]

        RouteUpload --> ParserEngine[services/parser.py<br/>Multi-Section Parser]
        RoutePreset --> ParserEngine

        ParserEngine --> Validator[Kinematic & Bounding Box Validator]
        Validator --> AnalyticsEngine[services/analytics.py<br/>RF Calculations & Extended Analysis]
        AnalyticsEngine --> RFMath[core/rf_math.py<br/>3GPP TS 36.214 SINR & Frequency Rasters]

        AnalyticsEngine --> SessionStore[(In-Memory Session Store)]
        SessionStore --> RoutesAPI[api/routes.py<br/>Summary, Points, Transitions, QoE]
    end

    subgraph Tier3["Tier 3: Web GIS Analytics Dashboard (React 19 / TypeScript)"]
        RoutesAPI --> APIService[services/api.ts]
        APIService --> AppState[App.tsx State Engine]

        AppState --> ThemeEngine[Theme System<br/>Dark & High-Contrast Light Mode]
        AppState --> FilterDrawer[FilterDrawer.tsx<br/>PCI, RAT, Operator, Quality, Handovers]

        AppState --> MapComponent[MapView.tsx<br/>Multi-Layer GIS Map & Popups]
        AppState --> StatsComponent[SummaryStats.tsx<br/>3GPP Distributions & CRS Mod-3]
        AppState --> QoEComponent[NetworkPerformancePanel.tsx<br/>Throughput & RTT Timelines]
        AppState --> EventsComponent[EventsAndAnomalies.tsx<br/>Normalized Transition Cards]
        AppState --> QualityComponent[DataQualityPanel.tsx<br/>Audit & Gaps Inspector]
        AppState --> TableComponent[MeasurementTable.tsx<br/>Searchable Tabular Grid]
        AppState --> ExportComponent[ReportExporter.tsx<br/>Print-Optimized Audit PDF]
    end
```

---

## 3. End-to-End Data Flow Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Tech as RAN Field Engineer
    participant Android as Android Drive Client
    participant Room as Room SQLite v6
    participant Backend as FastAPI Microservice
    participant Frontend as Web GIS Dashboard

    Tech->>Android: Tap "Start Survey Recording"
    Android->>Android: Start LocationTracker & TelephonyCollector polling (1 Hz)
    Android->>Android: Start QoeForegroundService (Background survey notification)

    loop Every Telephony Epoch (1000 ms)
        Android->>Android: Resolve Primary Serving Cell & Neighbor cells
        Android->>Android: Resolve SINR provenance (MEASURED vs 3GPP ESTIMATED vs UNAVAILABLE)
        Android->>Android: Detect cell transition & classify mobility event
        Android->>Room: Insert LteMeasurementEntity & CellTransitionEntity
    end

    loop Autonomous QoE Loop (Configurable 5m / 10m)
        Android->>Android: Run bounded HTTP download/upload & RTT ping probes
        Android->>Android: Compute RFC 3550 statistical jitter & packet loss
        Android->>Room: Insert ActiveQoeTestEntity
    end

    Tech->>Android: Tap "Stop Survey Recording"
    Android->>Room: Finalize SurveySessionEntity
    Tech->>Android: Tap "Export Composite CSV"
    Android->>Room: Query full session entities
    Android->>Android: CsvExporter builds 6-section composite CSV

    Tech->>Frontend: Drag-and-drop exported CSV into Ingestion Hub
    Frontend->>Backend: POST /api/datasets/upload (multipart/form-data)
    Backend->>Backend: parser.py parses 6-section composite format
    Backend->>Backend: rf_math.py & analytics.py compute extended analysis
    Backend-->>Frontend: Return IngestResponse (Summary, 100 sample points, QoE tests)

    Frontend->>Backend: GET /api/datasets/current/measurements?gps_only=true
    Backend-->>Frontend: Return full MeasurementPoint collection
    Frontend->>Backend: GET /api/datasets/current/extended-analysis
    Backend-->>Frontend: Return ExtendedDatasetAnalysis (Transitions, Anomalies, Quality)

    Frontend->>Frontend: Render multi-layer Leaflet map, KPI distributions, & transition cards
    Frontend->>Tech: Interactive inspection, filter isolation, & executive report export
```

---

## 4. Subsystem Details & Component Responsibilities

### 4.1 Android Client Subsystems
* **`telephony/`**:
  * `TelephonyCollector.kt`: Collects registered and neighbor cell information via `TelephonyManager`.
  * `ServingCellClassifier.kt`: Identifies primary serving cell using RIL registration flags and power hierarchy.
  * `SinrDiagnostics.kt`: Standardizes SINR provenance (`MEASURED`, `ESTIMATED`, `UNAVAILABLE`) and rejects uninitialized zero sentinels.
  * `LteBandTable.kt`: Maps EARFCN to 3GPP operating bands, duplex mode, and operator allocations.
  * `OperatorIdentity.kt`: Maps MCC/MNC to Bangladesh operators (Grameenphone, Robi, Banglalink, Teletalk).
* **`recorder/`**:
  * `DriveTestRecorder.kt`: Central coordinator managing survey session lifecycle, sample throttling, and Room insertion.
  * `GpsJitterFilter.kt`: Rejects GPS multipath teleportation jumps and computes kinematic speed.
* **`qoe/`**:
  * `QoeTestManager.kt`: Executes bounded chunked HTTP throughput and ICMP/TCP ping latency tests.
  * `NetworkQoeScheduler.kt`: Manages recurring scheduled testing loops.
  * `QoeForegroundService.kt`: Keeps the survey loop active during screen-off and Doze mode.
* **`ui/`**:
  * `RfChannelGraphView.kt`: Custom canvas drawing cubic Bézier bell-shaped channel envelopes with dynamic bounds and smart auto-windowing.
  * `MobilityTimelineView.kt`: Multi-lane mobility timeline separating confirmed handovers, intra-freq, inter-freq, and inter-RAT events.
  * `TransitionAdapter.kt`: Formats normalized transition cards underneath the timeline.

### 4.2 Backend Microservice Subsystems
* **`parser.py`**: Multi-section aware parser with regex header normalization and graceful recovery from malformed lines.
* **`rf_math.py`**: Mathematical implementation of 3GPP TS 36.133, TS 36.214, TS 38.101, and TS 45.005.
* **`analytics.py`**: Statistical aggregation, spatial bounding box calculation, cell transition detection, and anomaly classification.
* **`routes.py`**: REST endpoint router handling session caching, upload, filtering, and export.

### 4.3 Frontend Web Subsystems
* **`MapView.tsx`**: Interactive Leaflet GIS map with color-coded metric layers, route tracking, tower centroids, and transition diamonds.
* **`SummaryStats.tsx`**: Executive KPI cards, 3GPP distributions, carrier inventory, and CRS mod-3 collisions.
* **`EventsAndAnomalies.tsx`**: Table of normalized cell transitions, pilot pollution alerts, and weak coverage zones.
* **`NetworkPerformancePanel.tsx`**: Throughput and latency charts with active QoE test record logs.
* **`FilterDrawer.tsx`**: Slide-over multi-parameter telecom filter drawer with instant cross-component updates.

---

## 5. Security, Permissions & Data Privacy

1. **Android Runtime Permissions:**
   - `ACCESS_FINE_LOCATION` & `ACCESS_BACKGROUND_LOCATION`: Required for geographic mapping.
   - `READ_PHONE_STATE`: Required for telephony cell identity and signal strength.
   - `POST_NOTIFICATIONS`: Required for Android 13+ foreground survey notifications.
2. **Local Processing & Zero Data Exfiltration:**
   - All drive-test telemetry is stored strictly on the local device or inside the self-hosted Python backend.
   - No user credentials, location telemetry, or identity metrics are sent to third-party cloud services.
3. **No Root Requirement:**
   - Operates entirely on standard unrooted consumer and engineering devices via public Android SDK APIs.
