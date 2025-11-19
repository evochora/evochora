class OrganismApi {

    /**
     * Fetches organism summaries for a given tick.
     *
     * @param {number} tick - Tick number
     * @param {string|null} runId - Optional runId (null = let server resolve latest)
     * @returns {Promise<Array<Object>>} organismsForTick
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
     * Fetches detailed organism information for a specific organism at a given tick.
     *
     * @param {number} tick - Tick number
     * @param {number} organismId - Organism ID
     * @param {string|null} runId - Optional runId (null = let server resolve latest)
     * @returns {Promise<Object>} OrganismTickDetails with staticInfo and state
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


