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

By definition, carrier $\text{RSSI}$ is measured across OFDM symbol 0 carrying Cell-Specific Reference Signals (CRS). In each Resource Block (12 subcarriers):
- 2 Resource Elements carry CRS with power $P_{\text{CRS}} = \text{RSRP}$.
- 10 Resource Elements carry PDSCH user data with fractional traffic load $\eta \in [0.0, 1.0]$. Assuming equal power spectral density allocation ($P_{\text{data}} \approx \text{RSRP}$), the serving signal power per PRB is $(2 + 10\eta)\cdot \text{RSRP}$.
- All 12 Resource Elements carry thermal noise and external co-channel interference $I_{\text{ext}} + N_0$.

The total carrier power per PRB ($P_{\text{RB}} = \text{RSSI} / N$) is:
$$P_{\text{RB}} = \frac{\text{RSSI}}{N} = (2 + 10\eta)\cdot\text{RSRP}_{\text{linear}} + 12 \cdot (I_{\text{ext}} + N_0)$$

Dividing both sides by $\text{RSRP}_{\text{linear}}$:
$$\frac{1}{\text{RSRQ}_{\text{linear}}} = \frac{\text{RSSI}}{N \cdot \text{RSRP}_{\text{linear}}} = (2 + 10\eta) + \frac{12 \cdot (I_{\text{ext}} + N_0)}{\text{RSRP}_{\text{linear}}}$$

Since reference signal $\text{SINR}_{\text{linear}} = \frac{\text{RSRP}_{\text{linear}}}{I_{\text{ext}} + N_0}$:
$$\frac{1}{\text{RSRQ}_{\text{linear}}} = (2 + 10\eta) + \frac{12}{\text{SINR}_{\text{linear}}}$$
$$\frac{12}{\text{SINR}_{\text{linear}}} = \frac{1}{\text{RSRQ}_{\text{linear}}} - (2 + 10\eta)$$
$$\text{SINR}_{\text{linear}} = \frac{12}{\frac{1}{\text{RSRQ}_{\text{linear}}} - (2 + 10\eta)}$$

#### Bandwidth Independence in SINR Estimation
Notice that channel bandwidth $N$ cancels out identically in the SINR formulation ($RSRQ = RSRP / P_{\text{RB}}$). The number of resource blocks $N$ only scales wideband carrier RSSI ($\text{RSSI}_{\text{dBm}} = \text{RSRP}_{\text{dBm}} + 10\log_{10}(N) - \text{RSRQ}_{\text{dB}}$). Unknown or unexposed channel bandwidth introduces **zero mathematical error** into the estimated SINR value itself.

#### The Three Operational Regimes

1. **Regime 1: Nominal Loaded Regime ($\text{RSRQ} \le -8.45\text{ dB}$, $\frac{1}{\text{RSRQ}} \ge 7.0$)**
   - The cell traffic load can accommodate the nominal assumption $\eta = 0.5$ (50% traffic load).
   - Offset: $\text{offset} = 2.0 + 10.0(0.5) = 7.0$.
   - $\text{denom} = \frac{1}{\text{RSRQ}_{\text{linear}}} - 7.0 > 0$.
   - $\text{SINR}_{\text{dB}} = 10 \log_{10}\left(\frac{12.0}{\text{denom}}\right)$.
   - Examples:
     - $\text{RSRQ} = -11.0\text{ dB} \implies \text{SINR} = 3.3\text{ dB}$ (Confidence: `CALIBRATED_ESTIMATE`).
     - $\text{RSRQ} = -10.0\text{ dB} \implies \text{SINR} = 6.0\text{ dB}$ (Confidence: `CALIBRATED_ESTIMATE`).

