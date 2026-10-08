# Block Mechanism

**Status: ACCOMPLISHED — implemented on branch `feature/block-mechanism` (2026-10-08), in the five steps below; see *Outcome* at the end.**

A block is a span of the token stream between an opener and its closer, `.MACRO` … `.ENDMACRO`,
`.IFDEF` … `.ENDDEF`, `.PROC` … `.ENDPROC`. Its structure, where it begins, where its dividers
stand and where it ends, belongs to the core and is read before the handler of the opener runs, the
way rustc matches delimiters before the parser sees a token and the C preprocessor keeps its
conditional stack whatever the `#if` line says. One reader serves both phases that have blocks,
the preprocessor and the parser; each phase registers its block kinds and its block handlers. A
block whose structure is broken is reported, with both places where there are two, and left
behind as a whole, so that one mistake gives one message. A label takes no statement any more, and
a procedure stands only at the module level.

## Problem

1. **The header is read before the structure.** `MacroDirectiveHandler` and
   `RepeatDirectiveHandler` read their header line first, through `consume()`, which throws on a
   mistake, and the block only afterwards. On a header error the walk skips the line and nothing
   else: the body runs through the preprocessor as ordinary text, and the closer, reached with no
   block open, is reported as "closes no open block". `ConditionalBlockHandler` reads the block
   first and does not have the problem. The parser has it for `.PROC`, whose handler reads its body
   statement by statement and recognises `.ENDPROC` only by looking ahead; `.ENDPROC` is not a word
   the parser knows, so one reached outside a procedure is "Unknown directive".
2. **A label takes the next line.** A label alone on its line makes the statement of the next
   line its child, because `LabelDirectiveHandler` calls `context.declaration()`, which passes
   over newlines. `L:` before `.ENDPROC` therefore gives "Unknown directive '.ENDPROC'" and a
   procedure that never closes. The child is read by nothing but `LabelNodeConverter`; it is a
   leftover of the first parser.
3. **The two phases differ.** The preprocessor has `BlockKind` and `BlockReader`, which know every
   registered kind, count nesting and report a wrong closer with both places. The parser has
   nothing of the sort. The reader's recovery, on a block that does not close, sets the walk back
   to the opener and lets the opener's line pass unprocessed: the outer block is given up, and
   what follows is handled as if the block had never opened.

## Precedent

Read in the sources on 2026-10-08:

- **GNU as** collects a macro body up to `.endm` before it parses the macro's name; a header
  error is reported at the `.macro` line, the body is consumed and the macro is not defined
  (`gas/macro.c`, `define_macro`). An unterminated macro is reported at its opener.
- **libcpp and clang** push a conditional for every `#if`, whatever its expression says; `#endif`
  without `#if` is reported and dropped; at the end of the file every open conditional is
  reported at its own opener. clang, on `#ifdef` without a name, skips to the matching `#endif`
  "by not emitting an error when the #endif is reached".
- **rustc** matches `()[]{}` into token trees before the parser runs. A closer that does not
  match the innermost opener closes the innermost as unclosed and stays for the outer one when
  it matches an outer opener; the mismatch is reported with both places. After any delimiter
  error the recovered stream is discarded and the parser never runs on that file: the recovery
  serves to find every structural error in one pass.
- **NASM and LLVM MC** read the macro header first and show exactly problem 1: on a header error
  the body runs through and the closer is reported as "not defining a macro" (NASM) or
  "no current macro definition" (LLVM). LLVM's `.if` pushes its conditional before the
  expression is parsed.

## Options considered

