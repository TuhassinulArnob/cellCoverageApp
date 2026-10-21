# CellScope-5G: Mathematical Models, Algorithms & Heuristics Reference

**Document Version:** 3.0.0
**Status:** Complete & Verified
**Applicability:** Android Client, FastAPI Backend, React Web Dashboard
**Author:** Sarkar Tuhassinul Arnob (RAN & Lead Engineer)

---

## Executive Summary & Taxonomy

CellScope-5G implements a rigorous collection of **telecommunications physical-layer conversions**, **statistical estimators**, **spatial geometry algorithms**, **empirical heuristics**, and **rule-based state machine classifiers**.

This document defines every analytical model in the repository. In accordance with strict engineering standards, **no heuristic or rule-based classifier is falsely described as "Machine Learning"**. Every algorithm's mathematical formulation, source code entry point, inputs/outputs, assumptions, and validation requirements are documented below.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   CellScope-5G Analytical Taxonomy                     │
├─────────────────────────┬──────────────────────────────────────────────┤
│ 1. Deterministic 3GPP   │ • TS 36.133 RSRP / RSRQ IE Conversion       │
│    Standards            │ • TS 36.101 LTE Downlink/Uplink Frequency    │
│                         │ • TS 38.101 5G NR Global Frequency Raster    │
│                         │ • TS 45.005 GSM ARFCN Channel Carrier Bands  │
│                         │ • LTE CRS Modulo-3 Subcarrier Shift (v_shift)│
├─────────────────────────┼──────────────────────────────────────────────┤
│ 2. Radio & Spatial      │ • 3GPP TS 36.214 LTE SINR Estimation Model   │
│    Estimators           │ • RF Power-Weighted Cell Site Centroid       │
│                         │ • Haversine Empirical Coverage Radius (R)    │
│                         │ • Hexagonal Co-Channel Reuse Distance (D, Q) │
│                         │ • Timing Advance Spatial Range Extrapolation │
├─────────────────────────┼──────────────────────────────────────────────┤
│ 3. Rule-Based           │ • Serving Cell Registration Classifier       │
│    Classifiers          │ • Handover & Mobility Event State Machine    │
│                         │ • 3GPP RF Signal Quality Band Classifier     │
├─────────────────────────┼──────────────────────────────────────────────┤
│ 4. Heuristic & Network  │ • Sector Congestion & Interference Detector  │
│    Health Detectors     │ • Stronger Neighbor "Why Not Connected?"     │
│                         │ • Weak Coverage Corridor Segmenter           │
│                         │ • Critical Service Hole Detector             │
│                         │ • GPS Spatial Jitter & Displacement Filter   │
│                         │ • Active QoE Throughput & RFC 3550 Jitter    │
└─────────────────────────┴──────────────────────────────────────────────┘
```

---

## 1. SINR Provenance Resolution & 3GPP TS 36.214 Radio Model

### 1.1 Purpose & Classification
* **Classification:** Empirical Radio Model & Provenance Resolver (Statistical Estimator / Heuristic; *Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/telephony/SinrDiagnostics.kt` (`resolveLteSinr()`)
  * Backend: `backend/app/core/rf_math.py` (`resolve_sinr_provenance()`, `estimate_lte_sinr()`)
  * Frontend: `frontend/src/utils/telecom.ts` (`classifyCinr()`, provenance badge formatting)

### 1.2 Mathematical Derivation
Under 3GPP TS 36.214, Reference Signal Received Quality ($\text{RSRQ}$) is defined as:
$$\text{RSRQ} = \frac{N \cdot \text{RSRP}}{\text{RSSI}}$$
where $N$ is the integer number of downlink Resource Blocks (RBs) spanning the carrier bandwidth ($N \in \{6, 15, 25, 50, 75, 100\}$).

The wideband carrier RSSI linear power is composed of serving cell reference symbols, serving cell data traffic subcarriers, co-channel neighbor cell interference ($I_{\text{ext}}$), and thermal background noise ($N_0$):
$$\text{RSSI}_{\text{linear}} = 12 \cdot N \cdot \text{RSRP}_{\text{linear}} + \eta \cdot 12 \cdot N \cdot P_{\text{traffic}} + I_{\text{ext}} + N_0$$
where $\eta \in [0.0, 1.0]$ represents the fractional cell traffic load (assumed nominal $\eta = 0.5$ for 50% loaded operational networks).

