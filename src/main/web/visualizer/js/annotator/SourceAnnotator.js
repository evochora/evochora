import { RegisterTokenHandler } from './handlers/RegisterTokenHandler.js';
import { LabelReferenceTokenHandler } from './handlers/LabelReferenceTokenHandler.js';
import { ProcedureTokenHandler } from './handlers/ProcedureTokenHandler.js';
import { ParameterTokenHandler } from './handlers/ParameterTokenHandler.js';
import { RetInstructionHandler } from './handlers/RetInstructionHandler.js';

/**
 * Engine for token-level annotation in the source code view.
 * This class orchestrates the annotation process by managing a list of specialized
 * handlers. For a given line of code, it identifies tokens and delegates the
 * analysis to the appropriate handler.
 *
 * @class SourceAnnotator
 */
export class SourceAnnotator {
    /**
     * Initializes the SourceAnnotator and its list of token handlers.
     */
    constructor() {
        this.handlers = [
            new RegisterTokenHandler(),
            new LabelReferenceTokenHandler(),
            new ProcedureTokenHandler(),
            new ParameterTokenHandler(),
            new RetInstructionHandler()
        ];
    }

    /**
     * Generates annotations for a specific line of source code.
     * It parses the line's token information from the artifact, finds the right
     * handler for each token, and collects the results.
     *
     * @param {object} organismState The current dynamic state of the organism.
     * @param {object} artifact The static program artifact.
     * @param {string} placement The alias chain of the module placement the line belongs to.
     * @param {string} fileName The name of the source file being annotated.
     * @param {string} sourceLine The raw text of the source code line.
     * @param {number} lineNumber The 1-based line number.
     * @param {number} labelNamespaceMask The organism's label namespace, which turns a compiled
     *        label value into the value that stands in its body.
     * @returns {Array<object>} A list of annotation spans ready for rendering.
     */
    annotate(organismState, artifact, placement, fileName, sourceLine, lineNumber, labelNamespaceMask) {
        if (!artifact || !organismState || !fileName) return [];

        const tokenLookup = artifact.tokenLookup;
        if (!tokenLookup) return [];

        // 1. Find the entry of the file in its placement; two placements of one file share its
        // positions but not its tokens' meaning
        const fileEntry = this.fileEntryOf(tokenLookup, placement, fileName);
        if (!fileEntry || !fileEntry.lines) {
            console.debug("SourceAnnotator: No entry or lines for file", placement || '', fileName);
            return [];
        }

        // 2. Get tokens for this line
        // Handle lines as array or object values, find matching lineNumber
        let lineData = null;
        const lines = fileEntry.lines;
        if (Array.isArray(lines)) {
            lineData = lines.find(l => l.lineNumber === lineNumber);
        } else {
            lineData = Object.values(lines).find(l => l.lineNumber === lineNumber);
        }
        
        if (!lineData) {
            // console.debug("SourceAnnotator: No tokens for line", lineNumber);
            return [];
        }

        if (!lineData.columns) return [];

        let annotations = [];

        // 3. Process tokens
        lineData.columns.forEach(colData => {
            // columnNumber is the 1-based column of the token's first character within its line
            const column = colData.columnNumber;
            const tokens = colData.tokens;

            if (Array.isArray(tokens)) {
                tokens.forEach(tokenInfo => {
                    const tokenText = tokenInfo.tokenText;
                    
                    // 0-based index of the token's first character in sourceLine
                    const relColumn = column - 1;
                    
                    // Validate position (sanity check)
                    if (this.checkTokenAt(sourceLine, tokenText, relColumn)) {
                        const handler = this.findHandler(tokenText, tokenInfo);
                        if (handler) {
                            try {
                            const result = handler.analyze(tokenText, tokenInfo, organismState, artifact, labelNamespaceMask);
                            if (result) {
                                annotations.push({
                                    tokenText: tokenText,
                                    annotationText: result.annotationText,
                                    kind: result.kind,
                                    column: column, // 1-based column in the line
                                    relativeColumn: relColumn // 0-based index for slicing
                                });
                                }
                            } catch (error) {
                                console.error(`Annotation Error for token '${tokenText}' (handler: ${handler.constructor.name}):`, error.message);
                            }
                        }
                    } else {
                        // A mismatch means the artifact's token positions and the displayed line differ.
                        // It is logged, and no heuristic tries to locate the token elsewhere.
                        console.debug(`SourceAnnotator: Token '${tokenText}' mismatch at relCol ${relColumn} (column ${column})`);
                    }
                });
            }
        });

        return this.convertToInlineSpans(annotations, lineNumber);
    }
    