| Option | Why not chosen |
|---|---|
| Do nothing; fix the two defects in their handlers | Leaves the order "header, then structure" in three handlers and the parser without any block discipline; `.CONTROL` would add a fourth handler with its own loop. |
| A block discipline in the parser only, modelled on the preprocessor's (CONTROL_FLOW_DIRECTIVES, step 1 as first drafted) | Two readers with the same job and two recoveries that would drift apart; problem 1 stays in the preprocessor. |
| The handler reads its structure through a core method it calls after the header (`readBlock` as today, in both phases) | Keeps problem 1: a handler that fails at its header never reaches the call. |
| A tree of every block of the input in one pass, each handler given its subtree | The preprocessor cannot keep such a tree: a stored body is read again where it is expanded, and the conditional handler rewrites the stream under the tree. Two mechanisms for two phases. |
| A block with a broken structure is still handed to its handler, with the structure as recovered | The parser reads a nested block again when it parses the body and the preprocessor when it expands a stored body, so every nested error is reported twice, or, after the conditional handler has rewritten the stream, not at all. No surveyed compiler hands a broken construct to its consumer. |
| **The core reads the structure before the handler, in one reader for both phases; a broken block is reported and skipped** | Chosen. |

## Solution

### Terms

- A **block kind** is a set of openers, one closer and a set of dividers, all directives; a
  feature registers it with the phase it belongs to. The kinds of the preprocessor today are
  `.MACRO`/`.ENDMACRO`, `.REPEAT`/`.ENDREPEAT` and `.IFDEF`, `.IFNDEF`/`.ELSEIFDEF`,
  `.ELSEIFNDEF`, `.ELSEDEF`/`.ENDDEF`; the parser gets `.PROC`/`.ENDPROC`.
- The **body** of a block is everything after the opener's line up to the closer; the dividers
  cut it into **parts**. The opener's line, its header, belongs to the handler.
- A block is **whole** when its reader found its closer without an error; otherwise it is
  **broken**.
- A **stored** kind keeps its body for later, `.MACRO` and `.REPEAT`; a kind that is not stored
  is processed where it stands. Stored is an attribute the preprocessor records at registration;
  the parser has no stored kinds.

### The rule

When a walk of either phase reaches the opener of a registered kind, the core reads the extent of
the block on the token list, with a stack of every registered kind of that phase, so that blocks
nest and never overlap. Then:

- A whole block is handed to the handler of its opener with its parts and its end. The handler
  reads the header, does its work and never looks for a closer. The walk continues after the end.
- A broken block is reported and skipped: the handler is not called, and the walk continues after
  the place where the reading stopped. A handler that fails at its header is treated the same
  way: the block it was given is left behind as a whole. One mistake, one message, and nothing of
  a failed block reaches a later handler.

The reader reports, and the block is broken, when:

- the closer of an enclosing block arrives while the block is open: "`.ENDPROC` closes the
  `.PROC` opened at `main.evo:3`, but the `.CONTROL` opened at `main.evo:5` is still open". The
  inner block ends there, and the closer stays for the outer block, which is whole if nothing else
  is wrong with it;
- a divider of another kind stands at the block's own level: reported the same way with
  "divides", and passed over;
- a closer or divider of a kind that is not open stands at the block's own level: "`.ENDDEF`
  closes no open block", passed over;
- the input ends while the block is open: "`.PROC` opened at `main.evo:3` is not closed before
  the end of the input", one message per open opener, innermost first;
- in the preprocessor only, a block word stands in another file than the opener: "`.MACRO`
  opened at `lib.evo:4` is not closed before the end of `lib.evo`". Inclusions inject the
  tokens of another file into the stream, and a block never straddles such a boundary. The
  parser switches this rule off: after expansion the tokens of a macro carry the file of its
  definition, and a macro may open a block that another macro closes.

A closer or divider that a walk reaches outside any block is reported where it stands, "`.ENDPROC`
closes no open block", and passed over, in both phases.

The word `EXPORT` before a divider or closer belongs to that word, as it belongs to a statement
today: the reader takes from the parser a predicate for such a prefix, records for every divider
and closer whether one stands before it on the same line, and ends the part before it. The core
asks the block handler, per word, whether it accepts `EXPORT`, and reports "EXPORT is not supported
before '.ENDPROC'" as it does for a statement. The preprocessor gives no predicate.

