/**
 * Which entry of the jump bar is marked: the one of the section in view.
 *
 * @module JumpBar
 */

/** How far down the window, as a share of its height, the top of a section must have come for it to be in view */
const READING_LINE = 0.35;

/** How close to the end of the page, in pixels, the page counts as scrolled to its end */
const END_TOLERANCE = 2;

/**
 * @param {Array<number|null>} sectionTops - Where each section of the jump bar begins, relative to
 *     the top of the window; null for a section the page does not have
 * @param {{viewHeight: number, scrollY: number, pageHeight: number}} view - The height of the
 *     window, how far the page is scrolled and how high it is
 * @returns {number} The index of the marked entry, or -1 where no section is in view yet
 */
export function sectionInView(sectionTops, { viewHeight, scrollY, pageHeight }) {
    // At the end of the page the last section is the one in view, however short it is
    if (viewHeight + scrollY >= pageHeight - END_TOLERANCE) return sectionTops.length - 1;
    const line = viewHeight * READING_LINE;
    let current = -1;
    sectionTops.forEach((top, i) => {
        if (top !== null && top <= line) current = i;
    });
    return current;
}
