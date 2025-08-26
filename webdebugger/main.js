document.addEventListener('DOMContentLoaded', () => {
    window.EvoDebugger = window.EvoDebugger || {};

    class StatusManager {
        constructor() {
            this.statusBar = document.getElementById('status-bar');
            this.statusMessage = document.getElementById('status-message');
            this.statusClose = document.getElementById('status-close');
            
            this.statusClose.addEventListener('click', () => this.hideStatus());
        }
        
        showError(message, duration = 0) {
            this.showStatus(message, 'error', duration);
        }
        
        showInfo(message, duration = 5000) {
            this.showStatus(message, 'info', duration);
        }
        
        showStatus(message, type = 'info', duration = 0) {
            this.statusMessage.textContent = message;
            this.statusBar.className = `status-bar ${type}`;
            this.statusBar.style.display = 'flex';
            
            if (duration > 0) {
                setTimeout(() => this.hideStatus(), duration);
            }
        }
        
        hideStatus() {
            this.statusBar.style.display = 'none';
        }
    }

    class ApiService {
        constructor(statusManager) {
            this.statusManager = statusManager;
        }
        
        async fetchTickData(tick) {
            try {
                const res = await fetch(`/api/tick/${tick}`);
                if (!res.ok) {
                    if (res.status === 404) {
                        const errorData = await res.json().catch(() => ({}));
                        if (errorData.error) {
                            this.statusManager.showError(errorData.error);
                        } else {
                            this.statusManager.showError(`Tick ${tick} nicht gefunden`);
                        }
                    } else {
                        this.statusManager.showError(`HTTP Fehler: ${res.status}`);
                    }
                    throw new Error(`HTTP ${res.status}`);
                }
                return await res.json();
            } catch (error) {
                throw error;
            }
        }
    }

    class SidebarBasicInfoView {
        constructor(root) { 
            this.root = root;
            this.previousState = null;
        }
        
        update(info, navigationDirection) {
            const el = this.root.querySelector('[data-section="basic"]');
            if (!info || !el) return;
            
            // Berechne Änderungen nur bei "forward" Navigation
            const changeFlags = this.calculateChanges(info, navigationDirection);
            
            // Unveränderliche Infos über der Box
            const unchangeableInfo = [
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">ID:</span><span class="unchangeable-info-value">${info.id}</span></div>`,
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Parent:</span><span class="unchangeable-info-value">${info.parentId && info.parentId !== 'null' && info.parentId !== 'undefined' ? `<span class="clickable-parent" data-parent-id="${info.parentId}">${info.parentId}</span>` : 'N/A'}</span></div>`,
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Birth:</span><span class="unchangeable-info-value">${info.birthTick}</span></div>`,
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Program:</span><span class="unchangeable-info-value">${info.programId || 'N/A'}</span></div>`
            ].join('');
            
            // Veränderliche Werte in der Box
            const changeableValues = [
                `IP=[${info.ip.join('|')}]`,
                `DV=[${info.dv.join('|')}]`,
                `ER=${info.energy}`
            ].map((value, index) => {
                const key = ['ip', 'dv', 'energy'][index];
                const isChanged = changeFlags && changeFlags[key];
                return isChanged ? `<span class="changed">${value}</span>` : value;
            }).join(' ');
            
            el.innerHTML = `
                <div class="unchangeable-info">${unchangeableInfo}</div>
                <div class="code-view changeable-box">${changeableValues}</div>
            `;
            
            // Event Listener für klickbare Parent-ID
            const parentSpan = el.querySelector('.clickable-parent');
            if (parentSpan) {
                parentSpan.addEventListener('click', () => {
                    const parentId = parentSpan.dataset.parentId;
                    // Navigiere zum Birth-Tick des Parents
                    this.navigateToParent(parentId);
                });
            }
            
            // Speichere aktuellen Zustand für nächsten Vergleich
            this.previousState = { ...info };
        }
        
        calculateChanges(currentInfo, navigationDirection) {
            // Nur bei "forward" Navigation Änderungen hervorheben
            if (navigationDirection !== 'forward' || !this.previousState) {
                return null;
            }
            
            const changeFlags = {};
            changeFlags.energy = currentInfo.energy !== this.previousState.energy;
            changeFlags.ip = JSON.stringify(currentInfo.ip) !== JSON.stringify(this.previousState.ip);
            changeFlags.dv = JSON.stringify(currentInfo.dv) !== JSON.stringify(this.previousState.dv);
            
            return changeFlags;
        }
        
        navigateToParent(parentId) {
            // Finde den Birth-Tick des Parents
            if (this.appController && this.appController.state.lastTickData) {
                const organisms = this.appController.state.lastTickData.worldState?.organisms || [];
                const parent = organisms.find(o => String(o.id) === parentId);
                if (parent && parent.birthTick !== undefined) {
                    this.appController.navigateToTick(parent.birthTick);
                }
            }
        }
    }

    class SidebarNextInstructionView {
        constructor(root) { this.root = root; }
        update(next) {
            const el = this.root.querySelector('[data-section="nextInstruction"]');
            if (!el) return;
            el.innerHTML = next && next.disassembly ? `<div class="code-view">${next.disassembly}</div>` : '';
        }
    }

    class SidebarStateView {
        constructor(root) { this.root = root; }
        update(state) {
            const el = this.root.querySelector('[data-section="state"]');
            if (!state || !el) return;
            const fmt = r => `${r.value}`;
            const dr = (state.dataRegisters||[]).map(fmt).join(' ');
            const pr = (state.procRegisters||[]).map(fmt).join(' ');
            const ds = (state.dataStack||[]).join(' ');
            const cs = (state.callStack||[]).join(' -> ');
            const lr = (state.locationRegisters||[]).join(' ');
            const ls = (state.locationStack||[]).join(' ');
            const dps = (state.dps||[]).map(p=>`(${(p||[]).join('|')})`).join(' ');
            el.innerHTML = `<div class="code-view" style="font-size:0.9em;">DR: ${dr}\nPR: ${pr}\nLR: ${lr}\nLS: ${ls}\nDPs: ${dps}\nDS: ${ds}\nCS: ${cs}</div>`;
        }
    }

    class SidebarSourceView {
        constructor(root) { this.root = root; }
        update(src) {
            const el = this.root.querySelector('[data-section="source"]');
            if (!el) return;
            if (!src) { el.innerHTML = ''; return; }
            const header = `//${src.fileName}`;
            const linesHtml = (src.lines||[]).map(l=>`<div class="source-line ${l.isCurrent? 'highlight':''}"><span class="line-number">${l.number}</span><pre data-line="${l.number}">${String(l.content||'').replace(/</g,'&lt;')}</pre></div>`).join('');
            el.innerHTML = `<div class="code-view source-code-view" id="source-code-view" style="font-size:0.9em;"><div class="source-line"><span class="line-number"></span><pre>${header}</pre></div>${linesHtml}</div>`;
            if (Array.isArray(src.inlineValues)) {
                const grouped = new Map();
                for (const s of src.inlineValues) {
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
                        if (sp && (sp.kind === 'jump' || sp.kind === 'callJump') && typeof sp.text === 'string') {
                            const t = sp.text;
                            const cur = earliestByTextForJump.get(t);
                            if (!cur || (sp.startColumn || 0) < (cur.startColumn || 0)) {
                                earliestByTextForJump.set(t, sp);
                            }
                        }
                    }
                    // Step 2: Build list, skipping later duplicates of jump/callJump for same text
                    const seenByPosText = new Set();
                    const uniq = [];
                    for (const sp of spans) {
                        if (sp && (sp.kind === 'jump' || sp.kind === 'callJump')) {
                            const keep = earliestByTextForJump.get(sp.text);
                            if (keep !== sp) continue;
                        }
                        const key = `${sp.startColumn}|${sp.text}`;
                        if (seenByPosText.has(key)) continue;
                        seenByPosText.add(key);
                        uniq.push(sp);
                    }
                    uniq.sort((a,b)=>a.startColumn - b.startColumn);
                    let out = '';
                    let cur = 0;
                    for (const s of uniq) {
                        const idx = Math.max(0, Math.min(raw.length, (s.startColumn||1) - 1));
                        out += raw.slice(cur, idx).replace(/</g,'&lt;');
                        const cls = s.kind ? ` injected-value ${s.kind}` : ' injected-value';
                        const needsBracket = (s.kind === 'reg' || s.kind === 'define' || s.kind === 'jump' || s.kind === 'callJump');
                        const alreadyBracketed = typeof s.text === 'string' && s.text.startsWith('[') && s.text.endsWith(']');
                        const display = needsBracket && !alreadyBracketed ? `[${s.text}]` : s.text;
                        out += `<span class="${cls}">${String(display||'').replace(/</g,'&lt;')}</span>`;
                        cur = idx;
                    }
                    out += raw.slice(cur).replace(/</g,'&lt;');
                    pre.innerHTML = out;
                }
            }
            const container = el.querySelector('#source-code-view');
            const highlighted = container ? container.querySelector('.source-line.highlight') : null;
            if (container && highlighted) {
                try { highlighted.scrollIntoView({ block: 'center' }); } catch {}
                const top = highlighted.offsetTop - (container.clientHeight / 2) + (highlighted.clientHeight / 2);
                container.scrollTop = Math.max(0, Math.min(top, container.scrollHeight - container.clientHeight));
            }
        }
    }

    class SidebarView {
        constructor(root, appController) {
            this.root = root;
            this.appController = appController;
            this.basic = new SidebarBasicInfoView(root);
            this.basic.appController = appController; // Referenz für Parent-Navigation
            this.next = new SidebarNextInstructionView(root);
            this.state = new SidebarStateView(root);
            this.source = new SidebarSourceView(root);
        }
        update(details, navigationDirection) {
            if (!details) return;
            this.basic.update(details.basicInfo, navigationDirection);
            this.next.update(details.nextInstruction);
            this.state.update(details.internalState);
            this.source.update(details.sourceView);
        }
    }

    class SidebarManager {
        constructor() {
            this.sidebar = document.getElementById('sidebar');
            this.toggleBtn = document.getElementById('sidebar-toggle');
            this.isVisible = false;
            
            // Toggle button event listener
            this.toggleBtn.addEventListener('click', () => {
                this.toggleSidebar();
            });
        }
        
        showSidebar() {
            this.sidebar.classList.add('visible');
            this.isVisible = true;
        }
        
        hideSidebar() {
            this.sidebar.classList.remove('visible');
            this.isVisible = false;
        }
        
        toggleSidebar() {
            if (this.isVisible) {
                this.hideSidebar();
            } else {
                this.showSidebar();
            }
        }
        
        setToggleButtonVisible(visible) {
            this.toggleBtn.style.display = visible ? 'block' : 'none';
        }
        
        // Auto-hide when no organism is selected
        autoHide() {
            this.hideSidebar();
        }
        
        // Auto-show when organism is selected
        autoShow() {
            this.showSidebar();
        }
    }

    class ToolbarView {
        constructor(controller) {
            this.controller = controller;
            
            // Button event listeners
            document.getElementById('btn-prev').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick - 1));
            document.getElementById('btn-next').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick + 1));
            
            const input = document.getElementById('tick-input');
            document.getElementById('btn-goto').addEventListener('click', () => {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) this.controller.navigateToTick(v);
            });
            
            // Input field event listeners
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    const v = parseInt(input.value, 10);
                    if (!Number.isNaN(v)) this.controller.navigateToTick(v);
                }
            });
            input.addEventListener('change', () => {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) this.controller.navigateToTick(v);
            });
            
            // Input field click - select all text
            input.addEventListener('click', () => {
                input.select();
            });
            
            // Keyboard shortcuts
            document.addEventListener('keydown', (e) => {
                // Only handle shortcuts when not typing in input field
                if (document.activeElement === input) return;
                
                if (e.key === ' ') {
                    e.preventDefault(); // Prevent page scroll
                    this.controller.navigateToTick(this.controller.state.currentTick + 1);
                } else if (e.key === 'Backspace') {
                    e.preventDefault(); // Prevent browser back
                    this.controller.navigateToTick(this.controller.state.currentTick - 1);
                }
            });
        }
    }

    class AppController {
        constructor() {
            this.statusManager = new StatusManager();
            this.api = new ApiService(this.statusManager);
            this.canvas = document.getElementById('worldCanvas');
            this.renderer = new WorldRenderer(this.canvas, { WORLD_SHAPE: [100,30], CELL_SIZE: 22, TYPE_CODE:0, TYPE_DATA:1, TYPE_ENERGY:2, TYPE_ENERGY:2, TYPE_STRUCTURE:3, COLOR_BG:'#0a0a14', COLOR_EMPTY_BG:'#14141e', COLOR_CODE_BG:'#3c5078', COLOR_DATA_BG:'#32323c', COLOR_STRUCTURE_BG:'#ff7878', COLOR_ENERGY_BG:'#ffe664', COLOR_CODE_TEXT:'#ffffff', COLOR_DATA_TEXT:'#ffffff', COLOR_STRUCTURE_TEXT:'#323232', COLOR_ENERGY_TEXT:'#323232', COLOR_DEAD:'#505050' }, {});
            this.sidebar = new SidebarView(document.getElementById('sidebar'), this);
            this.sidebarManager = new SidebarManager();
            this.toolbar = new ToolbarView(this);
            this.state = { currentTick: 0, selectedOrganismId: null, lastTickData: null, totalTicks: null };
            this.canvas.addEventListener('click', (e) => this.onCanvasClick(e));
            
                    // Tracking für Navigationsrichtung (für Änderungs-Hervorhebung)
        this.lastNavigationDirection = null; // 'forward', 'backward', 'goto'
        
        // Referenz auf den AppController für Parent-Navigation
        this.appController = null;
        }
        async init() { await this.navigateToTick(0); }
        async navigateToTick(tick) {
            let target = typeof tick === 'number' ? tick : 0;
            if (target < 0) target = 0;
            if (typeof this.state.totalTicks === 'number' && this.state.totalTicks > 0) {
                const maxTick = Math.max(0, this.state.totalTicks - 1);
                if (target > maxTick) target = maxTick;
            }
            
            // Bestimme die Navigationsrichtung für Änderungs-Hervorhebung
            if (target === this.state.currentTick + 1) {
                this.lastNavigationDirection = 'forward';
            } else if (target === this.state.currentTick - 1) {
                this.lastNavigationDirection = 'backward';
            } else {
                this.lastNavigationDirection = 'goto';
            }
            
            try {
                const data = await this.api.fetchTickData(target);
                this.state.currentTick = target;
                this.state.lastTickData = data;
                if (typeof data.totalTicks === 'number') {
                    this.state.totalTicks = data.totalTicks;
                }
                if (data.worldMeta && Array.isArray(data.worldMeta.shape)) {
                    this.renderer.config.WORLD_SHAPE = data.worldMeta.shape;
                }
                // ISA-Mapping is intentionally not used; rely solely on cell.opcodeName provided by backend
                const typeToId = t => ({ CODE:0, DATA:1, ENERGY:2, STRUCTURE:3 })[t] ?? 1;
                const cells = (data.worldState?.cells||[]).map(c => ({ position: JSON.stringify(c.position), type: typeToId(c.type), value: c.value, opcodeName: c.opcodeName }));
                const organisms = (data.worldState?.organisms||[]).map(o => ({ organismId: o.id, programId: o.programId, energy: o.energy, positionJson: JSON.stringify(o.position), dps: o.dps, dv: o.dv }));
                this.renderer.draw({ cells, organisms, selectedOrganismId: this.state.selectedOrganismId });
                const ids = Object.keys(data.organismDetails||{});
                const sel = this.state.selectedOrganismId && ids.includes(this.state.selectedOrganismId) ? this.state.selectedOrganismId : null;
                if (sel) {
                    this.sidebar.update(data.organismDetails[sel], this.lastNavigationDirection);
                    this.sidebarManager.autoShow();
                    this.sidebarManager.setToggleButtonVisible(true);
                } else {
                    // No organism selected - auto-hide sidebar
                    this.sidebarManager.autoHide();
                    this.sidebarManager.setToggleButtonVisible(false);
                }
                this.updateTickUi();
            } catch (error) {
                // Error is already displayed by ApiService
                console.error('Failed to navigate to tick:', error);
            }
        }
        updateTickUi() {
            const input = document.getElementById('tick-input');
            if (input) input.value = String(this.state.currentTick || 0);
            const suffix = document.getElementById('tick-total-suffix');
            if (suffix) suffix.textContent = '/' + (this.state.totalTicks != null ? this.state.totalTicks : 'N/A');
            if (input && typeof this.state.totalTicks === 'number' && this.state.totalTicks > 0) {
                try { input.max = String(Math.max(0, this.state.totalTicks - 1)); } catch (_) {}
            }
        }
        onCanvasClick(event) {
            const rect = this.canvas.getBoundingClientRect();
            const x = event.clientX - rect.left;
            const y = event.clientY - rect.top;
            const gridX = Math.floor(x / this.renderer.config.CELL_SIZE);
            const gridY = Math.floor(y / this.renderer.config.CELL_SIZE);
            const organisms = (this.state.lastTickData?.worldState?.organisms)||[];
            for (const o of organisms) {
                const pos = o.position;
                if (Array.isArray(pos) && pos[0] === gridX && pos[1] === gridY) {
                    this.state.selectedOrganismId = String(o.id);
                    const det = this.state.lastTickData.organismDetails?.[this.state.selectedOrganismId];
                                    if (det) {
                    this.sidebar.update(det, this.lastNavigationDirection);
                }
                this.sidebarManager.autoShow();
                this.sidebarManager.setToggleButtonVisible(true);
                    const typeToId = t => ({ CODE:0, DATA:1, ENERGY:2, STRUCTURE:3 })[t] ?? 1;
                    this.renderer.draw({
                        cells: (this.state.lastTickData.worldState?.cells||[]).map(c=>({position: JSON.stringify(c.position), type: typeToId(c.type), value: c.value, opcodeName: c.opcodeName })),
                        organisms: (organisms||[]).map(o2=>({ organismId: o2.id, programId: o2.programId, energy: o2.energy, positionJson: JSON.stringify(o2.position), dps: o2.dps, dv: o2.dv })),
                        selectedOrganismId: o.id
                    });
                    return;
                }
            }
        }
        

    }

    window.EvoDebugger.ApiService = ApiService;
    window.EvoDebugger.SidebarView = SidebarView;
    window.EvoDebugger.ToolbarView = ToolbarView;
    window.EvoDebugger.AppController = AppController;
    window.EvoDebugger.controller = new AppController();
    // Auto-load first tick
    window.EvoDebugger.controller.init().catch(console.error);
});


