import { SourceAnnotator } from '../../annotator/SourceAnnotator.js';

/**
 * Manages the source code view in the organism panel.
 * This class is responsible for displaying the assembly code of the selected organism,
 * handling file switching for included files, highlighting the currently executing line,
 * and applying runtime annotations to the active line.
 *
 * The artifact's `sources` hold one entry per inclusion of a file, the main file first: every
 * module placement and every `.SOURCE` inclusion, each named by its placement, its resolved path
 * and its `instance`, the number its positions carry as their expansion. The view lists them as
 * items, built once per artifact: entries of one placement and file whose regions, notes and
 * machine instructions are equal cannot be told apart and are one item,
 * `{placement, path, resolvedPath, instances: [..], includedAt: [..], lines, leftOut, notes}`, in
 * the position of the first; entries that differ in anything shown stay items of their own. A
 * position finds its item by its placement, file and expansion.
 *
 * A macro expansion is no entry: the artifact's `expansions` name, by the number its positions
 * carry, where it was called (`calledAt`), where its body stands (`definedAt`, which finds the
 * item holding the body's lines the way a position does), its name, the arguments bound to its
 * parameters, and the regions and notes it decided. While the active position stands in an
 * expansion, a frame bar above the listing names the chain of expansions it stands in, innermost
 * first, with links to each body and each call; the call lines of the chain on display are marked
 * as frames.
 *
 * An item carries what the preprocessor recorded about its text: the regions of lines a
 * conditional left out (`leftOut`), each owned by its directive line, and notes at positions
 * (`notes`), such as the state of a flag a condition names, shown as an annotation after the word
 * at its position, as is the value a constant stands for (`LIMIT[=DATA:9]`), which the
 * artifact's `constantValues` give for the key of the constant's definition. The item's own are
 * always shown; those of an expansion only while the active position stands in it, directly or
 * through an expansion called in its body, and the item holds its body, because every expansion
 * shares the lines of the body but may have decided differently. The machine instructions under a
 * line are keyed by the expansion of their positions: a line shows those of the item's own
 * instances and, while expansions of the frame chain are shown, those of the shown expansions on
 * their body lines. A body outside a frame shows none: the definition is a template and produces
 * nothing by itself.
 *
 * The listing folds two kinds of lines under a line, each in a container that draws the same
 * guide line under the line's indicator: the machine instructions the compiler generated for a
 * line (`▶` collapsed, `▼` expanded), and the region of lines a conditional left out, after the
 * directive line that owns it (`[+]` folded, `[−]` unfolded, the lines greyed).
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
        this.items = []; // The entries of artifact.sources as the dropdown lists them, see buildItems()
        this.selectedIndex = null; // Index of the displayed item
        this.annotator = new SourceAnnotator();
        this.lastAnnotatedLine = null; // Track annotated line to restore it
        this.chain = []; // Expansions the active position stands in, innermost first
        this.shownExpansions = []; // Expansions whose records and instructions the listing shows
        this.instructionIndex = new Map(); // placement|file|expansion -> instructions by line
        this.unfoldedRegions = new Set(); // Keys of the regions the user unfolded, see foldKey()
        this.lineNotes = new Map(); // Line number -> notes of the listing, see recordsFor()
        this.lastExecutionState = null; // Arguments of the last updateExecutionState call

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
        this.chain = [];
        this.shownExpansions = [];
        this.instructionIndex = new Map();
        (Array.isArray(artifact?.sourceLineToInstructions) ? artifact.sourceLineToInstructions : []).forEach(entry =>
            this.instructionIndex.set(this.instructionKey(entry.placement, entry.fileName, entry.expansion), entry));
        this.unfoldedRegions.clear();
        this.lastExecutionState = null;
        this.items = this.buildItems();

        // UX Logic: Default to the main file, the first item, so view is not empty initially
        this.selectedIndex = this.items.length > 0 ? 0 : null;

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
        this.lastExecutionState = { organismState, staticInfo, labelNamespaceMask };

        const activeLocation = this.calculateActiveLocation(organismState, staticInfo);

        // 1. Auto-switch item if execution moved to a different inclusion, and re-render the
        // listing if the records or instructions it shows change with the expansions execution
        // stands in. Both come before the status bar and the highlighting, because the re-render
        // replaces the whole source view, the status bar with it, and everything written into it
        // has to be written after.
        const activeIndex = this.findSourceIndex(activeLocation);
        const entryChanged = activeIndex !== null && this.selectedIndex !== activeIndex;
        if (entryChanged) {
            this.selectedIndex = activeIndex;
        }
        this.chain = this.frameChain(activeLocation);
        if (entryChanged || this.expansionsShown().join(',') !== this.shownExpansions.join(',')) {
            this.lastAnnotatedLine = null; // Reset on re-render
            this.renderSourceStructure(); // Re-render needed because the content changed
        }

        this.showExecutionState(activeLocation, activeIndex, organismState, staticInfo, labelNamespaceMask);
    }

    /**
     * Shows an execution state on the listing on display, without switching the entry: the
     * status bar, the active line and its machine instruction, and the annotations of the active
     * line. The active line is marked only while the entry on display is the one it belongs to.
     *
     * @param {object|null} activeLocation - The active location, or an error object or null.
     * @param {number|null} activeIndex - The index of the entry the active location names, or null.
     * @param {object} organismState - The current dynamic state of the organism.
     * @param {object} staticInfo - Static info for the organism, including `initialPosition`.
     * @param {number} labelNamespaceMask - The label namespace the organism's body stands in.
     * @private
     */
    showExecutionState(activeLocation, activeIndex, organismState, staticInfo, labelNamespaceMask) {
        // 2. Handle Status Bar (Errors/Warnings including mutation detection) and the frames
        this.updateStatusBar(activeLocation, organismState);
        this.renderFrameBar(activeLocation);

        // 3. Update Line Highlighting (DOM manipulation only, no re-render)
        const onDisplay = activeIndex === null || activeIndex === this.selectedIndex;
        const activeLineNumber = activeLocation && onDisplay ? activeLocation.lineNumber : null;
        this.updateHighlighting(activeLineNumber);
        this.markCallLines(activeLineNumber);

        // 4. Update Machine Instruction Highlighting and Collapse State
        if (activeLineNumber && activeLocation.linearAddress !== undefined) {
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

        const items = this.items;
        const selectedItem = this.selectedIndex !== null ? items[this.selectedIndex] : null;
        this.shownExpansions = this.expansionsShown();

        // 1. Build File Dropdown: one item per inclusion that can be told apart, in the order of
        // the list, labelled by itemLabel(); the resolved path is the tooltip.
        let dropdownHtml = '';
        if (items.length > 1) {
            const options = items.map((item, index) => {
                const label = this.itemLabel(item, true);
                const selected = index === this.selectedIndex ? 'selected' : '';
                return `<option value="${index}" title="${this.escapeHtml(item.resolvedPath)}" ${selected}>`
                    + `${this.escapeHtml(label)}</option>`;
            }).join('');
            dropdownHtml = `<select id="assembly-file-select" class="assembly-file-dropdown">${options}</select>`;
        }

        // 2. Build Code Lines
        const codeLines = selectedItem && Array.isArray(selectedItem.lines) ? selectedItem.lines : [];
        const records = this.recordsFor(selectedItem, codeLines);
        this.lineNotes = records.notes;

        // A line opens the container of the region it begins and closes the container of the region
        // it ends; a region begins on the line after the directive line that owns it, and regions
        // never overlap, because a directive in a branch that was left out decides nothing.
        const codeHtml = codeLines.map((line, index) => {
            const lineNumber = index + 1;
            // Note: We do NOT set 'active' class here initially. It's handled by updateExecutionState.
            const ownsFold = records.folds.has(lineNumber);
            const foldedBy = records.foldedBy.get(lineNumber);
            const region = foldedBy !== undefined ? records.folds.get(foldedBy) : undefined;
            const leftOutClass = region ? 'left-out-line' : '';
            
            // Check if this line should show collapsible machine instructions.
            // Filter out NOPs - they are padding for mutation robustness and clutter the display.
            // Show collapsible if (after NOP filtering): (a) multiple instructions, OR (b) any synthetic instruction.
            let showCollapsible = false;
            let filteredInstructions = [];
            const machineInstructions = this.instructionsFor(selectedItem, lineNumber);
            if (machineInstructions.length > 0) {
                // Filter out NOP and WAIT instructions (padding for mutation robustness)
                filteredInstructions = machineInstructions.filter(
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
                const sign = this.unfoldedRegions.has(this.foldKey(lineNumber)) ? '[−]' : '[+]';
                collapseIndicator = `<span class="collapse-indicator" data-fold-line="${lineNumber}">${sign}</span>`;
            } else if (showCollapsible) {
                collapseIndicator = `<span class="collapse-indicator" data-source-line="${lineNumber}">▶</span>`;
            }

            let html = '';
            if (region && region.from === lineNumber) {
                const folded = this.unfoldedRegions.has(this.foldKey(foldedBy)) ? '' : ' folded';
                html += `<div class="left-out-region${folded}" data-fold-line="${foldedBy}">`;
            }
            html += `<div class="source-line ${lineClass} ${leftOutClass}" data-line="${lineNumber}">
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
                html += `<div class="machine-instructions-container collapsed" data-source-line="${lineNumber}" data-indicator-line="${lineNumber}">${machineInstructionsHtml}</div>`;
            }
            if (region && region.to === lineNumber) {
                html += '</div>';
            }

            return html;
        }).join('');

        // 3. Assemble DOM
        el.innerHTML = `
            <div class="source-view-container">
                ${dropdownHtml}
                <div class="source-frame-bar"></div>
                <div id="source-status-bar"></div>
                <div class="assembly-code-view" id="assembly-code-scroll-container">${codeHtml}</div>
            </div>
        `;

        // 4. Re-bind Event Listeners
        const dropdown = el.querySelector('#assembly-file-select');
        if (dropdown) {
            dropdown.addEventListener('change', (e) => {
                this.selectedIndex = Number(e.target.value);
                this.lastAnnotatedLine = null;
                this.renderSourceStructure();
                this.showLastExecutionState();
            });
        }

        // Update cached references
        this.dom.codeContainer = el.querySelector('.assembly-code-view');
        this.dom.status = el.querySelector('#source-status-bar');
        this.dom.frameBar = el.querySelector('.source-frame-bar');
        this.frameBarKey = null;

        // Bind click handlers for collapsible source lines and for the lines that own a fold
        this.bindCollapseHandlers();
        this.bindFoldHandlers();
    }

    /**
     * Shows the last execution state on the listing on display, as the last tick showed it, for a
     * listing that was rendered anew without a tick: the active line and its annotations when the
     * entry on display is the active one.
     * @private
     */
    showLastExecutionState() {
        if (!this.lastExecutionState) return;
        const { organismState, staticInfo, labelNamespaceMask } = this.lastExecutionState;
        const activeLocation = this.calculateActiveLocation(organismState, staticInfo);
        const activeIndex = this.findSourceIndex(activeLocation);
        this.showExecutionState(activeLocation, activeIndex, organismState, staticInfo, labelNamespaceMask);
    }

    /**
     * Builds the label of an item: `[CHAIN → ]path (from a:1 / b:2)`. The placement's alias chain
     * is left out when it is the main file's; `(from …)` names the line of every directive that
     * made one of the item's entries, and is absent for the main file. The file of such a line is
     * named by the path, as written, of the item the directive stands in, found as the item of a
     * position is; without such an item, by its file name.
     *
     * @param {object} item - The item.
     * @param {boolean} withInclusions - Whether the label names the inclusion points.
     * @returns {string} The label, not escaped.
     * @private
     */
    itemLabel(item, withInclusions) {
        const mainPlacement = this.items.length > 0 ? (this.items[0].placement || '') : '';
        const placement = item.placement || '';
        let label = placement === mainPlacement ? item.path : `${placement} → ${item.path}`;
        if (withInclusions && item.includedAt.length > 0) {
            const points = item.includedAt.map(at => {
                const including = this.findSourceIndex(at);
                const file = including !== null ? this.items[including].path : at.fileName;
                return `${file}:${at.lineNumber}`;
            });
            label += ` (from ${points.join(' / ')})`;
        }
        return label;
    }

    /**
     * Builds the items of the dropdown from the artifact's entries, once per artifact. An entry
     * whose placement and file equal an earlier item's, and whose regions, notes and machine
     * instructions per line are equal too, joins that item with its instance and inclusion point;
     * any other entry is an item of its own. The regions and notes are compared without the
     * instance they carry.
     *
     * @returns {Array<object>} The items, in the order of their first entries.
     * @private
     */
    buildItems() {
        const sources = this.artifact && Array.isArray(this.artifact.sources) ? this.artifact.sources : [];
        const withoutExpansion = records => (Array.isArray(records) ? records : [])
            .map(record => ({ ...record, expansion: undefined }));
        const items = [];
        const signatures = [];
        sources.forEach(source => {
            const instance = source.instance || 0;
            const signature = JSON.stringify({
                placement: source.placement || '',
                resolvedPath: source.resolvedPath,
                leftOut: withoutExpansion(source.leftOut),
                notes: withoutExpansion(source.notes),
                instructions: this.instructionEntry(source.placement, source.resolvedPath, instance)?.lines || {}
            });
            const same = source.includedAt ? signatures.indexOf(signature) : -1;
            if (same >= 0 && items[same].includedAt.length > 0) {
                items[same].instances.push(instance);
                items[same].includedAt.push(source.includedAt);
                return;
            }
            items.push({
                placement: source.placement || '',
                path: source.path,
                resolvedPath: source.resolvedPath,
                instances: [instance],
                includedAt: source.includedAt ? [source.includedAt] : [],
                lines: source.lines,
                leftOut: Array.isArray(source.leftOut) ? source.leftOut : [],
                notes: Array.isArray(source.notes) ? source.notes : []
            });
            signatures.push(signature);
        });
        return items;
    }

    /**
     * Returns the chain of expansions a location stands in, innermost first: the expansion of the
     * location, then the expansion its call stands in, as long as each is an expansion.
     *
     * @param {object|null} location - The active location, or an error object or null.
     * @returns {number[]} The numbers of the expansions; empty outside any.
     * @private
     */
    frameChain(location) {
        const chain = [];
        if (!location || location.error) return chain;
        let expansion = location.expansion || 0;
        while (this.expansionOf(expansion) && !chain.includes(expansion)) {
            chain.push(expansion);
            expansion = this.expansionOf(expansion).calledAt?.expansion || 0;
        }
        return chain;
    }

    /**
     * Returns the expansions whose records and instructions the listing shows: every expansion of
     * the chain the active position stands in whose body the item on display holds.
     *
     * @returns {number[]} The numbers of the expansions, innermost first; empty outside any.
     * @private
     */
    expansionsShown() {
        if (this.selectedIndex === null) return [];
        return this.chain.filter(number =>
            this.findSourceIndex(this.expansionOf(number).definedAt) === this.selectedIndex);
    }

    /**
     * Shows the chain of frames in the bar above the listing, innermost first:
     * `in NAME (P = ARG, …), expanded at FILE:LINE`, the name linking to the body, the call
     * position to the call. The bar is empty and hidden outside any expansion, whatever item is
     * on display; it is rebuilt only when the chain or the active line changes.
     *
     * @param {object|null} activeLocation - The active location.
     * @private
     */
    renderFrameBar(activeLocation) {
        const bar = this.dom.frameBar;
        if (!bar) return;
        const key = this.chain.length > 0 ? `${this.chain.join(',')}@${activeLocation?.lineNumber}` : '';
        if (key === this.frameBarKey) return;
        this.frameBarKey = key;
        if (this.chain.length === 0) {
            bar.innerHTML = '';
            bar.style.display = 'none';
            return;
        }
        bar.innerHTML = this.chain.map((number, depth) => {
            const expansion = this.expansionOf(number);
            const bindings = (expansion.bindings || [])
                .map(binding => `${binding.parameter} = ${binding.argument}`).join(', ');
            const name = this.escapeHtml(expansion.name) + (bindings ? ` (${this.escapeHtml(bindings)})` : '');
            const calledAt = expansion.calledAt;
            const caller = this.findSourceIndex(calledAt);
            const file = caller !== null ? this.itemLabel(this.items[caller], false) : calledAt.fileName;
            return `<span class="source-frame">in <a href="#" class="source-frame-link" data-frame="${depth}" `
                + `data-target="body">${name}</a>, expanded at <a href="#" class="source-frame-link" `
                + `data-frame="${depth}" data-target="call">${this.escapeHtml(file)}:${calledAt.lineNumber}</a></span>`;
        }).join('<span class="source-frame-separator"> ← </span>');
        bar.style.display = '';
        bar.querySelectorAll('.source-frame-link').forEach(link => {
            link.addEventListener('click', event => {
                event.preventDefault();
                const depth = Number(link.getAttribute('data-frame'));
                const expansion = this.expansionOf(this.chain[depth]);
                if (link.getAttribute('data-target') === 'call') {
                    this.goTo(expansion.calledAt, expansion.calledAt.lineNumber);
                } else {
                    // In the body: the active line in the innermost frame, the call of the next
                    // inner expansion in an outer one
                    const line = depth === 0
                        ? activeLocation.lineNumber
                        : this.expansionOf(this.chain[depth - 1]).calledAt.lineNumber;
                    this.goTo(expansion.definedAt, line);
                }
            });
        });
    }

    /**
     * Selects the item a position stands in, shows the execution state on it as a manual choice
     * in the dropdown does, and scrolls a line of it into view.
     *
     * @param {object} position - The position whose item is selected.
     * @param {number} lineNumber - The line to scroll to.
     * @private
     */
    goTo(position, lineNumber) {
        const index = this.findSourceIndex(position);
        if (index === null) return;
        this.selectedIndex = index;
        this.lastAnnotatedLine = null;
        this.renderSourceStructure();
        this.showLastExecutionState();
        const line = this.dom.codeContainer?.querySelector(`.source-line[data-line="${lineNumber}"]`);
        if (line) {
            line.scrollIntoView({ block: 'center' });
        }
    }

    /**
     * Marks every call line of the chain that stands in the item on display as a frame, unless it
     * is the active line.
     *
     * @param {number|null} activeLineNumber - The active line on display, or null.
     * @private
     */
    markCallLines(activeLineNumber) {
        if (!this.dom.codeContainer) return;
        this.dom.codeContainer.querySelectorAll('.active-call-line')
            .forEach(line => line.classList.remove('active-call-line'));
        this.chain.forEach(number => {
            const calledAt = this.expansionOf(number).calledAt;
            if (this.findSourceIndex(calledAt) !== this.selectedIndex || calledAt.lineNumber === activeLineNumber) return;
            const line = this.dom.codeContainer.querySelector(`.source-line[data-line="${calledAt.lineNumber}"]`);
            if (line) {
                line.classList.add('active-call-line');
            }
        });
    }

    /**
     * Binds click handlers to the directive lines that own a region left out, to fold and unfold
     * the region's container, marked `[+]` when folded and `[−]` when unfolded. Unfolded, its
     * lines are shown greyed; the choice is kept across re-renders.
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
                const region = this.dom.codeContainer.querySelector(`.left-out-region[data-fold-line="${lineNumber}"]`);
                if (region) {
                    region.classList.toggle('folded', !unfolded);
                }
                const indicator = this.dom.codeContainer.querySelector(`.collapse-indicator[data-fold-line="${lineNumber}"]`);
                if (indicator) {
                    indicator.textContent = unfolded ? '[−]' : '[+]';
                }
            });
        });
    }

    /**
     * Collects the regions and notes the listing shows: the item's own, those of the expansions
     * whose bodies the listing shows, see expansionsShown(), and the values of the constants used
     * outside the regions left out. The compiler records equal regions and equal notes once, so
     * every directive line owns at most one region of the listing.
     *
     * @param {object|null} item - The item on display.
     * @param {string[]} lines - The lines of the item.
     * @returns {{folds: Map<number, {from: number, to: number}>, foldedBy: Map<number, number>,
     *            notes: Map<number, Array<{end: number, text: string}>>}} The regions by their
     *          directive line, the directive line owning each folded line, and the notes by line,
     *          each with the index in the line after which it is shown.
     * @private
     */
    recordsFor(item, lines) {
        const folds = new Map();
        const foldedBy = new Map();
        const notes = new Map();
        if (!item) return { folds, foldedBy, notes };

        const expansions = this.shownExpansions.map(number => this.expansionOf(number));
        const leftOut = item.leftOut.concat(...expansions.map(expansion => expansion.leftOut || []));
        const shownNotes = item.notes.concat(...expansions.map(expansion => expansion.notes || []));

        leftOut.forEach(region => {
            folds.set(region.directiveLine, { from: region.from, to: region.to });
        });
        folds.forEach((fold, directiveLine) => {
            for (let line = fold.from; line <= fold.to; line++) {
                if (!foldedBy.has(line)) foldedBy.set(line, directiveLine);
            }
        });

        // The values of the constants used, then the notes of the preprocessor. The token map
        // describes the text of a line in every instance alike, so a line this listing shows as
        // left out gets no value: the instance on display did not compile it.
        this.annotator.constantNotes(this.artifact, item.placement, item.resolvedPath, lines)
            .forEach((lineNotes, line) => {
                if (!foldedBy.has(line)) notes.set(line, lineNotes);
            });
        shownNotes.forEach(note => {
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
     * Builds the key under which the unfolding of a region is kept: the item on display and the
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
     * annotations as annotation spans after the tokens they belong to. A note is a compile-time
     * annotation and carries the class `compile-time` besides `annotation`.
     *
     * @param {number} lineNumber - The 1-based line number.
     * @param {string} text - The original text of the line.
     * @param {Array<{relativeColumn: number, tokenText: string, annotationText: string}>} [annotations]
     *        The runtime annotations, by the 0-based column of their token.
     * @returns {string} The HTML.
     * @private
     */
    renderLineHtml(lineNumber, text, annotations = []) {
        const inserts = (this.lineNotes.get(lineNumber) || []).map(
            note => ({ start: note.end, end: note.end, text: note.text, className: 'annotation compile-time' }));
        annotations.forEach(ann => {
            const start = ann.relativeColumn;
            inserts.push({ start, end: start + ann.tokenText.length, text: ann.annotationText, className: 'annotation' });
        });
        inserts.sort((a, b) => a.end - b.end);

        let html = '';
        let lastIndex = 0;
        inserts.forEach(insert => {
            // Validate bounds to prevent crashes
            if (insert.start >= lastIndex && insert.end <= text.length) {
                html += this.escapeHtml(text.substring(lastIndex, insert.end));
                html += `<span class="${insert.className}">${this.escapeHtml(insert.text)}</span>`;
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
        const item = this.items[this.selectedIndex];
        const lines = item && Array.isArray(item.lines) ? item.lines : [];

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

        // The instructions of the active line in the expansion the active location stands in
        const machineInstructions = this.instructionEntry(activeLocation.placement, activeLocation.fileName,
            activeLocation.expansion || 0)?.lines?.[activeLocation.lineNumber]?.instructions;
        if (!machineInstructions) return null;

        const expectedInstruction = machineInstructions.find(
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
     * Returns an expansion of the artifact by its number.
     *
     * @param {number} number - The number the expansion's positions carry.
     * @returns {object|undefined} The expansion, `{calledAt, definedAt, name, bindings, leftOut,
     *          notes}`, or undefined if the number is no expansion.
     * @private
     */
    expansionOf(number) {
        return this.artifact?.expansions?.[number];
    }

    /**
     * Finds the item a position stands in: the item with its placement, its file and an instance
     * equal to its expansion; or, for a position in an expansion, the item its definition stands
     * in, found the same way.
     *
     * @param {object|null} location - A position, or an error object or null.
     * @returns {number|null} The index of the item, or null if the position names none.
     * @private
     */
    findSourceIndex(location) {
        let position = location;
        const seen = new Set();
        while (position && position.fileName && !seen.has(position.expansion || 0)) {
            const placement = position.placement || '';
            const expansion = position.expansion || 0;
            const index = this.items.findIndex(item => item.placement === placement
                && item.resolvedPath === position.fileName && item.instances.includes(expansion));
            if (index >= 0) return index;
            seen.add(expansion);
            position = this.expansionOf(expansion)?.definedAt;
        }
        return null;
    }

    /**
     * Returns the machine instructions shown under a line of an item, sorted by address: those of
     * the item's own instances and those of the expansions whose bodies the listing shows, see
     * expansionsShown(). The instructions of an expansion stand only on the lines of its body.
     *
     * @param {object|null} item - The item on display.
     * @param {number} lineNumber - The 1-based line number.
     * @returns {Array<object>} The instructions of the line; empty if it has none.
     * @private
     */
    instructionsFor(item, lineNumber) {
        if (!item) return [];
        const of = expansion => this.instructionEntry(item.placement, item.resolvedPath, expansion)
            ?.lines?.[lineNumber]?.instructions || [];
        return item.instances.concat(this.shownExpansions).flatMap(of)
            .sort((a, b) => a.linearAddress - b.linearAddress);
    }

    /**
     * Looks up the machine instructions of one file in one placement and one expansion in the
     * index of the artifact's `sourceLineToInstructions`, built once per artifact, which holds them
     * by placement, file and the expansion of their positions: the instance of an entry or the
     * number of an expansion.
     *
     * @param {string} placement - The alias chain of the placement; empty or absent for the
     *        empty one.
     * @param {string} fileName - The resolved path of the file.
     * @param {number} expansion - The instance of an entry or the number of an expansion.
     * @returns {{lines: Object<number, {instructions: Array<object>}>}|undefined} The instructions
     *          by line, or undefined if there are none.
     * @private
     */
    instructionEntry(placement, fileName, expansion) {
        return this.instructionIndex.get(this.instructionKey(placement, fileName, expansion));
    }

    /**
     * Builds the key of the instruction index.
     *
     * @param {string} placement - The alias chain of the placement; empty or absent for the
     *        empty one.
     * @param {string} fileName - The resolved path of the file.
     * @param {number} expansion - The instance of an entry or the number of an expansion.
     * @returns {string} The key.
     * @private
     */
    instructionKey(placement, fileName, expansion) {
        return `${placement || ''}|${fileName}|${expansion || 0}`;
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
