/**
 * Manages the source code view in the sidebar.
 * This class is responsible for displaying the assembly code of the selected organism,
 * handling file switching for included files, highlighting the currently executing line,
 * and applying runtime annotations to the active line.
 *
 * @class SidebarSourceView
 */
class SidebarSourceView {
    /**
     * Initializes the view, caching DOM elements and creating the annotator instance.
     * @param {HTMLElement} rootElement - The root element of the sidebar.
     */
    constructor(rootElement) {
        this.root = rootElement;
        this.artifact = null;
        this.selectedFile = null;
        this.annotator = new SourceAnnotator();
        this.lastAnnotatedLine = null; // Track annotated line to restore it
        
        // Cache references to active DOM elements
        this.dom = {
            section: rootElement.querySelector('[data-section="source"]'),
            codeContainer: null,
            dropdown: null,
            status: null
        };
    }

    /**
     * Sets the static program context (the ProgramArtifact).
     * This method triggers a full re-render of the source view. It includes an
     * internal check to avoid unnecessary re-renders if the artifact has not changed.
     *
     * @param {object} artifact - The ProgramArtifact containing sources, mappings, and token info.
     */
    setProgram(artifact) {
        if (this.artifact === artifact) {
            return; // No change, nothing to do
        }

        this.artifact = artifact;
        this.lastAnnotatedLine = null;
        
        // UX Logic: Default to first file so view is not empty initially
        if (this.artifact && this.artifact.sources) {
            const files = Object.keys(this.artifact.sources);
            if (files.length > 0) {
                this.selectedFile = files[0];
            } else {
                this.selectedFile = null;
            }
        } else {
            this.selectedFile = null;
        }

        // Full re-render of the source structure
        this.renderSourceStructure();
    }

    /**
     * Updates the dynamic execution state of the source view.
     * This method is optimized for high-frequency calls (every tick). It handles
     * status updates, auto-switching of files, line highlighting, and triggers annotations.
     *
     * @param {object} organismState - The current dynamic state of the organism (e.g., IP).
     * @param {object} staticInfo - Static info for the organism, including `initialPosition`.
     */
    updateExecutionState(organismState, staticInfo) {
        if (!this.artifact || !this.dom.section) return;

        const activeLocation = this.calculateActiveLocation(organismState, staticInfo);
        
        // 1. Handle Status Bar (Errors/Warnings)
        this.updateStatusBar(activeLocation);

        // 2. Auto-switch file if execution moved to a different file
        if (activeLocation && activeLocation.fileName) {
            const fileExists = this.artifact.sources && this.artifact.sources[activeLocation.fileName];
            if (fileExists && this.selectedFile !== activeLocation.fileName) {
                this.selectedFile = activeLocation.fileName;
                this.lastAnnotatedLine = null; // Reset on file switch
                this.renderSourceStructure(); // Re-render needed because file content changed
            }
        }

        // 3. Update Line Highlighting (DOM manipulation only, no re-render)
        const activeLineNumber = activeLocation ? activeLocation.lineNumber : null;
        this.updateHighlighting(activeLineNumber);

        // 4. Update Machine Instruction Highlighting and Collapse State
        if (activeLocation && activeLocation.linearAddress !== undefined) {
            this.updateMachineInstructionHighlighting(activeLocation.linearAddress, activeLocation.lineNumber);
            this.updateMachineInstructionCollapseState(activeLocation.lineNumber);
        } else {
            this.updateMachineInstructionHighlighting(null, null);
            this.updateMachineInstructionCollapseState(null);
        }

        // 5. Apply annotations to the active line
        if (activeLineNumber && activeLocation.fileName === this.selectedFile) {
            this.applyAnnotations(activeLocation.fileName, activeLineNumber, staticInfo, organismState);
        }
    }

