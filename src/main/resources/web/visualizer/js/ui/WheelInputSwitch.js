import { readWheelInputMode, writeWheelInputMode } from '../interaction/WheelInputSetting.js';

const MOUSE_ICON = '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="4.25" y="1.25" width="7.5" height="13.5" rx="3.75"/><line x1="8" y1="3.75" x2="8" y2="6.25"/></svg>';
const TOUCHPAD_ICON = '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="1.25" y="3.25" width="13.5" height="9.5" rx="1.75"/><circle class="dot" cx="6.5" cy="8" r="0.9"/><circle class="dot" cx="9.5" cy="8" r="0.9"/></svg>';

/** The segments of the switch: what each shows, its name for assistive technology and its tooltip. */
const SEGMENTS = Object.freeze([
    { mode: 'auto', content: 'Auto', label: 'Auto', title: 'Wheel and touchpad: detected per gesture' },
    { mode: 'mouse', content: MOUSE_ICON, label: 'Mouse', title: 'Mouse: the wheel zooms' },
    { mode: 'touchpad', content: TOUCHPAD_ICON, label: 'Touchpad', title: 'Touchpad: two fingers pan, a pinch zooms' },
]);

/**
 * Builds the switch that fixes what the wheel does: detected per gesture, zoom as a mouse wheel,
 * or pan as two fingers on a touchpad. The choice is stored in the browser.
 *
 * @param {function('auto'|'mouse'|'touchpad'): void} onChange - Receives the setting chosen.
 * @returns {HTMLElement} The switch, to be placed in the app switcher.
 */
export function createWheelInputSwitch(onChange) {
    const root = document.createElement('div');
    root.className = 'wheel-input-switch';
    root.setAttribute('role', 'group');
    root.setAttribute('aria-label', 'Wheel and touchpad');
    root.innerHTML = SEGMENTS.map(segment =>
        `<button type="button" data-mode="${segment.mode}" aria-label="${segment.label}" title="${segment.title}">${segment.content}</button>`
    ).join('');

    const buttons = [...root.querySelectorAll('button')];
    const show = (mode) => buttons.forEach(button =>
        button.setAttribute('aria-pressed', String(button.dataset.mode === mode)));
    show(readWheelInputMode());

    buttons.forEach(button => button.addEventListener('click', () => {
        const mode = button.dataset.mode;
        writeWheelInputMode(mode);
        show(mode);
        onChange(mode);
    }));
    return root;
}
