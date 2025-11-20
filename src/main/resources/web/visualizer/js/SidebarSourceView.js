/**
 * Manages the source code view in the sidebar.
 * Displays the assembly code of the selected organism, allows switching between
 * included files, and highlights the currently executing line.
 */
class SidebarSourceView {
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
     * Sets the static program context.
     * Checks internally if the artifact actually changed to avoid unnecessary re-renders.
     * @param {object} artifact - The ProgramArtifact containing sources and mappings.
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
     * Updates the dynamic execution state (IP position).
     * This method is optimized for high-frequency calls (every tick).
     * @param {object} organismState - Current state (includes IP).
     * @param {object} staticInfo - Static info (includes initial position).
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

        // 4. Apply annotations to the active line
        if (activeLineNumber && activeLocation.fileName === this.selectedFile) {
            this.applyAnnotations(activeLineNumber, organismState, activeLocation.fileName);
        }
    }

    /**
     * Renders the static structure (Dropdown + Source Code Text).
     * Destroys and recreates the code view DOM.
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
            return `<div class="source-line" data-line="${lineNumber}">
                        <span class="line-number">${String(lineNumber).padStart(3, ' ')}</span>
                        <pre class="assembly-line">${line.replace(/</g, '&lt;')}</pre>
                    </div>`;
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
    }

    /**
     * Efficiently updates CSS classes for highlighting without touching innerHTML.
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

    applyAnnotations(lineNumber, organismState, fileName) {
        const lineElement = this.dom.codeContainer.querySelector(`.source-line[data-line="${lineNumber}"] .assembly-line`);
        if (!lineElement) return;

        const originalLine = this.getOriginalLine(lineNumber);
        if (originalLine === null) return;

        const annotations = this.annotator.annotate(organismState, this.artifact, fileName, originalLine, lineNumber);
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
     * Calculates the active file and line based on IP.
     * Returns { fileName, lineNumber } or { error } or null.
     */
    calculateActiveLocation(organismState, staticInfo) {
        if (!organismState || !staticInfo || !this.artifact) return null;
        
        const ip = organismState.ip;
        const startPos = staticInfo.initialPosition;
        
        if (!Array.isArray(ip) || ip.length < 2 || !Array.isArray(startPos) || startPos.length < 2) {
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

        let sourceInfo = this.artifact.sourceMap[linearAddress];
        if (!sourceInfo) {
            return { error: `Address ${linearAddress} has no source info` };
        }
        
        // Handle wrapped sourceInfo (observed in JSON serialization)
        if (sourceInfo.sourceInfo) {
            sourceInfo = sourceInfo.sourceInfo;
        }
        
        return {
            fileName: sourceInfo.fileName,
            lineNumber: sourceInfo.lineNumber
        };
    }
    
    /**
     * Finds the longest common prefix (up to last slash) of all file paths.
     * @param {string[]} paths - Array of file paths
     * @return {string} The longest common prefix ending with '/' or empty string
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

    escapeHtml(text) {
        if (!text) return '';
        return text.replace(/&/g, "&amp;")
                   .replace(/</g, "&lt;")
                   .replace(/>/g, "&gt;")
                   .replace(/"/g, "&quot;")
                   .replace(/'/g, "&#039;");
    }
}
