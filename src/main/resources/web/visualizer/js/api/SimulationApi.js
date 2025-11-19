/**
 * API client for simulation metadata and tick range endpoints.
 */
class SimulationApi {
    /**
     * Fetches simulation metadata for a given run ID.
     * 
     * @param {string|null} runId - Optional run ID (defaults to latest run if null)
     * @returns {Promise<Object>} SimulationMetadata as JSON object
     * @throws {Error} If the request fails
     */
    async fetchMetadata(runId = null) {
        const url = runId 
            ? `/visualizer/api/simulation/metadata?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/simulation/metadata`;
        
        return apiClient.fetch(url);
    }
    
    /**
     * Fetches tick range (minTick, maxTick) for a given run ID.
     * 
     * @param {string|null} runId - Optional run ID (defaults to latest run if null)
     * @returns {Promise<{minTick: number, maxTick: number}>} Tick range
     * @throws {Error} If the request fails
     */
    async fetchTickRange(runId = null) {
        const url = runId
            ? `/visualizer/api/simulation/ticks?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/simulation/ticks`;
        
        return apiClient.fetch(url);
    }
}

// Export for global availability
window.SimulationApi = SimulationApi;

