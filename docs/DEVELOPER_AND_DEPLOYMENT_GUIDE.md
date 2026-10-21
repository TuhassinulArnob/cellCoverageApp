# CellScope-5G: Developer & Deployment Guide

**Document Version:** 3.0.0 (Production)
**Target Environments:** Windows 10/11, Ubuntu 22.04 LTS, macOS (Apple Silicon / Intel)
**Author:** Sarkar Tuhassinul Arnob (RAN & Lead Engineer)

---

## 1. Prerequisites & Environment Matrix

Ensure your development workstation meets the minimum toolchain requirements:

| Component | Minimum Version | Recommended Version | Purpose |
| :--- | :--- | :--- | :--- |
| **Java Development Kit (JDK)** | OpenJDK 17 | OpenJDK 21 or Android Studio JBR | Android Gradle compilation |
| **Android SDK Tools** | API 26 (Android 8.0) | API 35 (Android 15), Build-Tools 35.0.0 | Android target compilation |
| **Python** | Python 3.10 | Python 3.11.x or 3.12.x | FastAPI backend microservice |
| **Node.js & npm** | Node.js 18.x | Node.js 20.x or 22.x LTS | React / Vite web dashboard |
| **Android Device / Emulator** | Android 8.0+ | Physical LTE/5G Device with active SIM | Telephony live data testing |

---

## 2. Setting Up the Android Application

### 2.1 Setting the JDK Environment Variable
In PowerShell (Windows):
```powershell
# Set JAVA_HOME to Android Studio's bundled JetBrains Runtime (JBR)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

In bash (Linux / macOS):
```bash
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
```

### 2.2 Running Android Unit Tests
CellScope includes 52 test suites containing 272 automated unit tests across telephony, RF math, SINR provenance, handover classification, and Room DAOs.

In PowerShell (Windows):
```powershell
cd android
.\gradlew.bat testDebugUnitTest
```

In bash (Linux / macOS):
```bash
cd android
./gradlew testDebugUnitTest
```
*Expected Result:* `BUILD SUCCESSFUL` (272 passed, 0 failures).

### 2.3 Assembling the Debug APK
```powershell
cd android
.\gradlew.bat assembleDebug
```
*Output Artifact:*
The compiled debug APK is generated at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```
*File Size:* ~50.01 MB (`52,443,514 bytes`).

### 2.4 Installing the APK on a Device
1. Enable **Developer Options** and **USB Debugging** on your Android handset.
2. Connect your device via USB and verify ADB connectivity:
   ```bash
   adb devices
   ```
3. Install the APK:
   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```
4. Launch **CellScope 5G** and grant Location, Phone, and Notification permissions.

---

## 3. Setting Up the FastAPI Analytics Backend

### 3.1 Python Virtual Environment Setup
From the repository root:

In Windows (PowerShell):
```powershell
python -m venv venv
.\venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r backend/requirements.txt
```

In Linux / macOS (bash):
```bash
python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install -r backend/requirements.txt
```

### 3.2 Running Backend Test Suite (Pytest)
```powershell
# Ensure PYTHONPATH includes project root
$env:PYTHONPATH = "."
pytest backend/tests
```
*Expected Result:* `43 passed` in ~10 seconds.

### 3.3 Starting the Backend Development Server
```powershell
$env:PYTHONPATH = "."
uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --reload
```
* Interactive Swagger API Docs: `http://localhost:8000/docs`
* ReDoc Specification: `http://localhost:8000/redoc`
* API Root Health: `http://localhost:8000/api/health`

---

## 4. Setting Up the React / Vite Web Dashboard

### 4.1 Installing Dependencies
From the repository root:
```bash
cd frontend
npm install
```

### 4.2 Running Static Code Quality Linter
CellScope uses Oxlint for ultra-fast, high-rigor static code analysis:
```bash
# If running in Windows PowerShell, execute via cmd /c if script execution is restricted
cmd /c "npm --prefix frontend run lint"
```
*Expected Result:* `0 warnings and 0 errors` across all frontend files.

### 4.3 Starting the Frontend Development Server
```bash
cd frontend
npm run dev
```
The application will launch at `http://localhost:5173/` with hot module replacement (HMR).

### 4.4 Compiling the Production Build
```bash
cmd /c "npm --prefix frontend run build"
```
*Expected Result:*
```
dist/index.html                   1.51 kB │ gzip:   0.79 kB
dist/assets/index-*.css          79.66 kB │ gzip:  17.39 kB
dist/assets/index-*.js          583.56 kB │ gzip: 152.51 kB
✓ built in ~1.0s
```

---

## 5. End-to-End Survey Verification Workflow

1. **Record Survey on Handset:** Start a survey recording in the Android app. Drive or walk along a test route. Stop recording and tap **Export CSV**.
2. **Ingest into Web Dashboard:** Open `http://localhost:5173/`. Drag-and-drop the exported `.csv` into the top navbar Ingestion Hub or use the **Select Dataset** dropdown.
3. **Analyze Multi-Layer GIS Map:**
   - Toggle Metric Layers (`RSRP`, `RSRQ`, `CINR/SINR`, `PCI Mod-3`, `TA`).
   - Observe Solid circle markers (`MEASURED` SINR) vs. Dashed circle markers (`3GPP ESTIMATED` SINR).
   - Inspect Handover transition diamonds (`Confirmed Handover`, `Intra-Frequency`, `Inter-Frequency`, `Inter-RAT`).
4. **Inspect Active QoE Results:** Switch to the **Network QoE** tab to evaluate download/upload throughput graphs, RTT ping trends, and RFC 3550 jitter.
5. **Export Technical Audit Report:** Navigate to the **Report Exporter** tab and click **Export PDF / Print View** for an executive-ready A4 documentation deliverable.

---

## 6. Troubleshooting & Diagnostics Matrix

| Symptom / Error | Root Cause | Verified Resolution |
| :--- | :--- | :--- |
| `Execution of scripts is disabled on this system` (PowerShell) | Windows PowerShell ExecutionPolicy restriction | Prefix npm/batch commands with `cmd /c` (e.g., `cmd /c "npm --prefix frontend run build"`). |
| `Kotlin does not yet support 25 JDK target` | JDK version is newer than Kotlin 2.0 compiler target | Harmless warning; Gradle automatically falls back to JVM target 24/21 without impact. |
| `Using httpx with starlette.testclient is deprecated` | FastAPI / Starlette upstream deprecation | Harmless pytest warning; tests pass with exit code 0. |
| Map tiles render blank / white | Missing internet connection or tile host blocked | Leaflet is configured with public OpenStreetMap tiles. Ensure workstation or browser has outbound internet connectivity. |
| Telephony returns 0.0 dB SINR constantly | Device modem does not provide raw baseband RSSNR | CellScope v3.0 automatically detects uninitialized zeros and falls back to 3GPP TS 36.214 calibrated estimates. |
| All handovers labeled "CONFIRMED HANDOVER" | Legacy promotion of TAC change or vehicle motion | Fixed in v3.0: HandoverAnalyzer preserves exact normalized event classifications (`INTRA_FREQ`, `INTER_FREQ`, `INTER_RAT`). |
