import { apiClient } from './ApiClient.js';

/**
 * API client for simulation-level endpoints, such as retrieving metadata and tick ranges.
 * This class provides a high-level interface for fetching data related to the overall simulation run.
 *
 * @class SimulationApi
 */
export class SimulationApi {
    /**
     * Fetches the complete simulation metadata for a given run ID.
     * If no run ID is provided, the server will default to the latest available run.
     * 
     * @param {string|null} [runId=null] - The specific run ID to fetch metadata for.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<object>} A promise that resolves to the SimulationMetadata object.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchMetadata(runId = null, options = {}) {
        const { signal = null } = options;
        const url = runId 
            ? `/visualizer/api/simulation/metadata?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/simulation/metadata`;
        
        const fetchOptions = {};
        if (signal) {
            fetchOptions.signal = signal;
        }

        return apiClient.fetch(url, fetchOptions);
    }
    
}

