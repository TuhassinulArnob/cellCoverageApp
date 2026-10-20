import React, { useState, useEffect, useMemo } from 'react';
import { Navbar } from './components/Navbar';
import { MapView } from './components/MapView';
import { SummaryStats } from './components/SummaryStats';
import { MeasurementTable } from './components/MeasurementTable';
import { FileUpload } from './components/FileUpload';
import { EventsAndAnomalies } from './components/EventsAndAnomalies';
import { DataQualityPanel } from './components/DataQualityPanel';
import { ReportExporter } from './components/ReportExporter';
import { FilterDrawer } from './components/FilterDrawer';
import type {
  AvailableDatasetInfo,
  DatasetSummary,
  MeasurementPoint,
  ExtendedDatasetAnalysis,
  FilterState,
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
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Advanced Filters State
  const [isFilterDrawerOpen, setIsFilterDrawerOpen] = useState<boolean>(false);
  const [filters, setFilters] = useState<FilterState>({
    servingCellId: null,
    networkGen: null,
    operator: null,
    bandEarfcn: null,
    rsrpClass: null,
    rsrqClass: null,
    minCinr: null,
    handoversOnly: false,
    weakZonesOnly: false,
  });

  // Synchronize selectedCellId from quick select with filters.servingCellId
  const handleSelectedCellChange = (id: number | null) => {
    setFilters((prev) => ({ ...prev, servingCellId: id }));
  };

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
      setFilters({
        servingCellId: null,
        networkGen: null,
        operator: null,
        bandEarfcn: null,
        rsrpClass: null,
        rsrqClass: null,
        minCinr: null,
        handoversOnly: false,
        weakZonesOnly: false,
      });

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
      setFilters({
        servingCellId: null,
        networkGen: null,
        operator: null,
        bandEarfcn: null,
        rsrpClass: null,
        rsrqClass: null,
        minCinr: null,
        handoversOnly: false,
        weakZonesOnly: false,
      });

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

  // Filtered measurements applied across Map, Analytics, and MeasurementTable
  const filteredMeasurements = useMemo(() => {
    return measurements.filter((pt) => {
      if (filters.servingCellId !== null && pt.serving_cell_id !== filters.servingCellId) {
        return false;
      }
      if (filters.networkGen !== null && pt.network_gen !== filters.networkGen) {
        return false;
      }
      if (filters.operator !== null && pt.operator !== filters.operator) {
        return false;
      }
      if (filters.bandEarfcn !== null && pt.earfcn !== filters.bandEarfcn) {
        return false;
      }
      if (filters.rsrpClass !== null && pt.rsrp_class !== filters.rsrpClass) {
        return false;
      }
      if (filters.rsrqClass !== null && pt.rsrq_class !== filters.rsrqClass) {
        return false;
      }
      if (filters.minCinr !== null && (pt.cinr_db === null || pt.cinr_db < filters.minCinr)) {
        return false;
      }
      if (filters.handoversOnly && pt.handover_trigger !== 1) {
        return false;
      }
      if (filters.weakZonesOnly && (pt.rsrp_dbm === null || pt.rsrp_dbm >= -100)) {
        return false;
      }
      return true;
    });
  }, [measurements, filters]);

  // Dynamically recalculated summary when filters are active
  const effectiveSummary = useMemo(() => {
    if (!summary) return null;
    const isFiltered = filteredMeasurements.length !== measurements.length;
    if (!isFiltered) return summary;

    const validRsrp = filteredMeasurements.map((p) => p.rsrp_dbm).filter((v): v is number => v !== null);
    const validRsrq = filteredMeasurements.map((p) => p.rsrq_db).filter((v): v is number => v !== null);
    const validCinr = filteredMeasurements.map((p) => p.cinr_db).filter((v): v is number => v !== null);
    const hoCount = filteredMeasurements.filter((p) => p.handover_trigger === 1).length;

    const rsrpDist = { excellent: 0, good: 0, fair: 0, poor: 0, unknown: 0 };
    const rsrqDist = { excellent: 0, good: 0, fair: 0, poor: 0, unknown: 0 };
    const cinrDist = { excellent: 0, good: 0, fair: 0, poor: 0, unknown: 0 };

    filteredMeasurements.forEach((p) => {
      const rCls = p.rsrp_class.toLowerCase() as keyof typeof rsrpDist;
      if (rsrpDist[rCls] !== undefined) rsrpDist[rCls]++;

      const qCls = p.rsrq_class.toLowerCase() as keyof typeof rsrqDist;
      if (rsrqDist[qCls] !== undefined) rsrqDist[qCls]++;

      const cCls = p.cinr_class.toLowerCase() as keyof typeof cinrDist;
      if (cinrDist[cCls] !== undefined) cinrDist[cCls]++;
    });

    const uniquePcis = new Set(filteredMeasurements.map((p) => p.serving_cell_id).filter((v) => v !== null));

    return {
      ...summary,
      total_records: filteredMeasurements.length,
      records_with_gps: filteredMeasurements.filter((p) => p.has_valid_gps).length,
      unique_serving_cells: uniquePcis.size,
      handover_trigger_count: hoCount,
      handover_trigger_rate_pct:
        filteredMeasurements.length > 0 ? Math.round((hoCount / filteredMeasurements.length) * 1000) / 10 : 0,
      mean_rsrp_dbm: validRsrp.length > 0 ? Math.round((validRsrp.reduce((a, b) => a + b, 0) / validRsrp.length) * 10) / 10 : null,
      min_rsrp_dbm: validRsrp.length > 0 ? Math.min(...validRsrp) : null,
      max_rsrp_dbm: validRsrp.length > 0 ? Math.max(...validRsrp) : null,
      mean_rsrq_db: validRsrq.length > 0 ? Math.round((validRsrq.reduce((a, b) => a + b, 0) / validRsrq.length) * 10) / 10 : null,
      mean_cinr_db: validCinr.length > 0 ? Math.round((validCinr.reduce((a, b) => a + b, 0) / validCinr.length) * 10) / 10 : null,
      rsrp_distribution: rsrpDist,
      rsrq_distribution: rsrqDist,
      cinr_distribution: cinrDist,
    };
  }, [summary, filteredMeasurements, measurements.length]);

  const activeFilterCount = [
    filters.servingCellId !== null,
    filters.networkGen !== null,
    filters.operator !== null,
    filters.bandEarfcn !== null,
    filters.rsrpClass !== null,
    filters.rsrqClass !== null,
    filters.minCinr !== null,
    filters.handoversOnly,
    filters.weakZonesOnly,
  ].filter(Boolean).length;

  return (
    <div className="min-h-screen bg-[#F5F7FB] dark:bg-slate-950 text-slate-900 dark:text-slate-100 flex flex-col font-sans transition-colors duration-150">
      {/* Top Navbar */}
      <Navbar
        currentTab={currentTab}
        setCurrentTab={setCurrentTab}
        summary={summary}
        loading={loading}
      />

      {/* Main Content Area */}
      <main className="flex-1 max-w-[1720px] w-full mx-auto p-4 sm:p-5 lg:p-6">
        {loading && !summary ? (
          <div className="flex flex-col items-center justify-center h-96 gap-3 text-slate-600 dark:text-slate-400">
            <Loader2 className="w-8 h-8 animate-spin text-cyan-600 dark:text-cyan-400" />
            <p className="text-sm font-medium">Loading CellScope LTE Analytics Engine...</p>
          </div>
        ) : (
          <div>
            {currentTab === 'map' && (
              <MapView
                measurements={filteredMeasurements}
                summary={effectiveSummary}
                extendedAnalysis={extendedAnalysis}
                selectedCellId={filters.servingCellId}
                setSelectedCellId={handleSelectedCellChange}
                onOpenFilters={() => setIsFilterDrawerOpen(true)}
                activeFilterCount={activeFilterCount}
              />
            )}

            {currentTab === 'analytics' && (
              <SummaryStats
                summary={effectiveSummary}
                measurements={filteredMeasurements}
                extendedAnalysis={extendedAnalysis}
              />
            )}

            {currentTab === 'events' && (
              <EventsAndAnomalies
                analysis={extendedAnalysis}
                onNavigateToMap={() => {
                  setCurrentTab('map');
                }}
              />
            )}

            {currentTab === 'quality' && (
              <DataQualityPanel analysis={extendedAnalysis} />
            )}

            {currentTab === 'table' && (
              <MeasurementTable measurements={filteredMeasurements} />
            )}

            {currentTab === 'report' && (
              <ReportExporter summary={effectiveSummary} analysis={extendedAnalysis} />
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

      {/* Slide-over Filter Drawer */}
      <FilterDrawer
        isOpen={isFilterDrawerOpen}
        onClose={() => setIsFilterDrawerOpen(false)}
        filters={filters}
        setFilters={setFilters}
        measurements={measurements}
        totalPointsCount={measurements.length}
        filteredPointsCount={filteredMeasurements.length}
      />

      {/* Engineering Footer */}
      <footer className="bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 py-3.5 px-6 text-xs text-slate-500 transition-colors duration-150">
        <div className="max-w-[1720px] mx-auto flex flex-col sm:flex-row items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-700 dark:text-slate-400">CellScope-5G RAN Analytics Platform</span>
            <span>•</span>
            <span>3GPP TS 36.133 / TS 38.133 & TS 36.101 RF Standards</span>
            <span>•</span>
            <span>Lead: Sarkar Tuhassinul Arnob</span>
          </div>
          <div>Unified Telecom Dashboard: Interactive Multi-Layer Map, RF Analytics, Mobility Tracking & Canonical CSV Preservation</div>
        </div>
      </footer>
    </div>
  );
};

export default App;
