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

// Export für globale Verfügbarkeit
window.SidebarSourceView = SidebarSourceView;
