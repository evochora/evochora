class SidebarSourceView {
    constructor(root) { 
        this.root = root;
        this.currentProgramId = null;
        this.allSources = null;
        this.selectedFileName = null;
        this.currentFileName = null;
    }
    
    update(src) {
        const el = this.root.querySelector('[data-section="source"]');
        if (!el) return;
        
        
        // Handle missing sourceView gracefully
        if (!src) {
            this.renderNoSourceMessage(el);
            return;
        }
        
        // Prüfe, ob sich das Programm geändert hat
        const programId = this.getCurrentProgramId();
        if (programId !== this.currentProgramId) {
            this.currentProgramId = programId;
            this.allSources = null;
            this.selectedFileName = null;
            this.currentFileName = null; // Reset so it can be set to the new main program
        }
        
        // Lade alle verfügbaren Sources, falls noch nicht geladen
        if (!this.allSources && src.allSources) {
            try {
                this.allSources = typeof src.allSources === 'string' ? JSON.parse(src.allSources) : src.allSources;
            } catch (e) {
                console.warn('Failed to parse allSources:', e);
                this.allSources = {};
            }
        }
        
        // CRITICAL: Always use the current fileName from the source view to automatically select the file
        if (src.fileName && this.allSources) {
            // Check if the fileName exists in our sources, if not, try to find a similar one
            if (this.allSources[src.fileName]) {
                this.selectedFileName = src.fileName;
            } else {
                // Try to find a file with similar name (e.g., if we have "main.s" but source shows "org/evochora/main.s")
                const availableFiles = Object.keys(this.allSources);
                for (const fileName of availableFiles) {
                    if (fileName.endsWith(src.fileName) || fileName.includes(src.fileName.split('/').pop())) {
                        this.selectedFileName = fileName;
                        break;
                    }
                }
                // If still no match, use the first available file
                if (!this.selectedFileName && availableFiles.length > 0) {
                    this.selectedFileName = availableFiles[0];
                }
            }
            
            // CRITICAL: Only set currentFileName (main program) if it hasn't been set yet
            // This ensures that currentFileName represents the entry point, not the current execution file
            if (!this.currentFileName) {
                this.currentFileName = this.selectedFileName;
            }
            
            // Debug logging
        } else if (!this.selectedFileName && this.allSources && Object.keys(this.allSources).length > 0) {
            // Fallback: Wähle main.s als Standard, falls verfügbar, sonst die erste Datei
            const availableFiles = Object.keys(this.allSources);
            let defaultFile = availableFiles[0];
            
            // Suche nach main.s oder einer Datei mit "main" im Namen
            for (const fileName of availableFiles) {
                if (fileName.includes('main.s') || fileName.includes('main')) {
                    defaultFile = fileName;
                    break;
                }
            }
            
            this.selectedFileName = defaultFile;
            // Also set currentFileName for relative path calculations (only if not set yet)
            if (!this.currentFileName) {
                this.currentFileName = this.selectedFileName;
            }
        }
        

        
        // Erstelle die UI
        this.renderSourceView(el, src);
    }
    
    /**
     * Renders a helpful message when no source view information is available.
     * This happens when skipProgramArtefact=true or when no ProgramArtifact exists.
     */
    renderNoSourceMessage(el) {
        el.innerHTML = `
            <div class="no-source-message">
                <div class="no-source-icon">📄</div>
                <h3>No Source Code Available</h3>
                <p>This organism has no source code information because:</p>
                <ul>
                    <li>Program artifacts are disabled (<code>skipProgramArtefact: true</code>)</li>
                    <li>Or no compiled program exists for this organism</li>
                </ul>
                <p>You can still see the disassembled machine code in the "Next Instruction" section above.</p>
            </div>
        `;
    }
    
    getCurrentProgramId() {
        // Hole die programId aus dem aktuell ausgewählten Organismus
        if (window.EvoDebugger.controller && 
            window.EvoDebugger.controller.state && 
            window.EvoDebugger.controller.state.lastTickData) {
            
            const selectedOrganismId = window.EvoDebugger.controller.state.selectedOrganismId;
            
            if (selectedOrganismId) {
                const organismDetails = window.EvoDebugger.controller.state.lastTickData.organismDetails[selectedOrganismId];
                if (organismDetails && organismDetails.basicInfo) {
                    return organismDetails.basicInfo.programId;
                }
            }
        }
        return null;
    }
    
    renderSourceView(el, src) {
        if (!this.allSources || Object.keys(this.allSources).length === 0) {
            // Fallback: Zeige nur die aktuelle Source-View
            this.renderFallbackSourceView(el, src);
            return;
        }
        
        // Check if this is the first render or if we need to rebuild the structure
        const existingContainer = el.querySelector('.source-view-container');
        if (!existingContainer) {
            // First render - create the full structure
            const dropdownHtml = this.createFileDropdown();
            const selectedSource = this.allSources[this.selectedFileName] || [];
            const assemblyCodeHtml = this.createAssemblyCodeView(selectedSource, src);
            
            el.innerHTML = `<div class="source-view-container"><div class="file-selector"><select id="assembly-file-select" class="assembly-file-dropdown">${dropdownHtml}</select></div><div class="assembly-code-container">${assemblyCodeHtml}</div></div>`;
            
            // Event-Listener für den Dropdown
            const dropdown = el.querySelector('#assembly-file-select');
            if (dropdown) {
                dropdown.value = this.selectedFileName || '';
                dropdown.addEventListener('change', (e) => {
                    this.selectedFileName = e.target.value;
                    this.renderSourceView(el, src);
                });
            }
        } else {
            // Update only the content, preserve scroll position
            const selectedSource = this.allSources[this.selectedFileName] || [];
            const assemblyCodeHtml = this.createAssemblyCodeView(selectedSource, src);
            
            const assemblyContainer = el.querySelector('.assembly-code-container');
            if (assemblyContainer) {
                assemblyContainer.innerHTML = assemblyCodeHtml;
            }
            
            // Update dropdown if needed
            const dropdown = el.querySelector('#assembly-file-select');
            if (dropdown && dropdown.value !== this.selectedFileName) {
                dropdown.value = this.selectedFileName || '';
            }
        }
        
        // Check if we need to scroll to keep the current line visible
        this.scrollToCurrentLine(el);
    }
    
    createFileDropdown() {
        if (!this.allSources) return '';
        
        return Object.keys(this.allSources)
            .sort()
            .map(fileName => {
                const isSelected = fileName === this.selectedFileName;
                const displayName = this.getRelativePath(fileName);
                return `<option value="${fileName}" ${isSelected ? 'selected' : ''}>${displayName}</option>`;
            })
            .join('');
    }
    
    getRelativePath(fileName) {
        if (!fileName) return '';
        
        // Normalisiere Pfad-Separatoren (Windows \ zu /)
        const normalizedFileName = fileName.replace(/\\/g, '/');
        
        // Extrahiere den relativen Pfad vom Projekt-Root
        // Suche nach "assembly/" als Startpunkt für den relativen Pfad
        const assemblyIndex = normalizedFileName.indexOf('assembly/');
        if (assemblyIndex !== -1) {
            // Extrahiere alles ab "assembly/" bis zum Ende
            return normalizedFileName.substring(assemblyIndex);
        }
        
        // Fallback: Wenn "assembly/" nicht gefunden wird, zeige den vollen Pfad
        return normalizedFileName;
    }
    
    createAssemblyCodeView(sourceLines, src) {
        if (!sourceLines || sourceLines.length === 0) {
            return '<div class="no-source">Keine Assembly-Datei verfügbar</div>';
        }
        
        // CRITICAL: Don't filter out empty lines - preserve original line numbers for correct highlighting
        // The src.currentLine refers to the original source file line numbers
        if (!sourceLines || sourceLines.length === 0) {
            return '<div class="no-source">Keine Assembly-Zeilen verfügbar</div>';
        }
        
        // Erstelle Zeilen mit Zeilennummern (behalte alle Zeilen inkl. leere)
        // WICHTIG: Keine Leerzeichen oder Zeilenumbrüche zwischen den Zeilen
        const linesHtml = sourceLines.map((line, index) => {
            const lineNumber = index + 1; // Original line number from source file
            // CRITICAL: Use src.currentLine to determine if this is the current execution line
            const isCurrentLine = src.currentLine === lineNumber;
            const lineClass = isCurrentLine ? 'source-line current-line' : 'source-line';
            
            // Formatiere den Assembly-Code
            const formattedLine = this.formatAssemblyLine(line, lineNumber, src);
            
            return `<div class="${lineClass}" data-line="${lineNumber}"><span class="line-number">${lineNumber.toString().padStart(3, ' ')}</span><pre class="assembly-line">${formattedLine}</pre></div>`;
        }).join(''); // Kein Zeilenumbruch zwischen den Zeilen
        
        return `<div class="assembly-code-view" id="assembly-code-view">${linesHtml}</div>`;
    }
    
    formatAssemblyLine(line, lineNumber, src) {
        if (!line) return '';
        
        let formattedLine = String(line).replace(/</g, '&lt;').replace(/>/g, '&gt;');
        
        // Check if this line has annotations from the backend
        if (src.inlineSpans && Array.isArray(src.inlineSpans)) {
            const lineSpans = src.inlineSpans.filter(span => span.lineNumber === lineNumber);
            
            if (lineSpans.length > 0) {
                // Apply annotations in reverse order to maintain correct positions
                const sortedSpans = [...lineSpans].sort((a, b) => 
                    (b.tokenToAnnotate.length - a.tokenToAnnotate.length) || 
                    (b.occurrence - a.occurrence)
                );
                
                for (const span of sortedSpans) {
                    const token = span.tokenToAnnotate;
                    const annotation = span.annotationText;
                    const kind = span.kind || 'info';
                    const occurrence = span.occurrence || 1;
                    
                    if (token && annotation) {
                        // Create annotation HTML with token in normal color and annotation in green
                        const annotationHtml = `${token}<span class="injected-value ${kind}">[${annotation}]</span>`;
                        
                        // Replace the specific occurrence of the token
                        let currentOccurrence = 0;
                        formattedLine = formattedLine.replace(new RegExp(token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'), (match) => {
                            currentOccurrence++;
                            if (currentOccurrence === occurrence) {
                                return annotationHtml;
                            }
                            return match;
                        });
                    }
                }
            }
        }
        
        return formattedLine;
    }
    
    renderFallbackSourceView(el, src) {
        // Safety check: if src is missing key properties, show no source message
        if (!src || !src.fileName) {
            this.renderNoSourceMessage(el);
            return;
        }
        
        // Check if this is the first render or if we need to rebuild the structure
        const existingContainer = el.querySelector('#source-code-view');
        if (!existingContainer) {
            // First render - create the full structure
            const header = `//${this.getRelativePath(src.fileName) || 'Unknown'}`;
            
            // CRITICAL: Don't filter out empty lines - preserve original line numbers for correct highlighting
            const linesHtml = (src.lines || []).map((l, index) => {
                const lineNumber = index + 1; // Original line number from source file
                // CRITICAL: Use src.currentLine to highlight the current execution line
                const isCurrentLine = src.currentLine === lineNumber;
                const lineClass = isCurrentLine ? 'source-line current-line' : 'source-line';
                return `<div class="${lineClass}" data-line="${lineNumber}"><span class="line-number">${lineNumber}</span><pre data-line="${lineNumber}">${String(l.content || '').replace(/</g, '&lt;')}</pre></div>`;
            }).join('');
            
            el.innerHTML = `<div class="code-view source-code-view" id="source-code-view" style="font-size:0.9em;"><div class="source-line"><span class="line-number"></span><pre>${header}</pre></div>${linesHtml}</div>`;
            
            // Verarbeite Inline-Values (ursprüngliche Logik)
            this.processInlineValues(el, src);
        } else {
            // Update only the content, preserve scroll position
            const linesHtml = (src.lines || []).map((l, index) => {
                const lineNumber = index + 1; // Original line number from source file
                // CRITICAL: Use src.currentLine to highlight the current execution line
                const isCurrentLine = src.currentLine === lineNumber;
                const lineClass = isCurrentLine ? 'source-line current-line' : 'source-line';
                return `<div class="${lineClass}" data-line="${lineNumber}"><span class="line-number">${lineNumber}</span><pre data-line="${lineNumber}">${String(l.content || '').replace(/</g, '&lt;')}</pre></div>`;
            }).join('');
            
            // Update only the lines content, keep the header
            const headerLine = existingContainer.querySelector('.source-line:first-child');
            if (headerLine) {
                existingContainer.innerHTML = `<div class="source-line"><span class="line-number"></span><pre>//${this.getRelativePath(src.fileName) || 'Unknown'}</pre></div>${linesHtml}`;
            }
            
            // Verarbeite Inline-Values (ursprüngliche Logik)
            this.processInlineValues(el, src);
        }
        
        // Check if we need to scroll to keep the current line visible
        this.scrollToCurrentLine(el);
    }
    
    processInlineValues(el, src) {
        if (!Array.isArray(src.inlineSpans)) return;
        
        const grouped = new Map();
        for (const s of src.inlineSpans) {
            if (!grouped.has(s.lineNumber)) grouped.set(s.lineNumber, []);
            grouped.get(s.lineNumber).push(s);
        }
        
        for (const [ln, spans] of grouped.entries()) {
            const pre = el.querySelector(`pre[data-line="${ln}"]`);
            if (!pre) continue;
            
            const raw = pre.textContent || '';
            
            // Step 1: Earliest-only for jump/callJump of the same text
            const earliestByTextForJump = new Map();
            for (const sp of spans) {
                if (sp && (sp.kind === 'jump' || sp.kind === 'callJump') && typeof sp.tokenToAnnotate === 'string') {
                    const t = sp.tokenToAnnotate;
                    const cur = earliestByTextForJump.get(t);
                    if (!cur || (sp.occurrence || 0) < (cur.occurrence || 0)) {
                        earliestByTextForJump.set(t, sp);
                    }
                }
            }
            
            // Step 2: Build list, skipping later duplicates of jump/callJump for same text
            const seenByPosText = new Set();
            const uniq = [];
            for (const sp of spans) {
                if (sp && (sp.kind === 'jump' || sp.kind === 'callJump')) {
                    const keep = earliestByTextForJump.get(sp.tokenToAnnotate);
                    if (keep !== sp) continue;
                }
                const key = `${sp.occurrence}|${sp.tokenToAnnotate}`;
                if (seenByPosText.has(key)) continue;
                seenByPosText.add(key);
                uniq.push(sp);
            }
            
            uniq.sort((a, b) => (a.occurrence || 0) - (b.occurrence || 0));
            let out = '';
            let cur = 0;
            
            for (const s of uniq) {
                const idx = Math.max(0, Math.min(raw.length, (s.occurrence || 1) - 1));
                out += raw.slice(cur, idx).replace(/</g, '&lt;');
                
                const cls = s.kind ? ` injected-value ${s.kind}` : ' injected-value';
                
                                 // Show token in normal color and annotation in green
                 const token = String(s.tokenToAnnotate || '').replace(/</g, '&lt;');
                 const annotation = String(s.annotationText || '').replace(/</g, '&lt;');
                 
                 out += `${token}<span class="${cls}">[${annotation}]</span>`;
                cur = idx;
            }
            
            out += raw.slice(cur).replace(/</g, '&lt;');
            pre.innerHTML = out;
        }
    }
    
    scrollToCurrentLine(el) {
        const container = el.querySelector('#assembly-code-view');
        const highlighted = container ? container.querySelector('.source-line.current-line') : null;
        
        if (container && highlighted) {
            // Check if the highlighted line is already visible in the viewport
            const containerRect = container.getBoundingClientRect();
            const highlightedRect = highlighted.getBoundingClientRect();
            
            // Calculate if the line is visible within the container's viewport
            const lineOffsetTop = highlighted.offsetTop;
            const containerScrollTop = container.scrollTop;
            const containerVisibleHeight = container.clientHeight;
            const containerTotalHeight = container.scrollHeight;
            
            // Check if the line is within the visible area of the container
            // We need to account for the container's scroll position
            
            // The line is visible if it's within the scrolled viewport
            const isVisible = lineOffsetTop >= containerScrollTop && 
                            lineOffsetTop <= (containerScrollTop + containerVisibleHeight);
            
            // Only scroll if the line is not visible
            if (!isVisible) {
                // Manual scroll calculation to center the line
                const lineHeight = highlighted.clientHeight;
                const containerHeight = container.clientHeight;
                const targetScrollTop = lineOffsetTop - (containerHeight / 2) + (lineHeight / 2);
                
                // Ensure we don't scroll beyond the bounds
                const maxScrollTop = container.scrollHeight - containerHeight;
                const finalScrollTop = Math.max(0, Math.min(targetScrollTop, maxScrollTop));
                
                container.scrollTop = finalScrollTop;
            }
        }
    }
}

// Export für globale Verfügbarkeit
window.SidebarSourceView = SidebarSourceView;
