/**
 * Analytics API Client
 * 
 * Provides methods to interact with the Analytics backend endpoints.
 * 
 * @module AnalyticsApi
 */

const AnalyticsApi = (function() {
    'use strict';
    
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
    async function listRuns() {
        return await fetchJson('/runs');
    }
    
    /**
     * Fetches the manifest for a specific run.
     * Contains all metrics and their configurations.
     * 
     * @param {string} runId - Simulation run ID
     * @returns {Promise<{runId: string, metrics: Array<ManifestEntry>}>}
     */
    async function getManifest(runId) {
        return await fetchJson(`/manifest?runId=${encodeURIComponent(runId)}`);
    }
    
    /**
     * Lists Parquet files for a specific metric/LOD.
     * 
     * @param {string} runId - Simulation run ID
     * @param {string} prefix - File prefix (e.g. "population/lod0/")
     * @returns {Promise<string[]>} Array of relative file paths
     */
    async function listFiles(runId, prefix) {
        return await fetchJson(`/list?runId=${encodeURIComponent(runId)}&prefix=${encodeURIComponent(prefix)}`);
    }
    
    /**
     * Gets LOD file count information for a metric.
     * Used for Auto-LOD selection: choose LOD with acceptable file count.
     * 
     * @param {string} runId - Simulation run ID
     * @param {string} metricId - Metric identifier (e.g., 'population')
     * @returns {Promise<Array<{lod: string, fileCount: number}>>}
     */
    async function getLodInfo(runId, metricId) {
        return await fetchJson(`/lod-info?runId=${encodeURIComponent(runId)}&metricId=${encodeURIComponent(metricId)}`);
    }
    
    /**
     * Queries analytics data from the server (legacy server-side mode).
     * Server aggregates all Parquet files and returns JSON.
     * 
     * @param {string} runId - Simulation run ID
     * @param {string} metric - Metric identifier (e.g., 'population')
     * @param {string} [lod] - Optional LOD level. If not specified, server auto-selects.
     * @returns {Promise<Array<Object>>} Array of data rows
     * @deprecated Use fetchParquetBlob() + DuckDBClient for client-side queries
     */
    async function queryData(runId, metric, lod = null) {
        let url = `/data?runId=${encodeURIComponent(runId)}&metric=${encodeURIComponent(metric)}`;
        if (lod) {
            url += `&lod=${encodeURIComponent(lod)}`;
        }
        return await fetchJson(url);
    }
    
    /**
     * Fetches a merged Parquet file as Blob for client-side DuckDB WASM queries.
     * The server merges all Parquet files for a metric into one and streams it.
     * 
     * @param {string} metric - Metric identifier (e.g., 'vital_stats')
     * @param {string} [runId] - Optional run ID. If not specified, server uses latest.
     * @param {string} [lod='lod0'] - LOD level
     * @returns {Promise<Blob>} Merged Parquet file as Blob
     */
    async function fetchParquetBlob(metric, runId = null, lod = 'lod0') {
        let url = `${BASE_PATH}/parquet?metric=${encodeURIComponent(metric)}&lod=${encodeURIComponent(lod)}`;
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        
        const response = await fetch(url);
        if (!response.ok) {
            const text = await response.text();
            throw new Error(`API Error ${response.status}: ${text || response.statusText}`);
        }
        return await response.blob();
    }
    
    /**
     * Builds the full URL for a Parquet file.
     * DuckDB WASM requires absolute URLs (with scheme and host).
     * 
     * @param {string} runId - Simulation run ID
     * @param {string} filePath - Relative file path from listFiles
     * @returns {string} Full absolute URL to fetch the file
     */
    function getFileUrl(runId, filePath) {
        // DuckDB WASM needs absolute URLs, not relative paths
        // Encode each path segment separately to preserve slashes
        const encodedPath = filePath.split('/').map(segment => encodeURIComponent(segment)).join('/');
        return `${window.location.origin}${BASE_PATH}/files/${encodedPath}?runId=${encodeURIComponent(runId)}`;
    }
    
    /**
     * Parses a dataSource glob pattern to extract the prefix for listing files.
     * 
     * Example: "population/lod0/**\/*.parquet" → "population/lod0/"
     * 
     * @param {string} pattern - Glob pattern from ManifestEntry.dataSources
     * @returns {string} Prefix for listFiles
     */
    function extractPrefixFromPattern(pattern) {
        // Find the position of first wildcard
        const wildcardIdx = pattern.search(/[*?]/);
        if (wildcardIdx === -1) {
            // No wildcard, return as-is (unlikely)
            return pattern;
        }
        // Return everything up to the last slash before the wildcard
        const prefix = pattern.substring(0, wildcardIdx);
        const lastSlash = prefix.lastIndexOf('/');
        return lastSlash >= 0 ? prefix.substring(0, lastSlash + 1) : '';
    }
    
    /**
     * Fetches all Parquet file URLs for a metric at a specific LOD level.
     * Combines listFiles with getFileUrl.
     * 
     * @param {string} runId - Simulation run ID
     * @param {Object} metric - ManifestEntry from manifest
     * @param {string} lodLevel - LOD level key (e.g. "lod0", "lod1")
     * @returns {Promise<string[]>} Array of full URLs
     */
    async function getParquetFileUrls(runId, metric, lodLevel) {
        const pattern = metric.dataSources[lodLevel];
        if (!pattern) {
            throw new Error(`LOD level '${lodLevel}' not available for metric '${metric.id}'`);
        }
        
        const prefix = extractPrefixFromPattern(pattern);
        const files = await listFiles(runId, prefix);
        
        // Filter to only .parquet files and build URLs
        return files
            .filter(f => f.endsWith('.parquet'))
            .map(f => getFileUrl(runId, f));
    }
    
    /**
     * Gets available LOD levels for a metric.
     * 
     * @param {Object} metric - ManifestEntry from manifest
     * @returns {string[]} Array of LOD level keys (e.g. ["lod0", "lod1", "lod2"])
     */
    function getAvailableLodLevels(metric) {
        return Object.keys(metric.dataSources || {}).sort();
    }
    
    /**
     * Determines the best LOD level based on data range and zoom level.
     * Higher LOD = more aggregated = faster for large ranges.
     * 
     * @param {Object} metric - ManifestEntry from manifest
     * @param {number} tickRange - Number of ticks to display
     * @param {number} maxPoints - Maximum desired data points (default: 1000)
     * @returns {string} Best LOD level key
     */
    function selectOptimalLod(metric, tickRange, maxPoints = 1000) {
        const lodLevels = getAvailableLodLevels(metric);
        if (lodLevels.length === 0) {
            throw new Error(`No LOD levels available for metric '${metric.id}'`);
        }
        
        // For now, simple heuristic:
        // - lod0 (raw): up to maxPoints ticks
        // - lod1: up to maxPoints * lodFactor ticks
        // - etc.
        
        // Assume lodFactor of 10 between levels (could be made configurable)
        const lodFactor = 10;
        
        for (let i = 0; i < lodLevels.length; i++) {
            const level = lodLevels[i];
            const threshold = maxPoints * Math.pow(lodFactor, i);
            
            if (tickRange <= threshold) {
                return level;
            }
        }
        
        // Return highest LOD if range exceeds all thresholds
        return lodLevels[lodLevels.length - 1];
    }
    
    /**
     * Selects optimal LOD based on file counts.
     * Prefers higher LOD (less files) if file count exceeds threshold.
     * 
     * @param {Array<{lod: string, fileCount: number}>} lodInfo - LOD info from backend
     * @param {number} maxFiles - Maximum acceptable file count (default: 100)
     * @returns {string} Best LOD level key
     */
    function selectOptimalLodByFileCount(lodInfo, maxFiles = 100) {
        if (!lodInfo || lodInfo.length === 0) {
            return 'lod0'; // Fallback
        }
        
        // Sort by LOD level (lod0, lod1, lod2, ...)
        const sorted = [...lodInfo].sort((a, b) => a.lod.localeCompare(b.lod));
        
        // Find first LOD with acceptable file count
        for (const info of sorted) {
            if (info.fileCount <= maxFiles) {
                return info.lod;
            }
        }
        
        // If all have too many files, use highest LOD (least files)
        return sorted[sorted.length - 1].lod;
    }
    
    // Public API
    return {
        listRuns,
        getManifest,
        listFiles,
        getLodInfo,
        queryData,
        fetchParquetBlob,
        getFileUrl,
        extractPrefixFromPattern,
        getParquetFileUrls,
        getAvailableLodLevels,
        selectOptimalLod,
        selectOptimalLodByFileCount
    };
    
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = AnalyticsApi;
}

