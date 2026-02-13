import { loadingManager } from '../ui/LoadingManager.js';

/**
 * Web Worker instance for heavy data processing.
 * Initialized lazily on first use.
 */
let worker = null;
let requestCounter = 0;
const pendingRequests = new Map();

/**
 * Initialize the Web Worker lazily.
 */
function ensureWorkerInitialized() {
    if (worker) return;

    worker = new Worker('./js/workers/EnvironmentWorker.js');

    worker.onmessage = (e) => {
        const { type, requestId, error, data, timing, meta } = e.data;

        const pending = pendingRequests.get(requestId);
        if (!pending) return; // Request was aborted or already handled

        pendingRequests.delete(requestId);

        if (type === 'error') {
            pending.reject(new Error(error));
        } else if (type === 'fetchComplete') {
            pending.resolve({ data, timing, meta });
        } else if (type === 'mappingsSet') {
            pending.resolve();
        }
    };

    worker.onerror = (e) => {
        console.error('[EnvironmentApi Worker] Error:', e.message);
        // Reject all pending requests
        for (const [requestId, pending] of pendingRequests.entries()) {
            pending.reject(new Error(`Worker error: ${e.message}`));
            pendingRequests.delete(requestId);
        }
    };
}

/**
 * Send a message to the worker and return a promise for the response.
 */
function sendToWorker(type, payload, signal = null) {
    ensureWorkerInitialized();

    const requestId = ++requestCounter;

    return new Promise((resolve, reject) => {
        // Handle abort signal
        if (signal) {
            if (signal.aborted) {
                reject(new DOMException('Aborted', 'AbortError'));
                return;
            }
            signal.addEventListener('abort', () => {
                pendingRequests.delete(requestId);
                // Tell the worker to abort the actual fetch
                worker.postMessage({ type: 'abort', requestId });
                reject(new DOMException('Aborted', 'AbortError'));
            });
        }

        pendingRequests.set(requestId, { resolve, reject });
        worker.postMessage({ type, payload, requestId });
    });
}

/**
 * Sets the type mappings from metadata.
 * Forwards to the worker for ID-to-name resolution.
 * Safe to call with null/undefined metadata (no-op).
 *
 * @param {object|null|undefined} metadata - The simulation metadata containing moleculeTypes and opcodes maps.
 */
export function setTypeMappings(metadata) {
    if (!metadata) return;

    const payload = {};
    if (metadata.moleculeTypes) {
        payload.moleculeTypes = metadata.moleculeTypes;
    }
    if (metadata.opcodes) {
        payload.opcodes = metadata.opcodes;
    }

    if (Object.keys(payload).length > 0) {
        sendToWorker('setMappings', payload).catch((err) => {
            console.warn('[EnvironmentApi] Failed to set mappings in worker:', err.message);
        });
    }
}

/**
 * API client for environment-related data endpoints.
 * Uses a Web Worker for heavy Protobuf processing to prevent UI freezing.
 *
 * @class EnvironmentApi
 */
export class EnvironmentApi {
    /**
     * Fetches environment data (cell states) for a specific tick and a given rectangular region.
     * Processing happens in a Web Worker to keep the UI responsive.
     * Supports cancellation via an AbortSignal.
     * Includes performance timing (visible in browser console at Debug level).
     *
     * @param {number} tick - The tick number to fetch data for.
     * @param {{x1: number, x2: number, y1: number, y2: number}} region - The viewport region to fetch.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {string|null} [options.runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @param {boolean} [options.includeMinimap=false] - Include minimap data in response.
     * @param {boolean} [options.showLoading=true] - Whether to trigger the loading indicator.
     * @returns {Promise<{cells: Array<object>, minimap?: {width: number, height: number, cellTypes: Uint8Array}}>} A promise that resolves to the environment data.
     * @throws {Error} If the network request fails, is aborted, or the server returns an error.
     */
    async fetchEnvironmentData(tick, region, options = {}) {
        const { runId = null, signal = null, includeMinimap = false, showLoading = true } = options;

        // Build region query parameter
        const regionParam = `${region.x1},${region.x2},${region.y1},${region.y2}`;

        // Build URL
        let url = `/visualizer/api/environment/${tick}?region=${encodeURIComponent(regionParam)}`;
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        if (includeMinimap) {
            url += '&minimap';
        }

        if (showLoading && loadingManager) {
            loadingManager.incrementRequests();
        }

        // Measure total time from main thread perspective (includes message passing)
        const mainThreadStart = performance.now();

        try {
            // Delegate heavy work to Web Worker
            const { data, timing, meta } = await sendToWorker('fetch', { url }, signal);

            const mainThreadTotal = (performance.now() - mainThreadStart).toFixed(1);

            // Full timing object at debug level
            console.debug(`[Environment API] Tick ${tick}`, {
                server: timing.server,
                client: {
                    fetchMs: timing.client.fetchMs,
                    binaryMs: timing.client.binaryMs,
                    parseMs: timing.client.parseMs,
                    transformMs: timing.client.transformMs,
                    workerMs: timing.client.totalMs,  // Total time in worker
                    totalMs: mainThreadTotal          // Total time including message passing
                },
                cells: meta.cellCount,
                sizeKb: meta.sizeKb
            });

            // Convert minimap cellTypes back to Uint8Array (was converted to Array for transfer)
            const result = { cells: data.cells };
            if (data.minimap) {
                result.minimap = {
                    width: data.minimap.width,
                    height: data.minimap.height,
                    cellTypes: new Uint8Array(data.minimap.cellTypes),
                    ownerIds: data.minimap.ownerIds || null
                };
            }

            return result;
        } catch (error) {
            // Don't log abort errors
            if (error.name === 'AbortError') {
                throw error;
            }
            if (error.message.includes('fetch') || error.message.includes('Worker')) {
                throw new Error('Server not reachable. Is it running?');
            }
            throw error;
        } finally {
            if (showLoading && loadingManager) {
                loadingManager.decrementRequests();
            }
        }
    }

    /**
     * Fetches the available tick range (minTick, maxTick) for environment data.
     * This remains on the main thread as it's a small JSON payload.
     *
     * @param {string|null} [runId=null] - The specific run ID to fetch the tick range for.
     * @returns {Promise<{minTick: number, maxTick: number}>} A promise that resolves to an object containing the min and max tick.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchTickRange(runId = null) {
        const url = runId
            ? `/visualizer/api/environment/ticks?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/environment/ticks`;

        if (loadingManager) {
            loadingManager.incrementRequests();
        }

        try {
            const response = await fetch(url);
            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                throw new Error(errorData.message || `HTTP ${response.status}`);
            }
            return await response.json();
        } finally {
            if (loadingManager) {
                loadingManager.decrementRequests();
            }
        }
    }
}