    /**
     * Renders the static structure of the source view, including the file dropdown
     * and the code lines for the currently selected file.
     * This is a destructive operation that rebuilds the inner DOM of the source section.
     * @private
     */
    renderSourceStructure() {
        const el = this.dom.section;
        if (!el) return;

        // Guard: No artifact
        if (!this.artifact || !this.artifact.sources) {
            el.innerHTML = `<div class="source-view-container"><div>No source data available.</div></div>`;
            return;
        }

        const sources = this.artifact.sources;
        const files = Object.keys(sources);
        const commonPrefix = this.findCommonPrefix(files);

        // 1. Build File Dropdown
        let dropdownHtml = '';
        if (files.length > 1) {
            const options = files.map(file => {
                const displayPath = file.startsWith(commonPrefix) ? file.substring(commonPrefix.length) : file;
                const selected = file === this.selectedFile ? 'selected' : '';
                return `<option value="${file}" ${selected}>${displayPath}</option>`;
            }).join('');
            dropdownHtml = `<select id="assembly-file-select" class="assembly-file-dropdown">${options}</select>`;
        }

        // 2. Build Code Lines
        const codeLinesRaw = sources[this.selectedFile];
        
        let codeLines = [];
        if (codeLinesRaw) {
            if (Array.isArray(codeLinesRaw.lines)) {
                codeLines = codeLinesRaw.lines;
            } else if (Array.isArray(codeLinesRaw)) {
                codeLines = codeLinesRaw;
            }
        }

        const codeHtml = codeLines.map((line, index) => {
            const lineNumber = index + 1;
            // Note: We do NOT set 'active' class here initially. It's handled by updateExecutionState.
            
            // Check if this line has multiple machine instructions (collapsible)
            let hasMultipleInstructions = false;
            if (this.artifact && this.artifact.sourceLineToInstructions) {
                const sourceLineKey = `${this.selectedFile}:${lineNumber}`;
                const machineInstructions = this.artifact.sourceLineToInstructions[sourceLineKey];
                hasMultipleInstructions = machineInstructions && machineInstructions.instructions && machineInstructions.instructions.length > 1;
            }
            
            const collapsibleClass = hasMultipleInstructions ? 'collapsible-source-line' : '';
            // Always include collapse indicator column - either with symbol or empty placeholder
            const collapseIndicator = hasMultipleInstructions 
                ? `<span class="collapse-indicator" data-source-line="${lineNumber}">▶</span>`
                : '<span class="collapse-indicator-placeholder"></span>';
            
            let html = `<div class="source-line ${collapsibleClass}" data-line="${lineNumber}">
                        <span class="line-number">${String(lineNumber).padStart(3, ' ')}</span>
                        ${collapseIndicator}
                        <pre class="assembly-line">${line.replace(/</g, '&lt;')}</pre>
                    </div>`;
            
            // Add machine instructions if available for this source line (only if more than one instruction)
            if (this.artifact && this.artifact.sourceLineToInstructions) {
                const sourceLineKey = `${this.selectedFile}:${lineNumber}`;
                const machineInstructions = this.artifact.sourceLineToInstructions[sourceLineKey];
                if (machineInstructions && machineInstructions.instructions && machineInstructions.instructions.length > 1) {
                    const machineInstructionsHtml = machineInstructions.instructions.map((inst, instIndex) => {
                        const operandsDisplay = inst.operandsAsString ? ` ${inst.operandsAsString}` : '';
                        return `<div class="machine-instruction" data-linear-address="${inst.linearAddress}" data-instruction-index="${instIndex}">
                                    <span class="machine-instruction-indicator"> </span>
                                    <span class="machine-instruction-opcode">${this.escapeHtml(inst.opcode)}</span>
                                    <span class="machine-instruction-operands">${this.escapeHtml(operandsDisplay)}</span>
                                </div>`;
                    }).join('');
                    html += `<div class="machine-instructions-container collapsed" data-source-line="${lineNumber}" data-indicator-line="${lineNumber}">${machineInstructionsHtml}</div>`;
                }
            }
            
            return html;
        }).join('');

        // 3. Assemble DOM
        el.innerHTML = `
            <div class="source-view-container">
                ${dropdownHtml}
                <div id="source-status-bar"></div>
                <div class="assembly-code-view" id="assembly-code-scroll-container">${codeHtml}</div>
            </div>
        `;

        // 4. Re-bind Event Listeners
        const dropdown = el.querySelector('#assembly-file-select');
        if (dropdown) {
            dropdown.addEventListener('change', (e) => {
                this.selectedFile = e.target.value;
                this.renderSourceStructure();
                // Note: Highlighting will be restored on next tick update
            });
        }

        // Update cached references
        this.dom.codeContainer = el.querySelector('.assembly-code-view');
        this.dom.status = el.querySelector('#source-status-bar');
        
        // Bind click handlers for collapsible source lines
        this.bindCollapseHandlers();
    }

