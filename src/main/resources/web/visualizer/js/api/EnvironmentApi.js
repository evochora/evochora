import { loadingManager } from '../ui/LoadingManager.js';

/* global protobuf */

/*
 * =============================================================================
 * SCHEMA DUPLICATION NOTICE
 * =============================================================================
 * 
 * The Protobuf schema below is DUPLICATED from the server-side definition at:
 *   src/main/proto/org/evochora/datapipeline/api/contracts/http_api_contracts.proto
 * 
 * This duplication exists to avoid adding Node.js as a build dependency.
 * The trade-off is that schema changes must be manually synchronized.
 * 
 * WHEN TO REFACTOR:
 *   - If 3+ Protobuf messages are used in the frontend
 *   - If schema drift bugs occur frequently
 *   - If the project adds Node.js for other reasons anyway
 * 
 * HOW TO REFACTOR (using protoc --js_out + google-protobuf):
 * 
 * 1. Add to build.gradle.kts:
 *    
 *    protobuf {
 *        generateProtoTasks {
 *            all().forEach { task ->
 *                task.builtins {
 *                    create("js") {
 *                        option("import_style=commonjs,binary")
 *                    }
 *                }
 *            }
 *        }
 *    }
 *    
 *    tasks.register<Copy>("copyProtoJsToWeb") {
 *        dependsOn("generateProto")
 *        from("build/generated/source/proto/main/js")
 *        into("src/main/resources/web/visualizer/proto")
 *    }
 * 
 * 2. Update index.html (load as CommonJS before ES6 modules):
 *    
 *    <script src="https://cdn.jsdelivr.net/npm/google-protobuf/google-protobuf.min.js"></script>
 *    <script src="./proto/org/evochora/datapipeline/api/contracts/http_api_contracts_pb.js"></script>
 *    <script type="module" src="./js/main.js"></script>
 * 
 * 3. Update this file:
 *    
 *    // Remove PROTO_SCHEMA constant and ensureProtoInitialized()
 *    // Access global namespace instead:
 *    const { EnvironmentHttpResponse, CellHttpResponse } = 
 *        proto.org.evochora.datapipeline.api.contracts;
 *    
 *    // Change decode call:
 *    // FROM: EnvironmentHttpResponse.decode(new Uint8Array(arrayBuffer))
 *    // TO:   EnvironmentHttpResponse.deserializeBinary(new Uint8Array(arrayBuffer))
 *    
 *    // Change field access:
 *    // FROM: message.cells, message.tickNumber
 *    // TO:   message.getCellsList(), message.getTickNumber()
 * 
 * Benefits of refactoring:
 *   - Single source of truth (no schema duplication)
 *   - Smaller bundle (google-protobuf ~30KB vs protobufjs ~170KB)
 *   - No runtime schema parsing overhead
 * 
 * Trade-offs:
 *   - Generated JS is CommonJS (loaded via <script>, not ES6 import)
 *   - google-protobuf is ~10-20% slower at decoding than protobufjs
 * =============================================================================
 */

/**
 * Protobuf schema for environment API responses.
 * 
 * WARNING: This is duplicated from http_api_contracts.proto on the server.
 * If you modify the server schema, you MUST update this definition as well.
 * 
 * @see src/main/proto/org/evochora/datapipeline/api/contracts/http_api_contracts.proto
 */
const PROTO_SCHEMA = `
syntax = "proto3";
message EnvironmentHttpResponse {
  int64 tick_number = 1;
  int64 total_cells = 2;
  repeated CellHttpResponse cells = 3;
}
message CellHttpResponse {
  repeated int32 coordinates = 1 [packed=true];
  int32 molecule_type = 2;
  int32 molecule_value = 3;
  int32 owner_id = 4;
  int32 opcode_id = 5;
  int32 marker = 6;
}
`;

// Cached protobuf type (initialized lazily)
let EnvironmentHttpResponse = null;

// Cached type mappings from metadata (id -> name)
let moleculeTypeMap = null;
let opcodeMap = null;

/**
 * Initializes the Protobuf parser lazily.
 * Uses runtime schema parsing (protobufjs) - see header comment for codegen alternative.
 * @returns {Promise<void>}
 */
