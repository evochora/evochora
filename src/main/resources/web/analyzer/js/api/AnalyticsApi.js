/**
 * Analytics API Client
 *
 * Provides methods to interact with the Analytics backend endpoints.
 *
 * @module AnalyticsApi
 */

    const BASE_PATH = '/analyzer/api';

    /**
     * Fetches JSON from an API endpoint.
     *
     * @param {string} path - API path (without base)
     * @returns {Promise<Object>} Parsed JSON response
     * @throws {Error} If response is not OK
     */
    async function fetchJson(path) {
        const response = await fetch(`${BASE_PATH}${path}`);
        if (!response.ok) {
            const text = await response.text();
            throw new Error(`API Error ${response.status}: ${text || response.statusText}`);
        }
        return await response.json();
    }

    /**
     * Parses an integer from a response header.
     *
     * @param {Response} response
     * @param {string} name - Header name
     * @returns {number|null}
     */
    function parseIntHeader(response, name) {
        const val = response.headers.get(name);
        return val != null ? parseInt(val, 10) : null;
    }

    /**
     * Lists all available run IDs.
     *
     * @returns {Promise<Array<{runId: string, startTime?: number, status?: string}>>}
     */
export async function listRuns() {
        return await fetchJson('/runs');
    }

    /**
     * Fetches the manifest for a specific run.
     * Contains all metrics and their configurations.
     *
     * @param {string} runId - Simulation run ID
 * @returns {Promise<{runId: string, metrics: Array<Object>}>}
     */
export async function getManifest(runId) {
        return await fetchJson(`/manifest?runId=${encodeURIComponent(runId)}`);
    }

    /**
     * Fetches tick range and file count for a metric (lightweight, no file I/O).
     * Used to determine whether tick-range windowing is needed before making
     * the heavier /data or /parquet requests.
     *
     * @param {string} metric - Metric identifier
     * @param {string} runId - Simulation run ID
     * @param {string} [lod] - Optional LOD level
     * @returns {Promise<{tickMin: number|null, tickMax: number|null, fileCount: number, lod: string}>}
     */
export async function fetchTickRange(metric, runId, lod = null) {
        let url = `/tick-range?metric=${encodeURIComponent(metric)}&runId=${encodeURIComponent(runId)}`;
        if (lod) {
            url += `&lod=${encodeURIComponent(lod)}`;
        }
        return await fetchJson(url);
    }

    /**
     * Queries analytics data from the server.
     * Server aggregates all Parquet files and returns JSON.
     * Used by metrics without a generated query (server-side aggregation).
     *
     * @param {string} runId - Simulation run ID
     * @param {string} metric - Metric identifier (e.g., 'population')
     * @param {string} [lod] - Optional LOD level. If not specified, server auto-selects.
     * @param {AbortSignal} [signal] - Optional abort signal
     * @param {number} [tickFrom] - Optional minimum tick (inclusive)
     * @param {number} [tickTo] - Optional maximum tick (inclusive)
     * @returns {Promise<{data: Array<Object>, lod: string, tickMin: number|null, tickMax: number|null}>}
     * @throws {Error} With code 'NO_DATA' if metric has no data yet
     */
export async function queryData(runId, metric, lod = null, signal = null, tickFrom = null, tickTo = null) {
        let url = `/data?runId=${encodeURIComponent(runId)}&metric=${encodeURIComponent(metric)}`;
        if (lod) {
            url += `&lod=${encodeURIComponent(lod)}`;
        }
        if (tickFrom != null) {
            url += `&tickFrom=${tickFrom}`;
        }
        if (tickTo != null) {
            url += `&tickTo=${tickTo}`;
        }
        const response = await fetch(`${BASE_PATH}${url}`, signal ? { signal } : undefined);
        if (!response.ok) {
            if (response.status === 404) {
                const error = new Error('No data available yet');
                error.code = 'NO_DATA';
                throw error;
            }
            const text = await response.text();
            throw new Error(`API Error ${response.status}: ${text || response.statusText}`);
        }
        const data = await response.json();
        const resolvedLod = response.headers.get('X-LOD-Level') || null;
        const tickMin = parseIntHeader(response, 'X-Tick-Min');
        const tickMax = parseIntHeader(response, 'X-Tick-Max');
        return { data, lod: resolvedLod, tickMin, tickMax };
    }

    /**
     * Fetches a merged Parquet file as Blob for client-side DuckDB WASM queries.
     * The server merges all Parquet files for a metric into one and streams it.
     *
     * @param {string} metric - Metric identifier (e.g., 'vital_stats')
     * @param {string} [runId] - Optional run ID. If not specified, server uses latest.
     * @param {string} [lod=null] - LOD level. If null, server auto-selects optimal LOD.
     * @param {AbortSignal} [signal] - Optional abort signal
     * @param {number} [tickFrom] - Optional minimum tick (inclusive)
     * @param {number} [tickTo] - Optional maximum tick (inclusive)
     * @returns {Promise<{blob: Blob, metadata: {lod: string, fileCount: number, rowCount: number, processTimeMs: number, tickMin: number|null, tickMax: number|null}}>}
     * @throws {Error} With code 'NO_DATA' if metric has no data yet
     */
export async function fetchParquetBlob(metric, runId = null, lod = null, signal = null, tickFrom = null, tickTo = null) {
        let url = `${BASE_PATH}/parquet?metric=${encodeURIComponent(metric)}`;
        if (lod) {
            url += `&lod=${encodeURIComponent(lod)}`;
        }
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        if (tickFrom != null) {
            url += `&tickFrom=${tickFrom}`;
        }
        if (tickTo != null) {
            url += `&tickTo=${tickTo}`;
        }

        const response = await fetch(url, signal ? { signal } : undefined);
        if (!response.ok) {
            if (response.status === 404) {
                const error = new Error('No data available yet');
                error.code = 'NO_DATA';
                throw error;
            }
            const text = await response.text();
            throw new Error(`API Error ${response.status}: ${text || response.statusText}`);
        }

        // Extract metadata from response headers
        const metadata = {
            lod: response.headers.get('X-LOD-Level') || 'unknown',
            fileCount: parseInt(response.headers.get('X-File-Count') || '0', 10),
            rowCount: parseInt(response.headers.get('X-Row-Count') || '0', 10),
            processTimeMs: parseInt(response.headers.get('X-Process-Time-Ms') || '0', 10),
            tickMin: parseIntHeader(response, 'X-Tick-Min'),
            tickMax: parseIntHeader(response, 'X-Tick-Max')
        };

        const blob = await response.blob();
        return { blob, metadata };
}