2. **Regime 2: Bounded Light-Load Regime ($-8.45\text{ dB} < \text{RSRQ} < -3.01\text{ dB}$)**
   - When $\text{RSRQ} > -8.45\text{ dB}$, $\frac{1}{\text{RSRQ}} < 7.0$. A 50% traffic load ($\eta = 0.5$) is physically impossible because even with zero noise and zero interference ($I_{\text{ext}} + N_0 = 0$), maximum achievable RSRQ at 50% load is $1/7 = -8.45\text{ dB}$.
   - The instantaneous cell traffic load is physically bounded by the observed RSRQ:
     $$\eta \in [0.0, \eta_{\text{max}}], \quad \text{where } \eta_{\text{max}} = \frac{1/\text{RSRQ}_{\text{linear}} - 2.0}{10.0}$$
   - Under the principle of maximum entropy (uniform prior $\eta \sim U[0, \eta_{\text{max}}]$ for unmeasured eNodeB MAC scheduler load), the expected load is $\bar{\eta} = \frac{\eta_{\text{max}}}{2}$.
   - Substituting $\bar{\eta}$ into the denominator:
     $$\text{offset} = 2.0 + 10.0\bar{\eta} = 1.0 + \frac{1}{2 \cdot \text{RSRQ}_{\text{linear}}}$$
     $$\text{denom} = \frac{1}{\text{RSRQ}_{\text{linear}}} - \text{offset} = \frac{1 - 2\cdot\text{RSRQ}_{\text{linear}}}{2 \cdot \text{RSRQ}_{\text{linear}}}$$
     $$\text{SINR}_{\text{linear}} = \frac{24 \cdot \text{RSRQ}_{\text{linear}}}{1 - 2\cdot\text{RSRQ}_{\text{linear}}}$$
   - Physical bounds:
     - Minimum possible SINR occurs if cell is completely idle ($\eta = 0 \implies \text{offset} = 2.0 \implies \text{SINR}_{\text{min}} = \frac{12}{1/\text{RSRQ} - 2}$).
     - Maximum possible SINR occurs as $\eta \to \eta_{\text{max}}$ (noise-free limit, bounded by UE dynamic range at $+30.0\text{ dB}$).
   - Examples:
     - $\text{RSRQ} = -8.0\text{ dB} \implies \eta_{\text{max}} = 43.1\%, \bar{\eta} = 21.5\% \implies \mathbf{\text{SINR} = 7.5\text{ dB}}$ (Physical bounds: $[4.4\text{ dB}, 30.0\text{ dB}]$).
     - $\text{RSRQ} = -7.0\text{ dB} \implies \eta_{\text{max}} = 30.1\%, \bar{\eta} = 15.1\% \implies \mathbf{\text{SINR} = 9.0\text{ dB}}$ (Physical bounds: $[6.0\text{ dB}, 30.0\text{ dB}]$).
   - Method label: `3GPP_TS_36_214_BOUNDED_LOAD_MODEL`.
   - Confidence: `BOUNDED_LOAD_ESTIMATE`.

3. **Regime 3: Asymptotic Saturation Ceiling ($\text{RSRQ} \ge -3.01\text{ dB}$, e.g. $-3.0\text{ dB}$)**
   - In 3GPP TS 36.133 Table 9.1.7-1, RSRQ is reported up to Index 34 ($\text{RSRQ} \ge -3.0\text{ dB}$).
   - At $\text{RSRQ} = -3.0\text{ dB}$, $\frac{1}{\text{RSRQ}} \approx 1.995 \le 2.0$. Even at $\eta = 0$, $2\cdot\text{RSRP}$ accounts for the entire carrier power.
   - At this ceiling, external interference and noise fall below receiver detection resolution. Multiple physical SINR values ($>25\text{ dB}$) map to this single saturated value.
   - Mathematical inversion is indeterminate. The system returns `sinrDb = null`, `source = "UNAVAILABLE"`, and provides an explicit qualitative engineering diagnostic ("Clean RF channel conditions indicated by strong RSRQ (-3 dB); model-based SINR estimation unavailable at 3GPP saturation ceiling"). Never fabricates $30.0\text{ dB}$.

The implied total carrier $\text{RSSI}$ in physical $\text{dBm}$ is calculated as:
$$\text{RSSI}_{\text{dBm}} = \text{RSRP}_{\text{dBm}} + 10\log_{10}(N) - \text{RSRQ}_{\text{dB}}$$

### 1.3 Provenance Hierarchy & Sentinel Rejection
1. **`MEASURED`:**
   - Valid modem reading from `CellSignalStrengthLte.rssnr` (or 5G `CellSignalStrengthNr.ssSinr`).
   - Secondary check: In Android 10+ (API 29+), inspects `TelephonyManager.signalStrength.getCellSignalStrengths(CellSignalStrengthLte::class.java)` if `allCellInfo` leaves `rssnr` unpopulated.
   - Android sentinels (`99`, `0x7FFFFFFF`, `-1`) are strictly rejected.
   - **Zero Sentinel Protection:** Android RILs frequently return raw `0` when SINR is uninitialized or unsupported. Raw `0` is **rejected** as measured unless the session has already observed genuine non-zero readings (`hasSeenNonZeroRssnr == true`).
