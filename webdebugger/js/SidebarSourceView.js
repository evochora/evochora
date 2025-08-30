class SidebarSourceView {
    constructor(root) { 
        this.root = root;
        this.currentProgramId = null;
        this.allSources = null;
        this.selectedFileName = null;
    }
    
    update(src) {
        const el = this.root.querySelector('[data-section="source"]');
        if (!el) return;
        if (!src) { el.innerHTML = ''; return; }
        
        console.log('SidebarSourceView.update() called with:', { 
            src, 
            currentProgramId: this.currentProgramId,
            selectedFileName: this.selectedFileName,
            allSources: this.allSources 
        });
        
        // Prüfe, ob sich das Programm geändert hat
        const programId = this.getCurrentProgramId();
        if (programId !== this.currentProgramId) {
            console.log('Program changed from', this.currentProgramId, 'to', programId);
            this.currentProgramId = programId;
            this.allSources = null;
            this.selectedFileName = null;
        }
        
        // Lade alle verfügbaren Sources, falls noch nicht geladen
        if (!this.allSources && src.allSources) {
            try {
                this.allSources = typeof src.allSources === 'string' ? JSON.parse(src.allSources) : src.allSources;
                console.log('Loaded allSources:', this.allSources);
            } catch (e) {
                console.warn('Failed to parse allSources:', e);
                this.allSources = {};
            }
        }
        
        // WICHTIG: Bestimme die aktuelle Datei AUTOMATISCH bei jedem Tick
        if (src.fileName && this.allSources) {
            console.log('Setting selectedFileName to:', src.fileName);
            this.selectedFileName = src.fileName;
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
            
            console.log('No fileName in src, setting selectedFileName to default file:', defaultFile);
            this.selectedFileName = defaultFile;
        }
        
        // Erstelle die UI
        this.renderSourceView(el, src);
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
        
        // Erstelle den Dropdown für Assembly-Dateien
        const dropdownHtml = this.createFileDropdown();
        
        // Hole den Inhalt der ausgewählten Datei
        const selectedSource = this.allSources[this.selectedFileName] || [];
        console.log('Selected source for', this.selectedFileName, ':', selectedSource);
        
        // Erstelle den Assembly-Code mit Zeilennummern
        const assemblyCodeHtml = this.createAssemblyCodeView(selectedSource, src);
        
        // Kombiniere alles - OHNE Label "Assembly-Datei:"
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
        
        // Behalte .s Endung für bessere Lesbarkeit
        let relativePath = fileName;
        
        // Entferne den Hauptprogramm-Pfad, falls vorhanden
        // Beispiel: //org/evochora/organism/prototypes/main.s -> main.s
        // Beispiel: //behaviors.s -> behaviors.s
        if (relativePath.startsWith('//')) {
            relativePath = relativePath.substring(2); // Entferne //
            
            // Suche nach dem Hauptprogramm (normalerweise main.s oder ähnlich)
            const mainPrograms = ['main', 'program', 'organism'];
            let isMainProgram = false;
            
            for (const main of mainPrograms) {
                if (relativePath.includes(main)) {
                    isMainProgram = true;
                    break;
                }
            }
            
            if (isMainProgram) {
                // Für Hauptprogramme: Zeige nur den Dateinamen
                const parts = relativePath.split('/');
                relativePath = parts[parts.length - 1];
            } else {
                // Für andere Dateien: Zeige relativen Pfad
                // Beispiel: org/evochora/organism/prototypes/behaviors.s -> lib/behaviors.s
                const parts = relativePath.split('/');
                if (parts.length > 1) {
                    // Entferne den ersten Teil (org) und zeige den Rest
                    relativePath = parts.slice(1).join('/');
                }
            }
        }
        
        return relativePath;
    }
    
    createAssemblyCodeView(sourceLines, src) {
        if (!sourceLines || sourceLines.length === 0) {
            return '<div class="no-source">Keine Assembly-Datei verfügbar</div>';
        }
        
        console.log('Creating assembly code view with', sourceLines.length, 'lines');
        
        // Debug: Zeige die ersten 10 Zeilen, um zu sehen, was der Server sendet
        console.log('First 10 lines from server:', sourceLines.slice(0, 10));
        
        // WICHTIG: Entferne nur leere Zeilen, behalte alle Assembly-Zeilen
        const nonEmptyLines = sourceLines.filter(line => {
            if (!line) return false;
            const trimmed = String(line).trim();
            const hasContent = trimmed.length > 0;
            if (!hasContent) {
                console.log('Filtering out empty line:', line);
            }
            return hasContent;
        });
        
        console.log('After filtering,', nonEmptyLines.length, 'non-empty lines remain');
        
        if (nonEmptyLines.length === 0) {
            return '<div class="no-source">Keine Assembly-Zeilen verfügbar</div>';
        }
        
        // Erstelle Zeilen mit Zeilennummern (nur für nicht-leere Zeilen)
        // WICHTIG: Keine Leerzeichen oder Zeilenumbrüche zwischen den Zeilen
        const linesHtml = nonEmptyLines.map((line, index) => {
            const lineNumber = index + 1;
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
        
        // Füge Inline-Values hinzu, falls verfügbar
        if (src.inlineSpans && Array.isArray(src.inlineSpans)) {
            const lineSpans = src.inlineSpans.filter(span => span.lineNumber === lineNumber);
            
            if (lineSpans.length > 0) {
                // Sortiere Spans nach Position
                lineSpans.sort((a, b) => (a.occurrence || 0) - (b.occurrence || 0));
                
                // Füge Annotations hinzu
                for (const span of lineSpans) {
                    const token = span.tokenToAnnotate;
                    const annotation = span.annotationText;
                    const kind = span.kind || 'info';
                    
                    if (token && annotation) {
                        // Ersetze den Token mit einer annotierten Version
                        const regex = new RegExp(`\\b${token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'g');
                        formattedLine = formattedLine.replace(regex, 
                            `<span class="inline-annotation ${kind}" title="${annotation}">${token}</span>`
                        );
                    }
                }
            }
        }
        
        return formattedLine;
    }
    
    renderFallbackSourceView(el, src) {
        // Fallback für den Fall, dass keine allSources verfügbar sind
        const header = `//${this.getRelativePath(src.fileName) || 'Unknown'}`;
        
        // WICHTIG: Filtere leere Zeilen auch im Fallback
        const nonEmptyLines = (src.lines || []).filter(l => {
            if (!l || !l.content) return false;
            const trimmed = String(l.content).trim();
            return trimmed.length > 0;
        });
        
        const linesHtml = nonEmptyLines.map((l, index) => {
            const lineNumber = index + 1;
            return `<div class="source-line ${l.isCurrent ? 'highlight' : ''}"><span class="line-number">${lineNumber}</span><pre data-line="${lineNumber}">${String(l.content || '').replace(/</g, '&lt;')}</pre></div>`;
        }).join('');
        
        el.innerHTML = `<div class="code-view source-code-view" id="source-code-view" style="font-size:0.9em;"><div class="source-line"><span class="line-number"></span><pre>${header}</pre></div>${linesHtml}</div>`;
        
        // Verarbeite Inline-Values (ursprüngliche Logik)
        this.processInlineValues(el, src);
        
        // Scroll zur aktuellen Zeile
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
                const needsBracket = (s.kind === 'reg' || s.kind === 'define' || s.kind === 'jump' || s.kind === 'callJump');
                const alreadyBracketed = typeof s.annotationText === 'string' && s.annotationText.startsWith('[') && s.annotationText.endsWith(']');
                const display = needsBracket && !alreadyBracketed ? `[${s.annotationText}]` : s.annotationText;
                
                out += `<span class="${cls}">${String(display || '').replace(/</g, '&lt;')}</span>`;
                cur = idx;
            }
            
            out += raw.slice(cur).replace(/</g, '&lt;');
            pre.innerHTML = out;
        }
    }
    
    scrollToCurrentLine(el) {
        const container = el.querySelector('#source-code-view, #assembly-code-view');
        const highlighted = container ? container.querySelector('.source-line.highlight, .source-line.current-line') : null;
        if (container && highlighted) {
            try { 
                highlighted.scrollIntoView({ block: 'center' }); 
            } catch (e) {
                // Fallback: manueller Scroll
                const top = highlighted.offsetTop - (container.clientHeight / 2) + (highlighted.clientHeight / 2);
                container.scrollTop = Math.max(0, Math.min(top, container.scrollHeight - container.clientHeight));
            }
        }
    }
}

// Export für globale Verfügbarkeit
window.SidebarSourceView = SidebarSourceView;
