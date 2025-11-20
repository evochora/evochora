/**
 * API client for organism-related data endpoints.
 * This class provides methods to fetch summary and detailed information about
 * organisms at specific ticks.
 *
 * @class OrganismApi
 */
class OrganismApi {

    /**
     * Fetches a list of organism summaries for a given tick.
     * Each summary contains high-level information like ID, position, and energy,
     * suitable for rendering markers on the world grid.
     *
     * @param {number} tick - The tick number to fetch organism data for.
     * @param {string|null} [runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @returns {Promise<Array<object>>} A promise that resolves to an array of organism summary objects.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchOrganismsAtTick(tick, runId = null) {
        const params = new URLSearchParams();
        if (runId) {
            params.set('runId', runId);
        }

        const query = params.toString();
        const url = `/visualizer/api/organisms/${tick}${query ? `?${query}` : ''}`;

        const data = await apiClient.fetch(url, {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        });

        // API response shape: { runId, tick, organisms: [...] }
        return Array.isArray(data.organisms) ? data.organisms : [];
    }

    /**
     * Fetches detailed information for a single organism at a specific tick.
     * This includes both static info (like program ID) and the full dynamic state
     * (registers, stacks, etc.), suitable for display in the sidebar.
     *
     * @param {number} tick - The tick number.
     * @param {number} organismId - The ID of the organism to fetch.
     * @param {string|null} [runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @returns {Promise<object>} A promise that resolves to the detailed organism state object.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchOrganismDetails(tick, organismId, runId = null) {
        const params = new URLSearchParams();
        if (runId) {
            params.set('runId', runId);
        }

        const query = params.toString();
        const url = `/visualizer/api/organisms/${tick}/${organismId}${query ? `?${query}` : ''}`;

        return apiClient.fetch(url, {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        });
    }
}

// Export for global availability
window.OrganismApi = OrganismApi;


