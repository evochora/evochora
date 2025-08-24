document.addEventListener('DOMContentLoaded', () => {
    window.EvoDebugger = window.EvoDebugger || {};

    class ApiService {
        async fetchTickData(tick) {
            const res = await fetch(`/api/tick/${tick}`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return await res.json();
        }
    }

    class SidebarBasicInfoView {
        constructor(root) { this.root = root; }
        update(info) {
            const el = this.root.querySelector('[data-section="basic"]');
            if (!info || !el) return;
            el.innerHTML = `<div class="code-view">id=${info.id} program=${info.programId} energy=${info.energy} pos=${JSON.stringify(info.position)}</div>`;
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
        constructor(root) {
            this.root = root;
            this.basic = new SidebarBasicInfoView(root);
            this.next = new SidebarNextInstructionView(root);
            this.state = new SidebarStateView(root);
            this.source = new SidebarSourceView(root);
        }
        update(details) {
            if (!details) return;
            this.basic.update(details.basicInfo);
            this.next.update(details.nextInstruction);
            this.state.update(details.internalState);
            this.source.update(details.sourceView);
        }
    }

    class ToolbarView {
        constructor(controller) {
            this.controller = controller;
            document.getElementById('btn-first').addEventListener('click', () => this.controller.navigateToTick(0));
            document.getElementById('btn-prev').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick - 1));
            document.getElementById('btn-next').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick + 1));
            document.getElementById('btn-last').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick + 100));
            const input = document.getElementById('tick-input');
            document.getElementById('btn-goto').addEventListener('click', () => {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) this.controller.navigateToTick(v);
            });
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
        }
    }

    class AppController {
        constructor() {
            this.api = new ApiService();
            this.canvas = document.getElementById('worldCanvas');
            this.renderer = new WorldRenderer(this.canvas, { WORLD_SHAPE: [100,30], CELL_SIZE: 22, TYPE_CODE:0, TYPE_DATA:1, TYPE_ENERGY:2, TYPE_STRUCTURE:3, COLOR_BG:'#0a0a14', COLOR_EMPTY_BG:'#14141e', COLOR_CODE_BG:'#3c5078', COLOR_DATA_BG:'#32323c', COLOR_STRUCTURE_BG:'#ff7878', COLOR_ENERGY_BG:'#ffe664', COLOR_CODE_TEXT:'#ffffff', COLOR_DATA_TEXT:'#ffffff', COLOR_STRUCTURE_TEXT:'#323232', COLOR_ENERGY_TEXT:'#323232', COLOR_DEAD:'#505050' }, {});
            this.sidebar = new SidebarView(document.getElementById('sidebar'));
            this.toolbar = new ToolbarView(this);
            this.state = { currentTick: 0, selectedOrganismId: null, lastTickData: null, totalTicks: null };
            this.canvas.addEventListener('click', (e) => this.onCanvasClick(e));
        }
        async init() { await this.navigateToTick(0); }
        async navigateToTick(tick) {
            let target = typeof tick === 'number' ? tick : 0;
            if (target < 0) target = 0;
            if (typeof this.state.totalTicks === 'number' && this.state.totalTicks > 0) {
                const maxTick = Math.max(0, this.state.totalTicks - 1);
                if (target > maxTick) target = maxTick;
            }
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
                this.sidebar.update(data.organismDetails[sel]);
                document.getElementById('sidebar').classList.add('visible');
            } else {
                // do not auto-open sidebar
                document.getElementById('sidebar').classList.remove('visible');
            }
            this.updateTickUi();
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
                    if (det) this.sidebar.update(det);
                    document.getElementById('sidebar').classList.add('visible');
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


