/**
 * DuckDB WASM Client
 * 
 * Handles DuckDB initialization using esm.sh CDN which auto-resolves dependencies.
 * 
 * @module DuckDBClient
 */

const DuckDBClient = (function() {
    'use strict';
    
    // esm.sh automatically resolves all dependencies including apache-arrow
    const DUCKDB_VERSION = '1.29.0';
    const ESM_URL = `https://esm.sh/@duckdb/duckdb-wasm@${DUCKDB_VERSION}`;
    
    // State
    let db = null;
    let conn = null;
    let initialized = false;
    let initializing = false;
    let duckdbModule = null;
    
    /**
     * Initializes DuckDB WASM.
     * Safe to call multiple times - will return cached instance.
     */
    async function init() {
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
            console.log('[DuckDB] Initializing from esm.sh CDN...');
            
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
            console.log('[DuckDB] Initialized successfully');
            
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
    async function query(sql) {
        if (!initialized) {
            await init();
        }
        
        console.log('[DuckDB] Query:', sql.substring(0, 200) + (sql.length > 200 ? '...' : ''));
        const result = await conn.query(sql);
        const rows = result.toArray().map(row => row.toJSON());
        console.log(`[DuckDB] Returned ${rows.length} rows`);
        return rows;
    }
    
    /**
     * Queries Parquet files directly via HTTP URLs.
     * DuckDB WASM can read HTTP URLs directly in read_parquet().
     * 
     * @param {string[]} urls - Array of Parquet file URLs (full HTTP URLs)
     * @param {string} selectClause - SQL SELECT/ORDER clause (optional)
     * @returns {Promise<Array<Object>>} Query results
     */
    async function queryParquetFiles(urls, selectClause = null) {
        if (!initialized) {
            await init();
        }
        
        if (urls.length === 0) {
            return [];
        }
        
        // DuckDB can read HTTP URLs directly - no registration needed!
        // Just pass the URLs as strings in read_parquet()
        const urlList = urls.map(u => `'${u}'`).join(',');
        const filesArg = `[${urlList}]`;
        
        const sql = selectClause 
            ? selectClause.replace('$FILES', filesArg)
            : `SELECT * FROM read_parquet(${filesArg}) ORDER BY tick`;
        
        return await query(sql);
    }
    
    /**
     * Closes the DuckDB connection and cleans up.
     */
    async function close() {
        if (conn) {
            await conn.close();
            conn = null;
        }
        if (db) {
            await db.terminate();
            db = null;
        }
        initialized = false;
        console.log('[DuckDB] Closed');
    }
    
    /**
     * Checks if DuckDB is initialized.
     */
    function isInitialized() {
        return initialized;
    }
    
    // Public API
    return {
        init,
        query,
        queryParquetFiles,
        close,
        isInitialized
    };
    
})();

// Export for module systems (optional)
if (typeof module !== 'undefined' && module.exports) {
    module.exports = DuckDBClient;
}