async function ensureProtoInitialized() {
    if (EnvironmentHttpResponse) return;
    
    const root = protobuf.parse(PROTO_SCHEMA).root;
    EnvironmentHttpResponse = root.lookupType('EnvironmentHttpResponse');
}

/**
 * Sets the type mappings from metadata.
 * Call this after fetching metadata to enable ID-to-name resolution.
 * Safe to call with null/undefined metadata (no-op).
 * 
 * @param {object|null|undefined} metadata - The simulation metadata containing moleculeTypes and opcodes maps.
 */
export function setTypeMappings(metadata) {
    if (!metadata) return;  // Guard: fetchMetadata may return null/undefined
    if (metadata.moleculeTypes) {
        moleculeTypeMap = metadata.moleculeTypes;
    }
    if (metadata.opcodes) {
        opcodeMap = metadata.opcodes;
    }
}

/**
 * API client for environment-related data endpoints.
 * This class handles fetching the state of the world grid for a specific region and time (tick).
 * 
 * Uses Protobuf binary format for efficient transfer of large environment data.
 *
 * @class EnvironmentApi
 */
export class EnvironmentApi {
    /**
     * Fetches environment data (cell states) for a specific tick and a given rectangular region.
     * Supports cancellation via an AbortSignal.
     * Includes performance timing (visible in browser console at Debug level).
     * 
     * @param {number} tick - The tick number to fetch data for.
     * @param {{x1: number, x2: number, y1: number, y2: number}} region - The viewport region to fetch.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {string|null} [options.runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<{cells: Array<object>}>} A promise that resolves to the environment data.
     * @throws {Error} If the network request fails, is aborted, or the server returns an error.
     */
    async fetchEnvironmentData(tick, region, options = {}) {
        const { runId = null, signal = null } = options;
        
        // Ensure protobuf is initialized
        await ensureProtoInitialized();
        
        // Build region query parameter
        const regionParam = `${region.x1},${region.x2},${region.y1},${region.y2}`;
        
        // Build URL
        let url = `/visualizer/api/environment/${tick}?region=${encodeURIComponent(regionParam)}`;
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        
        const fetchOptions = {
            headers: {
                'Accept': 'application/x-protobuf'
            }
        };
        if (signal) {
            fetchOptions.signal = signal;
        }
        
        // --- Timing: Start ---
        const totalStart = performance.now();
        
        if (loadingManager) {
            loadingManager.incrementRequests();
        }
        
        try {
            // --- Timing: Network fetch ---
            const fetchStart = performance.now();
            const response = await fetch(url, fetchOptions);
            const fetchTime = performance.now() - fetchStart;
            
            if (!response.ok) {
                // Try to parse error as JSON
                const errorText = await response.text();
                let errorMessage;
                try {
                    const errorData = JSON.parse(errorText);
                    errorMessage = errorData.message || `HTTP ${response.status}: ${response.statusText}`;
                } catch {
                    errorMessage = `HTTP ${response.status}: ${response.statusText}`;
                }
                throw new Error(errorMessage);
            }
            
            // Read server timing headers
            const serverTiming = {
                load: response.headers.get('X-Timing-Load-Ms'),
                db: response.headers.get('X-Timing-Db-Ms'),
                decompress: response.headers.get('X-Timing-Decompress-Ms'),
                transform: response.headers.get('X-Timing-Transform-Ms'),
                serialize: response.headers.get('X-Timing-Serialize-Ms'),
                total: response.headers.get('X-Timing-Total-Ms'),
                cellCount: response.headers.get('X-Cell-Count')
            };
            
            // --- Timing: Get binary data ---
            const binaryStart = performance.now();
            const arrayBuffer = await response.arrayBuffer();
            const binaryTime = performance.now() - binaryStart;
            
            // --- Timing: Parse Protobuf ---
            const parseStart = performance.now();
            const message = EnvironmentHttpResponse.decode(new Uint8Array(arrayBuffer));
            const parseTime = performance.now() - parseStart;
            
            // --- Timing: Transform to app format ---
            const transformStart = performance.now();
            const cells = transformCells(message.cells);
            const transformTime = performance.now() - transformStart;
            
            const totalTime = performance.now() - totalStart;
            
            // Log timing at debug level (only visible when "Verbose" is enabled in DevTools)
            console.debug(`[Environment API] Tick ${tick}`, {
                server: {
                    loadMs: serverTiming.load,
                    dbMs: serverTiming.db,
                    decompressMs: serverTiming.decompress,
                    transformMs: serverTiming.transform,
                    serializeMs: serverTiming.serialize,
                    totalMs: serverTiming.total
                },
                client: {
                    fetchMs: fetchTime.toFixed(1),
                    binaryMs: binaryTime.toFixed(1),
                    parseMs: parseTime.toFixed(1),
                    transformMs: transformTime.toFixed(1),
                    totalMs: totalTime.toFixed(1)
                },
                cells: serverTiming.cellCount,
                sizeKb: response.headers.get('Content-Length') 
                    ? (parseInt(response.headers.get('Content-Length')) / 1024).toFixed(1) 
                    : 'unknown'
            });
            
            return { cells };
        } catch (error) {
            if (error instanceof TypeError && error.message.includes('fetch')) {
                throw new Error('Server not reachable. Is it running?');
            }
            throw error;
        } finally {
            if (loadingManager) {
                loadingManager.decrementRequests();
            }
        }
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
        
        // Tick range is still JSON (small payload)
        if (loadingManager) {
            loadingManager.incrementRequests();
        }
        
        try {
            const response = await fetch(url);
            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                throw new Error(errorData.message || `HTTP ${response.status}`);
            }
            return await response.json();
        } finally {
            if (loadingManager) {
                loadingManager.decrementRequests();
            }
        }
    }
}