In the preprocessor, a stored body may hold no directive registered as top level only, `.SOURCE`,
`.IMPORT`, `.REQUIRE`, `.DEFINE`, `.UNDEF`, at any depth: the dependency scan reads the text as
written and cannot expand a macro, and the two phases must see the same files and the same flags.
The preprocessor checks the body tokens of a whole stored block itself, after the reader; a hit
makes the block broken. The reader knows neither stored kinds nor the list.

### A label takes no statement

`LabelNode` loses its `statement` field, `LabelDirectiveHandler` returns the node alone, and the
loop around it parses the rest of the line as the next statement. `EXPORT L: NOP` still exports the
label. The IR is unchanged: the label definition and the statement were emitted in this order
before.

### `.PROC` stands only at the module level

`ProcedureSymbolCollector` reports a procedure defined inside any level, ".PROC may stand only at
the module level.", as `.IMPORT` and `.REQUIRE` are reported, and still defines the name and
enters the scope, so that the scopes stay balanced for `collectAfterChildren`. No program in the
repository nests a procedure, and nesting gives nothing: procedure-local registers belong to the
call frame, so an inner procedure never sees the outer's, and the inner body would lie in the
outer's cells. Access to a caller's registers is what `REF` parameters give.

### What a programmer sees

```
.MACRO                 # Expected macro name.          one message; the body and .ENDMACRO are skipped
  NOP
.ENDMACRO

.PROC A
  .CONTROL X
.ENDPROC               # .ENDPROC closes the .PROC opened at main.evo:5, but the .CONTROL opened at main.evo:6 is still open

L:                     # a label alone on its line
.ENDPROC               # closes the procedure, as it should

.PROC OUTER
  .PROC INNER          # .PROC may stand only at the module level.
  .ENDPROC
.ENDPROC

EXPORT .ENDPROC        # EXPORT is not supported before '.ENDPROC'.
```

### Architecture

The core learns block words, never features: a feature registers its kinds and its handlers, and
the reader, the registries and the walks see nothing but the registered words (AGENTS.md,
"Feature-Agnostic Core"; `CompilerArchitectureRulesTest`). The shared classes live in
`org.evochora.compiler.frontend`, beside `DirectiveLine`, which both phases use today; the handler
interface of each phase lives in that phase's package (AGENTS.md, "Package Layout").

| Component | Where | Does |
|---|---|---|
| `BlockKind` | `frontend` | Record of openers, closer and dividers, upper-cased, each word used once; moved from `frontend/preprocessor`, without `stored`. |
| `BlockReader` | `frontend` | Reads the extent of a block on a `List<Token>` from an opener index, with a lookup of word → kind, the file rule on or off, and an optional prefix predicate; returns a `Block`: the parts as index ranges, the dividers with their prefix flags, the end, and whether the block is whole; reports every structural error to the diagnostics. Knows no kind, no feature, no phase. Moved from `frontend/preprocessor` and given the recovery above. |
| `PreProcessorHandlerRegistry` | `frontend/preprocessor` | Holds, as today, the block kinds and the top-level-only directives; additionally, per opener, its block handler and, per kind, whether it is stored. Rejects a word that already belongs to a kind or a handler, so that no closer or divider can get a handler. |
| `IPreProcessorBlockHandler` | `frontend/preprocessor` | `process(preProcessor, context, block)`; the handler of an opener. `MacroDirectiveHandler`, `RepeatDirectiveHandler` and `ConditionalBlockHandler` implement it and stop calling `readBlock`. |
| `PreProcessor.expand()` | `frontend/preprocessor` | On an opener: read the block; for a stored kind, check the body for top-level-only words; call the block handler if the block is whole, else continue after it; on an `ErrorRecoveryException` from a block handler, continue after the block. On a stray closer or divider: report and pass over, as today. Everything else as today. |
| `ParserStatementRegistry` | `frontend/parser` | Additionally holds the block kinds of the parser and, per opener, its block handler, with the same conflict rule; `Locale.ROOT` throughout, as in the other registry. |
| `IParserBlockHandler` | `frontend/parser` | `parse(context, block)` returning the node, and `supportsExport(String word)` for the opener, the dividers and the closer, default `false`. `ProcDirectiveHandler` implements it, reads its header from the opener's line, enters its scope, has the core parse the one part, leaves the scope and returns the `ProcedureNode`; it loses its loop and its look-ahead. |
| `Parser`, `IParsingContext` | `frontend/parser` | One private method dispatches a statement: `EXPORT`, the keyword lookup, a block opener (read the block, ask about `EXPORT` per word, call the block handler or skip), the default handler, the error recovery. `parse()` and the new `IParsingContext` method `statements(from, to)`, which parses the statements of a part into a list, share it. Two more methods give a handler with dividers what it needs: `lineOf(index)`, the line of a divider by the rules of `DirectiveLine`, and `tokenAt(index)`, the token of a divider or the closer, for its position. `declaration()` leaves the interface; after the change nothing but the parser called it. |
| `IFeatureRegistrationContext`, `FeatureRegistry`, `Compiler` | `compiler` | `preprocessorBlock(kind, handler, stored)` replaces `preprocessorBlock(kind)`; `parserBlock(kind, handler)` is new; the compiler fills both registries from them. |
| `LabelNode`, `LabelDirectiveHandler`, `LabelNodeConverter` | `features/label` | The node without the child, the handler without the call, the converter without the recursion. |
| `ProcFeature`, `ProcDirectiveHandler`, `ProcedureSymbolCollector` | `features/proc` | Registers the kind `.PROC`/`.ENDPROC` with its handler; the handler as above; the collector reports a procedure inside a level. |

