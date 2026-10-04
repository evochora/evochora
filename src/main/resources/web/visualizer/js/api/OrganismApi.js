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
     * suitable for rendering markers on the world grid. With a root, the answer also tells how
     * the organisms descend from it.
     *
     * @param {number} tick - The tick number to fetch organism data for.
     * @param {string|null} [runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @param {string|null} [options.root=null] - The root of descent: an organism id, 'all' for the
     *        virtual root above the founders, or 'auto' for the common ancestor of the organisms
     *        alive at the tick. Without it the answer carries no descent.
     * @param {boolean} [options.showLoading=true] - Whether the request shows the loading indicator.
     * @returns {Promise<{organisms: Array<object>, totalOrganismCount: number, descent: object|null}>}
     *          A promise that resolves to the organism summaries, the number of organisms created up
     *          to the tick, and the descent of the organisms from the root (null without a root).
     * @throws {Error} If the network request fails or the server returns an error; a root that is
     *         malformed answers with status 400, a root that is not indexed with status 404.
     */
    async fetchOrganismsAtTick(tick, runId = null, options = {}) {
        const { signal = null, root = null, showLoading = true } = options;
        const params = new URLSearchParams();
        if (runId) {
            params.set('runId', runId);
        }
        if (root !== null && root !== undefined) {
            params.set('root', String(root));
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

        const data = await apiClient.fetch(url, fetchOptions, { showLoading });

        // API response shape: { organisms: [...], totalOrganismCount: N, descent: {...} (only with a root) }
        return {
            organisms: data.organisms,
            totalOrganismCount: data.totalOrganismCount,
            descent: data.descent ?? null
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
     * Fetches every mutation the organism and its ancestors received at their births.
     *
     * The answer is the same at every tick of a run — a mutation is recorded once, at the birth of
     * the organism that received it — so the tick segment the route requires is not read by the
     * server and any tick of the run answers the same. The cells carry absolute coordinates on the
     * body of the requested organism.
     *
     * @param {number} tick - The tick number the route requires; not read by the server.
     * @param {number} organismId - The ID of the organism whose lineage to fetch.
     * @param {string|null} [runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<{organismId: number, events: Array<object>}>} A promise that resolves to the
     *          lineage's events, oldest generation first.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchOrganismMutations(tick, organismId, runId = null, options = {}) {
        const { signal = null } = options;
        const params = new URLSearchParams();
        if (runId) {
            params.set('runId', runId);
        }

        const query = params.toString();
        const url = `/visualizer/api/organisms/${tick}/${organismId}/mutations${query ? `?${query}` : ''}`;

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
     * @param {object} [options={}] - Optional settings.
     * @param {boolean} [options.showLoading=true] - Whether the request counts towards the
     *        loading indicator; false for a poll the user did not start and need not see.
     * @returns {Promise<{minTick: number, maxTick: number}>} A promise that resolves to an object containing the min and max tick.
     * @throws {Error} If the network request fails or the server returns an error.
     */
    async fetchTickRange(runId = null, { showLoading = true } = {}) {
        const url = runId
            ? `/visualizer/api/organisms/ticks?runId=${encodeURIComponent(runId)}`
            : `/visualizer/api/organisms/ticks`;
        
        return apiClient.fetch(url, {}, { showLoading });
    }
}