2. **`ESTIMATED`:**
   - Evaluated strictly for 4G LTE when inputs satisfy standard 3GPP validity boundaries: $-140.0\text{ dBm} \le \text{RSRP} \le -40.0\text{ dBm}$ and $-25.0\text{ dB} \le \text{RSRQ} < -3.01\text{ dB}$.
   - Method label: `3GPP_TS_36_214_RSRQ_LOAD_MODEL` ($\text{RSRQ} \le -8.45\text{ dB}$) or `3GPP_TS_36_214_BOUNDED_LOAD_MODEL` ($-8.45\text{ dB} < \text{RSRQ} < -3.01\text{ dB}$).
   - Confidence: `CALIBRATED_ESTIMATE` or `BOUNDED_LOAD_ESTIMATE` with documented uncertainty and load bounds.
3. **`UNAVAILABLE`:**
   - Returned when inputs are missing, out of physical bounds, at the 3GPP index-34 saturation ceiling, or when evaluating 5G NR (3GPP TS 38.215 NR SS-RSRQ definitions do not map to LTE carrier RSSI equations). Never defaults to `0.0`.

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

---

## 12. Web-Based Radio Propagation & Mobility Analytics Engine

### 12.1 Log-Normal Shadowing & Empirical Baseline Model (`analyzeShadowing`)
* **Classification:** Spatial Moving Average & Empirical Regression Residual Estimator (*Not Machine Learning*).
* **Source Entry Points:**
  * Frontend: `frontend/src/utils/propagationAnalytics.ts` (`analyzeShadowing()`)
  * Frontend UI: `frontend/src/components/RadioPropagationPanel.tsx` (Sub-tab: `Shadowing Analysis`)
* **Mathematical Formulation:**
  1. **Geodesic Distance:** Consecutive Haversine distance along vehicle route:
     $$d_k = d_{k-1} + \mathcal{H}(\phi_{k-1}, \lambda_{k-1}, \phi_k, \lambda_k)$$
  2. **Spatial Moving Average Window ($W$):**
     $$\overline{\text{RSRP}}(d_k) = \frac{1}{2M + 1} \sum_{j=-M}^{M} \text{RSRP}(d_{k+j}) \quad (W = 2M + 1 \in \{5, 10, 20\})$$
  3. **Empirical Baseline Path-Loss Regression:**
     $$\text{RSRP}_{\text{trend}}(d) = \beta_0 + \beta_1 d$$
     where $\beta_1 = \frac{n \sum d_i R_i - \sum d_i \sum R_i}{n \sum d_i^2 - (\sum d_i)^2}$, $\beta_0 = \frac{\sum R_i - \beta_1 \sum d_i}{n}$.
  4. **Residual Variation:**
     $$\text{Residual}_i = \text{RSRP}_i - \overline{\text{RSRP}}_i$$
  5. **Log-Normal Shadowing Spread ($\sigma_\psi$):**
     $$\sigma_\psi = \sqrt{\frac{1}{n-1} \sum_{i=1}^n (\text{Residual}_i - \mu_{\text{res}})^2}$$
  6. **Candidate Shadowing Zones:** Contiguous runs of $\ge 3$ consecutive samples where $\text{Residual}_i \le -6.0\text{ dB}$, isolating localized structural blockage from broad distance decay.
* **Scientific Disclosures:** Exact eNodeB antenna coordinates and transmit EIRP are not broadcast over commercial LTE air interfaces; empirical regression is utilized rather than claiming unverified free-space path loss.

### 12.2 Macroscopic Signal Fluctuation & Sampling Rate Audit (`analyzeSignalFluctuation`)
* **Classification:** Statistical Fluctuation & Telemetry Periodicity Validator (*Not Machine Learning*).
* **Source Entry Points:**
  * Frontend: `frontend/src/utils/propagationAnalytics.ts` (`analyzeSignalFluctuation()`)
  * Frontend UI: `frontend/src/components/RadioPropagationPanel.tsx` (Sub-tab: `Signal Fluctuation`)
* **Mathematical Formulation:**
  1. **Consecutive Signal Delta:**
     $$\Delta\text{RSRP}_k = \text{RSRP}_k - \text{RSRP}_{k-1} \quad (\text{dB})$$
  2. **Rolling Standard Deviation ($\sigma_k$ over window $W=10$):**
     $$\mu_k = \frac{1}{W} \sum_{j=0}^{W-1} \text{RSRP}_{k-j}, \quad \sigma_k = \sqrt{\frac{1}{W-1}\sum_{j=0}^{W-1} (\text{RSRP}_{k-j} - \mu_k)^2}$$
  3. **Telemetry Periodicity:** $\Delta t_k = t_k - t_{k-1}$ (in seconds).
