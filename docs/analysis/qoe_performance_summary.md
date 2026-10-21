# Active Network QoE & Speed Performance Summary
**Test Protocol:** Multi-Threaded HTTP Streaming Throughput + Multi-Probe RTT Latency  
**Total Completed Test Sequences:** 41 / 41  
**Data Integrity:** 100% Measured via Active Probe Engine  

---

## 1. Key Performance Indicators (KPIs)
- **Downlink Throughput (Mean):** 16.79 Mbps
- **Downlink Throughput (Median):** 14.64 Mbps
- **Downlink Peak:** 33.29 Mbps
- **Downlink Floor (Min):** 1.04 Mbps
- **Uplink Throughput (Mean):** 4.38 Mbps
- **Uplink Throughput (Median):** 4.54 Mbps
- **Uplink Peak:** 6.47 Mbps
- **Median Ping Latency (RTT):** 81.0 ms
- **90th Percentile Latency (p90):** 93.0 ms
- **95th Percentile Latency (p95):** 98.0 ms
- **Average Jitter (RFC 3550):** 74.36 ms
- **Average Probe Loss:** 0.00%

## 2. Hourly Traffic-Performance Profile
| Hour Window | Test Count | Avg Download | Min Download | Max Download | Avg Upload | Avg Ping RTT |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 05:00 - 05:59 | 4 | 5.0 Mbps | 1.62 Mbps | 7.57 Mbps | 3.96 Mbps | 88.0 ms |
| 06:00 - 06:59 | 14 | 16.7 Mbps | 1.04 Mbps | 33.29 Mbps | 4.1 Mbps | 81.07 ms |
| 07:00 - 07:59 | 22 | 19.52 Mbps | 2.05 Mbps | 33.03 Mbps | 4.56 Mbps | 112.18 ms |
| 08:00 - 08:59 | 1 | 5.24 Mbps | 5.24 Mbps | 5.24 Mbps | 5.61 Mbps | 88.0 ms |

## 3. Radio Quality vs. Application Throughput Correlation
- **High Throughput (> 15 Mbps):** Consistently observed in sectors with RSRP ≥ -85 dBm and CINR ≥ 14 dB.
- **Congestion Anomaly Identification:** In two suburban Kushtia segments, download throughput dropped to < 1.5 Mbps despite clean radio RF (RSRP -82 dBm, CINR 16 dB), indicating backhaul congestion or downlink PRB scheduler saturation on the serving sector.
