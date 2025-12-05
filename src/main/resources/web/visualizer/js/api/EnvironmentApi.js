import { apiClient } from './ApiClient.js';

/**
 * API client for environment-related data endpoints.
 * This class handles fetching the state of the world grid for a specific region and time (tick).
 *
 * @class EnvironmentApi
 */
export class EnvironmentApi {
    /**
     * Fetches environment data (cell states) for a specific tick and a given rectangular region.
     * Supports cancellation via an AbortSignal.
     * 
     * @param {number} tick - The tick number to fetch data for.
     * @param {{x1: number, x2: number, y1: number, y2: number}} region - The viewport region to fetch.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {string|null} [options.runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<{tick: number, runId: string, region: object, cells: Array<object>}>} A promise that resolves to the environment data.
     * @throws {Error} If the network request fails, is aborted, or the server returns an error.
     */
    async fetchEnvironmentData(tick, region, options = {}) {
        const { runId = null, signal = null } = options;
        
        // Build region query parameter
        const regionParam = `${region.x1},${region.x2},${region.y1},${region.y2}`;
        
        // Build URL
        let url = `/visualizer/api/environment/${tick}?region=${encodeURIComponent(regionParam)}`;
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        
            const fetchOptions = {};
            if (signal) {
                fetchOptions.signal = signal;
            }
            
        return apiClient.fetch(url, fetchOptions);
    }

    /**
     * Fetches the available tick range (minTick, maxTick) for environment data.
     * Returns the ticks that have been indexed by the EnvironmentIndexer.
     * If no run ID is provided, the server will default to the latest available run.
     * 
     * @param {string|null} [runId=null] - The specific run ID to fetch the tick range for.
     * @returns {Promise<{minTick: number, maxTick: number}>} A promise that resolves to an object containing the min and max tick.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchTickRange(runId = null) {
        const url = runId
            ? `/visualizer/api/environment/ticks?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/environment/ticks`;
        
        return apiClient.fetch(url);
    }
}

