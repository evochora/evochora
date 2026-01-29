/**
 * Web Worker for heavy environment data processing.
 *
 * Offloads from main thread:
 * - fetch() network request
 * - arrayBuffer() reading (includes Gzip decompression)
 * - Protobuf decoding
 * - Cell data transformation
 *
 * This prevents UI freezing during large data loads (1M+ cells).
 *
 * @see EnvironmentApi.js for the main thread interface
 */

/* global protobuf */

// Import protobufjs in worker context
importScripts('https://cdn.jsdelivr.net/npm/protobufjs@8.0.0/dist/protobuf.min.js');

// Cached protobuf type
let EnvironmentHttpResponse = null;

// Cached type mappings (set via 'setMappings' message)
let moleculeTypeMap = null;
let opcodeMap = null;

// Active AbortControllers for cancellable requests (requestId -> AbortController)
const activeAbortControllers = new Map();

/**
 * Protobuf schema - must match server definition.
 * @see EnvironmentApi.js for full documentation on schema duplication.
 */
const PROTO_SCHEMA = `
syntax = "proto3";
message EnvironmentHttpResponse {
  int64 tick_number = 1;
  int64 total_cells = 2;
  repeated CellHttpResponse cells = 3;
  MinimapData minimap = 4;
}
message CellHttpResponse {
  repeated int32 coordinates = 1 [packed=true];
  int32 molecule_type = 2;
  int32 molecule_value = 3;
  int32 owner_id = 4;
  int32 opcode_id = 5;
  int32 marker = 6;
}
message MinimapData {
  int32 width = 1;
  int32 height = 2;
  bytes cell_types = 3;
}
`;

/**
 * Initialize Protobuf parser.
 */
async function ensureProtoInitialized() {
    if (EnvironmentHttpResponse) return;
    const root = protobuf.parse(PROTO_SCHEMA).root;
    EnvironmentHttpResponse = root.lookupType('EnvironmentHttpResponse');
}

/**
 * Resolve molecule type ID to name.
 */
function resolveMoleculeType(typeId) {
    if (!moleculeTypeMap) return `TYPE_${typeId}`;
    return moleculeTypeMap[typeId] || 'UNKNOWN';
}

/**
 * Resolve opcode ID to name.
 */
function resolveOpcode(opcodeId) {
    if (!opcodeMap) return `OP_${opcodeId}`;
    return opcodeMap[opcodeId] || '??';
}

/**
 * Transform Protobuf cells to app format.
 * Optimized for large datasets.
 */
function transformCells(protoCells) {
    const len = protoCells.length;
    const result = new Array(len);

    for (let i = 0; i < len; i++) {
        const cell = protoCells[i];
        const opcodeId = cell.opcodeId;

        result[i] = {
            coordinates: cell.coordinates,
            moleculeType: resolveMoleculeType(cell.moleculeType),
            moleculeValue: cell.moleculeValue,
            ownerId: cell.ownerId,
            opcodeName: opcodeId >= 0 ? resolveOpcode(opcodeId) : null,
            opcodeId: opcodeId,
            marker: cell.marker
        };
    }

    return result;
}

/**
 * Fetch and process environment data.
 * Returns timing information for performance debugging.
 * @param {string} url - The URL to fetch
 * @param {number} requestId - Request ID for abort tracking
 */
async function fetchEnvironmentData(url, requestId) {
    await ensureProtoInitialized();

    // Create AbortController for this request
    const abortController = new AbortController();
    activeAbortControllers.set(requestId, abortController);

    const timing = {};
    const totalStart = performance.now();

    try {
        // --- Fetch ---
        const fetchStart = performance.now();
        const response = await fetch(url, {
            headers: { 'Accept': 'application/x-protobuf' },
            signal: abortController.signal
        });
        timing.fetchMs = (performance.now() - fetchStart).toFixed(1);

        if (!response.ok) {
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

        // Extract server timing headers
        const serverTiming = {
            loadMs: response.headers.get('X-Timing-Load-Ms'),
            dbMs: response.headers.get('X-Timing-Db-Ms'),
            decompressMs: response.headers.get('X-Timing-Decompress-Ms'),
            transformMs: response.headers.get('X-Timing-Transform-Ms'),
            serializeMs: response.headers.get('X-Timing-Serialize-Ms'),
            totalMs: response.headers.get('X-Timing-Total-Ms')
        };
        const cellCount = response.headers.get('X-Cell-Count');
        const contentLength = response.headers.get('Content-Length');

        // --- Binary data (includes Gzip decompression) ---
        const binaryStart = performance.now();
        const arrayBuffer = await response.arrayBuffer();
        timing.binaryMs = (performance.now() - binaryStart).toFixed(1);

        // --- Protobuf decode ---
        const parseStart = performance.now();
        const message = EnvironmentHttpResponse.decode(new Uint8Array(arrayBuffer));
        timing.parseMs = (performance.now() - parseStart).toFixed(1);

        // --- Transform to app format ---
        const transformStart = performance.now();
        const cells = transformCells(message.cells);
        timing.transformMs = (performance.now() - transformStart).toFixed(1);

        timing.totalMs = (performance.now() - totalStart).toFixed(1);

        // Build result
        const result = { cells };

        // Include minimap if present
        if (message.minimap) {
            result.minimap = {
                width: message.minimap.width,
                height: message.minimap.height,
                cellTypes: Array.from(new Uint8Array(message.minimap.cellTypes))
            };
        }

        return {
            data: result,
            timing: {
                server: serverTiming,
                client: timing
            },
            meta: {
                cellCount,
                sizeKb: contentLength ? (parseInt(contentLength) / 1024).toFixed(1) : 'unknown'
            }
        };
    } finally {
        // Clean up AbortController
        activeAbortControllers.delete(requestId);
    }
}

/**
 * Message handler.
 */
self.onmessage = async function(e) {
    const { type, payload, requestId } = e.data;

    try {
        switch (type) {
            case 'setMappings':
                // Update type mappings when metadata changes
                if (payload.moleculeTypes) {
                    moleculeTypeMap = payload.moleculeTypes;
                }
                if (payload.opcodes) {
                    opcodeMap = payload.opcodes;
                }
                self.postMessage({ type: 'mappingsSet', requestId });
                break;

            case 'fetch':
                // Perform the heavy data fetch
                const result = await fetchEnvironmentData(payload.url, requestId);
                self.postMessage({
                    type: 'fetchComplete',
                    requestId,
                    ...result
                });
                break;

            case 'abort':
                // Abort a pending fetch request
                const controller = activeAbortControllers.get(requestId);
                if (controller) {
                    controller.abort();
                    activeAbortControllers.delete(requestId);
                }
                // No response needed - main thread already rejected the promise
                break;

            default:
                throw new Error(`Unknown message type: ${type}`);
        }
    } catch (error) {
        // Don't send error for aborted requests
        if (error.name === 'AbortError') {
            return;
        }
        self.postMessage({
            type: 'error',
            requestId,
            error: error.message
        });
    }
};
