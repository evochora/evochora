/**
 * A utility class for formatting various data types from the simulation
 * into human-readable strings for the UI.
 *
 * It strictly requires valid, non-null inputs in the form of specific object structures
 * (e.g., MOLECULE, VECTOR) from the simulation state. This method will throw an
 * error for any invalid input to enforce a "fail-fast" policy.
 *
 * @param {object} value - The value to format. Must be an object with a `kind` property of 'MOLECULE' or 'VECTOR'.
 * @returns {string} A string representation of the value suitable for display.
 * @throws {Error} If the value is null, undefined, not an object, or has an unknown/invalid object structure.
 */
class ValueFormatter {
    /**
     * Formats a given value into a display string.
     * It strictly requires valid, non-null inputs and recognized object structures
     * (e.g., MOLECULE, VECTOR) from the simulation state. This method will throw an
     * error for any invalid input to enforce a "fail-fast" policy.
     *
     * @param {*} value - The value to format. Must be an object with a `kind` of 'MOLECULE' or 'VECTOR'.
     * @returns {string} A string representation of the value suitable for display.
     * @throws {Error} If the value is null, undefined, not an object, or has an unknown/invalid object structure.
     */
    static format(value) {
        if (value === null || value === undefined) {
            throw new Error("ValueFormatter received null or undefined value.");
        }

        if (typeof value !== 'object') {
            throw new Error(`ValueFormatter received a non-object value, but expected an object with a 'kind' property. Value: ${value}`);
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
}
