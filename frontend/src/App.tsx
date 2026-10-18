import React, { useState, useEffect } from 'react';
import { Navbar } from './components/Navbar';
import { MapView } from './components/MapView';
import { SummaryStats } from './components/SummaryStats';
import { MeasurementTable } from './components/MeasurementTable';
import { FileUpload } from './components/FileUpload';
import { EventsAndAnomalies } from './components/EventsAndAnomalies';
import { DataQualityPanel } from './components/DataQualityPanel';
import { ReportExporter } from './components/ReportExporter';
import type {
  AvailableDatasetInfo,
  DatasetSummary,
  MeasurementPoint,
  ExtendedDatasetAnalysis,
} from './types/cellscope';
import {
  fetchAvailableDatasets,
  loadPresetDataset,
  uploadCsvFile,
  fetchMeasurements,
  fetchExtendedAnalysis,
} from './services/api';
import { Loader2 } from 'lucide-react';

export const App: React.FC = () => {
  const [currentTab, setCurrentTab] = useState<string>('map');
  const [summary, setSummary] = useState<DatasetSummary | null>(null);
  const [measurements, setMeasurements] = useState<MeasurementPoint[]>([]);
  const [extendedAnalysis, setExtendedAnalysis] = useState<ExtendedDatasetAnalysis | null>(null);
  const [availablePresets, setAvailablePresets] = useState<AvailableDatasetInfo[]>([]);
  const [selectedCellId, setSelectedCellId] = useState<number | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Initial load: Fetch preset catalog and default benchmark dataset
  useEffect(() => {
    async function init() {
      try {
        setLoading(true);
        setError(null);
        const presets = await fetchAvailableDatasets();
        setAvailablePresets(presets);

        // Load Dhaka Route 3 (ver 1) as initial default benchmark
        const defaultPresetId = 'bd_ds3_v1';
        const res = await loadPresetDataset(defaultPresetId);
        setSummary(res.summary);

        const allPoints = await fetchMeasurements({ gpsOnly: false });
        setMeasurements(allPoints);

        const ext = await fetchExtendedAnalysis();
        setExtendedAnalysis(ext);
      } catch (err: any) {
        console.error('Initialization error:', err);
        setError(err.message || 'Failed to initialize CellScope');
      } finally {
        setLoading(false);
      }
    }

    init();
  }, []);

  const handleSelectPreset = async (presetId: string) => {
    try {
      setLoading(true);
      setError(null);
      const res = await loadPresetDataset(presetId);
      setSummary(res.summary);
      setSelectedCellId(null);

      const allPoints = await fetchMeasurements({ gpsOnly: false });
      setMeasurements(allPoints);

      const ext = await fetchExtendedAnalysis();
      setExtendedAnalysis(ext);

      // If dataset has GPS, switch to map; otherwise switch to analytics
      if (res.summary.has_gps) {
        setCurrentTab('map');
      } else {
        setCurrentTab('analytics');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load dataset preset');
    } finally {
      setLoading(false);
    }
  };

  const handleUploadFile = async (file: File) => {
    try {
      setLoading(true);
      setError(null);
      const res = await uploadCsvFile(file);
      setSummary(res.summary);
      setSelectedCellId(null);

      const allPoints = await fetchMeasurements({ gpsOnly: false });
      setMeasurements(allPoints);

      const ext = await fetchExtendedAnalysis();
      setExtendedAnalysis(ext);

      if (res.summary.has_gps) {
        setCurrentTab('map');
      } else {
        setCurrentTab('analytics');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to process custom CSV file');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans">
      {/* Top Navbar */}
      <Navbar
        currentTab={currentTab}
        setCurrentTab={setCurrentTab}
        summary={summary}
        loading={loading}
      />

      {/* Main Content Area */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 lg:p-8">
        {loading && !summary ? (
          <div className="flex flex-col items-center justify-center h-96 gap-3 text-slate-400">
            <Loader2 className="w-8 h-8 animate-spin text-cyan-400" />
            <p className="text-sm font-medium">Loading CellScope LTE Analytics Engine...</p>
          </div>
        ) : (
          <div>
            {currentTab === 'map' && (
              <MapView
                measurements={measurements}
                summary={summary}
                selectedCellId={selectedCellId}
                setSelectedCellId={setSelectedCellId}
              />
            )}

            {currentTab === 'analytics' && (
              <SummaryStats summary={summary} />
            )}

            {currentTab === 'events' && (
              <EventsAndAnomalies analysis={extendedAnalysis} />
            )}

            {currentTab === 'quality' && (
              <DataQualityPanel analysis={extendedAnalysis} />
            )}

            {currentTab === 'table' && (
              <MeasurementTable measurements={measurements} />
            )}

            {currentTab === 'report' && (
              <ReportExporter summary={summary} analysis={extendedAnalysis} />
            )}

            {currentTab === 'import' && (
              <FileUpload
                availablePresets={availablePresets}
                onSelectPreset={handleSelectPreset}
                onUploadFile={handleUploadFile}
                loading={loading}
                error={error}
              />
            )}
          </div>
        )}
      </main>

      {/* Engineering Footer */}
      <footer className="bg-slate-900 border-t border-slate-800 py-4 px-6 text-xs text-slate-500">
        <div className="max-w-7xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-400">CellScope RAN Analytics Platform</span>
            <span>•</span>
            <span>3GPP TS 36.133 / TS 38.133 Compliance</span>
            <span>•</span>
            <span>Lead: Sarkar Tuhassinul Arnob</span>
          </div>
          <div>Unified Telecom Dashboard: Interactive Map, RF Analytics, Anomaly Detection & Canonical CSV Preservation</div>
        </div>
      </footer>
    </div>
  );
};

export default App;
