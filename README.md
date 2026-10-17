# Cell Coverage App (CellScope)

A cellular network coverage measurement and GPS recording platform designed for field drive-testing, RF signal quality assessment, and coverage analytics.

## Overview

**Cell Coverage App** enables RF engineers, network planners, and technicians to monitor, log, and analyze cellular signal metrics and location coordinates in real time. It captures physical-layer signal parameters alongside synchronized, high-accuracy GPS fixes for post-processing and coverage mapping.

## Key Features

- **Real-Time Cellular Telephony Monitoring**: Tracks serving cell identity, PCI (Physical Cell ID), TAC (Tracking Area Code), EARFCN, and channel bandwidth.
- **RF Signal Quality Logging**:
  - **RSRP** (Reference Signal Received Power)
  - **RSRQ** (Reference Signal Received Quality)
  - **RSSI** (Received Signal Strength Indicator)
  - **CQI** (Channel Quality Indicator)
  - **Timing Advance** and Handover tracking
- **Synchronized GPS Positioning**: Records latitude, longitude, altitude, speed, and positioning accuracy timestamped with network samples.
- **Session-Based Storage**: Built with local database persistence for managing multiple drive-test runs and measurement histories.
- **Standardized Data Export**: Exports standardized CSV logs ready for GIS visualization tools (QGIS, Google Earth) and post-drive analytics.

## Technical Highlights

- **Platform**: Native Android (Kotlin)
- **Architecture**: Reactive, lifecycle-aware architecture with Kotlin Coroutines & StateFlow
- **Data Persistence**: Android Room Database with Kotlin Symbol Processing (KSP)
- **UI Framework**: Android Material Components with View Binding

## Roadmap

- [x] Native Cellular & GPS Recording Engine
- [x] Local Database Session Management (Room + KSP)
- [x] Standardized CSV Log Export
- [ ] Live In-App Heatmap & Route Visualization
- [ ] 5G NR Extended Metrics & Carrier Aggregation
- [ ] Cloud Sync & Web Analytics Dashboard Integration

## License

This project is licensed under the MIT License.
