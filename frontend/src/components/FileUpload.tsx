import React, { useState } from 'react';
import type { AvailableDatasetInfo } from '../types/cellscope';
import { Upload, AlertCircle, Database, ArrowRight, Loader2 } from 'lucide-react';

interface FileUploadProps {
  availablePresets: AvailableDatasetInfo[];
  onSelectPreset: (presetId: string) => Promise<void>;
  onUploadFile: (file: File) => Promise<void>;
  loading: boolean;
  error: string | null;
}

export const FileUpload: React.FC<FileUploadProps> = ({
  availablePresets,
  onSelectPreset,
  onUploadFile,
  loading,
  error,
}) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setSelectedFile(e.target.files[0]);
    }
  };

  const handleCustomUpload = async () => {
    if (!selectedFile) return;
    await onUploadFile(selectedFile);
  };

  return (
    <div className="max-w-4xl mx-auto space-y-8">
      {/* Header */}
      <div>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-1">Dataset Ingestion & Presets</h2>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Load verified commercial LTE drive-test datasets from Dhaka, Bangladesh or upload your own field-test CSV.
        </p>
      </div>

      {error && (
        <div className="bg-rose-50 border border-rose-200 text-rose-700 dark:bg-rose-950/40 dark:border-rose-800 dark:text-rose-300 text-xs p-3 rounded-lg flex items-center gap-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Preset Catalog */}
      <div className="space-y-3">
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-2">
          <Database className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
          <span>Verified Bangladesh LTE Drive-Test Datasets</span>
        </h3>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {availablePresets.map((preset) => (
            <div
              key={preset.id}
              className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-cyan-500/50 dark:hover:border-cyan-600/50 rounded-xl p-4 shadow-sm dark:shadow-none transition-all flex flex-col justify-between"
            >
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-mono text-cyan-800 bg-cyan-100 border border-cyan-200 dark:text-cyan-400 dark:bg-cyan-950 dark:border-cyan-800 px-2 py-0.5 rounded">
                    {preset.category}
                  </span>
                  {preset.has_gps ? (
                    <span className="text-[10px] text-emerald-800 bg-emerald-100 border border-emerald-200 dark:text-emerald-400 dark:bg-emerald-950/80 dark:border-emerald-800 px-2 py-0.5 rounded font-medium">
                      GPS Tagged
                    </span>
                  ) : (
                    <span className="text-[10px] text-amber-800 bg-amber-100 border border-amber-200 dark:text-amber-400 dark:bg-amber-950/80 dark:border-amber-800 font-medium">
                      Tabular RF Only
                    </span>
                  )}
                </div>

                <h4 className="font-semibold text-slate-900 dark:text-white text-sm mb-1">{preset.title}</h4>
                <p className="text-xs text-slate-500 dark:text-slate-400 mb-3">{preset.description}</p>
              </div>

              <div className="pt-3 border-t border-slate-200 dark:border-slate-800 flex items-center justify-between">
                <span className="text-xs text-slate-500 dark:text-slate-500 font-mono">{preset.rows} rows</span>
                <button
                  onClick={() => onSelectPreset(preset.id)}
                  disabled={loading}
                  className="flex items-center gap-1.5 bg-slate-100 hover:bg-cyan-600 text-slate-700 hover:text-white dark:bg-slate-800 dark:hover:bg-cyan-600 dark:text-slate-200 text-xs font-semibold px-3 py-1.5 rounded-lg transition-colors border border-slate-300 hover:border-cyan-500 dark:border-slate-700"
                >
                  {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArrowRight className="w-3.5 h-3.5" />}
                  <span>Load Dataset</span>
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Custom Upload Section */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-6 shadow-sm dark:shadow-none transition-colors">
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200 mb-2 flex items-center gap-2">
          <Upload className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
          <span>Upload Custom Drive-Test CSV</span>
        </h3>
        <p className="text-xs text-slate-500 dark:text-slate-400 mb-4">
          CellScope automatically detects column variants (e.g. <code className="text-slate-700 dark:text-slate-300 font-semibold">Serving Cell RSRP</code>, <code className="text-slate-700 dark:text-slate-300 font-semibold">RSRQ</code>,{' '}
          <code className="text-slate-700 dark:text-slate-300 font-semibold">CINR-1</code>, <code className="text-slate-700 dark:text-slate-300 font-semibold">Speed (km/h)</code>) and converts 3GPP Information Element indices to physical units.
        </p>

        <div className="flex flex-col sm:flex-row items-center gap-3">
          <input
            type="file"
            accept=".csv"
            onChange={handleFileChange}
            className="block w-full text-xs text-slate-600 dark:text-slate-400 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-slate-100 file:text-cyan-700 dark:file:bg-slate-800 dark:file:text-cyan-400 hover:file:bg-slate-200 dark:hover:file:bg-slate-700 cursor-pointer bg-slate-50 dark:bg-slate-950 p-1.5 rounded-lg border border-slate-300 dark:border-slate-800"
          />
          <button
            onClick={handleCustomUpload}
            disabled={!selectedFile || loading}
            className="w-full sm:w-auto flex items-center justify-center gap-2 bg-cyan-600 hover:bg-cyan-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-semibold px-5 py-2.5 rounded-lg transition-colors shadow-sm whitespace-nowrap"
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />}
            <span>Parse & Analyze</span>
          </button>
        </div>
      </div>
    </div>
  );
};
