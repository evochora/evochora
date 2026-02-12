import { apiClient } from './ApiClient.js';

/**
 * API client for organism-related data endpoints.
 * This class provides methods to fetch summary and detailed information about
 * organisms at specific ticks.
 *
 * @class OrganismApi
 */
export class OrganismApi {

    /**
     * Fetches a list of organism summaries for a given tick.
     * Each summary contains high-level information like ID, position, and energy,
     * suitable for rendering markers on the world grid.
     *
     * @param {number} tick - The tick number to fetch organism data for.
     * @param {string|null} [runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<Array<object>>} A promise that resolves to an array of organism summary objects.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchOrganismsAtTick(tick, runId = null, options = {}) {
        const { signal = null } = options;
        const params = new URLSearchParams();
        if (runId) {
            params.set('runId', runId);
        }

        const query = params.toString();
        const url = `/visualizer/api/organisms/${tick}${query ? `?${query}` : ''}`;

        const fetchOptions = {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        };
        if (signal) {
            fetchOptions.signal = signal;
        }

        const data = await apiClient.fetch(url, fetchOptions);

        // API response shape: { organisms: [...], totalOrganismCount: N }
        return {
            organisms: Array.isArray(data.organisms) ? data.organisms : [],
            totalOrganismCount: data.totalOrganismCount || 0
        };
    }

    /**
     * Fetches detailed information for a single organism at a specific tick.
     * This includes both static info (like program ID) and the full dynamic state
     * (registers, stacks, etc.), suitable for display in the sidebar.
     *
     * @param {number} tick - The tick number.
     * @param {number} organismId - The ID of the organism to fetch.
     * @param {string|null} [runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<object>} A promise that resolves to the detailed organism state object.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchOrganismDetails(tick, organismId, runId = null, options = {}) {
        const { signal = null } = options;
        const params = new URLSearchParams();
        if (runId) {
            params.set('runId', runId);
        }

        const query = params.toString();
        const url = `/visualizer/api/organisms/${tick}/${organismId}${query ? `?${query}` : ''}`;

        const fetchOptions = {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        };
        if (signal) {
            fetchOptions.signal = signal;
        }

        return apiClient.fetch(url, fetchOptions);
    }

    /**
     * Fetches the available tick range (minTick, maxTick) for organism data.
     * Returns the ticks that have been indexed by the OrganismIndexer.
     * If no run ID is provided, the server will default to the latest available run.
     * 
     * @param {string|null} [runId=null] - The specific run ID to fetch the tick range for.
     * @returns {Promise<{minTick: number, maxTick: number}>} A promise that resolves to an object containing the min and max tick.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchTickRange(runId = null) {
        const url = runId
            ? `/visualizer/api/organisms/ticks?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/organisms/ticks`;
        
        return apiClient.fetch(url);
    }
}