    /**
     * Collects the compile-time notes on the constants of a file's lines: for every token the
     * token map marks as a constant, a note `[=VALUE]` after the token, with the value the
     * artifact's `constantValues` give for its qualified name, the path of its definition (or its
     * text, without one). Unlike
     * the annotations of {@link annotate}, they do not depend on the organism's state and are
     * shown on every line.
     *
     * @param {object} artifact The static program artifact.
     * @param {string} placement The alias chain of the module placement the lines belong to.
     * @param {string} fileName The name of the source file.
     * @param {string[]} lines The lines of the file.
     * @returns {Map<number, Array<{end: number, text: string}>>} The notes by 1-based line number,
     *          each with the index in the line after which it is shown.
     */
    constantNotes(artifact, placement, fileName, lines) {
        const notes = new Map();
        const values = artifact?.constantValues;
        const fileEntry = values && artifact.tokenLookup
            ? this.fileEntryOf(artifact.tokenLookup, placement, fileName) : null;
        if (!fileEntry || !fileEntry.lines) return notes;
        const lineEntries = Array.isArray(fileEntry.lines) ? fileEntry.lines : Object.values(fileEntry.lines);
        lineEntries.forEach(lineData => {
            const text = lines[lineData.lineNumber - 1];
            if (text === undefined || !Array.isArray(lineData.columns)) return;
            lineData.columns.forEach(colData => {
                (Array.isArray(colData.tokens) ? colData.tokens : []).forEach(tokenInfo => {
                    if (tokenInfo.tokenType !== 'CONSTANT') return;
                    const value = values[(tokenInfo.qualifiedName || tokenInfo.tokenText || '').toUpperCase()];
                    const start = colData.columnNumber - 1;
                    if (value === undefined || !this.checkTokenAt(text, tokenInfo.tokenText, start)) return;
                    const lineNotes = notes.get(lineData.lineNumber) || [];
                    lineNotes.push({ end: start + tokenInfo.tokenText.length, text: `[=${value}]` });
                    notes.set(lineData.lineNumber, lineNotes);
                });
            });
        });
        return notes;
    }

    /**
     * Finds the token lookup entry of a file in its placement, in the array or the object form of
     * the lookup.
     *
     * @param {Array<object>|object} tokenLookup The artifact's token lookup.
     * @param {string} placement The alias chain of the placement; empty or absent for the empty one.
     * @param {string} fileName The name of the source file.
     * @returns {object|undefined} The entry, or undefined if the lookup has none.
     * @private
     */
    fileEntryOf(tokenLookup, placement, fileName) {
        const wantedPlacement = placement || '';
        const matches = entry => (entry.placement || '') === wantedPlacement && entry.fileName === fileName;
        return Array.isArray(tokenLookup) ? tokenLookup.find(matches) : Object.values(tokenLookup).find(matches);
    }

    /**
     * Verifies that a token's text matches the source line at a given column.
     * This is a sanity check to ensure token data from the artifact aligns with the source.
     *
     * @param {string} line The full source code line.
     * @param {string} token The token text to check.
     * @param {number} index The 0-based column index where the token should start.
     * @returns {boolean} True if the token is found at the specified position.
     * @private
     */
    checkTokenAt(line, token, index) {
        if (index < 0 || index >= line.length) return false;
        // Check if line starts with token at index
        return line.substring(index, index + token.length) === token;
    }

    /**
     * Finds the first registered handler that can process the given token.
     *
     * @param {string} tokenText The text of the token.
     * @param {object} tokenInfo The metadata associated with the token.
     * @returns {object|null} The handler instance or null if no handler is found.
     * @private
     */
    findHandler(tokenText, tokenInfo) {
        return this.handlers.find(h => h.canHandle(tokenText, tokenInfo));
    }

    /**
     * Converts a list of raw annotation results into a final list of spans,
     * calculating occurrence counts for identical tokens on the same line.
     *
     * @param {Array<object>} rawAnnotations The list of results from the handlers.
     * @param {number} lineNumber The current line number.
     * @returns {Array<object>} A final list of annotation spans.
     * @private
     */
    convertToInlineSpans(rawAnnotations, lineNumber) {
        if (!rawAnnotations.length) return [];

        // Sort by relative column (left to right)
        rawAnnotations.sort((a, b) => a.relativeColumn - b.relativeColumn);

        const spans = [];
        const tokenCounts = {};

        rawAnnotations.forEach(ann => {
            if (!tokenCounts[ann.tokenText]) tokenCounts[ann.tokenText] = 0;
            tokenCounts[ann.tokenText]++;
            
            spans.push({
                lineNumber: lineNumber,
                tokenText: ann.tokenText,
                occurrence: tokenCounts[ann.tokenText],
                annotationText: ann.annotationText,
                kind: ann.kind,
                column: ann.column,
                relativeColumn: ann.relativeColumn // 0-based index in line
            });
        });

        return spans;
    }
}
