import { sleep } from 'k6';
import http from 'k6/http';
import { Rate } from 'k6/metrics';

// Every request that is not answered with 200 counts as an error
export let errorRate = new Rate('errors');

// The node to test, and the run to ask it for; without a run the node answers for its newest
const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8081';
const RUN_ID = __ENV.RUN_ID || '';
// Whom the virtual users act as: 'visualizer', 'analyzer', or both mixed where nothing is given
const SCENARIO = __ENV.SCENARIO || 'mixed';

// The requests by what they ask for. A request carries one of these names, so that the summary
// lists the response times of each kind instead of one line per tick.
const KINDS = ['metadata', 'ticks', 'environment 800x600', 'environment 100x50', 'organisms', 'organism details',
               'analyzer runs', 'analyzer manifest', 'analyzer tick range', 'analyzer parquet'];

const thresholds = {
  errors: ['rate<0.01'],             // Error rate should be below 1%
  http_req_duration: ['p(95)<1000'], // 95% of requests should be below 1000ms
};
// A threshold on a kind is what makes the summary show that kind on a line of its own
KINDS.forEach(kind => { thresholds[`http_req_duration{name:${kind}}`] = ['p(95)<1000']; });

export let options = {
  stages: [
    { duration: '10s', target: 5 },    // Ramp up
    { duration: '1m',  target: 50 },   // Steady state
    { duration: '10s', target: 0 },    // Ramp down
  ],
  thresholds,
};

// Requests the path with its parameters, the run among them where one is given, and counts the
// answer as an error unless it is 200
function get(kind, path, params = {}) {
  const all = RUN_ID ? Object.assign({}, params, { runId: RUN_ID }) : params;
  const query = Object.keys(all).map(key => `${key}=${encodeURIComponent(all[key])}`).join('&');
  const res = http.get(`${BASE_URL}${path}${query ? '?' + query : ''}`, { tags: { name: kind } });
  errorRate.add(res.status !== 200);
  return res;
}

function jsonOf(res) {
  try {
    return res.status === 200 ? res.json() : null;
  } catch (e) {
    return null;
  }
}

export default function () {
  // Mixed, a user is a Visualizer user 95% of the time and an Analyzer user otherwise
  const analyzer = SCENARIO === 'analyzer' || (SCENARIO !== 'visualizer' && Math.random() >= 0.95);
  if (analyzer) {
    analyzerScenario();
  } else {
    visualizerScenario();
  }
}

function getRandomRegion(envWidth, envHeight, viewWidth, viewHeight) {
  // Ensure we don't request more than the world size
  const actualW = Math.min(envWidth, viewWidth);
  const actualH = Math.min(envHeight, viewHeight);

  // Random start coordinates, so that the region lies inside the environment
  const x1 = Math.floor(Math.random() * (envWidth - actualW + 1));
  const y1 = Math.floor(Math.random() * (envHeight - actualH + 1));

  return `${x1},${x1 + actualW},${y1},${y1 + actualH}`;
}

function visualizerScenario() {
  // 1. The metadata of the run: the shape of its environment and how densely it was recorded
  const meta = jsonOf(get('metadata', '/visualizer/api/simulation/metadata'));
  if (!meta) {
    sleep(1);
    return;
  }
  const shape = meta.environment && meta.environment.shape;
  const envWidth = shape && shape.length >= 2 ? shape[0] : 1000;
  const envHeight = shape && shape.length >= 2 ? shape[1] : 1000;
  // A run holds every samplingInterval-th tick; any other tick is not there to be asked for
  const step = meta.samplingInterval || 1;

  // 2. The range of the recorded ticks
  const range = jsonOf(get('ticks', '/visualizer/api/environment/ticks'));
  if (!range || range.maxTick === null || range.maxTick === undefined) {
    sleep(1);
    return;
  }
  const minTick = range.minTick || 0;
  const recorded = Math.floor((range.maxTick - minTick) / step) + 1;

  // Start at a random recorded tick within the first 80% of the timeline, so that there is
  // room to step forward
  let currentTick = minTick + Math.floor(Math.random() * recorded * 0.8) * step;

  // The organism the user watches, kept across the ticks
  let selectedOrganismId = null;

  // View 10 recorded ticks in a row (playing, or stepping by hand)
  for (let i = 0; i < 10; i++) {
    if (currentTick > range.maxTick) break;
    const tickPath = `/${currentTick}`;

    // A. The environment zoomed out (800x600), at a random place
    get('environment 800x600', '/visualizer/api/environment' + tickPath, { region: getRandomRegion(envWidth, envHeight, 800, 600) });

    // B. The environment zoomed in (100x50), at a random place
    get('environment 100x50', '/visualizer/api/environment' + tickPath, { region: getRandomRegion(envWidth, envHeight, 100, 50) });

    // C. All organisms of this tick
    const listed = jsonOf(get('organisms', '/visualizer/api/organisms' + tickPath));
    const organisms = listed && listed.organisms ? listed.organisms : [];

    // D. The details of one organism: the selected one while it lives, and now and then another
    if (organisms.length > 0) {
      const stillThere = organisms.some(organism => organism.organismId === selectedOrganismId);
      if (!stillThere || Math.random() < 0.2) {
        selectedOrganismId = organisms[Math.floor(Math.random() * organisms.length)].organismId;
      }
      get('organism details', `/visualizer/api/organisms${tickPath}/${selectedOrganismId}`);
    }

    currentTick += step;

    // Between two ticks: fast while playing, slower by hand; 200ms on average
    sleep(0.2);
  }
}

function analyzerScenario() {
  // 1. The runs of the node
  if (get('analyzer runs', '/analyzer/api/runs').status !== 200) return;

  // 2. The manifest, which names the metrics
  const manifest = jsonOf(get('analyzer manifest', '/analyzer/api/manifest'));
  if (!manifest || !manifest.metrics) return;

  // 3. Every metric as the Analyzer's page loads it: the range of its ticks, then its data as
  //    Parquet at the level of detail the node chooses
  manifest.metrics.forEach(metric => {
    get('analyzer tick range', '/analyzer/api/tick-range', { metric: metric.id });
    get('analyzer parquet', '/analyzer/api/parquet', { metric: metric.id });
  });

  sleep(1);
}