    /**
     * Binds click handlers to collapsible source lines to toggle machine instructions visibility.
     * @private
     */
    bindCollapseHandlers() {
        if (!this.dom.codeContainer) return;
        
        const collapsibleLines = this.dom.codeContainer.querySelectorAll('.collapsible-source-line');
        collapsibleLines.forEach(line => {
            // Remove any existing listeners by cloning the element
            const newLine = line.cloneNode(true);
            line.parentNode.replaceChild(newLine, line);
            
            // Add click handler to toggle collapse
            newLine.addEventListener('click', (e) => {
                // Don't toggle if clicking on annotations
                if (e.target.closest('.register-annotation')) return;
                
                const lineNumber = parseInt(newLine.getAttribute('data-line'), 10);
                const container = this.dom.codeContainer.querySelector(
                    `.machine-instructions-container[data-source-line="${lineNumber}"]`
                );
                const indicator = this.dom.codeContainer.querySelector(
                    `.collapse-indicator[data-source-line="${lineNumber}"]`
                );
                if (container && indicator) {
                    container.classList.toggle('collapsed');
                    indicator.textContent = container.classList.contains('collapsed') ? '▶' : '▼';
                }
            });
        });
    }

    /**
     * Updates the collapse state of machine instructions. At each tick, all are collapsed
     * except the active one.
     * 
     * @param {number|null} activeLineNumber - The line number of the active source line, or null.
     * @private
     */
    updateMachineInstructionCollapseState(activeLineNumber) {
        if (!this.dom.codeContainer) return;
        
        // Collapse all machine instruction containers
        const allContainers = this.dom.codeContainer.querySelectorAll('.machine-instructions-container');
        allContainers.forEach(container => {
            container.classList.add('collapsed');
            const lineNumber = parseInt(container.getAttribute('data-source-line'), 10);
            const indicator = this.dom.codeContainer.querySelector(
                `.collapse-indicator[data-source-line="${lineNumber}"]`
            );
            if (indicator) {
                indicator.textContent = '▶';
            }
        });
        
        // Expand the active line's machine instructions
        if (activeLineNumber !== null && activeLineNumber !== undefined) {
            const activeContainer = this.dom.codeContainer.querySelector(
                `.machine-instructions-container[data-source-line="${activeLineNumber}"]`
            );
            const indicator = this.dom.codeContainer.querySelector(
                `.collapse-indicator[data-source-line="${activeLineNumber}"]`
            );
            if (activeContainer) {
                activeContainer.classList.remove('collapsed');
                if (indicator) {
                    indicator.textContent = '▼';
                }
            }
        }
    }

