# Changelog & Release Notes

All notable changes to the **CellScope-5G** cellular drive-testing and network intelligence platform are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.0.0] - 2026-10-21 (Production Release)

### Critical Bug Fixes & Refactorings

#### 1. SINR Provenance Resolution & Zero-Sentinel Rejection (BUG 1 Fix)
* **Android RIL Zero Sentinel Elimination:** Resolved issue where Android modems returning `CellSignalStrengthLte.rssnr == 0` (as an uninitialized or unsupported modem sentinel) were falsely treated as valid `0.0 dB [MEASURED]`.
* **Stateful Non-Zero Observation Tracking:** Added `hasSeenNonZeroRssnr` state tracking in `SinrDiagnostics.kt` and `TelephonyCollector.kt`. When `rawRssnr == 0 && !hasSeenNonZeroRssnr`, the zero sentinel is rejected from `MEASURED` and falls through to 3GPP TS 36.214 calibrated estimation (`ESTIMATED`) or `UNAVAILABLE`.
* **Elimination of False Co-Channel Interference Alerts:** Fixed `CapacityRecommendationEngine.kt` to treat unmeasured average SINR as `null` rather than `0.0`, eliminating false co-channel interference and cell-edge recommendations.
* **Cross-Platform Provenance Propagation:** Added `sinr_source` and `estimation_method` to Room SQLite v6 entities, CSV exporters, FastAPI parsers, and web dashboard components.
* **Map Provenance Differentiation:** Visualized SINR provenance on the Leaflet web map: circle markers render with a dashed border (`dashArray: '3, 3'`) when `sinr_source === 'ESTIMATED'` to distinguish them from solid measured baseband readings.

#### 2. Mobility Event Classification & Handover Card Normalization (BUG 2 Fix)
* **Removed Passive Handover Triggers:** Eliminated faulty heuristic in `DriveTestRecorder.kt` and `HandoverAnalyzer.kt` that asserted `hoTrigger = 1` and `transType = "CONFIRMED_HANDOVER"` whenever vehicle speed exceeded $5\text{ km/h}$ with an active data connection or during a TAC boundary crossing.
* **Decoupled Backend Handover Promotion:** Fixed `backend/app/services/parser.py` and `backend/app/services/analytics.py` where `is_tac_change or ("confirmed" in details)` forcefully promoted non-handover events to `CONFIRMED_HANDOVER`.
* **Frontend Event Card Normalization:** Fixed `EventsAndAnomalies.tsx` and `MapView.tsx` where `detLower.includes('confirmed')` caused every timeline event card and marker tooltip to display `⚡ Confirmed Handover`.
* **Standardized Event Taxonomy:** Event cards now strictly display normalized classifications:
  - `CONFIRMED HANDOVER` (only when reliable protocol evidence or active triggers confirm it).
  - `INTRA-FREQUENCY TRANSITION` (same carrier EARFCN).
  - `INTER-FREQUENCY TRANSITION` (carrier frequency switch).
  - `INTER-RAT TRANSITION` (technology change, e.g. 4G $\rightarrow$ 2G).
  - `CELL RESELECTION` (stationary or idle transition).
  - `RAPID PING-PONG CANDIDATE` (rapid oscillation back to prior cell).
  - `SIGNAL LOSS` (preceding signal drop below receiver threshold).
  - `OBSERVED CELL CHANGE` (serving cell switch without frequency telemetry).

---

### Core New Features & Platform Capabilities

#### 3. Restored Bell-Shaped RF Channel Frequency Graph
* **Cubic Bézier Channel Envelopes:** Renders continuous bell-shaped curves for serving and neighbor carriers derived from 3GPP nominal channel bandwidths and center frequencies.
* **Multi-Band Smart Auto-Windowing:** When "All Bands" is active and observed cells span multiple distant bands (> 60 MHz), automatically windows onto the active serving cell's band to prevent horizontal compression distortion.
* **Dynamic Range Standard:** Fixed Y-axis from $-140\text{ dBm}$ to $-40\text{ dBm}$ with 20 dB gridlines.
* **Interactive Touch Hit-Testing:** Tapping any envelope displays an amber glowing peak ring, vertical leader line, and updates the *Cell Carrier Inspection* card with duplex separation, bandwidth, and signal quality metrics.

