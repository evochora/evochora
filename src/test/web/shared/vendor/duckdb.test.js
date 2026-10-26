import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { expect, test } from 'vitest';

/**
 * The analyzer's DuckDB loads its Parquet extension from the node, at
 * extensions/<core version>/<platform>/parquet.duckdb_extension.wasm under the served DuckDB
 * files. An extension fits exactly one DuckDB core, and the build fetches it by a version written
 * in build.gradle.kts, apart from the DuckDB-WASM of package.json: an update of one without the
 * other leaves the analyzer unable to read any data. The test asks DuckDB-WASM for its core and
 * platform and expects the extension where the analyzer will ask for it.
 *
 * WEB_VENDOR_DIR is the directory the build serves as /shared/vendor; the Gradle task webTest
 * sets it.
 */
test('the Parquet extension lies where DuckDB-WASM asks for it', async () => {
    const vendor = process.env.WEB_VENDOR_DIR;
    expect(vendor, 'WEB_VENDOR_DIR names the served vendor directory').toBeTruthy();

    const require = createRequire(import.meta.url);
    const duckdb = require('@duckdb/duckdb-wasm/blocking');
    const db = await duckdb.createDuckDB(
        { mvp: { mainModule: require.resolve('@duckdb/duckdb-wasm/dist/duckdb-mvp.wasm'), mainWorker: '' } },
        new duckdb.VoidLogger(),
        duckdb.NODE_RUNTIME);
    await db.instantiate();
    const connection = db.connect();
    const [{ version, platform }] = connection
        .query('SELECT version() AS version, (SELECT platform FROM pragma_platform()) AS platform')
        .toArray()
        .map(row => row.toJSON());
    connection.close();

    const extension = join(vendor, 'duckdb', 'extensions', version, platform, 'parquet.duckdb_extension.wasm');
    expect(existsSync(extension), `DuckDB-WASM ${version} on ${platform} asks for ${extension}`).toBe(true);
});