    /**
     * Efficiently updates the CSS classes for line highlighting.
     * This method avoids re-rendering the entire code view by only manipulating
     * CSS classes on the relevant line elements. It also handles restoring the
     * original line text when an annotation is removed.
     *
     * @param {number|null} activeLineNumber - The 1-based line number to highlight, or null to remove all highlights.
     * @private
     */
    updateHighlighting(activeLineNumber) {
        if (!this.dom.codeContainer) return;

        // Remove existing highlight AND restore original text
        const prevActive = this.dom.codeContainer.querySelector('.active-source-line');
        if (prevActive) {
            prevActive.classList.remove('active-source-line');
            prevActive.removeAttribute('id'); // Remove marker ID
            
            // Restore original text if we modified it with annotations
            if (this.lastAnnotatedLine) {
                const originalLine = this.getOriginalLine(this.lastAnnotatedLine);
                const preElement = prevActive.querySelector('.assembly-line');
                if (preElement && originalLine !== null) {
                     preElement.textContent = originalLine; // Restore text (removes spans)
                }
                this.lastAnnotatedLine = null;
            }
        }

        if (activeLineNumber) {
            // Add new highlight
            const newLine = this.dom.codeContainer.querySelector(`.source-line[data-line="${activeLineNumber}"]`);
            if (newLine) {
                newLine.classList.add('active-source-line');
                newLine.id = 'active-line-marker'; // Set marker ID for scrolling
                
                // Smooth scroll into view
                newLine.scrollIntoView({ block: 'center', behavior: 'smooth' });
            }
        }
    }

    /**
     * Applies runtime annotations to the currently active source line.
     * It fetches annotations from the `SourceAnnotator` and dynamically rebuilds
     * the HTML of the line to include the annotation spans.
     *
     * @param {string} fileName - The name of the file containing the line.
     * @param {number} lineNumber - The 1-based line number to annotate.
     * @param {object} staticInfo - Static info for the organism.
     * @param {object} organismState - The current dynamic state of the organism.
     * @private
     */
    applyAnnotations(fileName, lineNumber, staticInfo, organismState) {
        const lineElement = this.dom.codeContainer.querySelector(`.source-line[data-line="${lineNumber}"] .assembly-line`);
        if (!lineElement) return;

        const originalLine = this.getOriginalLine(lineNumber);
        if (originalLine === null) return;

        // Combine dynamic state with the static initialPosition for the annotator
        const fullState = {
            ...organismState,
            initialPosition: staticInfo.initialPosition ? { components: staticInfo.initialPosition } : undefined
        };

        const annotations = this.annotator.annotate(fullState, this.artifact, fileName, originalLine, lineNumber);
        if (!annotations || annotations.length === 0) {
            // Ensure clean state just in case
            lineElement.textContent = originalLine;
            return;
        }

        // Apply annotations by rebuilding HTML
        let resultHtml = "";
        let lastIndex = 0;
        
        // Filter and sort annotations that have valid column info
        const validAnnotations = annotations.filter(ann => ann.relativeColumn !== undefined && ann.relativeColumn >= 0);
        
        // Annotations are already sorted by relativeColumn in SourceAnnotator
        
        validAnnotations.forEach(ann => {
            // relativeColumn is 0-based index in the line
            const tokenStart = ann.relativeColumn; 
            const tokenEnd = tokenStart + ann.tokenText.length;
            
            // Validate bounds to prevent crashes
            if (tokenStart >= lastIndex && tokenEnd <= originalLine.length) {
                // Append text before token
                resultHtml += this.escapeHtml(originalLine.substring(lastIndex, tokenEnd));
                
                // Append annotation
                resultHtml += `<span class="register-annotation">${this.escapeHtml(ann.annotationText)}</span>`;
                
                lastIndex = tokenEnd;
            }
        });
        
        // Append remaining text
        resultHtml += this.escapeHtml(originalLine.substring(lastIndex));
        
        lineElement.innerHTML = resultHtml;
        this.lastAnnotatedLine = lineNumber;
    }

