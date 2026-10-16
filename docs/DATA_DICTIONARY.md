# CellScope: LTE Drive-Test Data Dictionary & Dataset Verification

## 1. Dataset Origin & Verification
* **Source:** Mendeley Data (`https://data.mendeley.com/datasets/n2pvmtyn2j/2`)
* **Title:** *A Pilot LTE Drive-Test Dataset for Handover and Mobility Analysis in Urban Bangladesh* (Version 2.0)
* **Measurement Tool:** Innowireless XCAL-M drive-test logging suite
* **Location:** Dhaka, Bangladesh
* **License:** Creative Commons Attribution 4.0 International (CC BY 4.0)

---

## 2. Directory Hierarchy in `data/raw/`

```text
data/raw/Handover Dataset/Handover Dataset (ver 2)/
├── DRM Files/                     # Binary diagnostic logs (.drm) from XCAL-M
│   ├── DRM Files_Dataset_1/       # 7 DRM recordings
│   ├── DRM Files_Dataset_2/       # 2 DRM recordings
│   └── DRM Files_Dataset_3/       # 3 DRM recordings
├── Event Statistics/              # Layer-3 Mobility event summaries (.CSV)
│   ├── Event Statistics_Dataset_1/# 7 CSVs (Intra LTE-HO & Idle Mode LB)
│   ├── Event Statistics_Dataset_2/# 2 CSVs
│   └── Event Statistics_Dataset_3/# 3 CSVs
├── Measurement Reports/           # Decoded Layer-3 RRC signaling logs (.txt)
│   ├── Measurement Report_Dataset_1/
│   ├── Measurement Report_Dataset_2/
│   └── Measurement Report_Dataset_3/
├── Parent Dataset/                # Raw extracted tabular CSVs before cleaning
│   ├── Dataset_1/                 # 7 CSVs (no GPS coordinates)
│   ├── Dataset_2/                 # 2 CSVs (contains unacquired GPS NaN fixes)
│   └── Dataset_3/                 # 3 CSVs
└── Processed Dataset/             # Preprocessed CSVs with IE conversion & triggers
    ├── Dataset_1_ver_1.csv        # 922 rows (no GPS)
    ├── Dataset_1_ver_2.csv        # 922 rows (no GPS)
    ├── Dataset_2_ver_1.csv        # 592 rows (GPS tagged, has outlier lon 0.116667)
    ├── Dataset_2_ver_2.csv        # 592 rows
    ├── Dataset_3_ver_1.csv        # 640 rows (Recommended MVP benchmark: 100% valid GPS)
    └── Dataset_3_ver_2.csv        # 641 rows (Typo: 'Serving Cell RSRPQ')
```

---

## 3. Real-World Anomalies & Engineering Handling

| Anomaly / Edge Case | Files Affected | Engineering Detection & Handling |
| :--- | :--- | :--- |
| **Missing GPS Coordinates** | `Dataset_1` (all files) | System auto-detects lack of Lat/Lon. Map view provides guidance; RF Analytics and Tabular Grid remain fully functional. |
| **Antenna Glitch Outlier** | `Dataset_2_ver_1.csv` (rows with lon 0.116667) | Validator applies regional bounding box (`BD_LAT_MIN` to `BD_LAT_MAX`, `BD_LON_MIN` to `BD_LON_MAX`) and isolates bad fixes without dropping radio metrics. |
| **Unacquired GPS Fixes (`NaN`)** | `Parent Dataset/Dataset_2/File 1.csv` | Validator flags rows as `has_valid_gps: false` and excludes them from map layers while preserving tabular data. |
| **Column Header Whitespace** | Multiple CSVs (e.g. `'Timestamp        '`) | Parser cleans all headers via regex trimming and case-insensitive canonical aliasing. |
| **Column Header Typos** | `Dataset_3_ver_2.csv` (`Serving Cell RSRPQ`) | Alias dictionary maps `Serving Cell RSRPQ` to canonical `rsrq`. |
| **3GPP Information Element (IE) Values** | All Processed & Parent files | Converted to physical dBm and dB via 3GPP TS 36.133 formulas. |

---

## 4. 3GPP TS 36.133 Radio Conversion Formulas

### RSRP (Reference Signal Received Power)
* **Stored Format:** 3GPP Information Element integer index $0 \le \text{IE} \le 97$.
* **Conversion Formula:**
  $$\text{RSRP}_{\text{dBm}} = \text{IE} - 140 \text{ dBm}$$
* **Coverage Standards:**
  * **Excellent:** $\ge -80\text{ dBm}$ (High throughput, near tower)
  * **Good:** $-80\text{ dBm}$ to $-90\text{ dBm}$ (Typical good coverage)
  * **Fair:** $-90\text{ dBm}$ to $-100\text{ dBm}$ (Indoor / moderate distance)
  * **Poor:** $< -100\text{ dBm}$ (Cell edge / coverage hole risk)

### RSRQ (Reference Signal Received Quality)
* **Stored Format:** 3GPP Information Element integer index $0 \le \text{IE} \le 34$.
* **Conversion Formula:**
  $$\text{RSRQ}_{\text{dB}} = (\text{IE} \times 0.5) - 19.5 \text{ dB}$$
* **Quality Standards:**
  * **Excellent:** $\ge -10\text{ dB}$
  * **Good:** $-10\text{ dB}$ to $-14\text{ dB}$
  * **Fair:** $-14\text{ dB}$ to $-17\text{ dB}$
  * **Poor:** $< -17\text{ dB}$ (Interference / high load)

### CINR / SINR (Carrier to Interference plus Noise Ratio)
* **Stored Format:** Physical $\text{dB}$ values ($-30\text{ dB}$ to $+35\text{ dB}$).
* **RF Standards:**
  * **Excellent:** $\ge 20\text{ dB}$
  * **Good:** $13\text{ dB}$ to $20\text{ dB}$
  * **Fair:** $0\text{ dB}$ to $13\text{ dB}$
  * **Poor:** $< 0\text{ dB}$ (Noise/Interference dominated)