What the core does not get: a node or a marker for a block's end, a tree of blocks, a method or a
field that names a directive.

### Documentation

- `docs/ASSEMBLY_SPEC.md`: under "Blocks", that a block with a broken structure is reported and
  skipped as a whole, and that `.PROC` is a block of the same discipline whose words a macro may
  open and another macro close; under `.PROC`, that a procedure stands only at the module level;
  under "Labels", nothing changes for the programmer, a label alone on its line was always meant to
  mark the next line.
- `AGENTS.md`, "Registry-Based Dispatch": `ParserStatementRegistry` also holds the block kinds of
  the parser, `parserBlock`.
- `docs/COMPILER_CORE_BOUNDARY.md`: the rows of `label` and `proc`; the sentence on `EXPORT`
  covers block words.
- `docs/proposals/README.md`: this document's row; CONTROL_FLOW_DIRECTIVES builds on it and loses
  the parser part of its step 1.

## Consequences for programs and experiments

- Every program in the repository compiles unchanged; the reference artifact of
  `CompilerOutputEquivalenceTest` is unchanged by every step.
- A program with a block mistake gets fewer and better messages: one per mistake, at the opener
  or with both places, and no "Unknown directive '.ENDPROC'" any more.
- A procedure inside a procedure is reported; no program has one.
- `EXPORT .ENDPROC` is reported instead of leaving the procedure open.
- `.REPEAT n STATEMENT` without an `.ENDREPEAT` gets the message for an open block, at the
  `.REPEAT`, instead of the hint at the shorthand `STATEMENT^n`: the block is read before the
  handler that gives the hint runs. With an `.ENDREPEAT` the hint stays.
- The messages for an unclosed procedure change from ".PROC 'STEP' is not closed; expected
  .ENDPROC." to the form of the preprocessor, ".PROC opened at main.evo:3 is not closed before the
  end of the input".
- No hot path: the compiler only.

## Pitfalls

- **A block is read twice in the parser**: once when the enclosing block's extent is read, once
  when the body is parsed and the walk reaches the inner opener. The cost is linear in the depth
  and not measurable; the second reading never reports, because an enclosing block is parsed only
  when it is whole, and every block inside a whole block is whole.
- **A structural error inside a stored body** is reported at the definition, as today, and the
  macro is not defined; a program that then expands the macro gets no further message from the
  preprocessor, and the compilation stops after the phase, as it does for every error.
- **The order of messages changes**: the structural errors of a block come before the errors of
  its content, because the structure is read first.
