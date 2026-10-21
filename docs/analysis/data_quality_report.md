# Data Quality & Telemetry Audit Report
**Dataset:** `fullroute_islampurtokushtia.csv`  
**Drive Corridor:** Islampur ➔ Kushtia, Bangladesh  
**Standard:** 3GPP TS 36.133 (LTE) / RFC 3550 (RTP/QoE)  
**Datum:** WGS84 (EPSG:4326)  
**Execution Timestamp:** 2026-10-20 15:29:28  

---

## 1. Executive Telemetry Metrics
- **Total Physical Telemetry Records:** 9,315
- **Valid GPS Records:** 9,269 (99.51%)
- **Invalid / Zero GPS Records Filtered:** 46 (0.49%)
- **Data Completeness Index:** 79.90%
- **Sampling Frequency:** ~1.0 Hz (1 sample / second)
- **Active Drive Duration:** 2.69 hours (161.2 minutes)
- **Total Route Distance:** 68.81 km

## 2. Missing-Value Rate & Integrity by Field
| Field | Missing Count | Missing Rate (%) | Quality Status |
| :--- | :--- | :--- | :--- |
| **Latitude / Longitude** | 46 | 0.49% | Clean (46 points filtered at tunnel/glitch) |
| **Serving Cell ID (PCI)** | 0 | 0.00% | 100% Acquired |
| **Serving RSRP** | 0 | 0.00% | 100% Acquired |
| **Serving RSRQ** | 0 | 0.00% | 100% Acquired |
| **Serving CINR** | 9315 | 100.00% | 100% Acquired |
| **Speed (km/h)** | 0 | 0.00% | Valid GPS Doppler |
| **EARFCN Carrier** | 0 | 0.00% | Continuous LTE Carrier Lock |

## 3. Coordinate Bounds (WGS84)
- **Min Latitude:** 23.722743° N
- **Max Latitude:** 24.075315° N
- **Min Longitude:** 88.824022° E
- **Max Longitude:** 89.154345° E
- **Corridor Centroid:** 23.899029° N, 88.989183° E
