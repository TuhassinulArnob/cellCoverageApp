import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent
DATA_RAW_DIR = BASE_DIR / "data" / "raw"

# Bangladesh Geographic Bounding Box for GPS Sanity Checks
BD_LAT_MIN = 20.5
BD_LAT_MAX = 26.8
BD_LON_MIN = 88.0
BD_LON_MAX = 92.8

# RF Quality Classification Thresholds (3GPP TS 36.133)
# RSRP (dBm)
RSRP_EXCELLENT = -80.0
RSRP_GOOD = -90.0
RSRP_FAIR = -100.0
# RSRP < -100 is Poor, < -110 is Very Poor / Edge

# RSRQ (dB)
RSRQ_EXCELLENT = -10.0
RSRQ_GOOD = -14.0
RSRQ_FAIR = -17.0
# RSRQ < -17 is Poor

# SINR / CINR (dB)
CINR_EXCELLENT = 20.0
CINR_GOOD = 13.0
CINR_FAIR = 0.0
# CINR < 0 is Poor (Interference dominated)