- **Two closers in the wrong order**, `.IFDEF` … `.REPEAT` … `.ENDDEF` … `.ENDREPEAT`, give two
  messages: the two places at the `.ENDDEF`, which ends the `.REPEAT` and closes the `.IFDEF`,
  and "closes no open block" at the `.ENDREPEAT` the walk then reaches. Both are true, and the
  second stands where the fix is made; rustc reports the same two.
- **The tokens of a broken block stay in the stream**, unprocessed, and the walk goes on after
  them. Nothing reads them: the compilation ends after the phase on the errors reported.
- **Diagnostics tests** that expect the old recovery or the old `.PROC` text change in the step
  that changes the behaviour, each named in the implementation table.

## Implementation

Strictly in this order; every step leaves the build green, and a defect is reproduced by a test
that fails before its fix (AGENTS.md, "Defect tests"). One branch, one pull request.

| # | Step | Files | Verification | Finished state |
|---|---|---|---|---|
| 1 | A label takes no statement | test first: `ParserTest` or `LabelDirectiveTest`, a label alone on its line before `.ENDPROC` inside a procedure, parsed through `Compiler.compile` with the standard features (fails today with "Unknown directive"); then `LabelNode`, `LabelDirectiveHandler`, `LabelNodeConverter`, the six `statement()` assertions in `ParserTest` (the statement is now the next node of the list), the constructor call in `CallAnalysisHandlerTypeSafetyTest` | `gw test --tests 'org.evochora.compiler.*'` | the defect test passes; a label before an instruction on the next line gives the IR it gave before; the reference artifact is unchanged |
| 2 | The shared reader and the preprocessor | test first: `MacroExpansionTest` or `CompilerDiagnosticsTest`, `.MACRO` without a name followed by a body and `.ENDMACRO`, exactly one message (fails today with a second, "closes no open block"); then `BlockKind` and `BlockReader` moved to `frontend` with the recovery of this document, `IPreProcessorBlockHandler`, `PreProcessorHandlerRegistry` (handler per opener, stored per kind, conflict rule, `Locale.ROOT`), `PreProcessor` (dispatch, top-level-only check, skip of a broken block), `IFeatureRegistrationContext`, `FeatureRegistry`, `Compiler`, `MacroFeature`, `RepeatFeature`, `ConditionalFeature`, `MacroDirectiveHandler`, `RepeatDirectiveHandler`, `ConditionalBlockHandler`; `BlockReaderTest` moved to `frontend` and rewritten for the recovery (foreign closer, foreign divider, stray word at own level, end of input with two open blocks, file rule on and off, prefix recorded); `CompilerDiagnosticsTest`, `RepeatDirectiveTest`, `ConditionalExpansionTest`, `MacroExpansionTest` for the changed recovery | `gw test --tests 'org.evochora.compiler.*'` | the defect test passes; every rule of "The rule" has a test on the reader; the reference artifact is unchanged |
| 3 | The parser's block discipline and `.PROC` | test first: new `ParserBlockTest` in `compiler/frontend/parser` with two test-local block kinds and stub handlers, asserting a stray closer at top level, a foreign closer with both places and the outer block whole, a block open at the end of the input, a header error that skips the body, `EXPORT` before a closer accepted and refused; then `ParserStatementRegistry`, `IParserBlockHandler`, `Parser`, `IParsingContext`, `IFeatureRegistrationContext`, `FeatureRegistry`, `Compiler`, `ProcFeature`, `ProcDirectiveHandler`; the nine hand-built registries that register `.PROC` (`IrGeneratorTest`, `SemanticAnalyzerTest`, `ProcedureDirectiveTest`, `UsingClauseIntegrationTest`, `EmissionIntegrationTest`, `RegDirectiveTest`, `ImportDirectiveTest`, `RequireDirectiveTest`, `ModuleSourceConstIntegrationTest`); the `.PROC` message in `CompilerDiagnosticsTest`; `ProcedureDirectiveTest` | `gw test --tests 'org.evochora.compiler.*'` | every case of `ParserBlockTest` passes; `.ENDPROC` is a word the parser knows; the reference artifact is unchanged |
| 4 | `.PROC` only at the module level | test first: `CompilerDiagnosticsTest`, a procedure inside a procedure reported with the message of this document at the inner `.PROC` (fails today); then `ProcedureSymbolCollector`; the nested-procedure test in `RegDirectiveTest` keeps testing that the parser stays balanced, with its comment saying so; the comment on the bank reference count in `ParserState` | `gw test --tests 'org.evochora.compiler.*'` | the defect test passes; the parser keeps accepting the nesting the analysis reports |
| 5 | Documentation and the small things | the documents listed under Documentation; this document's status; the comments of `LabelDirectiveHandler` and `LabelNodeConverter` if step 1 left any trace | review of the diff against the neighbouring sections; `gw check` | every document uses the terms block, part, whole; PMD and the full suite green |

