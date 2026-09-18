/**
 * Tick Window View
 *
 * The part of the run every card shows. A track stands for the whole run and carries the window:
 * dragging an edge sets that side, dragging the window moves it, dragging over the track outside
 * it draws a new one, and a double click or the button beside the last tick shows the whole run again. The two ticks stand beside the
 * track for typing, as "28M", "28.5M", "500k" or a plain number, whose digits are grouped as they
 * are typed. A field that holds no tick - a decimal part without a suffix - is marked and keeps
 * its text until it is completed, or Escape puts the tick back.
 *
 * The window is reported when a drag ends or a typed tick is confirmed, never while dragging:
 * every report makes the cards load.
 *
 * @module TickWindowView
 */

import { bindTickField, formatTick, parseTick } from '../../../shared/tick/TickText.js';

/** Distance in pixels within which a press takes hold of an edge. */
const EDGE_GRIP = 6;

/** Smallest share of the run a window may cover. */
const MIN_SHARE = 1 / 2000;

let root = null;
let canvas = null;
let tooltip = null;
let fromInput = null;
let toInput = null;
let resetButton = null;
let onChange = () => {};

/** Tick range of the run. */
let extent = null;
/** Window shown, always within the extent; equal to it when the whole run is shown. */
let view = null;
/** Drag in progress: what is dragged, and the tick and window the drag started from. */
let drag = null;
/** Tick under the pointer, or null. */
let hoverTick = null;

/**
 * Builds the view into a container.
 *
 * @param {HTMLElement} container - Element the view is appended to
 * @param {function(?{from: number, to: number}): void} handler - Receives the new window, or null
 *        when the whole run is to be shown
 */
export function init(container, handler) {
    onChange = handler || (() => {});

    root = document.createElement('div');
    root.className = 'tick-window';
    root.hidden = true;
    root.innerHTML = `
        <input class="tick-window-input" type="text" inputmode="decimal" aria-label="First tick shown">
        <div class="tick-window-track">
            <canvas></canvas>
            <div class="tick-window-tooltip"></div>
        </div>
        <input class="tick-window-input" type="text" inputmode="decimal" aria-label="Last tick shown">
        <button class="tick-window-reset" aria-label="Show the whole run" data-tooltip="Show the whole run">\u2922</button>
    `;
    container.appendChild(root);

    [fromInput, toInput] = root.querySelectorAll('.tick-window-input');
    canvas = root.querySelector('canvas');
    tooltip = root.querySelector('.tick-window-tooltip');
    resetButton = root.querySelector('.tick-window-reset');

    const track = root.querySelector('.tick-window-track');
    track.addEventListener('pointerdown', handlePointerDown);
    track.addEventListener('pointermove', handlePointerMove);
    track.addEventListener('pointerup', handlePointerUp);
    track.addEventListener('pointercancel', handlePointerUp);
    track.addEventListener('pointerleave', () => {
        if (!drag) {
            hoverTick = null;
            tooltip.classList.remove('visible');
            draw();
        }
    });
    track.addEventListener('dblclick', () => commit(null));

    [fromInput, toInput].forEach(input => {
        input.addEventListener('keydown', event => {
            // Enter takes a tick and leaves the field; a text that is no tick keeps the field
            if (event.key === 'Enter' && handleTyped(input)) input.blur();
            if (event.key === 'Escape') {
                showInputs();
                input.blur();
            }
        });
        input.addEventListener('change', () => handleTyped(input));
        // Typing replaces the tick: nothing has to be deleted first
        input.addEventListener('focus', () => input.select());
        bindTickField(input);
    });
    resetButton.addEventListener('click', () => commit(null));

    new ResizeObserver(draw).observe(track);
}

/**
 * Sets the tick range of the run and the window shown on it.
 *
 * @param {?{min: number, max: number}} runExtent - Tick range of the run, or null to hide the view
 * @param {?{from: number, to: number}} tickWindow - Window shown, or null for the whole run
 */
