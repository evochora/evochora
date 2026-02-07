/**
 * A utility class for formatting various data types from the simulation
 * into human-readable strings for the UI.
 *
 * It strictly requires valid, non-null inputs. It accepts raw arrays (for vectors)
 * and specific object structures (e.g., MOLECULE, VECTOR) from the simulation state.
 * This method will throw an error for any other invalid input to enforce a "fail-fast" policy.
 *
 * @param {*} value - The value to format. Can be a raw array or an object with a `kind` of 'MOLECULE' or 'VECTOR'.
 * @returns {string} A string representation of the value suitable for display.
 * @throws {Error} If the value is null, undefined, or has an unknown/invalid structure.
 */
export class ValueFormatter {
    /**
     * Formats a given value into a display string.
     * It strictly requires valid, non-null inputs. It accepts primitives, recognized
     * object structures (e.g., MOLECULE, VECTOR), and raw arrays for vectors.
     * This method will throw an error for any other invalid input to enforce a "fail-fast" policy.
     *
     * @param {*} value - The value to format. Can be a primitive, a raw array, or an object with a `kind` property.
     * @returns {string} A string representation of the value suitable for display.
     * @throws {Error} If the value is null, undefined, or has an unknown/invalid object structure.
     */
    static format(value) {
        if (value === null || value === undefined) {
            throw new Error("ValueFormatter received null or undefined value.");
        }

        // Handle raw arrays (from location registers/stack) as vectors.
        if (Array.isArray(value)) {
            return value.join('|');
        }

        if (typeof value !== 'object') {
            throw new Error(`ValueFormatter received a non-object/non-array value, but expected a structured object. Value: ${value}`);
        }

        if (value.kind === 'MOLECULE') {
            if (value.type === undefined || value.value === undefined) {
                throw new Error(`Invalid MOLECULE object: ${JSON.stringify(value)}`);
            }
            const typeName = value.type || '';
            const typeAbbr = (typeName.length > 0 ? typeName.charAt(0).toUpperCase() + ':' : '');
            return `${typeAbbr}${value.value}`;
        }

        if (value.kind === 'VECTOR') {
            if (!Array.isArray(value.vector)) {
                throw new Error(`Invalid VECTOR object: ${JSON.stringify(value)}`);
            }
            return value.vector.join('|');
        }
        
        // Any other object structure is considered an error.
        throw new Error(`Unknown object structure passed to ValueFormatter: ${JSON.stringify(value)}`);
    }

    /**
     * Converts a DV vector into a Unicode arrow symbol for display.
     * @param {number[]} dv - The direction vector, e.g., [1, 0].
     * @returns {string} A string containing the arrow symbol ('→', '←', '↑', '↓') or 'x' for non-cardinal vectors.
     */
    static formatDvAsArrow(dv) {
        if (!Array.isArray(dv) || dv.length < 2) {
            return 'x';
        }
        const [dx, dy] = dv;
        if (dx === 1 && dy === 0) return '→';
        if (dx === -1 && dy === 0) return '←';
        if (dx === 0 && dy === -1) return '↑';
        if (dx === 0 && dy === 1) return '↓';
        return 'x';
    }

    /**
     * Formats a genome hash (64-bit long) as a 6-character Base62 string.
     * Base62 uses 0-9, a-z, A-Z for compact, readable representation.
     *
     * @param {number|bigint|string} hash - The genome hash value (can be number, bigint, or string)
     * @returns {string} 6-character Base62 representation, or '------' if hash is null/0
     */
    static formatGenomeHash(hash) {
        if (hash == null || hash === 0 || hash === '0') {
            return '------';
        }
        const chars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
        let result = '';
        let n = BigInt(hash);
        // Handle signed long (Java sends signed, we need unsigned interpretation)
        if (n < 0n) {
            n = n + (1n << 64n);
        }
        for (let i = 0; i < 6; i++) {
            result = chars[Number(n % 62n)] + result;
            n = n / 62n;
        }
        return result;
    }
}