## Decisions

1. The structure of a block is read by the core before the handler of its opener runs, in both
   phases, by one reader in `org.evochora.compiler.frontend`.
2. A block whose structure is broken is reported and skipped as a whole; its handler is not
   called. A handler that fails at its header leaves its whole block behind the same way.
3. Recovery: a closer of an enclosing block ends the inner block, reported with both places, and
   stays for the outer; a foreign divider at a block's own level is reported and passed over; a
   closer or divider with no open block of its kind is reported where it stands; a block open at
   the end of the input is reported at its opener, one message per opener.
4. The file rule is an option of the reader that the preprocessor sets and the parser does not.
5. Each phase has a block handler type of its own, registered in one call with its block kind;
   closers and dividers have no handler, which the registries enforce.
6. `EXPORT` before a divider or closer belongs to that word; the reader records it through a
   prefix predicate the parser supplies, and the core asks the block handler per word with
   `supportsExport(String word)`.
7. Stored kinds and the top-level-only check are the preprocessor's: it checks the body tokens of
   a whole stored block itself, and a hit makes the block broken.
8. `declaration()` leaves `IParsingContext`; the context parses the statements of a part into a
   list, and `parse()` shares the dispatch with it.
9. A label takes no statement; the rest of its line is the next statement.
10. `.PROC` stands only at the module level, reported by the symbol collector with the scope
    still entered.
11. Five steps in the order above, the preprocessor before the parser; one pull request.
12. `.CONTROL`/`.CASE`/`.ENDCONTROL` are not part of this document; CONTROL_FLOW_DIRECTIVES
    builds on it.

## Outcome

Implemented in the five steps above. What the built code does beyond the solution text:

- **Three methods for a block handler of the parser.** Besides the statements of a part,
  `IParsingContext` gives a handler the line of a divider, `lineOf`, and the token of a divider
  or the closer, `tokenAt`; the test handler with dividers in `ParserBlockTest` builds a block
  with a named head, named cases and an end that takes `EXPORT` from these three and nothing
  else, as a control block will.
- **A stray closer or divider takes its line with it.** The parser reports the word and skips
  to the end of its line, so that `.CASE X` outside a block gives one message and not a second
  for the `X`.
- **The hint for `.REPEAT n STATEMENT`** stays where the block is whole, `.ENDREPEAT` included;
  without an `.ENDREPEAT` the block is reported as open at the `.REPEAT`, because the reader
  runs before the handler that gives the hint.
- **The tokens of a broken block stay in the stream** of the preprocessor and the parser skips
  over them; four assertions on the stream after an error changed for that, and no reader of the
  stream sees them, since the compilation ends after the phase.
- **The rule on nested procedures lives in the analysis.** `ProcedureSymbolCollector` reports
  the nesting; the parser accepts it and keeps its register banks balanced by the reference
  count that exists for that case, which its comments and the nested-procedure test in
  `RegDirectiveTest` say.
- **The five hand-made preprocessor registrations in tests** that paired a handler with a block
  kind by hand went away: the test helper registers kind, handler and stored together, as the
  features do.
