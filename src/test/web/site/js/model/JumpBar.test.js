import { describe, expect, test } from 'vitest';
import { sectionInView } from '../../../../../../infrastructure/docker/site/js/model/JumpBar.js';

const view = (scrollY = 0) => ({ viewHeight: 1000, scrollY, pageHeight: 10000 });

describe('the entry the jump bar marks', () => {
    test('is none while no section has come up to the reading line', () => {
        expect(sectionInView([600, 1400, 2200], view())).toBe(-1);
    });

    test('is the last section whose top has passed the reading line', () => {
        expect(sectionInView([-900, 350, 1200], view(3000))).toBe(1);
        expect(sectionInView([-900, 351, 1200], view(3000))).toBe(0);
    });

    test('skips a section the page does not have', () => {
        expect(sectionInView([-900, null, 1200], view(3000))).toBe(0);
    });

    test('is the last section at the end of the page, however short it is', () => {
        expect(sectionInView([-2000, -800, 900], view(8998))).toBe(2);
        expect(sectionInView([-2000, -800, 900], view(8997))).toBe(1);
    });
});
