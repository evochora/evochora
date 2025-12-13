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
     * Queries analytics data from the server (legacy server-side mode).
     * Server aggregates all Parquet files and returns JSON.
     * 
     * @param {string} runId - Simulation run ID
     * @param {string} metric - Metric identifier (e.g., 'population')
     * @param {string} [lod] - Optional LOD level. If not specified, server auto-selects.
     * @returns {Promise<Array<Object>>} Array of data rows
     * @throws {Error} With code 'NO_DATA' if metric has no data yet
     * @deprecated Use fetchParquetBlob() + DuckDBClient for client-side queries
     */
export async function queryData(runId, metric, lod = null) {
        let url = `/data?runId=${encodeURIComponent(runId)}&metric=${encodeURIComponent(metric)}`;
        if (lod) {
            url += `&lod=${encodeURIComponent(lod)}`;
        }
        const response = await fetch(`${BASE_PATH}${url}`);
        if (!response.ok) {
            if (response.status === 404) {
                const error = new Error('No data available yet');
                error.code = 'NO_DATA';
                throw error;
            }
            const text = await response.text();
            throw new Error(`API Error ${response.status}: ${text || response.statusText}`);
        }
        return await response.json();
    }
    
    /**
     * Fetches a merged Parquet file as Blob for client-side DuckDB WASM queries.
     * The server merges all Parquet files for a metric into one and streams it.
     * 
     * @param {string} metric - Metric identifier (e.g., 'vital_stats')
     * @param {string} [runId] - Optional run ID. If not specified, server uses latest.
     * @param {string} [lod=null] - LOD level. If null, server auto-selects optimal LOD.
     * @returns {Promise<{blob: Blob, metadata: {lod: string, fileCount: number, rowCount: number, processTimeMs: number}}>}
     * @throws {Error} With code 'NO_DATA' if metric has no data yet
     */
export async function fetchParquetBlob(metric, runId = null, lod = null) {
        let url = `${BASE_PATH}/parquet?metric=${encodeURIComponent(metric)}`;
        if (lod) {
            url += `&lod=${encodeURIComponent(lod)}`;
        }
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        
        const response = await fetch(url);
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
            processTimeMs: parseInt(response.headers.get('X-Process-Time-Ms') || '0', 10)
        };
        
        const blob = await response.blob();
        return { blob, metadata };
}

