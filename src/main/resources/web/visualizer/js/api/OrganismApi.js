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

        const response = await fetch(url, {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        });

        if (!response.ok) {
            const text = await response.text().catch(() => '');
            throw new Error(`Failed to fetch organisms for tick ${tick}: ${response.status} ${text}`);
        }

        const data = await response.json();
        // API response shape: { runId, tick, organisms: [...] }
        return Array.isArray(data.organisms) ? data.organisms : [];
    }
}

// Export for global availability
window.OrganismApi = OrganismApi;