/**
 * Transforms Protobuf cells to the format expected by the app.
 * Resolves numeric IDs to names using cached mappings.
 * Optimized for large datasets (1M+ cells).
 * 
 * @param {Array} protoCells - Array of CellHttpResponse messages.
 * @returns {Array<object>} Transformed cells with string names.
 */
function transformCells(protoCells) {
    const len = protoCells.length;
    const result = new Array(len);
    
    for (let i = 0; i < len; i++) {
        const cell = protoCells[i];
        const opcodeId = cell.opcodeId;
        
        result[i] = {
            coordinates: cell.coordinates,  // Already an array-like, no need to copy
            moleculeType: resolveMoleculeType(cell.moleculeType),
            moleculeValue: cell.moleculeValue,
            ownerId: cell.ownerId,
            opcodeName: opcodeId >= 0 ? resolveOpcode(opcodeId) : null,
            opcodeId: opcodeId,  // Raw ID for tooltip (shows full ID for unknown opcodes)
            marker: cell.marker
        };
    }
    
    return result;
}

/**
 * Resolves molecule type ID to name using cached mapping.
 * Fails fast if mappings not initialized (developer error).
 * Returns 'UNKNOWN' for unknown IDs (valid simulation state from mutations).
 * 
 * @param {number} typeId - The molecule type ID.
 * @returns {string} The molecule type name or "UNKNOWN".
 * @throws {Error} If moleculeTypeMap not initialized.
 */
function resolveMoleculeType(typeId) {
    if (!moleculeTypeMap) {
        throw new Error('moleculeTypeMap not initialized. Call setTypeMappings() first.');
    }
    return moleculeTypeMap[typeId] || 'UNKNOWN';
}

/**
 * Resolves opcode ID to name using cached mapping.
 * Fails fast if mappings not initialized (developer error).
 * Returns '??' for unknown IDs (valid simulation state from mutations).
 * The raw opcodeId is passed separately for tooltip display.
 * 
 * @param {number} opcodeId - The opcode ID.
 * @returns {string} The opcode name or "??" for unknown.
 * @throws {Error} If opcodeMap not initialized.
 */
function resolveOpcode(opcodeId) {
    if (!opcodeMap) {
        throw new Error('opcodeMap not initialized. Call setTypeMappings() first.');
    }
    return opcodeMap[opcodeId] || '??';
}
