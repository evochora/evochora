/**
 * The elements the views of the homepage are built from.
 *
 * @module Element
 */

/**
 * @param {string} tag
 * @param {{className?: string, text?: string}} [properties]
 * @param {Array<Node|string>} [children]
 * @returns {HTMLElement}
 */
export function element(tag, { className, text } = {}, children = []) {
    const node = document.createElement(tag);
    if (className !== undefined) node.className = className;
    if (text !== undefined) node.textContent = text;
    node.append(...children);
    return node;
}

/**
 * @param {string} url
 * @param {Array<Node|string>} children
 * @param {{className?: string, newTab?: boolean}} [options]
 * @returns {HTMLAnchorElement} A link that opens in a new tab where newTab is set
 */
export function link(url, children, { className, newTab = false } = {}) {
    const a = element('a', { className }, children);
    a.href = url;
    if (newTab) {
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
    }
    return a;
}
