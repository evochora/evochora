/**
 * API client for environment data endpoints.
 */
class EnvironmentApi {
    /**
     * Fetches environment data for a specific tick and region.
     * 
     * @param {number} tick - The tick number
     * @param {{x1: number, x2: number, y1: number, y2: number}} region - Viewport region
     * @param {Object} options - Optional parameters
     * @param {string|null} options.runId - Optional run ID (defaults to latest run)
     * @param {AbortSignal} options.signal - AbortSignal for request cancellation
     * @returns {Promise<{tick: number, runId: string, region: Object, cells: Array}>} Environment data
     * @throws {Error} If the request fails
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
        
        try {
            const fetchOptions = {};
            if (signal) {
                fetchOptions.signal = signal;
            }
            
            const response = await fetch(url, fetchOptions);
            
            if (!response.ok) {
                // Handle AbortError separately
                if (signal && signal.aborted) {
                    throw new DOMException('Request aborted', 'AbortError');
                }
                
                if (response.status === 404) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || `Tick ${tick} not found`);
                }
                if (response.status === 400) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || 'Invalid request parameters');
                }
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            return await response.json();
        } catch (error) {
            // Re-throw AbortError as-is (should be ignored by caller)
            if (error.name === 'AbortError') {
                throw error;
            }
            
            if (error.name === 'TypeError' && error.message.includes('fetch')) {
                throw new Error('Server not reachable - is it running?');
            }
            throw error;
        }
    }
}

// Export for global availability
window.EnvironmentApi = EnvironmentApi;

