/** The ways the value of a molecule type is written; the `valueFormat` of a palette entry is one of them. */
export const VALUE_FORMAT = Object.freeze({ DECIMAL: 'decimal', HEX: 'hex' });

/**
 * The single palette of molecule type colours for the visualizer.
 *
 * Every molecule type registered on the server has one entry, keyed by the type name the
 * simulation metadata reports (`moleculeTypes`). `UNKNOWN` covers a molecule whose type this
 * palette does not know, so an unexpected type is visible instead of silently drawn as background.
 *
 * Each entry carries:
 * - `bg`: the cell background colour as a 0xRRGGBB integer
 * - `text`: the colour of the value drawn on that background
 * - `abbr`: the short type prefix used where a value is written as `<abbr>:<value>`
 * - `valueFormat`: how the value of the type is written, one of {@link VALUE_FORMAT}: a number in
 *   decimal, or a bit pattern in hexadecimal (see `ValueFormatter.formatMoleculeValue`)
 *
 * The empty-pixel colour and the ground colour are not molecule types and are exported
 * separately below.
 */
export const MOLECULE_TYPE_PALETTE = {
    CODE:      { bg: 0x3c5078, text: 0xffffff, abbr: 'C',  valueFormat: 'decimal' },  // blue-gray
    DATA:      { bg: 0x32323c, text: 0xffffff, abbr: 'D',  valueFormat: 'decimal' },  // dark gray
    ENERGY:    { bg: 0xffe664, text: 0x323232, abbr: 'E',  valueFormat: 'decimal' },  // yellow
    STRUCTURE: { bg: 0xff7878, text: 0x323232, abbr: 'S',  valueFormat: 'decimal' },  // red/pink
    LABEL:     { bg: 0xa0a0a8, text: 0x323232, abbr: 'L',  valueFormat: 'hex'     },  // light gray
    LABELREF:  { bg: 0xa0a0a8, text: 0xffffff, abbr: 'LR', valueFormat: 'hex'     },  // light gray, light text distinguishes it from LABEL
    REGISTER:  { bg: 0x506080, text: 0xffffff, abbr: 'R',  valueFormat: 'decimal' },  // medium blue-gray
    STATE:     { bg: 0x32323c, text: 0xffd166, abbr: 'ST', valueFormat: 'decimal' },  // dark gray as DATA, amber text distinguishes it
    UNKNOWN:   { bg: 0xff00ff, text: 0xffffff, abbr: '?',  valueFormat: 'decimal' }   // magenta, unmistakable
};

/** The name of the entry every unrecognized type falls back to. */
export const UNKNOWN_TYPE_NAME = 'UNKNOWN';

/** Colour of a minimap pixel whose cells hold no molecule. */
export const EMPTY_CELL_COLOR = 0x1e1e28;

/**
 * Colour of the ground the molecules are drawn on: an empty cell of the environment grid, and a
 * minimap area for which no cell data exists at all.
 */
export const NO_DATA_COLOR = 0x14141e;

/**
 * Returns the palette entry of a molecule type name.
 *
 * @param {string} typeName - The type name from the run metadata, e.g. 'CODE'.
 * @returns {{bg: number, text: number, abbr: string, valueFormat: string}} The entry, or the UNKNOWN entry.
 */
export function moleculeTypeEntry(typeName) {
    return MOLECULE_TYPE_PALETTE[typeName] || MOLECULE_TYPE_PALETTE[UNKNOWN_TYPE_NAME];
}

/**
 * Returns the type name to display for a molecule type name.
 *
 * @param {string} typeName - The type name from the run metadata, e.g. 'CODE'.
 * @returns {string} The same name if the palette knows it, otherwise 'UNKNOWN'.
 */
export function moleculeTypeName(typeName) {
    return (typeName in MOLECULE_TYPE_PALETTE) ? typeName : UNKNOWN_TYPE_NAME;
}
