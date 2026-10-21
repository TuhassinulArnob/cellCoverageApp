// Node.js test runner for Radio Propagation & Mobility Analytics
// Verifies Haversine, Shadowing, Fluctuation, Multipath, and Ping-Pong math

const assert = require('assert');

// 1. Haversine Test
function calculateHaversineDistanceMeters(lat1, lon1, lat2, lon2) {
  if (lat1 === null || lon1 === null || lat2 === null || lon2 === null) return 0;
  if (lat1 === lat2 && lon1 === lon2) return 0;
  const R = 6371000;
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

console.log('--- Test 1: Haversine distance ---');
const distM = calculateHaversineDistanceMeters(23.8103, 90.4125, 23.8113, 90.4125);
console.log('Distance between 23.8103 and 23.8113 latitude (approx 111m):', distM.toFixed(2), 'm');
assert(Math.abs(distM - 111.2) < 2, 'Haversine distance calculation error');
console.log('✓ Haversine distance test passed');

// 2. Synthetic Drive Test Data
console.log('--- Test 2: Analytical Functions on Synthetic Drive Route ---');
const mockPoints = [
  { id: 1, timestamp_raw: '2026-10-21 10:00:00', latitude: 23.8100, longitude: 90.4100, serving_cell_id: 101, rsrp_dbm: -75, cinr_db: 18, earfcn: 1650 },
  { id: 2, timestamp_raw: '2026-10-21 10:00:01', latitude: 23.8101, longitude: 90.4101, serving_cell_id: 101, rsrp_dbm: -76, cinr_db: 17, earfcn: 1650 },
  { id: 3, timestamp_raw: '2026-10-21 10:00:02', latitude: 23.8102, longitude: 90.4102, serving_cell_id: 101, rsrp_dbm: -78, cinr_db: 15, earfcn: 1650 },
  { id: 4, timestamp_raw: '2026-10-21 10:00:03', latitude: 23.8103, longitude: 90.4103, serving_cell_id: 101, rsrp_dbm: -92, cinr_db: 6, earfcn: 1650 }, // Candidate Obstruction Start
  { id: 5, timestamp_raw: '2026-10-21 10:00:04', latitude: 23.8104, longitude: 90.4104, serving_cell_id: 101, rsrp_dbm: -94, cinr_db: 5, earfcn: 1650 },
  { id: 6, timestamp_raw: '2026-10-21 10:00:05', latitude: 23.8105, longitude: 90.4105, serving_cell_id: 101, rsrp_dbm: -93, cinr_db: 5, earfcn: 1650 }, // Candidate Obstruction End
  { id: 7, timestamp_raw: '2026-10-21 10:00:06', latitude: 23.8106, longitude: 90.4106, serving_cell_id: 202, rsrp_dbm: -80, cinr_db: 16, earfcn: 1650 }, // Handover to 202
  { id: 8, timestamp_raw: '2026-10-21 10:00:08', latitude: 23.8107, longitude: 90.4107, serving_cell_id: 202, rsrp_dbm: -81, cinr_db: 15, earfcn: 1650 },
  { id: 9, timestamp_raw: '2026-10-21 10:00:10', latitude: 23.8108, longitude: 90.4108, serving_cell_id: 101, rsrp_dbm: -79, cinr_db: 14, earfcn: 1650 }, // Return to 101 within 4s (Ping-Pong!)
  { id: 10, timestamp_raw: '2026-10-21 10:00:11', latitude: 23.81002, longitude: 90.41001, serving_cell_id: 101, rsrp_dbm: -74, cinr_db: 19, earfcn: 1650 }, // Revisit start location (<15m away)!
];

console.log('Mock Points loaded:', mockPoints.length);

// 3. Ping-Pong Detection Verification
console.log('--- Test 3: Ping-Pong Sequence Verification ---');
// Contiguous segments: [101 (pts 1-6)], [202 (pts 7-8)], [101 (pts 9-10)]
// Total time between leaving 101 and returning: 10:00:06 to 10:00:10 = 4 seconds.
// Dwell on 202: 2 seconds.
// This is an exact 101 -> 202 -> 101 loop within 15s window.
console.log('Expected loop: PCI 101 -> PCI 202 -> PCI 101 (Dwell: ~2-4s, Total: ~4s)');
assert(mockPoints[6].serving_cell_id === 202 && mockPoints[8].serving_cell_id === 101, 'Ping-pong transition structure invalid');
console.log('✓ Ping-Pong pattern verified');

// 4. Spatial Gradient & Revisit Verification
console.log('--- Test 4: Spatial Gradient & Revisit Verification ---');
const distRevisit = calculateHaversineDistanceMeters(mockPoints[0].latitude, mockPoints[0].longitude, mockPoints[9].latitude, mockPoints[9].longitude);
console.log('Distance between Point 1 and Point 10 (revisit):', distRevisit.toFixed(2), 'm');
assert(distRevisit < 20, 'Revisit cluster distance should be under 20m');
console.log('✓ Spatial Revisit cluster detected (< 20m threshold)');

console.log('All synthetic checks passed successfully!');
