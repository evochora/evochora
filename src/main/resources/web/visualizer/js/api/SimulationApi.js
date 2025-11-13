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
        
        try {
            const response = await fetch(url);
            
            if (!response.ok) {
                if (response.status === 404) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || 'Metadata not found');
                }
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            return await response.json();
        } catch (error) {
            if (error.name === 'TypeError' && error.message.includes('fetch')) {
                throw new Error('Server not reachable - is it running?');
            }
            throw error;
        }
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
        
        try {
            const response = await fetch(url);
            
            if (!response.ok) {
                if (response.status === 404) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || 'No ticks available');
                }
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            return await response.json();
        } catch (error) {
            if (error.name === 'TypeError' && error.message.includes('fetch')) {
                throw new Error('Server not reachable - is it running?');
            }
            throw error;
        }
    }
}

// Export for global availability
window.SimulationApi = SimulationApi;

