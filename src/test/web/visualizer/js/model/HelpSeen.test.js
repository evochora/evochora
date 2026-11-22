import { describe, expect, test } from 'vitest';
import { HELP_SEEN_KEY, markHelpSeen, wasHelpSeen } from '../../../../../main/web/visualizer/js/model/HelpSeen.js';

/** A storage as the browser offers it, holding the given entries. */
const storageWith = (entries = {}) => {
    const held = new Map(Object.entries(entries));
    return { getItem: key => held.has(key) ? held.get(key) : null, setItem: (key, value) => held.set(key, String(value)) };
};

/** A storage that refuses every access, as a browser can in a private window. */
const refusing = {
    getItem() { throw new Error('refused'); },
    setItem() { throw new Error('refused'); },
};

describe('the mark that the help was seen', () => {
    test('a browser without the mark has not seen the help', () => {
        expect(wasHelpSeen(storageWith())).toBe(false);
    });

    test('closing the help once is remembered', () => {
        const storage = storageWith();
        markHelpSeen(storage);
        expect(storage.getItem(HELP_SEEN_KEY)).toBe('true');
        expect(wasHelpSeen(storage)).toBe(true);
    });

    test('a browser that refuses its storage counts as one that has not seen the help', () => {
        expect(wasHelpSeen(refusing)).toBe(false);
        expect(() => markHelpSeen(refusing)).not.toThrow();
        expect(wasHelpSeen(null)).toBe(false);
        expect(() => markHelpSeen(null)).not.toThrow();
    });
});
