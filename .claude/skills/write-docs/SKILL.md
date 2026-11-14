---
name: write-docs
description: Write, revise or review documentation that people read — the guides and references under docs/, README.md, CONTRIBUTING.md, comments in config/*.conf and reference.conf, notebooks. Use whenever such a text is written, changed or checked for quality, including a single sentence.
---

# Writing documentation for people

A document is written for one reader at a time: someone who reads it from the start, or who
jumps to one place and reads on from there. Every rule below serves that reader. Where a
`humanizer` skill is installed, apply it as well, every time, before a text is presented.

## Before writing

- Read the whole document, not only the place that changes. A change stands at one place, and
  at that place the reader knows only what the document has said before it.
- Decide where the change belongs in the whole text, not where it happens to fit. A sentence
  that is correct but stands in the wrong section is a defect.
- Know who reads the document. A guide for users and a reference for developers use different
  words: what a developer works with can be named by its name in the code, what a user never
  sees is left out or explained.

## Rules for the text

**Terms**
- A term is introduced before it is used, in one clause or with a link to the place that
  explains it; otherwise it is left out. A forward link is acceptable in a reference, an
  unexplained term is not.
- One term for one thing, throughout the document. The project's established terms are used as
  they are and never paraphrased, even where a paraphrase seems clearer.
- A sentence relies on no fact the document states later.

**Wording**
- No sales language and no filler: no word that only praises, no sentence that only announces
  what follows or repeats what was said, no claimed benefit where the text could say what
  happens.
- No dashes as parentheses; use commas, brackets or a colon.
- Phrases the writer tends to repeat are used where they fit and never piled up.
- Bold marks a term that is being defined, as in a glossary list, and nothing else.
- A change takes the form, the depth and the wording of the text around it, so that nobody can
  tell it was written separately. A new section has the heading level, the parts and the depth
  of its neighbours; what a reader needs beyond that lives in the help text of a command, in
  error messages and in the comments of the configuration.
- Describe what the software does for the reader, not how it does it.

**Values and configuration**
- A value that the configuration or a constant in the code holds is never written as a number.
  Name the setting or the constant instead.
- A document describes behaviour; how a setting is made is explained in the configuration
  itself. The document says that something can be set and where, and links there.

## Checks before presenting

Do these by reading, not by tooling, and do all of them before anything is shown:

1. Read every changed passage as a reader who has read the document up to there and nothing
   else: does every term it uses stand introduced above it?
2. Read the section the change sits in as a whole: does it read well, does the change fit what
   comes before and after, is anything said twice?
3. Search the document for the patterns the rules name: sales words, dashes, piled-up phrases,
   paraphrased terms, hard-coded values. Fix every occurrence or none; a document that follows
   a rule in some places and not in others is worse than before.
4. Check every statement against the code or the configuration it describes, and name where
   it stands when asked.
5. Compile or run every complete code example with the real tool. An example in running text,
   such as an operand inside a sentence, needs no compiling.
6. Apply the `humanizer` skill, where one is installed.
