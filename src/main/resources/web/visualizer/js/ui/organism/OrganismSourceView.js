import { SourceAnnotator } from '../../annotator/SourceAnnotator.js';

/**
 * Manages the source code view in the organism panel.
 * This class is responsible for displaying the assembly code of the selected organism,
 * handling file switching for included files, highlighting the currently executing line,
 * and applying runtime annotations to the active line.
 *
 * The artifact's `sources` hold every file once per module placement it stands in, the main
 * file first. Two placements of one file share its text but not its code or its annotations,
 * so the view selects an entry of that list, never a file alone.
 *
 * An entry also carries what the preprocessor recorded about the file: the regions of lines a
 * conditional left out (`leftOut`), each owned by its directive line, and notes at positions
 * (`notes`), such as the state of a flag a condition names. Both are recorded per instance of
 * injected tokens, a macro expansion or a `.SOURCE` inclusion, under the number a position carries
 * as its expansion: those of expansion 0 belong to the text in no such instance and are always
 * shown; those of expansion n only while the active position stands in expansion n, because every
 * instance shares the lines of its text but may have decided differently. A region is
 * folded at its directive line, with the arrows of the machine code under a line; a note is shown
 * as an annotation after the word at its position.
 *
 * @class OrganismSourceView
 */
export class OrganismSourceView {
    /**
     * Initializes the view, caching DOM elements and creating the annotator instance.
     * @param {HTMLElement} rootElement - The root element of the organism panel.
     */
    constructor(rootElement) {
        this.root = rootElement;
        this.artifact = null;
        this.selectedIndex = null; // Index of the displayed entry in artifact.sources
        this.annotator = new SourceAnnotator();
        this.lastAnnotatedLine = null; // Track annotated line to restore it
        this.activeExpansion = 0; // Instance of injected tokens whose regions and notes the listing shows
        this.unfoldedRegions = new Set(); // Keys of the regions the user unfolded, see foldKey()
        this.lineNotes = new Map(); // Line number -> notes of the listing, see recordsFor()

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
        this.activeExpansion = 0;
        this.unfoldedRegions.clear();
        
        // UX Logic: Default to the main file, the first entry, so view is not empty initially
        const sources = this.sourceEntries();
        this.selectedIndex = sources.length > 0 ? 0 : null;

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
     * @param {number} labelNamespaceMask - The label namespace the organism's body stands in.
     */
    updateExecutionState(organismState, staticInfo, labelNamespaceMask) {
        if (!this.artifact || !this.dom.section) return;

        const activeLocation = this.calculateActiveLocation(organismState, staticInfo);

        // 1. Auto-switch entry if execution moved to a different file or placement, and re-render
        // the listing if execution entered or left an instance of injected tokens the entry has
        // regions or notes of. This comes first because the re-render replaces the whole source
        // view, the status bar with it: a warning written before it would be thrown away again, which is
        // why one only ever appeared when execution happened to stay in the entry already on display.
        const activeIndex = this.findSourceIndex(activeLocation);
        const entryChanged = activeIndex !== null && this.selectedIndex !== activeIndex;
        if (entryChanged) {
            this.selectedIndex = activeIndex;
        }
        const activeExpansion = activeLocation && !activeLocation.error ? (activeLocation.expansion || 0) : 0;
        const expansionChanged = activeExpansion !== this.activeExpansion
            && (this.hasRecordsOf(this.activeExpansion) || this.hasRecordsOf(activeExpansion));
        this.activeExpansion = activeExpansion;
        if (entryChanged || expansionChanged) {
            this.lastAnnotatedLine = null; // Reset on re-render
            this.renderSourceStructure(); // Re-render needed because the content changed
        }

        // 2. Handle Status Bar (Errors/Warnings including mutation detection)
        this.updateStatusBar(activeLocation, organismState);

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
        if (activeLineNumber && activeIndex !== null && activeIndex === this.selectedIndex) {
            this.applyAnnotations(activeLocation.placement, activeLocation.fileName, activeLineNumber, staticInfo,
                organismState, labelNamespaceMask);
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

        // Guard: No artifact - clear the section
        if (!this.artifact || !this.artifact.sources) {
            el.innerHTML = '';
            this.dom.codeContainer = null;
            this.dom.status = null;
            return;
        }

        const sources = this.sourceEntries();
        const selectedSource = this.selectedIndex !== null ? sources[this.selectedIndex] : null;

        // 1. Build File Dropdown: one entry per placement, in the order of the list. An entry of
        // the main file's placement shows its path alone, every other one the placement's alias
        // chain and the path the program wrote; the resolved path is the tooltip.
        let dropdownHtml = '';
        if (sources.length > 1) {
            const mainPlacement = sources[0].placement || '';
            const options = sources.map((source, index) => {
                const placement = source.placement || '';
                const label = placement === mainPlacement ? source.path : `${placement} → ${source.path}`;
                const selected = index === this.selectedIndex ? 'selected' : '';
                return `<option value="${index}" title="${this.escapeHtml(source.resolvedPath)}" ${selected}>`
                    + `${this.escapeHtml(label)}</option>`;
            }).join('');
            dropdownHtml = `<select id="assembly-file-select" class="assembly-file-dropdown">${options}</select>`;
        }

        // 2. Build Code Lines
        const codeLines = selectedSource && Array.isArray(selectedSource.lines) ? selectedSource.lines : [];
        const records = this.recordsFor(selectedSource, codeLines);
        this.lineNotes = records.notes;

        const codeHtml = codeLines.map((line, index) => {
            const lineNumber = index + 1;
            // Note: We do NOT set 'active' class here initially. It's handled by updateExecutionState.
            const ownsFold = records.folds.has(lineNumber);
            const foldedBy = records.foldedBy.get(lineNumber);
            const foldedClass = foldedBy !== undefined
                ? `left-out-line${this.unfoldedRegions.has(this.foldKey(foldedBy)) ? '' : ' folded'}`
                : '';
            const foldedByAttribute = foldedBy !== undefined ? ` data-folded-by="${foldedBy}"` : '';
            
            // Check if this line should show collapsible machine instructions.
            // Filter out NOPs - they are padding for mutation robustness and clutter the display.
            // Show collapsible if (after NOP filtering): (a) multiple instructions, OR (b) any synthetic instruction.
            let showCollapsible = false;
            let filteredInstructions = [];
            const machineInstructions = this.machineInstructionsOf(
                selectedSource.placement, selectedSource.resolvedPath, lineNumber);
            if (machineInstructions && machineInstructions.instructions) {
                // Filter out NOP and WAIT instructions (padding for mutation robustness)
                filteredInstructions = machineInstructions.instructions.filter(
                    i => i.opcode !== 'NOP' && i.opcode !== 'WAIT'
                );
                const hasMultiple = filteredInstructions.length > 1;
                const hasSynthetic = filteredInstructions.some(i => i.synthetic);
                showCollapsible = hasMultiple || hasSynthetic;
            }

            // A directive line that owns a region folds it; such a line produces no code
            showCollapsible = showCollapsible && !ownsFold;
            const lineClass = ownsFold ? 'fold-source-line' : (showCollapsible ? 'collapsible-source-line' : '');
            // Always include collapse indicator column - either with symbol or empty placeholder
            let collapseIndicator = '<span class="collapse-indicator-placeholder"></span>';
            if (ownsFold) {
                const arrow = this.unfoldedRegions.has(this.foldKey(lineNumber)) ? '▼' : '▶';
                collapseIndicator = `<span class="collapse-indicator" data-fold-line="${lineNumber}">${arrow}</span>`;
            } else if (showCollapsible) {
                collapseIndicator = `<span class="collapse-indicator" data-source-line="${lineNumber}">▶</span>`;
            }

            let html = `<div class="source-line ${lineClass} ${foldedClass}" data-line="${lineNumber}"${foldedByAttribute}>
                        <span class="line-number">${String(lineNumber).padStart(3, ' ')}</span>
                        ${collapseIndicator}
                        <pre class="assembly-line">${this.renderLineHtml(lineNumber, line)}</pre>
                    </div>`;

            // Add machine instructions container if collapsible (uses filtered instructions without NOPs)
            if (showCollapsible && filteredInstructions.length > 0) {
                const machineInstructionsHtml = filteredInstructions.map((inst, instIndex) => {
                    const operandsDisplay = inst.operandsAsString ? ` ${inst.operandsAsString}` : '';
                    return `<div class="machine-instruction" data-linear-address="${inst.linearAddress}" data-instruction-index="${instIndex}">
                                <span class="machine-instruction-indicator"> </span>
                                <span class="machine-instruction-opcode">${this.escapeHtml(inst.opcode)}</span>
                                <span class="machine-instruction-operands">${this.escapeHtml(operandsDisplay)}</span>
                            </div>`;
                }).join('');
                html += `<div class="machine-instructions-container collapsed ${foldedClass}" data-source-line="${lineNumber}" data-indicator-line="${lineNumber}"${foldedByAttribute}>${machineInstructionsHtml}</div>`;
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
                this.selectedIndex = Number(e.target.value);
                this.renderSourceStructure();
                // Note: Highlighting will be restored on next tick update
            });
        }

        // Update cached references
        this.dom.codeContainer = el.querySelector('.assembly-code-view');
        this.dom.status = el.querySelector('#source-status-bar');
        
        // Bind click handlers for collapsible source lines and for the lines that own a fold
        this.bindCollapseHandlers();
        this.bindFoldHandlers();
    }

    /**
     * Binds click handlers to the directive lines that own a region left out, to fold and unfold
     * the region. Unfolded, its lines are shown greyed; the choice is kept across re-renders.
     * @private
     */
    bindFoldHandlers() {
        if (!this.dom.codeContainer) return;

        this.dom.codeContainer.querySelectorAll('.fold-source-line').forEach(line => {
            line.addEventListener('click', () => {
                const lineNumber = parseInt(line.getAttribute('data-line'), 10);
                const key = this.foldKey(lineNumber);
                const unfolded = !this.unfoldedRegions.has(key);
                if (unfolded) {
                    this.unfoldedRegions.add(key);
                } else {
                    this.unfoldedRegions.delete(key);
                }
                this.dom.codeContainer.querySelectorAll(`[data-folded-by="${lineNumber}"]`)
                    .forEach(el => el.classList.toggle('folded', !unfolded));
                const indicator = this.dom.codeContainer.querySelector(`.collapse-indicator[data-fold-line="${lineNumber}"]`);
                if (indicator) {
                    indicator.textContent = unfolded ? '▼' : '▶';
                }
            });
        });
    }

    /**
     * Collects the regions and notes of an entry that the listing shows: those of expansion 0 and
     * those of the active expansion. The compiler records equal regions and equal notes once, so
     * every directive line owns at most one region of the listing.
     *
     * @param {object|null} source - The entry on display.
     * @param {string[]} lines - The lines of the entry.
     * @returns {{folds: Map<number, {from: number, to: number}>, foldedBy: Map<number, number>,
     *            notes: Map<number, Array<{end: number, text: string}>>}} The regions by their
     *          directive line, the directive line owning each folded line, and the notes by line,
     *          each with the index in the line after which it is shown.
     * @private
     */
    recordsFor(source, lines) {
        const folds = new Map();
        const foldedBy = new Map();
        const notes = new Map();
        if (!source) return { folds, foldedBy, notes };

        const shown = record => {
            const expansion = record.expansion || 0;
            return expansion === 0 || expansion === this.activeExpansion;
        };

        (Array.isArray(source.leftOut) ? source.leftOut : []).filter(shown).forEach(region => {
            folds.set(region.directiveLine, { from: region.from, to: region.to });
        });
        folds.forEach((fold, directiveLine) => {
            for (let line = fold.from; line <= fold.to; line++) {
                if (!foldedBy.has(line)) foldedBy.set(line, directiveLine);
            }
        });

        (Array.isArray(source.notes) ? source.notes : []).filter(shown).forEach(note => {
            const text = lines[note.line - 1];
            if (text === undefined) return;
            // A note is shown after the word that starts at its column, as an annotation of a
            // register is shown after the register
            let end = Math.max(0, note.column - 1);
            while (end < text.length && /[A-Za-z0-9_]/.test(text[end])) end++;
            const lineNotes = notes.get(note.line) || [];
            lineNotes.push({ end, text: note.text });
            notes.set(note.line, lineNotes);
        });
        return { folds, foldedBy, notes };
    }

    /**
     * Reports whether the entry on display has a region or note of an instance of injected
     * tokens. Expansion 0 is always shown, so it never counts.
     *
     * @param {number} expansion - The number of the expansion.
     * @returns {boolean} True if a region or note of that expansion exists in the entry.
     * @private
     */
    hasRecordsOf(expansion) {
        if (!expansion || this.selectedIndex === null) return false;
        const source = this.sourceEntries()[this.selectedIndex];
        if (!source) return false;
        const of = record => (record.expansion || 0) === expansion;
        return (Array.isArray(source.leftOut) && source.leftOut.some(of))
            || (Array.isArray(source.notes) && source.notes.some(of));
    }

    /**
     * Builds the key under which the unfolding of a region is kept: the entry on display and the
     * directive line.
     *
     * @param {number} directiveLine - The line that owns the region.
     * @returns {string} The key.
     * @private
     */
    foldKey(directiveLine) {
        return `${this.selectedIndex}:${directiveLine}`;
    }

    /**
     * Builds the HTML of a line: its text with the notes of the listing and the given runtime
     * annotations as annotation spans after the tokens they belong to.
     *
     * @param {number} lineNumber - The 1-based line number.
     * @param {string} text - The original text of the line.
     * @param {Array<{relativeColumn: number, tokenText: string, annotationText: string}>} [annotations]
     *        The runtime annotations, by the 0-based column of their token.
     * @returns {string} The HTML.
     * @private
     */
    renderLineHtml(lineNumber, text, annotations = []) {
        const inserts = (this.lineNotes.get(lineNumber) || []).map(note => ({ start: note.end, end: note.end, text: note.text }));
        annotations.forEach(ann => {
            const start = ann.relativeColumn;
            inserts.push({ start, end: start + ann.tokenText.length, text: ann.annotationText });
        });
        inserts.sort((a, b) => a.end - b.end);

        let html = '';
        let lastIndex = 0;
        inserts.forEach(insert => {
            // Validate bounds to prevent crashes
            if (insert.start >= lastIndex && insert.end <= text.length) {
                html += this.escapeHtml(text.substring(lastIndex, insert.end));
                html += `<span class="annotation">${this.escapeHtml(insert.text)}</span>`;
                lastIndex = insert.end;
            }
        });
        return html + this.escapeHtml(text.substring(lastIndex));
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
            
            // Restore the line as the listing built it if we modified it with annotations
            if (this.lastAnnotatedLine) {
                const originalLine = this.getOriginalLine(this.lastAnnotatedLine);
                const preElement = prevActive.querySelector('.assembly-line');
                if (preElement && originalLine !== null) {
                     // Removes the runtime annotations, keeps the notes
                     preElement.innerHTML = this.renderLineHtml(this.lastAnnotatedLine, originalLine);
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
     * the HTML of the line from its original text, with the notes of the listing and the
     * annotation spans.
     *
     * @param {string} placement - The alias chain of the placement the line belongs to.
     * @param {string} fileName - The name of the file containing the line.
     * @param {number} lineNumber - The 1-based line number to annotate.
     * @param {object} staticInfo - Static info for the organism.
     * @param {object} organismState - The current dynamic state of the organism.
     * @param {number} labelNamespaceMask - The label namespace the organism's body stands in.
     * @private
     */
    applyAnnotations(placement, fileName, lineNumber, staticInfo, organismState, labelNamespaceMask) {
        const lineElement = this.dom.codeContainer.querySelector(`.source-line[data-line="${lineNumber}"] .assembly-line`);
        if (!lineElement) return;

        const originalLine = this.getOriginalLine(lineNumber);
        if (originalLine === null) return;

        // Combine dynamic state with the static initialPosition for the annotator
        const fullState = {
            ...organismState,
            initialPosition: staticInfo.initialPosition ? { components: staticInfo.initialPosition } : undefined
        };

        const annotations = this.annotator.annotate(fullState, this.artifact, placement, fileName, originalLine,
            lineNumber, labelNamespaceMask);
        if (!annotations || annotations.length === 0) {
            // Ensure clean state just in case
            lineElement.innerHTML = this.renderLineHtml(lineNumber, originalLine);
            return;
        }

        // Annotations with valid column info; relativeColumn is the 0-based index in the line
        const validAnnotations = annotations.filter(ann => ann.relativeColumn !== undefined && ann.relativeColumn >= 0);

        lineElement.innerHTML = this.renderLineHtml(lineNumber, originalLine, validAnnotations);
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
        if (this.selectedIndex === null) return null;
        const source = this.sourceEntries()[this.selectedIndex];
        const lines = source && Array.isArray(source.lines) ? source.lines : [];

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
     * Updates the status bar at the top of the source view to display errors and warnings.
     * Checks for location errors and opcode mismatches (runtime vs compiled).
     *
     * @param {object|null} activeLocation - The location object which may contain an `error` property.
     * @param {object|null} organismState - The organism's dynamic state for opcode comparison.
     * @private
     */
    updateStatusBar(activeLocation, organismState) {
        if (!this.dom.status) return;

        // Collect warning message (location error or opcode mismatch)
        let warning = null;

        if (activeLocation && activeLocation.error) {
            warning = activeLocation.error;
        } else {
            const mismatch = this.detectOpcodeMismatch(activeLocation, organismState);
            if (mismatch) {
                warning = `Opcode mismatch: expected ${mismatch.expected}, found ${mismatch.actual}`;
            }
        }

        // Render or hide status bar
        if (warning) {
            this.dom.status.innerHTML = `
                <div style="color: #ffaa00; padding: 5px; font-size: 0.85em; border-bottom: 1px solid #333; background-color: #191923;">
                    ⚠️ ${warning}
                </div>`;
            this.dom.status.style.display = 'block';
        } else {
            this.dom.status.style.display = 'none';
            this.dom.status.innerHTML = '';
        }
    }

    /**
     * Detects if the runtime opcode differs from the compiled opcode at the current IP.
     *
     * @param {object|null} activeLocation - The location with linearAddress.
     * @param {object|null} organismState - The organism state with instructions.next.
     * @returns {{expected: string, actual: string}|null} Mismatch info or null if no mismatch.
     * @private
     */
    detectOpcodeMismatch(activeLocation, organismState) {
        if (!activeLocation || activeLocation.linearAddress === undefined) return null;

        const actualOpcode = organismState?.instructions?.next?.opcodeName;
        if (!actualOpcode) return null;

        const machineInstructions = this.machineInstructionsOf(activeLocation.placement, activeLocation.fileName,
            activeLocation.lineNumber);
        if (!machineInstructions?.instructions) return null;

        const expectedInstruction = machineInstructions.instructions.find(
            i => i.linearAddress === activeLocation.linearAddress
        );
        if (!expectedInstruction) return null;

        if (expectedInstruction.opcode !== actualOpcode) {
            return { expected: expectedInstruction.opcode, actual: actualOpcode };
        }
        return null;
    }

    /**
     * Calculates the active source location (file and line number) based on the organism's IP.
     * It translates the absolute IP into a relative program coordinate, then uses the
     * artifact's source maps to find the corresponding line.
     *
     * <p>This method handles toroidal grids correctly by wrapping the relative coordinates
     * when they exceed half the grid size. This ensures that an organism crossing a grid
     * boundary (e.g., from x=99 to x=0 on a 100-wide grid) produces the correct relative
     * offset (+1) instead of the naive difference (-99).</p>
     *
     * @param {object} organismState - The organism's dynamic state, containing the `ip`.
     * @param {object} staticInfo - The organism's static info, containing the `initialPosition`.
     * @returns {{placement: string, fileName: string, lineNumber: number, expansion: number, linearAddress?: number}|{error: string}|null}
     *          The location object, with the instance of injected tokens the instruction was
     *          compiled in (0 outside any), an error object, or null.
     * @private
     */
    calculateActiveLocation(organismState, staticInfo) {
        if (!organismState || !staticInfo || !this.artifact) return null;

        const ip = organismState.ip;
        let startPos = staticInfo.initialPosition;

        // Handle nested vector format (e.g., { components: [x, y] })
        if (startPos && startPos.components && Array.isArray(startPos.components)) {
            startPos = startPos.components;
        }

        if (!Array.isArray(ip) || ip.length < 2 || !Array.isArray(startPos) || startPos.length < 2) {
            return { error: "Invalid IP or Start Position data" };
        }

        // Calculate relative coordinates with toroidal wrapping
        const relCoords = this.calculateToroidalRelativeCoords(ip, startPos);
        const relX = relCoords[0];
        const relY = relCoords[1];

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
            placement: sourceInfo.placement || '',
            fileName: sourceInfo.fileName,
            lineNumber: sourceInfo.lineNumber,
            expansion: sourceInfo.expansion || 0,
            linearAddress: linearAddress
        };
    }

    /**
     * Calculates relative coordinates with toroidal wrapping.
     *
     * <p>When an organism crosses a grid boundary in a toroidal world, the naive difference
     * between IP and start position can be misleading. For example, if an organism starts
     * at x=99 on a 100-wide grid and moves right to x=0, the naive difference is -99,
     * but the actual relative offset is +1.</p>
     *
     * <p>This method normalizes the difference to [0, width) to match the layout engine's
     * coordinate space, which always starts at [0,0] and grows non-negatively.</p>
     *
     * @param {number[]} ip - The current instruction pointer [x, y].
     * @param {number[]} startPos - The organism's initial position [x, y].
     * @returns {number[]} The relative coordinates [relX, relY] with toroidal wrapping applied.
     * @private
     */
    calculateToroidalRelativeCoords(ip, startPos) {
        let relX = ip[0] - startPos[0];
        let relY = ip[1] - startPos[1];

        // Get environment properties from artifact (attached during caching in AppController)
        const envProps = this.artifact?.envProps;
        const worldShape = envProps?.worldShape;
        const toroidal = envProps?.toroidal;

        if (worldShape && toroidal) {
            // Wrap X coordinate if toroidal in X dimension
            if (toroidal[0] && worldShape[0] > 0) {
                const width = worldShape[0];
                // Normalize to [0, width) to match the layout engine's non-negative coordinates.
                // The layout always starts at [0,0] and grows positively, so we must NOT
                // shift to [-width/2, width/2) as that would produce negative keys that
                // don't exist in the relativeCoordToLinearAddress map.
                relX = ((relX % width) + width) % width;
            }

            // Wrap Y coordinate if toroidal in Y dimension
            if (toroidal[1] && worldShape[1] > 0) {
                const height = worldShape[1];
                // Normalize to [0, height) to match the layout engine's non-negative coordinates.
                relY = ((relY % height) + height) % height;
            }
        }

        return [relX, relY];
    }

    /**
     * Returns the artifact's source entries, one per file and module placement.
     *
     * @returns {Array<{placement: string, path: string, resolvedPath: string, lines: string[],
     *          leftOut: Array<{expansion: number, directiveLine: number, from: number, to: number}>,
     *          notes: Array<{expansion: number, line: number, column: number, text: string}>}>} The
     *          entries, or an empty array without an artifact.
     * @private
     */
    sourceEntries() {
        return this.artifact && Array.isArray(this.artifact.sources) ? this.artifact.sources : [];
    }

    /**
     * Finds the source entry an active location names by its placement and file.
     *
     * @param {object|null} location - The active location, or an error object or null.
     * @returns {number|null} The index of the entry, or null if the location names none.
     * @private
     */
    findSourceIndex(location) {
        if (!location || !location.fileName) return null;
        const placement = location.placement || '';
        const index = this.sourceEntries().findIndex(
            source => (source.placement || '') === placement && source.resolvedPath === location.fileName);
        return index >= 0 ? index : null;
    }

    /**
     * Looks up the machine instructions of a source line in the artifact's
     * `sourceLineToInstructions`, which holds one entry per placement and file with the
     * instructions by line number. Two placements of one file share its lines but not its code.
     *
     * @param {string} placement - The alias chain of the placement; empty or absent for the empty one.
     * @param {string} fileName - The resolved path of the file.
     * @param {number} lineNumber - The 1-based line number.
     * @returns {{instructions: Array<object>}|null} The instructions of the line, or null if
     *          the artifact has none for it.
     * @private
     */
    machineInstructionsOf(placement, fileName, lineNumber) {
        const entries = this.artifact?.sourceLineToInstructions;
        if (!Array.isArray(entries)) return null;
        const wanted = placement || '';
        const entry = entries.find(e => (e.placement || '') === wanted && e.fileName === fileName);
        return entry?.lines?.[lineNumber] || null;
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
