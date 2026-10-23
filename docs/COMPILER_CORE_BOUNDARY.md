# Compiler Core Boundary

This document records where the core of the compiler still knows more about a feature than the
rules in AGENTS.md allow. It describes the state as it is. The couplings listed here are
accepted, not sanctioned: a change may not deepen them, and each is a candidate for a later
decoupling.

That the core and the features are kept apart otherwise is proven by
`CompilerFeatureSeparationTest`: the reference program compiles without each feature once the
parts that need it are taken out, and fails at those parts while they are in. What a feature
builds on, it declares in `ICompilerFeature.requires()`.

## What the core does on its own

The core is the pipeline in `Compiler.java`, the eleven phases with their registries and
contexts, the three data formats under `model/`, the symbol table, the flag stores of the two
read-time phases, the instruction-set view and the machinery of computations in
`compiler/expression`.
Five phases are pure dispatch and hold no language logic: the preprocessor, the IR generator,
the IR rewriter, the linker and the compiler itself.

The core does contain logic for four groups of concepts. None of them is declared a core
concept in AGENTS.md; they are listed here because they are where the coupling lives.

1. **The module system.** The Lexer phase detects cycles between files, records a
   placement of a module at every import under its alias chain, each after the placements it
   imports, and distinguishes a `.SOURCE` file from a module. The placement of a position is
   carried by the lexer, which records it, by the preprocessor, which gives the tokens of the
   main file the placement of the compilation root, and by the symbol table, which files the
   names of a module under its placement and looks a name up in the placement of the position it
   is written at; the tokenizer gives every token an empty placement, and the handlers of
   `importdir` and `source` set the placement of the tokens they inline. The semantic analyzer
   wires the placements together before any handler runs; the symbol table keeps the names of
   two placements of one file apart. The symbol table resolves a path segment by segment: into a
   placement through an alias of the module level, whoever bound it there, and into the scope a
   symbol opened, which it treats generically, as any symbol whose node registered a scope; every
   segment reached through a foreign level has to carry the `EXPORT` flag. An alias symbol that
   leads to no placement is told apart from any other name through the capability
   `IPlacementAlias` of its node, never through the kind of the symbol. The table also holds
   every kind of symbol to the one-segment rule when it is defined, and knows which placement is
   the compilation root. `ModuleScope` holds the symbols, one alias map and a store for the records
   of features (`record`, `update`): immutable records a feature replaces by their changed copy
   until the scope is frozen, which the core never reads;
   what a requirement is, and what binds it, lives in `importdir`. The compiler lexes the texts of
   the configured values and the parser reads one of them as an operand when a handler asks
   (`operandOf`); what the values mean is the feature's. The parser knows the `EXPORT` keyword and asks
   each statement handler whether it accepts it, and each block handler per word of its block,
   the opener, a divider or the closer. The directives are features; the system behind
   them is core.
2. **Labels.** The layout engine records label addresses, claims a cell per label and assigns
   every label a machine value of its own. It records the cell of every label under the label's
   qualified name among the named layout values, from which a computation in the layout takes
   the position a label's name stands for. The emitter encodes label cells and builds the two
   label maps of the artifact. `SourceLineIndex` formats a label reference as a jump delta for
   the visualizer. The IR generator turns every identifier that survives phase 6 into a label
   reference. The `label` feature owns only the syntax, the symbol collection and the linking
   rule.
3. **Registers and procedure parameters.** The tokenizer recognises and validates register tokens
   and knows that one bank is reserved for procedure parameters. The token-map generator has
   its own branch for identifiers. `LinkingContext` has a field for
   call-site bindings, `EmissionContext` fields for procedure parameter names and register
   aliases, and `ProgramArtifact` carries all of them as named fields.

4. **Computations.** `compiler/expression` holds the operation registry, the parser and the
   evaluator of computations, the rule of which tokens form one (`Computation`) and the rule of
   where their results are noted (`ValueNotes`); every phase that computes uses them. The
   machinery knows an operation by its notation and its level, which it fixes (`Notation`,
   `Level`), and names three operations itself: the index `[]`, which the parser reads at a
   bracket, the typing `:`, which the parser reads after a molecule type, and the vector `|`,
   whose parts the shared rule reads; the notes show the result of the last two at their parts. It
   knows the name `@` of the cursor (`INameResolution.CURSOR`), which every resolution answers, and
   hands an operation the cursor and the origin the line counts its vectors from
   (`IEvaluationContext`). The registry holds the operations against the words of the
   preprocessor, `:` excepted, which the preprocessor reads at the start of a statement only. The
   three names are part of the tree the data model defines (`OperationExpression`), which writes a
   computation as text by them; the machinery and the features take them from there.
   Which operations exist, and what they compute, is the `expression` feature's.

Smaller instances: the tokenizer emits a colon token for `:` (label and typing); `Symbol.Type` is a closed
enum whose eight members are named after individual features, mapped exhaustively in
`TokenKindMapper`.

