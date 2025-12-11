import { check, sleep } from 'k6';
import http from 'k6/http';
import { Rate } from 'k6/metrics';

// Custom metrics
export let errorRate = new Rate('errors');

// Configuration
const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8081';

// Test options
export let options = {
  stages: [
    { duration: '10s', target: 5 },    // Ramp up
    { duration: '1m',  target: 50 },  // Steady state
    { duration: '10s', target: 0 },    // Ramp down
  ],
  thresholds: {
    errors: ['rate<0.01'],             // Error rate should be below 1%
    http_req_duration: ['p(95)<1000'], // 95% of requests should be below 1000ms
  },
};

export default function () {
  // 95% of the time act as a Visualizer user, 5% as an Analyzer user
  if (Math.random() < 0.95) {
    visualizerScenario();
  } else {
    analyzerScenario();
  }
}

function getRandomRegion(envWidth, envHeight, viewWidth, viewHeight) {
  // Ensure we don't request more than the world size
  const actualW = Math.min(envWidth, viewWidth);
  const actualH = Math.min(envHeight, viewHeight);

  // Calculate max starting points
  const maxX = envWidth - actualW;
  const maxY = envHeight - actualH;

  // Random start coordinates
  const x1 = Math.floor(Math.random() * (maxX + 1));
  const y1 = Math.floor(Math.random() * (maxY + 1));
  const x2 = x1 + actualW;
  const y2 = y1 + actualH;

  return `${x1},${x2},${y1},${y2}`;
}

function visualizerScenario() {
  // 1. Initial Setup: Get Simulation Metadata (for Environment Shape)
  let metadataRes = http.get(`${BASE_URL}/visualizer/api/simulation/metadata`);
  
  if (!check(metadataRes, { 'metadata 200': (r) => r.status === 200 })) {
    errorRate.add(1);
    sleep(1);
    return;
  }

  let envWidth = 1000;
  let envHeight = 1000;

  try {
    const meta = metadataRes.json();
    if (meta.environment && meta.environment.shape && meta.environment.shape.length >= 2) {
      envWidth = meta.environment.shape[0];
      envHeight = meta.environment.shape[1];
    }
  } catch (e) {
    // Keep defaults if parsing fails
  }

  // 2. Get Tick Range
  let res = http.get(`${BASE_URL}/visualizer/api/environment/ticks`);
  
  if (!check(res, { 'setup ticks 200': (r) => r.status === 200 })) {
    errorRate.add(1);
    sleep(1);
    return;
  }

  let range = { minTick: 0, maxTick: 100 };
  try {
    const body = res.json();
    range.minTick = body.minTick || 0;
    range.maxTick = body.maxTick || 100;
  } catch (e) {
    // Keep defaults
  }

  // Pick a random start tick within the first 80% of the timeline
  // so we have room to "play" forward
  const rangeSpan = range.maxTick - range.minTick;
  let currentTick = range.minTick + Math.floor(Math.random() * (rangeSpan * 0.8));
  
  // Persist a selected organism ID across the session (user watching one organism)
  let selectedOrganismId = null;

  // Simulate viewing 10 sequential ticks (simulating "Play" or manual stepping)
  for (let i = 0; i < 10; i++) {
    // Stop if we exceed max tick
    if (currentTick > range.maxTick) break;

    // A. Fetch Environment - Zoom Level 1 (Overview - 800x600)
    // Randomly positioned viewport within the world bounds
    const regionZoom1 = getRandomRegion(envWidth, envHeight, 800, 600);
    http.get(`${BASE_URL}/visualizer/api/environment/${currentTick}?region=${regionZoom1}`);

    // B. Fetch Environment - Zoom Level 2 (Detailed - 100x50)
    // Randomly positioned detailed viewport (simulating panning or focused view)
    const regionZoom2 = getRandomRegion(envWidth, envHeight, 100, 50);
    http.get(`${BASE_URL}/visualizer/api/environment/${currentTick}?region=${regionZoom2}`);

    // C. Fetch All Organisms for this tick
    let orgRes = http.get(`${BASE_URL}/visualizer/api/organisms/${currentTick}`);
    
    // D. Fetch Organism Detail (if one is selected, or select one now)
    if (orgRes.status === 200) {
      try {
        const orgs = orgRes.json();
        if (orgs && orgs.length > 0) {
          // If we don't have a selection yet, or randomly 20% of time switch selection
          if (!selectedOrganismId || Math.random() < 0.2) {
            const randomOrg = orgs[Math.floor(Math.random() * orgs.length)];
            selectedOrganismId = randomOrg.id || randomOrg.organismId;
          }

          if (selectedOrganismId) {
             http.get(`${BASE_URL}/visualizer/api/organisms/${currentTick}/${selectedOrganismId}`);
          }
        }
      } catch (e) {
        // Ignore json errors
      }
    }

    // Advance tick
    currentTick++;
    
    // User delay between ticks (simulating "Play" speed ~5-10 ticks/sec or manual clicks)
    // If playing, this is fast (e.g. 100ms). If manual, it's slower.
    // Let's average it to 200ms
    sleep(0.2); 
  }
}

function analyzerScenario() {
  // 1. List Runs
  let res = http.get(`${BASE_URL}/analyzer/api/runs`);
  if (!check(res, { 'analyzer runs 200': (r) => r.status === 200 })) {
    errorRate.add(1);
    return;
  }

  // 2. Get Manifest (metrics definition)
  res = http.get(`${BASE_URL}/analyzer/api/manifest`);
  if (!check(res, { 'analyzer manifest 200': (r) => r.status === 200 })) {
    errorRate.add(1);
    return;
  }

  let metrics = [];
  try {
    const body = res.json();
    if (body.metrics) {
      metrics = body.metrics.map(m => m.id);
    }
  } catch (e) {
    return;
  }

  // 3. Fetch data for all available metrics
  metrics.forEach(metricId => {
     http.get(`${BASE_URL}/analyzer/api/data?metric=${metricId}&lod=lod0`);
  });
  
  sleep(1);
}
