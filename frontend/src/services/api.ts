import type {
  AvailableDatasetInfo,
  DatasetSummary,
  IngestResponse,
  MeasurementPoint,
  ExtendedDatasetAnalysis,
  QoeTestRecord,
} from '../types/cellscope';

const API_BASE = '/api';

export async function fetchAvailableDatasets(): Promise<AvailableDatasetInfo[]> {
  const res = await fetch(`${API_BASE}/datasets/available`);
  if (!res.ok) throw new Error('Failed to load dataset catalog');
  return res.json();
}

export async function loadPresetDataset(id: string): Promise<IngestResponse> {
  const res = await fetch(`${API_BASE}/datasets/load-preset/${id}`, { method: 'POST' });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to load preset dataset');
  }
  return res.json();
}

export async function uploadCsvFile(file: File): Promise<IngestResponse> {
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch(`${API_BASE}/datasets/upload`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to upload CSV file');
  }
  return res.json();
}

export async function fetchCurrentSummary(): Promise<DatasetSummary> {
  const res = await fetch(`${API_BASE}/datasets/current/summary`);
  if (!res.ok) throw new Error('Failed to fetch dataset summary');
  return res.json();
}

export async function fetchMeasurements(options: {
  cellId?: number;
  quality?: string;
  gpsOnly?: boolean;
  limit?: number;
} = {}): Promise<MeasurementPoint[]> {
  const params = new URLSearchParams();
  if (options.cellId !== undefined) params.append('cell_id', options.cellId.toString());
  if (options.quality) params.append('quality', options.quality);
  if (options.gpsOnly !== undefined) params.append('gps_only', options.gpsOnly.toString());
  if (options.limit !== undefined) params.append('limit', options.limit.toString());

  const res = await fetch(`${API_BASE}/datasets/current/measurements?${params.toString()}`);
  if (!res.ok) throw new Error('Failed to fetch measurement points');
  return res.json();
}

export async function fetchExtendedAnalysis(): Promise<ExtendedDatasetAnalysis> {
  const res = await fetch(`${API_BASE}/datasets/current/extended-analysis`);
  if (!res.ok) throw new Error('Failed to fetch extended telecom analysis');
  return res.json();
}

export async function fetchQoeTests(options: {
  rat?: string;
  operator?: string;
  pci?: number;
  status?: string;
} = {}): Promise<QoeTestRecord[]> {
  const params = new URLSearchParams();
  if (options.rat) params.append('rat', options.rat);
  if (options.operator) params.append('operator', options.operator);
  if (options.pci !== undefined) params.append('pci', options.pci.toString());
  if (options.status) params.append('status', options.status);

  const res = await fetch(`${API_BASE}/datasets/current/qoe-tests?${params.toString()}`);
  if (!res.ok) throw new Error('Failed to fetch network QoE tests');
  return res.json();
}

export function getCsvExportUrl(extended: boolean = false): string {
  return `${API_BASE}/datasets/current/export/csv?extended=${extended}`;
}
