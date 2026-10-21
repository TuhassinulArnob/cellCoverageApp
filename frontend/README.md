# CellScope-5G: Web GIS & Engineering Analytics Dashboard

**Subsystem:** Web Analytics Dashboard
**Framework & Toolchain:** React 19, TypeScript, Vite 8, Tailwind CSS v4
**Mapping Engine:** Leaflet 1.9 with OpenStreetMap Raster Layers & Tile Filters
**Icons & Visualizations:** Lucide React, Chart.js
**Linter & Code Quality:** Oxlint 1.81

---

## 1. Overview & Objectives

The **CellScope-5G Web Dashboard** is a high-performance single-page application (SPA) designed for desktop, laptop, and NOC (Network Operations Center) environments. It enables RAN engineers to visualize, filter, and inspect complex cellular drive-test surveys, evaluate multi-carrier frequency usage, detect co-channel CRS collisions, trace handover transitions, and generate publication-grade PDF technical reports.

---

## 2. Directory Structure

```
frontend/src/
├── App.tsx                      # Root application component & filter state coordinator
├── main.tsx                     # React DOM entry point
├── index.css                    # Tailwind CSS imports & Leaflet map tile filter classes
├── components/                  # Domain-specific UI panels & GIS components
│   ├── Navbar.tsx               # Top branding, dataset preset catalog & theme toggle
│   ├── MapView.tsx              # Multi-layer GIS map, popups & point inspector
│   ├── SummaryStats.tsx         # Executive KPI cards, 3GPP distributions & CRS Mod-3
│   ├── RfAnalyticsPanel.tsx     # RF charts, carrier frequency table & signal bands
│   ├── EventsAndAnomalies.tsx   # Normalized cell transitions & anomaly alerts
│   ├── NetworkPerformancePanel.tsx # Active QoE throughput, latency, jitter & loss
│   ├── DataQualityPanel.tsx     # Missing coordinates, telemetry gaps & provenance
│   ├── MeasurementTable.tsx     # Searchable 3GPP tabular grid with CSV export
│   ├── ReportExporter.tsx       # Printable A4 executive engineering audit report
│   └── FilterDrawer.tsx         # Slide-over multi-parameter telecom filter drawer
├── services/
│   └── api.ts                   # Asynchronous Axios/Fetch client connecting to FastAPI
├── types/
│   └── cellscope.ts             # TypeScript definitions matching backend Pydantic schemas
└── utils/
    └── telecom.ts               # 3GPP TS 36.101/38.101 formulas, EARFCN raster, CRS Mod-3
```

---

## 3. Key Features & Visual Capabilities

### 3.1 Multi-Layer GIS Map (`components/MapView.tsx`)
* **Metric Color Scales:** Renders physical radio samples with standardized telecom color coding for `RSRP (dBm)`, `RSRQ (dB)`, `CINR / SINR (dB)`, `PCI Mod-3 Shift`, `Timing Advance`, and `Speed (km/h)`.
* **Visual Provenance Discrimination:** Circle markers render with a **solid border** for modem `MEASURED` baseband signals, and a **dashed border** (`dashArray: '3, 3'`) for `3GPP ESTIMATED` signals.
* **Elevated Transition Diamonds:** Displays colored diamonds at cell handover points with normalized labels:
  - Green (`#10B981`): `Confirmed Handover`
  - Cyan (`#06B6D4`): `Intra-Frequency Transition`
  - Blue (`#3B82F6`): `Inter-Frequency Transition`
  - Amber (`#F59E0B`): `Inter-RAT Transition`
  - Yellow (`#EAB308`): `Cell Reselection`
  - Rose (`#EF4444`): `Ping-Pong Candidate`
  - Purple (`#8B5CF6`): `Observed Cell Change`
* **Coverage Corridors & Tower Centroids:** Highlights weak coverage corridors ($< -105\text{ dBm}$) with orange polylines and displays estimated cell sites triangulated from power-weighted centroids ($\sum w_i \mathbf{x}_i / \sum w_i$).

### 3.2 Global Light & Dark Theme Support
* **Dual Theming:** Seamless switching between dark theme (default for drive engineers) and high-contrast telecom light mode (`#F5F7FB` background, crisp white cards, `#0F172A` text).
* **Zero-Flash Script:** Pre-render script in `index.html` preventing theme blinking, backed by persistent `localStorage` (`'cellscope-theme'`).
* **Map Tile Synchronization:** Dynamic CSS tile filters (`cellscope-dark-tiles` inverted tiles vs. `cellscope-light-tiles` clean OpenStreetMap tiles).

### 3.3 Advanced Slide-over Telecom Filter Drawer (`components/FilterDrawer.tsx`)
* Filters observations by Serving PCI, RAT Generation (`5G NR`, `4G LTE`, `2G GSM`), Operator, EARFCN / Band, Signal Quality classes, Handover-only, and Weak-zones-only toggles.
* Filter selections dynamically recalculate map layers, KPI summaries, and distribution statistics.

### 3.4 3GPP TS 36.101 Carrier Inventory & CRS Modulo-3 Analytics
* Identifies carrier frequencies ($F_{\text{DL}}, F_{\text{UL}}$), duplex modes, and operator allocations from detected EARFCNs.
* Groups observed PCIs by $v_{\text{shift}} = \text{PCI} \bmod 3$, highlighting co-channel pilot collision risks.

### 3.5 Executive Technical Report Exporter (`components/ReportExporter.tsx`)
* Generates an audit-ready, paginated A4 summary report suitable for executive review and network planning sign-off.

---

## 4. Setup, Development & Production Build

### 4.1 Installing Dependencies
From the `frontend/` directory:
```bash
npm install
```

### 4.2 Running Static Code Quality Linter (Oxlint)
```bash
npm run lint
```
*Expected Result:* `Found 0 warnings and 0 errors` across 20 files.

### 4.3 Starting the Development Server
```bash
npm run dev
```
Launches the development server at `http://localhost:5173/` with Hot Module Replacement.

### 4.4 Compiling the Production Build
```bash
npm run build
```
*Expected Output:*
```
dist/index.html                   1.51 kB │ gzip:   0.79 kB
dist/assets/index-*.css          79.66 kB │ gzip:  17.39 kB
dist/assets/index-*.js          583.56 kB │ gzip: 152.51 kB
✓ built in ~1.0s
```
Production assets are generated in `dist/` ready for hosting on Nginx, Cloudflare Pages, or static web servers.