    /**
     * Retrieves the original, un-annotated text for a given line number from the artifact.
     *
     * @param {number} lineNumber - The 1-based line number.
     * @returns {string|null} The original line text, or null if not found.
     * @private
     */
    getOriginalLine(lineNumber) {
        if (!this.artifact || !this.artifact.sources || !this.selectedFile) return null;
        const source = this.artifact.sources[this.selectedFile];
        
        let lines = [];
        if (Array.isArray(source)) lines = source;
        else if (source.lines) lines = source.lines;
        
        const index = lineNumber - 1;
        if (index >= 0 && index < lines.length) {
            return lines[index];
        }
        return null;
    }

    /**
     * Updates the highlighting for machine instructions based on the active linear address.
     * 
     * @param {number|null} activeLinearAddress - The linear address of the active instruction, or null to clear highlighting.
     * @param {number|null} activeLineNumber - The line number of the active source line, or null.
     * @private
     */
    updateMachineInstructionHighlighting(activeLinearAddress, activeLineNumber) {
        if (!this.dom.codeContainer) return;
        
        // Remove active class from all machine instructions
        const allMachineInstructions = this.dom.codeContainer.querySelectorAll('.machine-instruction');
        allMachineInstructions.forEach(el => {
            el.classList.remove('active-machine-instruction');
            const indicator = el.querySelector('.machine-instruction-indicator');
            if (indicator) {
                indicator.textContent = ' ';
            }
        });
        
        // Mark the active machine instruction if we have an active linear address
        if (activeLinearAddress !== null && activeLinearAddress !== undefined && activeLineNumber !== null) {
            const machineInstructionsContainer = this.dom.codeContainer.querySelector(
                `.machine-instructions-container[data-source-line="${activeLineNumber}"]`
            );
            if (machineInstructionsContainer) {
                const activeMachineInstruction = machineInstructionsContainer.querySelector(
                    `.machine-instruction[data-linear-address="${activeLinearAddress}"]`
                );
                if (activeMachineInstruction) {
                    activeMachineInstruction.classList.add('active-machine-instruction');
                    const indicator = activeMachineInstruction.querySelector('.machine-instruction-indicator');
                    if (indicator) {
                        indicator.textContent = '→';
                    }
                }
            }
        }
    }

    /**
     * Updates the status bar at the top of the source view, typically to display errors.
     * @param {object|null} activeLocation - The location object which may contain an `error` property.
     * @private
     */
    updateStatusBar(activeLocation) {
        if (!this.dom.status) return;
        
        if (activeLocation && activeLocation.error) {
            this.dom.status.innerHTML = `
                <div style="color: #ffaa00; padding: 5px; font-size: 0.85em; border-bottom: 1px solid #333; background-color: #191923;">
                    ⚠️ ${activeLocation.error}
                </div>`;
            this.dom.status.style.display = 'block';
        } else {
            this.dom.status.style.display = 'none';
            this.dom.status.innerHTML = '';
        }
    }

