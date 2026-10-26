import { defineConfig } from 'vitest/config';

// The web frontend's tests: src/test/web mirrors the tree of src/main/web.
export default defineConfig({
    test: {
        include: ['src/test/web/**/*.test.js'],
        environment: 'node',
    },
});
