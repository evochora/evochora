/**
 * A utility class for formatting various data types from the simulation
 * into human-readable strings for the UI.
 */
class ValueFormatter {
    /**
     * Formats a given value into a display string.
     * @param {*} value - The value to format. Can be a primitive, an object, or null/undefined.
     * @returns {string} A string representation of the value.
     */
    static format(value) {
        if (value === null || value === undefined) return "null";

        if (typeof value === 'object') {
            if (value.kind === 'MOLECULE') {
                // Use 'type' property as seen in SidebarStateView.js
                const typeName = value.type || '';
                const typeAbbr = (typeName.length > 0 ? typeName.charAt(0).toUpperCase() + ':' : '');

                return `${typeAbbr}${value.value}`;
            }

            if (value.kind === 'VECTOR') {
                if (value.vector) return value.vector.join('|');
            }

            // Fallbacks
            if (value.scalar !== undefined) return value.scalar.toString();
            if (Array.isArray(value)) return value.join('|');
            if (value.x !== undefined && value.y !== undefined) return `${value.x}|${value.y}`;
        }

        return value.toString();
    }
}
