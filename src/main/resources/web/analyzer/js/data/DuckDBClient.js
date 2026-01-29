/**
 * DuckDB WASM Client
 * 
 * Handles DuckDB initialization using esm.sh CDN which auto-resolves dependencies.
 * 
 * @module DuckDBClient
 */

    // esm.sh automatically resolves all dependencies including apache-arrow
    const DUCKDB_VERSION = '1.30.0';
    const ESM_URL = `https://esm.sh/@duckdb/duckdb-wasm@${DUCKDB_VERSION}`;
    
    // State
    let db = null;
    let conn = null;
    let initialized = false;
    let initializing = false;
    let duckdbModule = null;
    
    /**
     * Converts BigInt values to Numbers in an object.
     * DuckDB WASM returns BigInt for BIGINT columns, but Chart.js can't handle them.
     * 
     * @param {Object} obj - Object potentially containing BigInt values
     * @returns {Object} Object with BigInts converted to Numbers
     */
    function convertBigInts(obj) {
        if (obj === null || obj === undefined) {
            return obj;
        }
        if (typeof obj === 'bigint') {
            // Safe conversion - will lose precision for values > Number.MAX_SAFE_INTEGER
            return Number(obj);
        }
        if (Array.isArray(obj)) {
            return obj.map(convertBigInts);
        }
        if (typeof obj === 'object') {
            const result = {};
            for (const [key, value] of Object.entries(obj)) {
                result[key] = convertBigInts(value);
            }
            return result;
        }
        return obj;
    }
    
    /**
     * Initializes DuckDB WASM.
     * Safe to call multiple times - will return cached instance.
     */
export async function init() {
        if (initialized) {
            return { db, conn };
        }
        
        if (initializing) {
            // Wait for ongoing initialization
            while (initializing) {
                await new Promise(resolve => setTimeout(resolve, 50));
            }
            if (initialized) {
                return { db, conn };
            }
            throw new Error('DuckDB initialization failed');
        }
        
        initializing = true;
        
        try {
            // Dynamic import from esm.sh (auto-resolves apache-arrow dependency)
            duckdbModule = await import(ESM_URL);
            
            // Get pre-configured jsdelivr bundles (for WASM files)
            const JSDELIVR_BUNDLES = duckdbModule.getJsDelivrBundles();
            const bundle = await duckdbModule.selectBundle(JSDELIVR_BUNDLES);
            
            // Create worker - need to fetch and create blob URL for CORS
            const workerResponse = await fetch(bundle.mainWorker);
            const workerBlob = await workerResponse.blob();
            const workerUrl = URL.createObjectURL(workerBlob);
            const worker = new Worker(workerUrl);
            
            // Initialize with console logger
            const logger = new duckdbModule.ConsoleLogger(duckdbModule.LogLevel.WARNING);
            db = new duckdbModule.AsyncDuckDB(logger, worker);
            
            // Instantiate with WASM module
            await db.instantiate(bundle.mainModule, bundle.pthreadWorker);
            
            // Create connection
            conn = await db.connect();
            
            initialized = true;
            return { db, conn };
            
        } catch (error) {
            console.error('[DuckDB] Initialization failed:', error);
            throw error;
        } finally {
            initializing = false;
        }
    }
    
    /**
     * Executes a SQL query and returns results as JSON array.
     * 
     * @param {string} sql - SQL query to execute
     * @returns {Promise<Array<Object>>} Query results
     */
export async function query(sql) {
        if (!initialized) {
            await init();
        }
        
        const result = await conn.query(sql);
        const rows = result.toArray().map(row => convertBigInts(row.toJSON()));
        return rows;
    }
    
        // Counter for unique file names
    let fileCounter = 0;
    
/**
     * Registers a Parquet file blob and queries it with the provided SQL.
     * Used for client-side DuckDB WASM queries on merged Parquet from server.
     * 
     * @param {Blob} parquetBlob - Parquet file as Blob
     * @param {string} sql - SQL query with {table} placeholder
     * @returns {Promise<Array<Object>>} Query results
     */
export async function queryParquetBlob(parquetBlob, sql) {
        if (!initialized) {
            await init();
        }
        
        // Use unique filename to avoid conflicts with parallel queries
        const fileName = `data_${++fileCounter}.parquet`;
        
        // Register the blob as a file in DuckDB's virtual filesystem
        await db.registerFileHandle(fileName, parquetBlob, duckdbModule.DuckDBDataProtocol.BROWSER_FILEREADER, true);
        
        // Replace ALL {table} placeholders with the file reference
        const finalSql = sql.replaceAll('{table}', `'${fileName}'`);
        
        const result = await conn.query(finalSql);
        const rows = result.toArray().map(row => convertBigInts(row.toJSON()));
        return rows;
    }
    
    /**
     * Closes the DuckDB connection and cleans up.
     */
export async function close() {
        if (conn) {
            await conn.close();
            conn = null;
        }
        if (db) {
            await db.terminate();
            db = null;
        }
        initialized = false;
    }
    
    /**
     * Checks if DuckDB is initialized.
     */
export function isInitialized() {
        return initialized;
}