`CompilerOptions.defines`, the preprocessor flags of a compilation, is read by a feature and, in
the core, by `ProgramIdentity` and by `Compiler.java`: `ProgramIdentity` takes the defines into
the program's identity as a key of the compilation's variant and attaches no meaning to their
names or values.
`sourceRoots` is read by the core. The core upper-cases the names, rejects two keys that are one
name, reports a name that is no flag name or that nothing may define, seeds the two flag stores, one per read-time phase, from the defines without giving the
flags a meaning, and hands the options to the contexts of Phases 1 and 2 as before.

A token carries two fields of provenance that the core uses only as keys — in the token map, in
the symbol table by placement, in the instruction index by expansion and in the emitter's
consistency walk — and to whose particular values it attaches no meaning: the
instance of injected tokens its position stands in, `SourceInfo.expansion`, 0 for the text as
written, and the tokens it replaced, `Token.replaces`, one per level of substitution, each at its
position in the instance it stands in. A feature that injects tokens sets them, with a number the
preprocessor context hands out (`nextInstance`): the `macro` feature stamps the body tokens and
the arguments it substitutes, `source` the tokens of every inclusion. For a source view the
preprocessor keeps the source files of the compilation as one entry per inclusion: the main
file, and every inclusion a feature adds through `includes`, named by its placement, file and
instance and by the position of the directive that made it; `importdir` and `source` add theirs.
An instance that is no inclusion of its own — tokens copied from a template and injected at a
position — is reported through `expands`, with the position of the template, the position of
the injection, a name and the bindings of its parameters as text; `macro` does this for every
expansion, and the name and bindings are its words, which the core passes on. The preprocessor
offers `leftOut`, a region of lines owned by a directive line, and `note`, a text at a position;
each records at exactly the position it is given, in its placement, file and instance, and
equal records are kept once. A feature that records at a token decides which of its positions
count: `conditional` notes the state of a flag right after the token that names it and after
every token it replaced. The preprocessor returns the entries and the expansions with these records
attached — a record goes to the entry or the expansion whose number it carries; from phase 7 on
they travel, with the token map, in the IR's `DebugInfo`, which the emitter copies into the
artifact.

Any other character sequence a feature reads is not a case of the tokenizer but a symbol the
feature registers through `IFeatureRegistrationContext.lexerSymbol`; the tokenizer emits it as one
`SYMBOL` token whose text the feature's handlers compare: `*` and `..` for `place`, `+` for
`org` and `dir`, which read their marker `@+` or `@-` as the name `@` followed by `+` or the
minus, `+`, `*` and `/` for `expression`, `^` for `repeat`. The tokenizer tries the registered symbols at the start of every token,
longest first, before its fixed cases, so a symbol may consist only of the characters
`! & * + , . / < = > ? ^ ~ -`, which cut into no identifier, number, register, string, comment
or statement end. `|` and `:` stay fixed tokens, because the parser itself reads them in the
vector and typed literals every operand may take; `( ) [ ]` and `,` are fixed tokens as well,
and `@` alone is a name token. A symbol equal to one of the tokenizer's fixed characters
(`Tokenizer.FIXED_CHARACTERS`: `.` `-` `( ) [ ] ,` `|` `:` `;` `#` `"`) is rejected, since the
symbol would take the character from the tokenizer's own case, while longer sequences such as
`..` are allowed. `\` and `§` are kept out of the alphabet as a reserve for the core.
The alphabet is one constant in `FeatureRegistry`; it grows by a character when a feature needs
one and the core does not claim it.

## What cannot be added without touching the core

A feature can add a directive, a statement, an AST node, an IR directive, a rewrite rule, a
linking rule, an emission contributor, a lexer symbol and an operation through
`IFeatureRegistrationContext`.
A feature cannot, without a core change:

- put data of its own into `ProgramArtifact`, whose fields are fixed (issue #153);
- introduce a symbol kind, because `Symbol.Type` is closed.

What the core fixes by decision is listed in AGENTS.md under "Fixed Kinds"; the open
alternative for the IR is recorded under `docs/proposals/ideas/OPEN_COMPILER_BACKEND.md`.

## Candidates for decoupling

In the order in which they would be worth taking on. None is scheduled.

1. The `.PUSH_CTX`/`.POP_CTX` coupling between `source`, `importdir` and `ctx`: `source` and
   `importdir` declare that they require `ctx`, but they still emit directives by name that
   `ctx` owns. Either the including features close their own inclusion, or the context boundary
   becomes a core concept with a core handler, so that no feature emits a directive another
   feature owns.
2. The feature-named fields on `LinkingContext`, `EmissionContext` and `ProgramArtifact`
   (issue #153): a feature-owned metadata container would let `proc`, `reg`, `place` and
   `label` carry their data under a key of their own. `Expansion.name` and
   `Expansion.bindings`, which `PreProcessor.expands` takes, and `constantValues` on
   `EmissionContext` and `ProgramArtifact` are the same kind of coupling.
3. `Symbol.Type`: the kinds a symbol can have could be asked of the defining node, as the
   phases already do through `IIdentifierBinding` and `IJumpTarget`; the token map is the only
   reader left.
4. The label logic in layout, emitter and `SourceLineIndex`, once the label is not one of the
   three fixed IR item kinds any more.
5. The module system, which is the largest block and the least likely to move: the symbol
   table would need to resolve names through a handler the module features register.