#### 4. Automated Active Network QoE Testing Engine
* **Zero-Touch Background Scheduler:** Starting a survey recording automatically initiates an initial bounded QoE test and schedules periodic testing loops (5m/10m/custom intervals).
* **Foreground Service Persistence:** Managed via `QoeForegroundService` with an ongoing notification to prevent Doze-mode termination while the screen is off.
* **Removal of Hardcoded Caps:** Replaced legacy 50 MB daily cap with configurable per-test data limits and roaming safeguards.
* **Full Data Pipeline Integration:** Bounded HTTP/TCP download/upload throughput, RTT median/min/max, RFC 3550 statistical jitter, and packet loss are collected, persisted in Room DB, exported to CSV, and visualized in the web dashboard.

#### 5. 6-Section Composite Survey CSV Engine
* **Modular Multi-Section Format:** Resilient parser supporting `[SURVEY_METADATA]`, `[TELEMETRY_SAMPLES]`, `[ACTIVE_QOE_TESTS]`, `[MOBILITY_EVENTS]`, `[SERVICE_HOLES]`, and `[ESTIMATED_TOWERS]`.
* **Backward Compatibility:** Seamlessly ingests legacy single-table drive-test CSVs (e.g. XCAL-M, Mendeley Data).

#### 6. Global Light & Dark Theme Support (Web Dashboard)
* **Application-Wide Dual Theming:** Full theme toggle supporting dark mode and high-contrast telecom light mode (`#F5F7FB` background, clean white cards).
* **Zero-Flash Persistence:** Inline anti-flash script in `index.html` backed by `localStorage` (`'cellscope-theme'`).
* **Synchronized GIS Map Tiles:** Dynamic Leaflet tile filter toggle (`cellscope-dark-tiles` inverted tiles vs. `cellscope-light-tiles` natural OpenStreetMap tiles).

#### 7. 3GPP TS 45.005 GSM ARFCN Band Classification
* **Accurate Band Discrimination:** Strictly separates P-GSM 900 (ARFCN 1..124), E-GSM 900 (ARFCN 0, 975..1023), GSM 850 (ARFCN 128..251), and DCS 1800 (ARFCN 512..885).
* **Metric Provenance:** Strictly isolates 2G GSM RxLev from 4G RSRP and 5G SS-RSRP.

#### 8. LTE CRS Modulo-3 Interference & Cellular Reuse Modeling
* **CRS Subcarrier Shift Isolation:** Restricts subcarrier collision analysis strictly to LTE networks ($v_{\text{shift}} = N_{\text{ID}}^{\text{cell}} \bmod 3$).
* **Hexagonal Reuse Analytics:** Computes theoretical co-channel reuse distance ($D = R\sqrt{3N}$) and reuse ratio ($Q = \sqrt{3N}$).

---

### Verification & Test Suite Summary
* **Android Unit Tests:** 272 / 272 Passed (Exit Code 0).
* **Android Debug APK Assembly:** Built successfully (`app-debug.apk`, 52,443,514 bytes).
* **FastAPI Backend Pytest:** 43 / 43 Passed (Exit Code 0).
* **Frontend Static Linter (Oxlint):** 0 warnings, 0 errors across 20 files (Exit Code 0).
* **Frontend Production Build:** Built successfully (`tsc -b && vite build` in 771ms).

---

## [2.0.0] - 2026-10-18 (Historical Release)
* Native Android Kotlin application with Room SQLite Database v6.
* Live TelephonyManager cell info collection and MapLibre mobile map view.
* On-device paginated A4 PDF report generation.
* Single-table CSV survey export and basic handover detection.

---

## [1.0.0] - 2026-10-15 (MVP Prototype)
* Initial MVP prototype with React web dashboard and FastAPI analytical service.
* Ingestion of Mendeley Bangladesh LTE benchmark drive-test logs.
