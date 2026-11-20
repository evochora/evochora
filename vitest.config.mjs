import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// The web frontend's tests: src/test/web mirrors the tree of src/main/web.
export default defineConfig({
    // The node serves src/main/web/shared as /shared, and the frontend's modules import it by that
    // address. The tests run without a node, so the address is given the directory behind it.
    resolve: {
        alias: [{ find: /^\/shared\//, replacement: fileURLToPath(new URL('./src/main/web/shared/', import.meta.url)) }],
    },
    test: {
        include: ['src/test/web/**/*.test.js'],
        environment: 'node',
    },
});