* **Sampling Rate Validation Disclosure:** Standard Android TelephonyManager polling (~1 Hz / $\Delta t \approx 1.0\text{ s}$) corresponds to $\approx 8 - 15\text{ m}$ of vehicle travel per fix. Because RF carrier wavelength at 1.8 GHz is $\lambda = c / f = 0.166\text{ m}$ (16.6 cm), a 1-second sample spans $50 - 100\lambda$, aliasing microscopic constructive/destructive Rayleigh fading nulls. The engine strictly characterizes **macroscopic signal fluctuations**, not fast fading.

### 12.3 Exploratory Multipath Spatial Rate of Change & Revisit Clusters (`analyzeMultipathPropagation`)
* **Classification:** Spatial Gradient & Revisit Distance Cluster Estimator (*Not Machine Learning*).
* **Source Entry Points:**
  * Frontend: `frontend/src/utils/propagationAnalytics.ts` (`analyzeMultipathPropagation()`)
  * Frontend UI: `frontend/src/components/RadioPropagationPanel.tsx` (Sub-tab: `Multipath Propagation`)
* **Mathematical Formulation:**
  1. **Spatial Gradient:**
     $$\nabla_s = \frac{|\text{RSRP}_k - \text{RSRP}_{k-1}|}{\Delta d_k} \quad (\text{dB/meter for } \Delta d_k \ge 1.5\text{ m})$$
     High spatial variation flagged when $\nabla_s \ge 0.4\text{ dB/m}$ within the same serving cell.
  2. **Revisited Location Clustering:** Identifies pairs of fixes $(i, j)$ separated by $\Delta t \ge 15\text{ s}$ but located within geographic radius $R \le 20\text{ m}$:
     $$\mathcal{H}(\phi_i, \lambda_i, \phi_j, \lambda_j) \le 20\text{ m} \implies \Delta\text{RSRP}_{\text{revisit}} = |\text{RSRP}_j - \text{RSRP}_i|$$
* **Scientific Disclosures:** Commercial UE basebands do not output Channel Impulse Responses (CIR), Power Delay Profiles (PDP), or Angle of Arrival (AoA). Analysis is explicitly designated as exploratory macroscopic spatial variation.

### 12.4 Ping-Pong Handover Oscillation Detection (`analyzePingPongHandover`)
* **Classification:** 3GPP TS 36.331 Event A3 Mobility Loop State Machine (*Not Machine Learning*).
* **Source Entry Points:**
  * Frontend: `frontend/src/utils/propagationAnalytics.ts` (`analyzePingPongHandover()`)
  * Frontend UI: `frontend/src/components/RadioPropagationPanel.tsx` (Sub-tab: `Ping-Pong Handover`)
* **Mathematical Formulation:**
  1. **Chronological Segment Decomposition:** Partition drive sequence into contiguous serving cell runs $S_1, S_2, \dots, S_m$, where each segment $S_k$ has cell ID $C_k$, duration $T_k$, start time $t_{s, k}$, and end time $t_{e, k}$.
  2. **Ping-Pong Loop Pattern:** Detects sequences $S_k \rightarrow S_{k+1} \rightarrow S_{k+2}$ where:
     $$C_k = C_{k+2} \quad \text{and} \quad C_{k+1} \neq C_k$$
     $$\text{Total Loop Elapsed Time: } \Delta t_{\text{loop}} = t_{s, k+2} - t_{s, k+1} \le T_{\text{window}} \quad (T_{\text{window}} \in \{5, 10, 15, 30, 60\}\text{ s})$$
  3. **Intermediate Dwell Time:** $\Delta t_{\text{dwell}} = T_{k+1}$.
  4. **Classification:**
     - `CONFIRMED_PING_PONG`: Constituent transition is accompanied by an explicit network handover trigger (`is_handover_trigger: true` or `event_type: 'CONFIRMED_HANDOVER'`).
     - `INFERRED_PING_PONG_OSCILLATION`: Pattern observed from periodic serving cell snapshots without explicit RRC handover confirmation.
  5. **Cell-Pair Transition Frequency Matrix:** Aggregates forward ($C_A \rightarrow C_B$) and reverse ($C_B \rightarrow C_A$) switches for all cell pairs.