export function show(runExtent, tickWindow) {
    if (!root) return;
    extent = runExtent && runExtent.max > runExtent.min ? runExtent : null;
    root.hidden = !extent;
    if (!extent) return;
    view = clamp(tickWindow ? { ...tickWindow } : { from: extent.min, to: extent.max });
    showInputs();
    draw();
}

/** Smallest width a window may have on this run. */
function minWidth() {
    return Math.max(1, Math.round((extent.max - extent.min) * MIN_SHARE));
}

/** Keeps a window inside the run and no narrower than the smallest width, preserving its width. */
function clamp(next) {
    const width = Math.min(extent.max - extent.min, Math.max(minWidth(), next.to - next.from));
    const from = Math.min(Math.max(extent.min, next.from), extent.max - width);
    return { from, to: from + width };
}

/** Whether a window covers the whole run. */
function isWholeRun(candidate) {
    return candidate.from <= extent.min && candidate.to >= extent.max;
}

/** Writes the window into the two tick fields and clears their marks. */
function showInputs() {
    fromInput.value = formatTick(view.from);
    toInput.value = formatTick(view.to);
    fromInput.classList.remove('invalid');
    toInput.classList.remove('invalid');
    resetButton.disabled = isWholeRun(view);
}

/** Takes over a window and reports it. */
function commit(next) {
    view = next ? clamp(next) : { from: extent.min, to: extent.max };
    showInputs();
    draw();
    onChange(isWholeRun(view) ? null : { ...view });
}

/**
 * Takes the typed ticks as the window, if they are one. A field whose tick lies outside the run
 * is marked; when the two ticks leave no window between them, the field typed into last is.
 *
 * @param {HTMLInputElement} [changed] - The field typed into
 * @returns {boolean} Whether the typed ticks were a window
 */
function handleTyped(changed = toInput) {
    const from = parseTick(fromInput.value);
    const to = parseTick(toInput.value);
    const fromValid = from !== null && from >= extent.min && from <= extent.max - minWidth();
    const toValid = to !== null && to >= extent.min + minWidth() && to <= extent.max;
    fromInput.classList.toggle('invalid', !fromValid);
    toInput.classList.toggle('invalid', !toValid);
    if (!fromValid || !toValid) return false;
    if (to - from < minWidth()) {
        changed.classList.add('invalid');
        return false;
    }
    commit({ from, to });
    return true;
}

/**
 * The tick at a position of the track, on a round value: a pixel covers many ticks, and the round
 * one among them is the one a reader would have typed. The ends of the run stay reachable.
 */
function tickAt(clientX) {
    const box = canvas.getBoundingClientRect();
    const share = Math.min(1, Math.max(0, (clientX - box.left) / box.width));
    const tick = extent.min + share * (extent.max - extent.min);
    const unit = Math.max(1, markStep(box.width) / 100);
    return Math.min(extent.max, Math.max(extent.min, Math.round(tick / unit) * unit));
}

/** The position of a tick on a track of the given width. */
function xOf(tick, width) {
    return (tick - extent.min) / (extent.max - extent.min) * width;
}

/** What a press at a position takes hold of. */
function gripAt(clientX) {
    const box = canvas.getBoundingClientRect();
    const x = clientX - box.left;
    const left = xOf(view.from, box.width);
    const right = xOf(view.to, box.width);
    if (Math.abs(x - left) <= EDGE_GRIP && x < (left + right) / 2) return 'from';
    if (Math.abs(x - right) <= EDGE_GRIP) return 'to';
    if (x > left && x < right && !isWholeRun(view)) return 'window';
    return 'new';
}