    /**
     * Calculates the active source location (file and line number) based on the organism's IP.
     * It translates the absolute IP into a relative program coordinate, then uses the
     * artifact's source maps to find the corresponding line.
     *
     * @param {object} organismState - The organism's dynamic state, containing the `ip`.
     * @param {object} staticInfo - The organism's static info, containing the `initialPosition`.
     * @returns {{fileName: string, lineNumber: number, linearAddress?: number}|{error: string}|null} The location object, an error object, or null.
     * @private
     */
    calculateActiveLocation(organismState, staticInfo) {
        if (!organismState || !staticInfo || !this.artifact) return null;
        
        const ip = organismState.ip;
        const startPos = staticInfo.initialPosition;
        
        if (!Array.isArray(ip) || ip.length < 2 || !Array.isArray(startPos) || startPos.length < 2) {
            // Correctly use the vector from staticInfo if available
            if(staticInfo.initialPosition && staticInfo.initialPosition.components) {
                 const relX = ip[0] - staticInfo.initialPosition.components[0];
                 const relY = ip[1] - staticInfo.initialPosition.components[1];
                 const coordKey = `${relX}|${relY}`;
                 if (!this.artifact.relativeCoordToLinearAddress) {
                     return { error: "Missing address mapping in artifact" };
                 }
                 const linearAddress = this.artifact.relativeCoordToLinearAddress[coordKey];
                 if (linearAddress === undefined) {
                     return { error: `IP ${ip[0]}|${ip[1]} not mapped to a source line` };
                 }
                 if (!this.artifact.sourceMap) {
                     return { error: "Missing source map in artifact" };
                 }
                 let sourceInfo = this.artifact.sourceMap.find(sm => sm.linearAddress === linearAddress);
                 if (!sourceInfo) {
                     return { error: `Address ${linearAddress} has no source info` };
                 }
                 if (sourceInfo.sourceInfo) {
                    sourceInfo = sourceInfo.sourceInfo;
                 }
                 return {
                     fileName: sourceInfo.fileName,
                     lineNumber: sourceInfo.lineNumber,
                     linearAddress: linearAddress
                 };
            }
            return { error: "Invalid IP or Start Position data" };
        }

        const relX = ip[0] - startPos[0];
        const relY = ip[1] - startPos[1];
        
        // Deterministic key generation (must match Java backend)
        const coordKey = `${relX}|${relY}`;
        
        // Ensure relativeCoordToLinearAddress exists
        if (!this.artifact.relativeCoordToLinearAddress) {
            return { error: "Missing address mapping in artifact" };
        }

        const linearAddress = this.artifact.relativeCoordToLinearAddress[coordKey];
        
        if (linearAddress === undefined) {
            return { error: `IP ${ip[0]}|${ip[1]} not mapped to a source line` };
        }
        
        // Ensure sourceMap exists
        if (!this.artifact.sourceMap) {
            return { error: "Missing source map in artifact" };
        }

        let sourceInfo = this.artifact.sourceMap.find(sm => sm.linearAddress === linearAddress);
        
        if (!sourceInfo) {
            return { error: `Address ${linearAddress} has no source info` };
        }
        
        // Handle wrapped sourceInfo (observed in JSON serialization)
        if (sourceInfo.sourceInfo) {
            sourceInfo = sourceInfo.sourceInfo;
        }
        
        return {
            fileName: sourceInfo.fileName,
            lineNumber: sourceInfo.lineNumber,
            linearAddress: linearAddress
        };
    }
    
    /**
     * Finds the longest common prefix (up to the last slash) of an array of file paths.
     * Used to shorten file paths displayed in the dropdown.
     *
     * @param {string[]} paths - An array of file paths.
     * @returns {string} The longest common prefix ending with a '/', or an empty string.
     * @private
     */
    findCommonPrefix(paths) {
        if (!paths || paths.length === 0) return '';
        if (paths.length === 1) {
            const lastSlash = paths[0].lastIndexOf('/');
            return lastSlash >= 0 ? paths[0].substring(0, lastSlash + 1) : '';
        }
        
        let prefix = paths[0];
        for (let i = 1; i < paths.length; i++) {
            const path = paths[i];
            let matchLength = 0;
            const minLength = Math.min(prefix.length, path.length);
            for (let j = 0; j < minLength; j++) {
                if (prefix[j] === path[j]) matchLength++;
                else break;
            }
            prefix = prefix.substring(0, matchLength);
            if (!prefix) return '';
        }
        
        const lastSlash = prefix.lastIndexOf('/');
        return lastSlash >= 0 ? prefix.substring(0, lastSlash + 1) : '';
    }

    /**
     * Escapes HTML special characters in a string to prevent XSS.
     * @param {string} text The text to escape.
     * @returns {string} The escaped string.
     * @private
     */
    escapeHtml(text) {
        if (!text) return '';
        return text.replace(/&/g, "&amp;")
                   .replace(/</g, "&lt;")
                   .replace(/>/g, "&gt;")
                   .replace(/"/g, "&quot;")
                   .replace(/'/g, "&#039;");
    }
}
