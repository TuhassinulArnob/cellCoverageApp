# Mobility, Handover Intelligence & RF Anomaly Diagnostics
**Total Mobile Cell Transitions:** 246  
**Intra-Frequency Transitions:** 80  
**Inter-Frequency Transitions:** 158  
**Inter-RAT Transitions:** 0  
**Confirmed Sector Handovers:** 1  
**Detected Ping-Pong Oscillations:** 50  
**Weak Coverage Corridors (< -105 dBm):** 11  
**Critical Service Holes (< -115 dBm):** 0  

---

## 1. Mobility Classification Breakdown
| Mobility Category | Count | Representation on UI | Evidence Criteria |
| :--- | :--- | :--- | :--- |
| **Confirmed Handover** | 1 | Green Diamond (`#10b981`) | Inter-TAC boundary crossing or verified trigger |
| **Intra-Frequency** | 80 | Cyan Diamond (`#06b6d4`) | Serving switch on identical EARFCN carrier |
| **Inter-Frequency** | 158 | Blue Diamond (`#3b82f6`) | Serving switch between different EARFCN carriers |
| **Inter-RAT** | 0 | Amber Diamond (`#f59e0b`) | Change in radio access technology (LTE ➔ NR) |
| **Ping-Pong Oscillation** | 50 | Red Diamond (`#ef4444`) | Re-selection to previous cell within 10 fixes |

## 2. Weak Coverage Corridors & Shadow Fading Segments
- **Segment #1:** 78 samples (Mean RSRP: -111.7 dBm, Min: -113.0 dBm) at PCI 228. Coordinates: (24.0750, 88.8240) ➔ (24.0742, 88.8288). Reason: Cell edge attenuation or obstruction
- **Segment #2:** 77 samples (Mean RSRP: -108.8 dBm, Min: -113.0 dBm) at PCI 192. Coordinates: (24.0728, 88.8286) ➔ (24.0678, 88.8280). Reason: Cell edge attenuation or obstruction
- **Segment #3:** 58 samples (Mean RSRP: -107.0 dBm, Min: -108.0 dBm) at PCI 231. Coordinates: (24.0430, 88.8479) ➔ (24.0406, 88.8539). Reason: Cell edge attenuation or obstruction
- **Segment #4:** 41 samples (Mean RSRP: -109.0 dBm, Min: -110.0 dBm) at PCI 40. Coordinates: (24.0182, 89.0072) ➔ (24.0189, 89.0104). Reason: Cell edge attenuation or obstruction
- **Segment #5:** 36 samples (Mean RSRP: -107.6 dBm, Min: -110.0 dBm) at PCI 224. Coordinates: (23.9904, 89.0199) ➔ (23.9846, 89.0197). Reason: Cell edge attenuation or obstruction
- **Segment #6:** 20 samples (Mean RSRP: -110.0 dBm, Min: -110.0 dBm) at PCI 347. Coordinates: (23.9777, 89.0237) ➔ (23.9764, 89.0250). Reason: Cell edge attenuation or obstruction
- **Segment #7:** 20 samples (Mean RSRP: -111.0 dBm, Min: -112.0 dBm) at PCI 345. Coordinates: (23.9652, 89.0448) ➔ (23.9647, 89.0477). Reason: Cell edge attenuation or obstruction
- **Segment #8:** 7 samples (Mean RSRP: -107.0 dBm, Min: -107.0 dBm) at PCI 389. Coordinates: (23.9636, 89.0553) ➔ (23.9637, 89.0564). Reason: Cell edge attenuation or obstruction
- **Segment #9:** 5 samples (Mean RSRP: -108.4 dBm, Min: -112.0 dBm) at PCI 303. Coordinates: (23.8162, 89.1066) ➔ (23.8156, 89.1067). Reason: Cell edge attenuation or obstruction
- **Segment #10:** 16 samples (Mean RSRP: -107.0 dBm, Min: -107.0 dBm) at PCI 113. Coordinates: (23.7599, 89.1206) ➔ (23.7586, 89.1213). Reason: Cell edge attenuation or obstruction