/** Starts a drag: of an edge, of the window, or of a new window over the track. */
function handlePointerDown(event) {
    if (!extent || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drag = { grip: gripAt(event.clientX), startTick: tickAt(event.clientX), startView: { ...view }, moved: false };
}

/**
 * Moves the window with a drag and shows the tick under the pointer; nothing is reported until the
 * drag ends.
 */
function handlePointerMove(event) {
    if (!extent) return;
    const tick = tickAt(event.clientX);
    hoverTick = tick;

    if (drag) {
        drag.moved = drag.moved || tick !== drag.startTick;
        const start = drag.startView;
        if (drag.grip === 'from') {
            view = { from: Math.min(tick, start.to - minWidth()), to: start.to };
        } else if (drag.grip === 'to') {
            view = { from: start.from, to: Math.max(tick, start.from + minWidth()) };
        } else if (drag.grip === 'window') {
            view = clamp({ from: start.from + tick - drag.startTick, to: start.to + tick - drag.startTick });
        } else if (drag.moved) {
            view = { from: Math.min(drag.startTick, tick), to: Math.max(drag.startTick, tick) };
        }
        view = { from: Math.max(extent.min, view.from), to: Math.min(extent.max, view.to) };
        fromInput.value = formatTick(view.from);
        toInput.value = formatTick(view.to);
    }

    const grip = drag ? drag.grip : gripAt(event.clientX);
    event.currentTarget.style.cursor =
        grip === 'from' || grip === 'to' ? 'ew-resize' : grip === 'window' ? 'grab' : 'crosshair';

    const box = canvas.getBoundingClientRect();
    tooltip.textContent = drag && drag.grip !== 'new'
        ? `${formatTick(view.from)} – ${formatTick(view.to)}`
        : formatTick(tick);
    tooltip.style.left = `${Math.min(box.width - 40, Math.max(40, event.clientX - box.left))}px`;
    tooltip.classList.add('visible');
    draw();
}

/** Ends a drag and reports the window, unless the pointer never moved or no window is left. */
function handlePointerUp(event) {
    if (!drag) return;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    const finished = drag;
    drag = null;
    if (!finished.moved || view.to - view.from < minWidth()) {
        view = finished.startView;
        showInputs();
        draw();
        return;
    }
    commit(view);
}

/** Step between the marks of the track: the 1-2-5 step that puts them about 90 pixels apart. */
function markStep(width) {
    const raw = (extent.max - extent.min) / Math.max(1, width / 90);
    const power = Math.pow(10, Math.floor(Math.log10(raw)));
    return [1, 2, 5, 10].map(factor => factor * power).find(step => step >= raw);
}

/** Draws the track: the run, the window on it, the scale and the tick under the pointer. */
function draw() {
    if (!canvas || !extent || !view) return;
    const box = canvas.getBoundingClientRect();
    if (box.width === 0) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(box.width * ratio);
    canvas.height = Math.round(box.height * ratio);
    const ctx = canvas.getContext('2d');
    ctx.scale(ratio, ratio);
    const w = box.width;
    const h = box.height;

    ctx.fillStyle = '#0f0f18';
    ctx.fillRect(0, 0, w, h);

    const left = xOf(view.from, w);
    const right = xOf(view.to, w);
    ctx.fillStyle = 'rgba(74, 158, 255, 0.35)';
    ctx.fillRect(left, 0, Math.max(1, right - left), h);

    // Every mark carries its tick centred above it
    const step = markStep(w);
    ctx.font = '9px "Roboto Mono", "Courier New", monospace';
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';
    for (let tick = Math.ceil(extent.min / step) * step; tick <= extent.max; tick += step) {
        const x = Math.round(xOf(tick, w));
        ctx.fillStyle = 'rgba(255, 255, 255, 0.25)';
        ctx.fillRect(Math.min(w - 1, x), h - 6, 1, 6);
        // A tick that would not fit centred over its mark is left out: a shifted one misleads
        const label = formatTick(tick);
        const half = ctx.measureText(label).width / 2;
        if (x - half < 2 || x + half > w - 2) continue;
        ctx.fillStyle = 'rgba(255, 255, 255, 0.55)';
        ctx.fillText(label, x, (h - 6) / 2 + 1);
    }

    ctx.fillStyle = '#4a9eff';
    ctx.fillRect(Math.round(left), 0, 2, h);
    ctx.fillRect(Math.round(right) - 2, 0, 2, h);

    if (hoverTick !== null && !drag) {
        ctx.fillStyle = 'rgba(255, 255, 255, 0.35)';
        ctx.fillRect(Math.round(xOf(hoverTick, w)) - 1, 0, 2, h);
    }
}