Carrier-to-Interference-plus-Noise Ratio ($\text{SINR}$) on the Reference Signals is:
$$\text{SINR}_{\text{linear}} = \frac{\text{RSRP}_{\text{linear}}}{I_{\text{ext}} + N_0}$$

Dividing by $N \cdot \text{RSRP}_{\text{linear}}$ and rearranging yields the 3GPP load model:
$$\frac{1}{\text{RSRQ}_{\text{linear}}} = 12 + 12\eta\frac{P_{\text{traffic}}}{\text{RSRP}} + \frac{1}{\text{SINR}_{\text{linear}}}$$

Assuming equal power allocation between data and reference symbols ($P_{\text{traffic}} \approx \text{RSRP}$), we define the load offset factor:
$$\text{offset} = 2.0 + 10.0 \cdot \eta \quad (\text{offset} = 7.0 \text{ for } \eta = 0.5)$$
$$\text{denom} = \left(\frac{1}{\text{RSRQ}_{\text{linear}}}\right) - \text{offset}$$
$$\text{SINR}_{\text{dB}} = 10 \log_{10}\left(\frac{12.0}{\max(\text{denom}, 0.001)}\right)$$
Clamped bounds: $[-20.0\text{ dB}, +30.0\text{ dB}]$.

The implied total carrier $\text{RSSI}$ in physical $\text{dBm}$ is calculated as:
$$\text{RSSI}_{\text{dBm}} = \text{RSRP}_{\text{dBm}} + 10\log_{10}(N) - \text{RSRQ}_{\text{dB}}$$

### 1.3 Provenance Hierarchy & Sentinel Rejection
1. **`MEASURED`:**
   - Valid modem reading from `CellSignalStrengthLte.rssnr` (or 5G `CellSignalStrengthNr.ssSinr`).
   - Android sentinels (`99`, `0x7FFFFFFF`, `-1`) are strictly rejected.
   - **Zero Sentinel Protection:** Android RILs frequently return raw `0` when SINR is uninitialized or unsupported. Raw `0` is **rejected** as measured unless the session has already observed genuine non-zero readings (`hasSeenNonZeroRssnr == true`).
2. **`ESTIMATED`:**
   - Evaluated strictly for 4G LTE when inputs satisfy standard 3GPP validity boundaries: $-140.0\text{ dBm} \le \text{RSRP} \le -40.0\text{ dBm}$ and $-25.0\text{ dB} \le \text{RSRQ} \le -3.0\text{ dB}$.
   - Method label: `3GPP_TS_36_214_RSRQ_LOAD_MODEL`.
   - Confidence: `CALIBRATED_ESTIMATE` with documented uncertainty ($\pm 3.5\text{ dB}$).
3. **`UNAVAILABLE`:**
   - Returned when inputs are missing, out of physical bounds, or when evaluating 5G NR (3GPP TS 38.215 NR SS-RSRQ definitions do not map to LTE carrier RSSI equations). Never defaults to `0.0`.

---

## 2. Handover & Mobility Event State Machine

