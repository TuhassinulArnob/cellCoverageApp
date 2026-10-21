# CellScope-5G: Android Native Field Client

**Subsystem:** Android Mobile Application
**Target Platform:** Android 8.0+ (API Level 26 to 35)
**Language & Toolchain:** Kotlin 2.0.0, Gradle 8.9, Java 21 (Android Studio JBR)
**Database:** Android Room SQLite Database (Version 6) via KSP
**Artifact:** `app-debug.apk` (~50.01 MB, debug keystore signed)

---

## 1. Overview & Objectives

The **CellScope-5G Android Application** is an enterprise-grade cellular field drive-testing client designed for Radio Access Network (RAN) engineers, RF technicians, and academic researchers. Running directly on Android handsets without requiring root access, it collects real-time physical-layer cellular telemetry, executes autonomous active Quality of Experience (QoE) throughput and latency benchmarks, visualizes RF frequency channel distributions, and logs comprehensive drive-test survey records.

---

## 2. Directory Structure

```
android/app/src/
├── main/
│   ├── AndroidManifest.xml          # Runtime permissions & Foreground Service declarations
│   ├── java/com/cellscope/drive/
│   │   ├── MainActivity.kt          # Host Activity, Navigation Drawer & UI orchestration
│   │   ├── analytics/               # Handover and capacity recommendation engines
│   │   │   ├── CapacityRecommendationEngine.kt
│   │   │   ├── HandoverAnalyzer.kt
│   │   │   └── NetworkContinuityAnalyzer.kt
│   │   ├── data/                    # Room SQLite v6 database, entities & DAOs
│   │   │   ├── db/CellScopeDatabase.kt
│   │   │   ├── entity/              # LteMeasurementEntity, ActiveQoeTestEntity, etc.
│   │   │   ├── dao/                 # Data Access Objects (Room DAOs)
│   │   │   ├── CsvImporter.kt       # 6-Section Composite CSV Parser
│   │   │   └── CsvExporter.kt       # Multi-Section CSV Exporter
│   │   ├── export/
│   │   │   └── PdfReportExporter.kt # Paginated A4 canvas document generator
│   │   ├── model/                   # LteMetrics, ObservedCell, RF models
│   │   ├── qoe/                     # Active network testing engine
│   │   │   ├── QoeTestManager.kt    # Bounded throughput & RTT ping probes
│   │   │   ├── NetworkQoeScheduler.kt
│   │   │   └── QoeForegroundService.kt
│   │   ├── recorder/                # Survey recording lifecycle coordinator
│   │   │   ├── DriveTestRecorder.kt
│   │   │   ├── LocationTracker.kt
│   │   │   └── GpsJitterFilter.kt
│   │   ├── telephony/               # Low-level modem & telephony collection
│   │   │   ├── TelephonyCollector.kt
│   │   │   ├── ServingCellClassifier.kt
│   │   │   ├── SinrDiagnostics.kt   # Standardized SINR provenance resolver
│   │   │   ├── LteBandTable.kt      # 3GPP EARFCN frequency conversions
│   │   │   └── OperatorIdentity.kt  # Bangladesh MCC/MNC operator mapping
│   │   └── ui/                      # Custom views, adapters & map integration
│   │       ├── analytics/
│   │       │   ├── RfChannelGraphView.kt   # Bell-shaped cubic Bézier channel curves
│   │       │   └── MobilityTimelineView.kt # Multi-lane mobility timeline
│   │       ├── map/DriveMapView.kt         # MapLibre native GIS map
│   │       └── TransitionAdapter.kt        # Normalized event cards
│   └── res/                         # Layouts, vector icons, colors, themes
└── test/                            # Unit test suites (52 suites, 272 tests)
```

---

## 3. Core Architectural Subsystems

### 3.1 Telephony Telemetry & SINR Provenance Resolution
* **Modem Polling:** `TelephonyCollector.kt` polls `TelephonyManager.getAllCellInfo()` and registers `TelephonyCallback` listeners to capture serving and neighbor cells.
* **Serving Cell Classifier:** `ServingCellClassifier.kt` discriminates primary serving cells using `CellInfo.isRegistered` and connection status hierarchy (`CONNECTION_PRIMARY_SERVING`), with signal dominance fallbacks.
* **SINR Provenance:** Implemented in `SinrDiagnostics.kt`. Correctly handles Android modem uninitialized zero sentinels via `hasSeenNonZeroRssnr`. Distinguishes `SOURCE_MEASURED`, `SOURCE_ESTIMATED` (via 3GPP TS 36.214 RSRQ load model), and `SOURCE_UNAVAILABLE`.

### 3.2 Automated Active Network QoE Engine
* **Autonomous Scheduling:** `NetworkQoeScheduler.kt` schedules recurring bounded network tests (5m/10m/custom intervals) while a survey is active.
* **Foreground Service:** `QoeForegroundService.kt` presents an ongoing notification to guarantee continuous execution across Doze mode and screen-off states.
* **Probe Metrics:** Computes HTTP/TCP download throughput (1.5 MB chunk stream), upload throughput (1.0 MB multipart stream), RTT median/min/max latency, RFC 3550 statistical jitter, and packet loss ratio.

### 3.3 Restored Bell-Shaped RF Channel Frequency Graph
* `RfChannelGraphView.kt` renders continuous cubic Bézier channel curves with smart auto-windowing, minimum 16dp visual half-widths, fixed -140 to -40 dBm telecom bounds, and touch hit-testing.

### 3.4 Mobility Event Classification
* `HandoverAnalyzer.kt` categorizes cell transitions strictly into:
  - `CONFIRMED_HANDOVER` (explicit Layer-3 triggers).
  - `INTRA_FREQ` (same carrier EARFCN).
  - `INTER_FREQ` (different carrier EARFCNs).
  - `INTER_RAT` (radio technology changes, e.g. 4G $\rightarrow$ 2G).
  - `CELL_RESELECTION` (stationary / idle transition).
  - `PING_PONG` (rapid reselection $\le 15\text{ s}$).
  - `SIGNAL_LOSS` (prior signal drops below $-125\text{ dBm}$).
  - `OBSERVED_CELL_CHANGE` (serving cell switch without frequency telemetry).

---

## 4. Building & Running

### 4.1 Prerequisites
1. **JDK 17 or 21:** Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`).
2. **Android SDK:** Installed with Build-Tools 35.0.0.

### 4.2 Executing Unit Tests
```powershell
# Set JAVA_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Run all 272 unit tests
.\gradlew.bat testDebugUnitTest
```
*Expected Result:* `BUILD SUCCESSFUL` (272 passed, 0 failures).

### 4.3 Building the Debug APK
```powershell
.\gradlew.bat assembleDebug
```
*Generated Output:*
```
android/app/build/outputs/apk/debug/app-debug.apk
```
*File Size:* `52,443,514 bytes` (~50.01 MB).

### 4.4 Installing via ADB
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Required Runtime Permissions

The app prompts for the following permissions on initial launch:
* `android.permission.ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: Required for GPS drive-test route tracking.
* `android.permission.ACCESS_BACKGROUND_LOCATION`: Required for survey route recording when the screen is locked.
* `android.permission.READ_PHONE_STATE`: Required for reading raw telephony baseband signal strength and cell IDs.
* `android.permission.POST_NOTIFICATIONS`: Required on Android 13+ (API 33+) for the ongoing survey Foreground Service.
