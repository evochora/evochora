/**
 * Engine for token-level annotation in the source code view.
 */
class SourceAnnotator {
    constructor() {
        this.handlers = [
            new RegisterTokenHandler(),
            new ParameterTokenHandler()
        ];
    }

    annotate(organismState, artifact, fileName, sourceLine, lineNumber) {
        if (!artifact || !organismState || !fileName) return [];

        const tokenLookup = artifact.tokenLookup;
        if (!tokenLookup) return [];

        // 1. Find file entry (handle array or object structure)
        let fileEntry = null;
        if (Array.isArray(tokenLookup)) {
            fileEntry = tokenLookup.find(entry => entry.fileName === fileName);
        } else {
            fileEntry = Object.values(tokenLookup).find(entry => entry.fileName === fileName);
        }

        if (!fileEntry || !fileEntry.lines) {
            console.debug("SourceAnnotator: No entry or lines for file", fileName);
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

        // 3. Calculate Line Start Offset to convert absolute token pos to relative
        // We can rely on the backend normalizing line endings to \n (Unix-style).
        // See IncludeDirectiveHandler.java and Compiler.java
        const allLines = this.getAllLines(artifact, fileName);
        if (!allLines) return []; // Should not happen if we have sourceLine

        // Calculate offset for Unix endings (\n)
        let offset = 0;
        
        for (let i = 0; i < lineNumber - 1; i++) {
            const len = allLines[i].length;
            offset += len + 1; // +1 for \n
        }

        let annotations = [];

        // 4. Process tokens
        lineData.columns.forEach(colData => {
            const absColumn = colData.columnNumber; 
            const tokens = colData.tokens;

            if (Array.isArray(tokens)) {
                tokens.forEach(tokenInfo => {
                    const tokenText = tokenInfo.tokenText;
                    
                    // Determine relative column (0-based index in sourceLine)
                    // Backend guarantees consistent \n normalization, so we use simple arithmetic.
                    const relColumn = absColumn - 1 - offset;
                    
                    // Validate position (sanity check)
                    if (this.checkTokenAt(sourceLine, tokenText, relColumn)) {
                        const handler = this.findHandler(tokenText, tokenInfo);
                        if (handler) {
                            const result = handler.analyze(tokenText, tokenInfo, organismState, artifact);
                            if (result) {
                                annotations.push({
                                    tokenText: tokenText,
                                    annotationText: result.annotationText,
                                    kind: result.kind,
                                    column: absColumn, // absolute for sorting
                                    relativeColumn: relColumn // relative for slicing
                                });
                            }
                        }
                    } else {
                        // If this fails, it means the backend normalization is still broken or frontend lines differ.
                        // We log this but do NOT try to guess/fix it heuristically.
                        console.debug(`SourceAnnotator: Token '${tokenText}' mismatch at relCol ${relColumn} (abs ${absColumn}, offset ${offset})`);
                    }
                });
            }
        });

        return this.convertToInlineSpans(annotations, lineNumber);
    }
    
    getAllLines(artifact, fileName) {
        if (!artifact.sources || !artifact.sources[fileName]) return null;
        const source = artifact.sources[fileName];
        if (Array.isArray(source)) return source;
        if (source.lines) return source.lines;
        return null;
    }
    
    checkTokenAt(line, token, index) {
        if (index < 0 || index >= line.length) return false;
        // Check if line starts with token at index
        return line.substring(index, index + token.length) === token;
    }

    findHandler(tokenText, tokenInfo) {
        return this.handlers.find(h => h.canHandle(tokenText, tokenInfo));
    }

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

const INSTRUCTION_CONSTANTS = {
    PR_BASE: 1000,
    FPR_BASE: 2000
};

class RegisterTokenHandler {
    canHandle(token, tokenInfo) {
        const type = tokenInfo.tokenType;
        return type === 'ALIAS' || (type === 'VARIABLE' && token.startsWith('%'));
    }

    analyze(token, tokenInfo, state, artifact) {
        if (!token.startsWith('%')) return null;

        const canonicalReg = this.resolveToCanonicalRegister(token, artifact);
        
        if (canonicalReg) {
            const value = this.getRegisterValue(canonicalReg, state);
            return {
                annotationText: `=${this.formatValue(value)}`,
                kind: 'reg'
            };
        } else {
            const value = this.getRegisterValue(token, state);
            if (value !== null && value !== undefined) {
                return {
                    annotationText: `=${this.formatValue(value)}`,
                    kind: 'reg'
                };
            }
        }
        return null;
    }

    resolveToCanonicalRegister(token, artifact) {
        const upper = token.toUpperCase();
        if (artifact.registerAliasMap && artifact.registerAliasMap[upper] !== undefined) {
            const regId = artifact.registerAliasMap[upper];
            if (regId >= INSTRUCTION_CONSTANTS.FPR_BASE) return `%FPR${regId - INSTRUCTION_CONSTANTS.FPR_BASE}`;
            if (regId >= INSTRUCTION_CONSTANTS.PR_BASE) return `%PR${regId - INSTRUCTION_CONSTANTS.PR_BASE}`;
            return `%DR${regId}`;
        }
        return null;
    }

    getRegisterValue(canonicalName, state) {
        if (!canonicalName || !state) return null;
        const upper = canonicalName.toUpperCase();

        if (upper.startsWith('%DR')) {
            const id = parseInt(upper.substring(3), 10);
            return (state.dataRegisters && id < state.dataRegisters.length) ? state.dataRegisters[id] : null;
        }
        if (upper.startsWith('%PR')) {
            const id = parseInt(upper.substring(3), 10);
            return (state.procedureRegisters && id < state.procedureRegisters.length) ? state.procedureRegisters[id] : null;
        }
        if (upper.startsWith('%FPR')) {
            const id = parseInt(upper.substring(4), 10);
            return (state.formalParamRegisters && id < state.formalParamRegisters.length) ? state.formalParamRegisters[id] : null;
        }
        if (upper.startsWith('%LR')) {
            const id = parseInt(upper.substring(3), 10);
            return (state.locationRegisters && id < state.locationRegisters.length) ? state.locationRegisters[id] : null;
        }
        return null;
    }

    formatValue(value) {
        if (value === null || value === undefined) return "null";
        
        if (typeof value === 'object') {
            if (value.kind === 'MOLECULE') {
                 const typeName = value.typeName || '';
                 const typeAbbr = typeName.length > 0 ? typeName.charAt(0) + ':' : '';
                 return `${typeAbbr}${value.value}`;
            }
            if (value.kind === 'VECTOR') {
                return value.vector.join('|');
            }
        }
        
        if (typeof value === 'number') return value.toString();
        if (Array.isArray(value)) return value.join('|');
        return value.toString();
    }
}

class ParameterTokenHandler {
    canHandle(token, tokenInfo) {
        return tokenInfo.tokenType === 'VARIABLE' && 
               tokenInfo.scope && 
               tokenInfo.scope.toLowerCase() !== 'global';
    }

    analyze(token, tokenInfo, state, artifact) {
        const procName = tokenInfo.scope; 
        if (!procName) return null;

        const paramNames = artifact.procNameToParamNames ? artifact.procNameToParamNames[procName.toUpperCase()] : null;
        if (!paramNames) return null;

        let paramIndex = -1;
        for (let i = 0; i < paramNames.length; i++) {
            if (paramNames[i].toUpperCase() === token.toUpperCase()) {
                paramIndex = i;
                break;
            }
        }

        if (paramIndex === -1) return null;

        if (state.callStack && state.callStack.length > 0) {
            const finalRegId = this.resolveBindingChain(state.callStack, paramIndex);
            if (finalRegId >= 0) {
                const val = this.getRegisterValueById(finalRegId, state);
                if (val !== null) {
                     const formatted = this.formatValue(val);
                     return {
                         annotationText: `=${formatted}`,
                         kind: 'param'
                     };
                }
            }
        }
        return null;
    }

    resolveBindingChain(callStack, initialFprIndex) {
        let currentRegId = INSTRUCTION_CONSTANTS.FPR_BASE + initialFprIndex; 
        
        for (let i = callStack.length - 1; i >= 0; i--) {
            const frame = callStack[i];
            const bindings = frame.fprBindings;
            
            const key = currentRegId.toString();
            if (bindings && bindings[key] !== undefined) {
                const mappedId = bindings[key];
                currentRegId = mappedId;
                if (currentRegId < INSTRUCTION_CONSTANTS.FPR_BASE) { 
                    return currentRegId;
                }
            } else {
                break;
            }
        }
        return currentRegId;
    }
    
    idToCanonicalName(id) {
        if (id >= INSTRUCTION_CONSTANTS.FPR_BASE) return `%FPR${id - INSTRUCTION_CONSTANTS.FPR_BASE}`;
        if (id >= INSTRUCTION_CONSTANTS.PR_BASE) return `%PR${id - INSTRUCTION_CONSTANTS.PR_BASE}`;
        if (id >= 0) return `%DR${id}`;
        return "INVALID";
    }

    getRegisterValueById(id, state) {
        if (id >= INSTRUCTION_CONSTANTS.FPR_BASE) {
            const idx = id - INSTRUCTION_CONSTANTS.FPR_BASE;
            return (state.formalParamRegisters && idx < state.formalParamRegisters.length) ? state.formalParamRegisters[idx] : null;
        }
        if (id >= INSTRUCTION_CONSTANTS.PR_BASE) {
            const idx = id - INSTRUCTION_CONSTANTS.PR_BASE;
            return (state.procedureRegisters && idx < state.procedureRegisters.length) ? state.procedureRegisters[idx] : null;
        }
        if (id >= 0) {
            return (state.dataRegisters && id < state.dataRegisters.length) ? state.dataRegisters[id] : null;
        }
        return null;
    }
    
    formatValue(value) {
        if (value === null || value === undefined) return "null";
        
        if (typeof value === 'object') {
            if (value.kind === 'MOLECULE') {
                 const typeName = value.typeName || '';
                 const typeAbbr = typeName.length > 0 ? typeName.charAt(0) + ':' : '';
                 return `${typeAbbr}${value.value}`;
            }
            if (value.kind === 'VECTOR') {
                return value.vector.join('|');
            }
        }
        
        if (typeof value === 'number') return value.toString();
        if (Array.isArray(value)) return value.join('|');
        return value.toString();
    }
}

window.SourceAnnotator = SourceAnnotator;