### 2.1 Purpose & Classification
* **Classification:** Rule-Based Deterministic Event Classifier & Telemetry Synthesizer (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/analytics/HandoverAnalyzer.kt`
  * Android Recorder: `android/app/src/main/java/com/cellscope/drive/recorder/DriveTestRecorder.kt`
  * Backend: `backend/app/services/parser.py` (`extract_cell_transition_records()`)
  * Backend Analytics: `backend/app/services/analytics.py` (`compute_extended_analysis()`)

### 2.2 Standard Event Taxonomy & Evidence Requirements

A PCI, TAC, frequency, or RSRP change alone **does not prove a confirmed handover**. Handovers require specific protocol evidence.

| Event Type | Display Card Label | Marker Color | Evidence Required for Confirmation |
| :--- | :--- | :--- | :--- |
| `CONFIRMED_HANDOVER` | `⚡ Confirmed Handover` | Emerald (`#10B981`) | Explicit modem Layer-3 Handover Trigger asserted (`handover_trigger == 1`), RRC Connection Reconfiguration message, or explicit field test logging flag. |
| `INTRA_FREQ` | `↔ Intra-Frequency Transition` | Cyan (`#06B6D4`) | Serving cell change where channel frequency matches exactly (`from_channel == to_channel`), without explicit L3 handover confirmation. |
| `INTER_FREQ` | `⇄ Inter-Frequency Transition` | Blue (`#3B82F6`) | Serving cell change across distinct carrier channel numbers (`from_channel != to_channel`), indicating carrier reselection or blind redirection. |
| `INTER_RAT` | `⇅ Inter-RAT Transition` | Amber (`#F59E0B`) | Radio access technology change (e.g., 4G LTE $\rightarrow$ 2G GSM or 4G LTE $\rightarrow$ 5G NR). |
| `CELL_RESELECTION` | `⇋ Cell Reselection` | Yellow (`#EAB308`) | Serving cell transition occurring while handset is stationary ($< 2.0\text{ km/h}$) or in RRC_IDLE mode. |
| `PING_PONG` | `↺ Ping-Pong Candidate` | Rose (`#EF4444`) | Rapid reselection back to the previously serving cell ($\text{PCI}_A \rightarrow \text{PCI}_B \rightarrow \text{PCI}_A$) within a temporal window $\Delta t \le 15.0\text{ seconds}$. |
| `SIGNAL_LOSS` | `⚠ Signal Loss` | Rose (`#F43F5E`) | Abrupt drop of serving signal below receiver sensitivity ($<-125\text{ dBm}$) preceding the transition. |
| `OBSERVED_CELL_CHANGE` | `• Observed Cell Change` | Purple (`#8B5CF6`) | Serving cell identity change without carrier frequency telemetry or protocol triggers. |

### 2.3 Historical Bug Resolution
* In previous revisions, vehicle speed $\ge 5\text{ km/h}$ combined with data connection or TAC change was erroneously promoted to `CONFIRMED_HANDOVER`.
* In v3.0, TAC boundary crossings are annotated as inter-TAC geographic boundaries, and event cards underneath timeline graphs reflect exact normalized event records.

---

## 3. Serving Cell Identification & Registration Classifier

### 3.1 Purpose & Classification
* **Classification:** Rule-Based Priority Selector (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/telephony/ServingCellClassifier.kt`
  * Android Collector: `android/app/src/main/java/com/cellscope/drive/telephony/TelephonyCollector.kt`

### 3.2 Decision Hierarchy
1. **RIL Registration Flag:** Scans `CellInfo.isRegistered`. If exactly one cell has `isRegistered == true`, it is classified as the serving cell.
2. **Connection Status Priority:** On Android 10+ (API 29+), inspects `CellInfoLte.cellConnectionStatus`:
   - `CONNECTION_PRIMARY_SERVING` $\rightarrow$ Rank 1
   - `CONNECTION_SECONDARY_SERVING` $\rightarrow$ Rank 2 (Carrier Aggregation SCell)
   - `CONNECTION_NONE` $\rightarrow$ Rank 3 (Neighbor)
3. **RF Signal Dominance Tie-Breaker:** If the modem provides ambiguous registration flags (common on dual-SIM devices), selects the candidate on the registered network operator with the highest RSRP.

---

## 4. RF Power-Weighted Cell Site Centroid Triangulation

### 4.1 Purpose & Classification
* **Classification:** Spatial Heuristic / Statistical Power-Weighted Centroid Estimator (*Not Machine Learning*).
* **Source Entry Points:**
  * Backend: `backend/app/services/analytics.py` (`compute_extended_analysis()`)
  * Frontend: `frontend/src/components/MapView.tsx` (`estimatedTowers`)

### 4.2 Mathematical Formulation
Handsets observe signals along a drive-test route rather than at the tower. The physical tower coordinates $(\hat{\phi}_{\text{tower}}, \hat{\lambda}_{\text{tower}})$ for a specific Physical Cell ID ($\text{PCI}$) are estimated using linear RF power weighting:

$$w_i = 10^{\frac{\text{RSRP}_i}{10}} \quad (\text{linear milliwatts scale})$$

$$\hat{\phi}_{\text{tower}} = \frac{\sum_{i=1}^{M} w_i \cdot \phi_i}{\sum_{i=1}^{M} w_i}, \quad \hat{\lambda}_{\text{tower}} = \frac{\sum_{i=1}^{M} w_i \cdot \lambda_i}{\sum_{i=1}^{M} w_i}$$

where $(\phi_i, \lambda_i)$ is the handset GPS fix for sample $i$ observing that PCI.

### 4.3 Spatial Uncertainty & Coverage Radius
The spatial spread of observations defines the uncertainty radius ($R_{\text{uncertainty}}$):
$$R_{\text{uncertainty}} = \max_{i} \mathcal{H}(\hat{\phi}_{\text{tower}}, \hat{\lambda}_{\text{tower}}, \phi_i, \lambda_i)$$
where $\mathcal{H}$ is the Haversine great-circle distance function.

**Limitations:**handset GPS drive-testing cannot determine true antenna tower locations without eNodeB/gNodeB base-station almanac databases. Centroids represent the geometric center of maximum received RF energy along the surveyed road.

---

## 5. LTE CRS Modulo-3 Interference & Cellular Reuse Analytics

### 5.1 Purpose & Classification
* **Classification:** Deterministic 3GPP Reference Signal Math & Hexagonal Cellular Geometry (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/analytics/CapacityRecommendationEngine.kt`
  * Frontend: `frontend/src/utils/telecom.ts` (`calculateCrsShift()`, `analyzePciMod3Collisions()`)
  * Frontend: `frontend/src/components/SummaryStats.tsx`

### 5.2 Mathematical Formulation
1. **LTE CRS Subcarrier Shift:**
   In 3GPP LTE downlink OFDM, Cell-Specific Reference Signals (CRS) are shifted in frequency by $v_{\text{shift}}$ subcarriers to prevent pilot collision:
   $$v_{\text{shift}} = N_{\text{ID}}^{\text{cell}} \bmod 3 = (3 \cdot N_{\text{ID}}^{(1)} + N_{\text{ID}}^{(2)}) \bmod 3 = N_{\text{ID}}^{(2)} \in \{0, 1, 2\}$$
   Where $N_{\text{ID}}^{(1)} \in [0, 167]$ is the Physical-layer Cell-Identity Group, and $N_{\text{ID}}^{(2)} \in [0, 2]$ is the Physical-layer Identity.
   When neighboring cells share the same carrier frequency and the same $v_{\text{shift}}$, their CRS pilots collide, degrading channel estimation and PDSCH decoding even under moderate RSRP.

2. **Hexagonal Frequency Reuse Distance ($D$) & Reuse Ratio ($Q$):**
   $$D = R \cdot \sqrt{3N}$$
   $$Q = \frac{D}{R} = \sqrt{3N}$$
   where $N = i^2 + ij + j^2$ is the cluster size ($N \in \{1, 3, 4, 7, 12\}$), and $R$ is the empirical cell radius derived from drive-test trajectory bounds.

---

## 6. Radio Channel Frequency Raster Calculators

### 6.1 Purpose & Classification
* **Classification:** Deterministic 3GPP Standard Conversion Tables (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/telephony/LteBandTable.kt`
  * Frontend: `frontend/src/utils/telecom.ts` (`resolveEarfcn()`, `resolveArfcn()`)

### 6.2 Standard Formulations
* **LTE Downlink Carrier Frequency (3GPP TS 36.101 Table 5.7.3-1):**
  $$F_{\text{DL}} = F_{\text{DL\_low}} + 0.1 \cdot (N_{\text{DL}} - N_{\text{Offs\_DL}}) \text{ MHz}$$
* **5G NR Global Frequency Raster (3GPP TS 38.101-1):**
  $$F_{\text{REF}} = \begin{cases} 0.005 \cdot N_{\text{REF}} \text{ MHz} & N_{\text{REF}} \in [0, 599999] \\ 3000.0 + 0.015 \cdot (N_{\text{REF}} - 600000) \text{ MHz} & N_{\text{REF}} \in [600000, 2016666] \end{cases}$$
* **GSM ARFCN Frequency Raster (3GPP TS 45.005):**
  $$F_{\text{DL}}(n) = \begin{cases} 935.0 + 0.2 \cdot n \text{ MHz} & n \in [1, 124] \quad (\text{P-GSM 900}) \\ 925.0 + 0.2 \cdot (n - 1024) \text{ MHz} & n \in [975, 1023] \quad (\text{E-GSM 900}) \\ 1805.2 + 0.2 \cdot (n - 512) \text{ MHz} & n \in [512, 885] \quad (\text{DCS 1800}) \end{cases}$$

---

## 7. Active Network QoE Estimators & Statistical Metrics

### 7.1 Purpose & Classification
* **Classification:** Active Network Probing & IETF Statistical Calculations (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/qoe/QoeTestManager.kt`
  * Backend: `backend/app/services/parser.py` (`extract_qoe_records()`)
  * Frontend: `frontend/src/components/NetworkPerformancePanel.tsx`

### 7.2 Formulations
1. **Bounded HTTP/TCP Throughput:**
   $$R_{\text{throughput}} = \frac{8 \cdot B_{\text{transferred}}}{10^6 \cdot \Delta t_{\text{seconds}}} \text{ Mbps}$$
   Executed over bounded byte ranges (1.5 MB download, 1.0 MB upload) with early time-out limits to prevent user data exhaustion.
2. **Ping RTT Statistics:**
   Median, Mean, and P95 latency calculated over $K = 10$ sequential HTTP probe round-trips:
   $$\text{RTT}_{\text{median}} = \text{median}(\{t_1, t_2, \dots, t_K\})$$
3. **Statistical Jitter (RFC 3550 Formulation):**
   $$J_i = J_{i-1} + \frac{|D(i-1, i)| - J_{i-1}}{16}$$
   where $D(i-1, i) = (R_i - S_i) - (R_{i-1} - S_{i-1})$ represents transit time variance.
4. **Packet / Probe Loss Ratio:**
   $$L = \left(\frac{K_{\text{sent}} - K_{\text{acknowledged}}}{K_{\text{sent}}}\right) \times 100\%$$

---

## 8. Sector Congestion & Interference Detector

### 8.1 Purpose & Classification
* **Classification:** Multi-Parameter Correlative Heuristic (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/analytics/CapacityRecommendationEngine.kt`
  * Backend: `backend/app/services/analytics.py` (`detect_rf_anomalies()`)

### 8.2 Evaluation Rule
Sector congestion occurs when radio signal coverage is strong, but user throughput and quality drop due to multi-user scheduling saturation:
$$\text{Congestion Alert} = \left(\text{RSRP} \ge -90.0\text{ dBm}\right) \land \left(\text{RSRQ} \le -14.0\text{ dB}\right) \land \left(R_{\text{throughput}} < 2.0\text{ Mbps}\right)$$

---

## 9. Stronger Neighbor "Why Not Connected?" Diagnostic

### 9.1 Purpose & Classification
* **Classification:** Rule-Based RF Diagnostic Heuristic (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/analytics/CapacityRecommendationEngine.kt`

### 9.2 Formulation
Detects missed handover triggers by evaluating delta RSRP against registered neighbor cells:
$$\Delta\text{RSRP} = \text{RSRP}_{\text{neighbor}} - \text{RSRP}_{\text{serving}}$$
$$\text{If } \Delta\text{RSRP} \ge +6.0\text{ dB for } t \ge 5.0\text{ s} \implies \text{Flag "Stronger Neighbor: Missing Neighbor Relation or Handover Hysteresis Bias"}$$

---

## 10. GPS Spatial Jitter & Kalman-like Speed Consistency Filter

### 10.1 Purpose & Classification
* **Classification:** Kinematic Validity Heuristic (*Not Machine Learning*).
* **Source Entry Points:**
  * Android: `android/app/src/main/java/com/cellscope/drive/recorder/GpsJitterFilter.kt`
  * Backend: `backend/app/services/parser.py` (`validate_coordinates()`)

### 10.2 Filter Logic
1. **Regional Bounding Box:** Latitudes outside $[20.0, 27.0]$ or Longitudes outside $[88.0, 93.0]$ (for Bangladesh surveys) are tagged `has_valid_gps: false`.
2. **Speed-Displacement Inconsistency:**
   $$\Delta d = \mathcal{H}(\phi_{k-1}, \lambda_{k-1}, \phi_k, \lambda_k)$$
   $$v_{\text{calculated}} = \frac{\Delta d}{\Delta t}$$
   If $v_{\text{calculated}} > 180\text{ km/h}$ and $\Delta d > 0.5\text{ km}$ within $\Delta t < 2\text{ s}$, the fix is identified as a GPS multipath jump and excluded from polyline rendering.
3. **Telemetry Gap:** If $\Delta d > 150\text{ meters}$ between consecutive fixes, a `telemetry_gap` counter is incremented.

---

## 11. Weak Coverage Corridors & Service Holes

### 11.1 Purpose & Classification
* **Classification:** Contiguous Temporal-Spatial Sequence Segmenter (*Not Machine Learning*).
* **Source Entry Points:**
  * Backend: `backend/app/services/analytics.py` (`detect_weak_coverage_segments()`, `detect_service_holes()`)
  * Frontend: `frontend/src/components/EventsAndAnomalies.tsx`

### 11.2 Segment Criteria
* **Weak Coverage Zone:** Sequence of $\ge 3$ consecutive telemetry samples where $\text{RSRP} < -105.0\text{ dBm}$.
* **Critical Service Hole:** Sequence where $\text{RSRP} < -115.0\text{ dBm}$ or physical modem radio state reports `OUT_OF_SERVICE`.
