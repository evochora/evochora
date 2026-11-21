/**
 * The one way the homepage reads an answer: as parsed JSON, or as null.
 *
 * @module Http
 */

/**
 * @param {string} url
 * @returns {Promise<object|Array|null>} The parsed response, or null where the server does not
 *     answer, answers with an error or answers with something that is not JSON. It never rejects,
 *     so a caller tells "not there" from a value without catching anything.
 */
export const readJson = url => fetch(url).then(response => response.ok ? response.json() : null).catch(() => null);
