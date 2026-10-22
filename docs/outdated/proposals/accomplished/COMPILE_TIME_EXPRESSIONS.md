# Compile-Time Expressions

**Status: ACCOMPLISHED — implemented in two pull requests, the prerequisites as #212 (2026-10-21) and the
expressions on branch `feature/expressions` (2026-10-21 to 2026-10-22), in the twelve steps below; see
*Outcome* at the end.**

## Problem

An operand of the assembly language is one of five fixed forms: a register, a number, a typed
literal such as `DATA:5`, a vector such as `1|0`, or a name. Nothing is computed. A constant holds
a literal, a `.PLACE` holds numbers, a `.REPEAT` holds a number, a flag holds a number. Whoever
wants `WIDTH-1` writes `99` and keeps the two in step by hand.

The case that makes this a problem is the shell of an organism. The default primordial places a
`STRUCTURE` shell one cell outside its body and a label on each corner, and its reproduction
behavior finds the corners by those labels. Shell and labels are the reproduction's business, but
they stand in the main file, because a module cannot compute where to put them: a `.ORG` or
`.PLACE` inside a module counts from the position the module was imported at, and the module may
not know where that is, nor depend on it. The main file would have to hand the module a place in
the main file's frame, and nothing in the language can express a place.

Smaller instances of the same gap: a value that depends on another (`HALF` of `ROWS`) is written
twice; a `.REPEAT` cannot take a flag, so a configuration cannot change a row's length; a number
that does not fit a cell is cut to 20 bits in silence (`SETI %DR0 DATA:1048576` compiles to
`DATA:0`); a constant can be used before its definition only where a vector is expected, by way of a
gap in the analysis; a label where a vector is expected is accepted and emitted wrongly; a number
without a type is rejected as a written operand but silently taken as `DATA` through a constant
(`.CONST FIVE 5`, `SETI %DR0 FIVE`).

## Precedent

| | Expression type | Lifetime | Not yet known | Extension |
|---|---|---|---|---|
| GNU as | `expressionS`: operator, two symbols, constant; a symbol's value is "section plus offset"; `.` is the current location | parse → `fixS` for the writer | symbolic `sym - sym + const`, resolved at write time | `md_operator`, `expr_set_rank` (named operators with a rank), target hooks |
| NASM | `expr` vector of (type, value) terms; `$` is the current position | pass to pass | critical expressions must resolve in pass 1 | none |
| ca65 / ld65 | `ExprNode` tree, one node type with an operation code; `*` is the program counter | written into the object file, evaluated by the linker | tree kept until link time | built-in functions hard-wired in the parser |
| LLVM MC | `MCExpr`: Constant, SymbolRef, Unary, Binary, Target; immutable, every node with a location | parse → fixup → layout, re-evaluated per layout iteration | `MCValue`: `A - B + const` | `MCTargetExpr` subclasses |
| javac, Roslyn | constant folded during attribution / binding, value stored on the tree | later phases read the stored value | — | — |
| Swift | custom operators in named precedence groups; unrelated groups need parentheses | | | `precedencegroup` |
| cpp | a token remembers whether whitespace preceded it (`PREV_WHITE`), so that `NAME(` and `NAME (` differ after macro expansion | | | |
| clang, gcc, NASM, ca65, gas | the component that reads a file tokenizes it and follows its includes as it meets them | | | |

Common to all: one representation from parsing to the stage that knows the last value, early
folding of what is known, a symbolic remainder for what is not, the error for "known too late"
raised by the consumer, and the set of operations open through a table or hook rather than through
the tree. A symbol's value carries whether it is a place or a number, so that two places cannot be
added and a place minus a place is a number; a symbol assigned from `.` takes the location of its
own line; `.`, `$` and `*` are predefined symbols, not operators. Where "check" and "replace" are
separate phases (javac, Roslyn), the value is computed once and handed on as data. Nowhere is there
a pass that finds files and a second pass that tokenizes them.

## Options considered

Each row names the option that was taken and the ones that were not, with the case that decided it.
The language rows were decided first and are the requirements of the architecture; the maintainer
kept them open to a better proposal, and several changed during the architecture work and the
reviews (parentheses as the marker, `|` as a chain, the position of `|` in the reading order,
positions as a value kind, `@` as the cursor, the lexer as the phase that finds the files).

### The language

| Question | Taken | Not taken, and why |
|---|---|---|
| Notation | infix with mathematical precedence, `NAME(a, b)`, `V[i]` | a Scheme-like `(MIN A B)` with every computation parenthesised: long expressions unreadable; a `NAME(a b)` without commas: "a hybrid of both worlds" |
| Where a computation may stand without parentheses | nowhere: a computation stands in parentheses; free are the forms the language has today, a call and an index access, and a typed literal or a vector with names or these forms as its parts | "spaces separate operands, parentheses allow spaces": needs a whitespace flag on every token and a rule set the maintainer found hard to keep in mind; "maximal munch of operator characters" (Swift, Haskell): `A*-1` becomes an unknown operator `*-` |
| A call is a name directly followed by `(` | the tokenizer gives such a `(` its own token type, `CALL_PARENTHESIS`, which survives a macro substitution | "the parser compares columns": a macro argument keeps the columns of the call site, a body token those of the body, so `DATA:V[0]` with `V` substituted is no index any more; "calls and index accesses only inside parentheses": `OUT_NW[0]..OUT_SE[0]` would need a pair of parentheses around every piece |
| Minus | always an operator, unary or binary by position; no negative number tokens | "`-` before a digit is part of the number": `(A-1)` needs the parser to split a token |
| Value kinds | number (64-bit), typed value, vector, molecule type, position; typed ∘ number keeps the type, equal types combine, different types are an error ("full" rule) | "typed values compute only through `NUMBER()`": more to write, nothing safer |
| Molecule type | a value kind of its own, `Value.Type` | "typed value carries the type as text, `TYPE(x):n` a special form": `TYPE` would be the one function that is not a function |
| Position | a value kind of its own: a place, as a vector from the origin of the program (the main file's origin, the organism's start position at run time); every jump target is one, `@` is the cursor's, `POSITION(v)` makes one from a vector relative to the origin the line counts from; a position is computed in the layout, where it arises | "a vector": `.CONST EAST 1\|0` and a label are both vectors, so a value handed to a module or exported from it could not be told from a displacement; "forbid exporting and passing positions": the shell would depend on every import line subtracting its own `@`; "a position carries its module and the layout converts between frames": the layout would learn what a placement is, a coupling the core boundary lists and that no change may deepen, and nothing needs it once a position is a program-origin vector |
| Vector construction `a\|b\|c` | a chain notation: all adjacent `\|` form one operation over all parts; every part has to be a number | "`\|` as binary concatenation": `NW\|5` with a vector constant `NW` becomes a legal three-dimensional vector and hides a kind confusion, undetected in a three-dimensional world |
| Reading order | a named scale of levels in the core; `\|` is the widest ordered level | "`\|` tighter than `-`" (from one example): `A\|B*2` and `-A\|B` compute wrongly without an error; "numeric ranks" (gas): a feature can create a level the spec does not have |
| Word operators (`AND`, `OR`, `XOR`, `SHL`, `SHR`) | a level `UNORDERED`: unordered against the infix and chain levels, so parentheses whenever one stands next to a different infix or chain operator; prefix, index and typing bind tighter | "C precedence": `A SHL 2 + 1` reads as `A SHL 3`; "one level, left to right": `A AND B OR C` surprises |
| Labels | positions, computed only in layout directives and in definitions; a computed position in an instruction operand is an error, and so is a number or a vector computed from a position; a definition whose value is only a name that leads to a label stays an alias of the label, as today | "a label's position as an operand value": a data pointer never jumps to a computed position, organisms orient relatively (a principle of the system); "a number or a vector computed from positions as an instruction argument, its value the one at compile time" (reconsidered 2026-10-22, rejected again): a variation operator that copies or moves code, as `GeneDuplicationPlugin` copies a block starting at a label, leaves a jump or a label operand working in the copy, because the label is found at run time, while a value computed from positions points to the wrong place without a sign; the compiler would make the fragile form the convenient one, and it would cost a fourth IR operand form; the `POS` instruction is unused and no precedent; "an alias of a label is a layout value too": `JMPI LOCAL` with `.CONST LOCAL TARGET` would lose the label, which `ModuleValuesTest` has today |
| The frame of a module | a module receives places as positions; a position is the same place wherever it is used | "the shell in the main file", "import at the origin", "program coordinates inside a module", "absolute import offsets": each makes a module depend on where or how it is imported, or know its importer; "`@` at the import line, the importer subtracts it": works for one level, fails when a value with that `@` is exported back or handed on |
| `@` | a predefined name, the position of the cursor, one meaning everywhere a computation stands, as gas `.`, NASM `$`, ca65 `*`; in a definition it is the cursor at the definition's line, in a `USING VALUE` the cursor at the import line | "a new word `HERE`"; "`@` is the direction in `.DIR`": two meanings; "`@` inside a vector component means that component of the cursor": an exception to the kind rules at one place; "`@` not in `USING VALUE`": a restriction only the compiler's internals explain; "`@` as an operation without arguments": the registry would need a notation for an atom that nothing else uses |
| `P[i]` | the component of a position relative to the origin the line counts from; allowed everywhere | "only in layout directives": the number carries no frame, but neither does any number; a restriction without a reason in the language |
| `.ORG 0\|@+2`, `.DIR @+0\|1` | unchanged: the syntax of the two directives, outside any computation, read by their handlers as the tokens `@` and `+`/`-` | "dissolve the marker into a computation": 130 lines of programs, and `.DIR @+0\|1` would silently read as a different vector |
| A definition whose value the layout knows | computed where it is written: a constant and a default at their line, a `USING VALUE` at the import line; a use below resolves it by name, a use above is a forward reference (gas `.set x, .`, NASM `equ`) | "the definition's tree is substituted at each use and computed there": `@` in it would mean the cursor of the use, a constant would have a value per use, and the visualizer could show none; "a `USING VALUE` computed at the `.REQUIRE VALUE` line": `@` and `P[i]` would mean the module's cursor and frame, so they would have to be forbidden in `USING VALUE` for a reason no programmer can see |
| Reserved names | the names of the registered operations and `@` cannot be defined — not as a constant, a label, a procedure, an alias, a requirement, a flag, a macro or a macro parameter | "only macros are checked": `.CONST SHL 2` would make `(A SHL SHL)` unreadable with a message at the wrong place |
| Flags | compute with numbers and flags, in `.DEFINE`, `.IFDEF`, `.REPEAT`, `^`; `.IFDEF X` alone stays "set"; configured flags are numbers | "flags stay literals": behind all four reference assemblers; a `.REPEAT` of a flag is harmless for the shell, because a label at the end of the row measures the row; "configured flags compute": a configuration has no order (`HashMap`, HOCON object), `gas --defsym` takes an integer too; the dependency is written in the program with `.DEFINE` |
| Define before use | every name computes with definitions above it; the analysis checks it at the line | "resolve forward": today's rule already, closes the vector gap, and makes cycles impossible |
| Functions | `NUMBER(x)`, `TYPE(x)`, `POSITION(v)`; a rotation for `.DIR` is not decided and not needed while `@+a\|b` stays | `SIZEOF`, `STARTOF`, a `PROGRAM` argument: a module would know the program |
| Names of operations against the preprocessor | an operation named like a preprocessor word is rejected after all features registered, `:` excepted because its handler acts only at the start of a statement; a word that is a prefix or infix operator cannot also be a function | "the macro wins" (C): `MIN(A, 4)` would mean a macro in one file and a function in another |
| Error messages | one sentence, the standard term of the error, names quoted, no rule explained, no list of directives, no question | explanatory sentences ("a label has no position in code; it is used in .ORG and .PLACE"): go stale when a directive is added, and explain what the spec explains; "Procedure 'STEP' cannot be used as a value.": needs the kind of a symbol, which `nobodyReadsTheKindOfASymbol` forbids |

### The architecture

| Question | Taken | Not taken, and why |
|---|---|---|
| Where expressions live | a core concept: records in `model/expression`, behaviour in `compiler/expression`, the concrete operations in a feature | "an expressions feature that owns the tree, the parser and the evaluator": every phase that needs a value would call that feature; two reviews found the violations at exactly these seams |
| One tree or one per phase | one immutable tree from parsing to the end of the layout | "each phase computes to today's literals": labels and `@` are layout values and cannot be computed earlier; "a flat normal form `sym + const` for the backend" (gas, LLVM): does not cover vectors and functions |
| Node kinds | three: literal, name, operation | "literal, name, unary, binary, call, component" (LLVM): LLVM separates them for fixed-size arena nodes, a two-operand relocation algorithm and a grammar without functions; none applies here |
| Location | every node carries the `SourceInfo` of its first token (LLVM `SMLoc`) | "only the operand carries one" (ca65): an error inside `(A + FOO*2)` could not point at `FOO` |
| The literal of AST and IR | one `Value` type, used by the AST (`LiteralNode`), the IR (`IrLiteral`, `IrValue.Literal`) and the expressions | "a `Value` next to the three AST and three IR literal forms": the same three shapes a third time, converted at every phase boundary |
| Operations | an interface with key, notation, level, signature and computation on values in an evaluation context; an operation may answer "not yet"; instances made by five groups that hold their kind rule once and implement a group interface the backend rule covers; overloads by value kinds; a repeated last parameter; the key of an operator character is (character, position) | "enumeration and `switch`" (the assemblers, javac): a feature could add no operation; "one class per operation": the full rule written into every arithmetic operation; "computation on values alone": `P[i]` needs the origin the directive counts from, which values do not carry |
| Who registers the built-in operations | the `expression` feature, through the same `operation` call any feature uses; the parser takes operators and their level from the registry | "the core registers its groups first": a special path used only once, and an order rule that duplicates are already an error |
| What a feature may register | functions, prefix and infix operators, chains; an operator character needs a lexer symbol or a fixed token of the tokenizer | "functions only": the registry would have to tell one feature from another |
| How a handler reaches the evaluator | a read accessor `operations()` on `IFeatureRegistrationContext`, the pattern of `isa()`, handing a handler the registry at registration; the registry is complete when the phases run, as the instruction set is; the layout and the read-time contexts get it from the compiler | "a context parameter on `IAnalysisHandler`": an interface change across every analysis handler for one new need |
| Packages of the behaviour | `compiler/expression` for what frontend and backend share, with the sub-packages `parser` (tokens) and `symbols` (symbol table) that the backend is forbidden to use; the root references neither, nor `model/ast` | "one flat package": the backend would depend, one step removed, on tokens and the symbol table; "a third package `frontend/expression`": the behaviour of one concept in two places |
| Phase 4 and phase 6 | phase 4 computes every expression once and records the result in the symbol table, its output, as data of `model/expression`; phase 6 substitutes from the table | "phase 6 evaluates again through a class next to phase 4": a later phase using an earlier phase's logic; "phase 4 checks kinds only": division by zero and type mismatches would surface in a phase without a diagnostics engine; "the symbol table stores the evaluator's result record": a data format would depend on a behaviour package |
| Who does the work in each phase | handlers of the directive features (analysis, post-process, layout, emission), library classes in `compiler/expression`, a method on the context where a phase has to offer a service (read time, layout, emission); phases 5 and 6 read the recorded result of an `ExpressionNode` themselves, as phase 6 replaces an identifier today | "the phase evaluates before dispatch", "a class next to the phase": phases are thin orchestrators; "a post-process handler and a token-map contributor of the `expression` feature read the recorded results": without the feature `(A)` would reach phase 7 as an internal error, against the probe rule of the core boundary, and three context methods would serve one caller |
| Who knows the labels in the layout | the `LayoutContext` keeps the named layout values — the cell of every label, which the engine records as it does today, and the value of every layout-valued definition, which the feature's layout handler records — and `evaluate` resolves names from them; no handler reads them; the emitter hands the same values, undeciphered, to the emission context | "the engine's local map, handed to the linker": `.ORG NW` cannot be computed; the knowledge is the same, the place is one every phase-free service can reach; "the emitter writes definition values into `constantValues`": core code that knows which named values are constants, a coupling the core boundary lists as a candidate for decoupling |
| How a name inside a computation reaches the token map | the generator classifies every name of an `ExpressionNode` as it classifies an `IdentifierNode`, in the one place that reads the kind of a symbol | "a classification service on the context, called by a contributor of the `expression` feature": two context methods for one caller; "the contributor classifies": `nobodyReadsTheKindOfASymbol` forbids it the kind of a symbol; "no entries": the value behind `WIDTH` in `(WIDTH-1)`, which the visualizer shows today, would vanish |
| How a directive reads its components | the directive cuts its arguments at its own separators, outside brackets, and reads each piece with a reporting `operandOf`; the parser knows no separator | "a `component()` entry in the parser that stops before `\|`": the parser would learn the separators of three directives |
| Where a `USING VALUE` is computed | at the import line, by a directive the import converter emits for every binding that is a computation; the requirement's own line records that its value lies under its name | "at the `.REQUIRE VALUE` line for every source": `@` and `P[i]` in a `USING VALUE` would take the module's cursor and frame |
| Read time | the lexer is the phase that finds the files: it tokenizes each file once and runs the scan handlers over the tokens of each placement; one flag store per read-time phase, spanning the whole scan, filled by `conditional`; it moves to the core, created and seeded by the core, when computing at read time needs it there | "lex every file before the scan": the files are only known once the scan has followed the imports; "a shared character scanner for the scan": keeps the regexes and two condition parsers; "no computing at read time": the maintainer's decision for computing; "one store for both phases": the preprocessor would see the end state of the scan; "a store per scanned file": a `.DEFINE` before an `.IMPORT` would be lost in the imported file; "the store in the core in the first pull request": core code that runs only for one feature, before anything in the core needs it |
| Visualizer | the result of a computation as a note `[=value]`, the existing per-position channel | "a value field in `TokenInfo`": changes the artifact and does not reach the layout values |

## Solution

### What the programmer writes

**A computation stands in parentheses.** Outside parentheses the language is the one of today: a
number (with its sign), a name, a register, a vector of numbers `3|5`, a typed literal `DATA:5`, and
two new forms that end visibly, a call `NAME(…)` and an index `V[…]`; a typing with a name on either
side computes like a call does, `DATA:MASKED`, `WALL_TYPE:7`, `WALL_TYPE:WALL_NUMBER`, and so does
a typed literal whose value is a call, an index or a parenthesis, `DATA:(WIDTH-1)`, `DATA:MIN(ROWS, 8)`,
`DATA:V[0]`; only a molecule type with a number, `DATA:5`, `DATA:-5`, is the literal of today. A
vector with a name or one of these forms among its parts computes the same way, `WIDTH|HEIGHT`,
`1|(HEIGHT-1)`; only a vector of numbers, `3|5`, is the literal of today.
Inside parentheses, inside the parentheses of a call and inside the brackets of an index, spaces are
free and everything computes:

```
.DEFINE ROWS 4                    # a flag, for the preprocessor
.CONST WIDTH 100
.CONST HEIGHT 70
.CONST AREA (WIDTH * HEIGHT)
.CONST UNIT ENERGY:50
.CONST LIMIT 8
SETI %DR0 DATA:(WIDTH-1)
SETI %DR1 (UNIT * 2)              # ENERGY:100 — a typed value keeps its type
SETV %DR2 (WIDTH-1|HEIGHT-1)      # one pair of parentheses around the whole vector
SETI %DR3 DATA:MIN(LIMIT, 8)      # if a feature registers MIN
SETI %DR4 (TYPE(UNIT):7)          # ENERGY:7
.REPEAT (ROWS * 2)                # flags and numbers
```

`A-1` and `A -1` outside parentheses are two operands, as they are today; the instruction's argument
count reports it. A call is a name directly followed by `(`; `NAME (x)` is a name and a parenthesis,
and `NOT(A)` is the prefix operator `NOT` on the parenthesis `(A)`, because `NOT` is no function.

**Values.** A number is a 64-bit integer; the lexer reads literals in decimal, hexadecimal, binary
and octal without a sign, and a minus in front is the sign operator. A typed value is a molecule
type and a number; a vector is a list of numbers; a molecule type is a value of its own (`DATA`,
`ENERGY`, …, whatever the instruction set knows). A position is a place in the program, measured
from the origin of the program: the origin of the main file, which at run time is the organism's
start position; the compiler never knows the origin of the environment. Types and positions never
become cells. A number is checked against the range of what it becomes: a cell in an instruction
operand and in a `.PLACE`, a coordinate in a layout directive, a count in `.REPEAT` and `^`; a vector
is checked against the dimensions of the world where it becomes the cells of an instruction
argument, and nowhere else.

**Operators and functions.**

| Level, tight to wide | Operators | Reads |
|---|---|---|
| index | `a[i]` | component `i` of a vector, or of a position relative to the origin the line counts from, counting from 0 |
| typing | `T:n` | the typed value; `T` a molecule type, written or named, `n` a number with its sign, so `DATA:-5` |
| prefix | `-a`, `NOT a` | sign per component for a vector; bitwise not for a number |
| multiplicative | `*`, `/`, `MOD` | left to right |
| additive | `+`, `-` | left to right |
| chain | `a\|b\|c` | a vector of the parts, each a number with its sign |
| unordered | `AND`, `OR`, `XOR`, `SHL`, `SHR` | one operator with itself left to right; next to a different infix or chain operator only in parentheses |

Typed with number keeps the type (`UNIT * 2` is `ENERGY:100`); two typed values combine only with
equal types; a vector adds to and subtracts from a vector of the same length, multiplies and divides
by a number, and nothing else; a number alone where an operand wants a literal is an error, as it is
today when written, and no longer slips through a constant. A position plus or minus a vector is a
position; a position minus a position is a vector; a position plus a position, times a number, or
with a sign is an error — the rules gas has for sections. `NUMBER(x)` gives the number of a typed
value, `TYPE(x)` its type, `POSITION(v)` the place `v` relative to the origin the line counts from —
the module's origin, or the file's inside a `.SOURCE`d file, as for a vector in `.ORG`. Division by
zero, overflow and an index out of range are errors.

**Names and `@`.** A name in a computation is a constant, a value of a `.REQUIRE VALUE`, or a jump
target: a label, a procedure or a control block, each a position. `@` is a predefined name, the
position of the cursor, the cell the next code would go to; it cannot be defined, nor can the name
of an operation. Every constant and value is defined above its use; a use above the definition
reports "the name is not defined", so that a definition can never depend on itself; a label is
known everywhere in phase 4 and is a forward reference in the layout when it stands below the
directive that uses it. A definition whose value is only a name that leads to a label is an alias of
that label, as today: `JMPI LOCAL` with `.CONST LOCAL TARGET` jumps to the label; a name in
parentheses, `.CONST LOCAL (TARGET)`, is a computation and its value a layout value. A definition whose
value is a computation that only the layout knows, such as `(NW - (1|1))` or `(@ + (0|2))`, takes its
value at its own line, as gas gives a symbol set from `.`, and is used in layout directives only;
`JMPI X` with such an `X` is an error. The component of a position, `P[i]`, is the component
relative to the origin the line counts from; like any number it carries no frame.

**Layout directives.** `.ORG`, `.DIR` and `.PLACE` take an operand wherever they take a number today,
and keep their syntax and their separators: `.ORG 0|@+2` with the markers `@+` and `@-`, `.DIR @+0|1`
for the rotation, `.PLACE WALL 1..WIDTH|0` with a constant `WALL` of `STRUCTURE:100`. The markers are
the syntax of the two directives, outside any computation; a space between `@` and the sign is
accepted. A vector in `.ORG` or `.PLACE` means, as today, a place relative to the origin the line
counts from; a position means that place of the program; `.ORG (@ + (0|2))` continues two rows below
the cursor, `(0|(@[1]+2))` is the cursor's row plus two in the module's column `0`. A label stands
above the directive that uses it.

**The shell, owned by the behavior.** The main file hands the module two places of its own frame;
the module uses them as if they were its own:

```
# default/main.evo
.CONST WIDTH 100
.CONST HEIGHT 70
.ORG 1|0
JMPI MAIN_LOOP; …
…
  CALL REPRODUCE.STEP REF %RESULT
…
.ORG 0|@+2
.IMPORT "LIB:behavior/reproduce/shell-first.evo" AS REPRODUCE USING … VALUE BODY_NW POSITION(0|0) VALUE BODY_SE POSITION(WIDTH-1|HEIGHT-1)

# LIB:behavior/reproduce/shell-first.evo
.REQUIRE VALUE BODY_NW
.REQUIRE VALUE BODY_SE
.CONST OUT_NW (BODY_NW - (1|1))
.CONST OUT_SE (BODY_SE + (1|1))
.PLACE STRUCTURE:100 OUT_NW[0]..OUT_SE[0]|OUT_NW[1]      # north edge
…
.ORG POSITION(BODY_SE[0]|BODY_NW[1])
NE_CORNER:
…
.ORG BODY_NW
NW_CORNER:
.ORG 0|0
EXPORT .PROC STEP REF RESULT
…
```

The layout puts `.ORG BODY_NW` at `0|0` of the main file wherever the module was imported, because
`BODY_NW` is that place of the program, and `OUT_NW[0]` is the column of that corner relative to the
module's origin, computed when the layout reaches the `.PLACE`; `POSITION(BODY_SE[0]|BODY_NW[1])`
counts from the module's origin as its components do, and is the north-east corner. A module hands a
position on to a module of its own unchanged (`USING VALUE X BODY_NW`); a position a module exports
is right in the importer too. A row whose length a flag decides is measured by a label at its end. A
`.ORG` to a position moves the cursor, and the cursor stays there: after the last corner the behavior
returns with `.ORG 0|0` to its own origin, the cell the import line stood at, and puts its code
there. The main file starts its code at `1|0`, beside the corner label on `0|0`, and imports the
behavior below its own code. Whatever the main file places after the import line counts from where
the module left the cursor.

**Flags and configuration.** `.DEFINE`, `.IFDEF`, `.REPEAT` and `^` compute with numbers and flags;
`.IFDEF X` alone keeps meaning "set", true with or without a value and false when unset; `.IFDEF
(A+1) = 3` is a comparison; `.IFDEF (A+1)` alone holds when the computation can be computed, every
flag in it defined with a value, and otherwise reports what any computation reports ("'A' is not a
flag.", "'A' has no value."); `.IFNDEF` takes a name, as today. `--define NAME=INTEGER` stays a number; a flag that depends on
another is written in the program, `.DEFINE HALF (ROWS/2)`. `--value NAME=(WIDTH-1)` takes what
`.CONST` takes, with the names of the main program above the `.REQUIRE VALUE` line. An error in a
configured value is reported against the configuration, naming it, without a file or line.

**Error messages.** One sentence with the standard term, names quoted, the position at the node
that failed; the spec explains the rules, the message does not:

`Expected an expression after '-'.` · `Expected a number after '|'.` · `Unknown function 'MINN'.` · `Register '%DR0' cannot be used in an expression.` · `'AND' and '+' need
parentheses.` · `'FOO' is not defined.` (and the symbol table's other texts) · `'X' cannot be used as
a value.` · `Argument 2 for instruction 'SETV' has the wrong type. Expected VECTOR, but got POSITION.`
(a position or a molecule type, `… got TYPE.`, in an operand or a label slot) · `Argument 2 for
instruction 'SETI' is computed from a position.` · `Cannot pass 'D' as VAL argument: it is computed
from a position.` ·
`'X' has no value.` (a flag set without a value, in a computation) · `Invalid operands for ':': typed and number.` (a defined name that is no molecule type before `:`; an undefined one reports `'FOO' is not defined.`)
· `Division by zero.` · `Integer overflow.` · `Type mismatch: ENERGY and DATA.` · `Invalid operands
for '*': vector and vector.` · `Invalid vector component: 'NW' is a vector.` · `Index 3 out of bounds
for a vector of length 2.` · `Value 1048576 out of range for a cell (-524288 to 524287).` · `Value
4294967296 out of range for a coordinate.` · `Expected a vector with 2 components.` · `Forward
reference to 'NW'.` (a label or a layout-valued definition below the line that uses it) · `'ROWS' is
not a flag.` · `Expected a number.` · `Configured value 'WIDTH' = (A/0): division by zero.` · `'MIN'
is a function and cannot be a macro name.` · `'SHL' is an operator and cannot be defined.` · `'@'
cannot be defined.` · at start-up: `Operation '^' is a preprocessor directive.`

**The visualizer** shows the source as written and, behind each computation, its result as a yellow
note `[=99|69]`, the way it shows a flag's state behind its name wherever a directive reads the flag;
a constant keeps the value behind its name, inside a computation and a layout-valued one included.
The typing `T:n` and the vector `a|b|c` show no note of their own; a part of them that computes shows
its result at its end, a name its value as every constant.

### The architecture

**Data: `model/expression`.** `Expression` is a sealed interface with three records, each carrying
the `SourceInfo` of its first token and the position right after the last token read for it, the
closing parentheses around it included: `LiteralExpression` (a `Value`), `NameExpression` (the text as
written, qualified by the phase that resolves it; `@` is never qualified) and `OperationExpression`
(the name of an operation and its operands, in order). `Value` is sealed with five records: number
(`long`), typed (type and number), vector (an immutable list of numbers), type (the name the
instruction set knows) and position (a vector from the program's origin); `Kind` names the five.
`Value` is the one literal of the compiler: the AST's three literal nodes become one
`LiteralNode(Value, SourceInfo)`, the IR's three literal operands one `IrLiteral(Value)`,
`IrValue.Int64` and `IrValue.Vector` one `IrValue.Literal(Value)`. The tree is syntax-free:
operations carry registry names, names are qualified before they reach the IR, and the tree never
leaves the compiler. `SourceInfo` and `Expression` are the shared types of the three data formats;
`model/expression` references none of `model/token`, `model/ast`, `model/ir`; `model/symbols`
records for an expression its `Value`, or its remaining `Expression` with its `Kind`, and so
references `model/expression` as it references `model/ast`, and nothing of `compiler/expression`.

**Behaviour: `compiler/expression`,** a phase-free package like `isa` and `diagnostics`, referencing
no phase. Its root holds what frontend and backend share and references neither its sub-packages nor
`model/token`, `model/ast` nor `model/symbols`; the sub-packages hold what only the frontend may use,
and the backend's architecture test forbids them by name:

- `IOperation`: a key (a name, or an operator character with its position), a notation (function,
  prefix, infix, chain), a level, a signature (the value kinds in, the kind out; the last parameter
  may be repeated) and the computation: values and an evaluation context in, a value, "not yet" or a
  reason out — a result type of this package. The context names, in the layout, the cursor and the
  origin the line counts its vectors from; in phase 4 it names nothing of the kind, and an operation
  that needs it answers "not yet": `POSITION(v)` and `P[i]`. `-` as a prefix and `-` as an infix are
  two keys; a duplicate signature, or a second notation or level for one key, is an error, as is a
  name that is a preprocessor word (`:` excepted, whose handler acts only at the start of a
  statement) and a word that would be both an operator and a function. The checks run once all
  features have registered. Operations are made by groups; a group implements `IOperationGroup`, so
  that the backend's rule covers the classes that compute.
- The registry: by key, the notation, the level, and the overloads. Features fill it through
  `IFeatureRegistrationContext.operation(IOperation)` and read it through
  `IFeatureRegistrationContext.operations()`, the pattern of `isa()`: a handler takes the registry
  at registration and uses it when its phase runs, when every feature has registered. The compiler
  hands the same registry to the layout and to the read-time contexts. Its names and `@` are the
  reserved names: the core hands them to the symbol table and to the flag stores when it creates
  them, and `define` rejects them wherever anything is defined; the preprocessor context offers them
  too, so that `.MACRO` rejects them as a macro name or a parameter name, case-insensitively.
- The precedence scale: the named levels of the table above, each with its order and whether it
  mixes. `UNORDERED` is unordered against the infix and chain levels only; a different operator of
  the same `UNORDERED` level does not mix either. The right operand of `:` is read at the prefix level, so that
  `DATA:-5` needs no parentheses; a part of a chain is read at the level below the chain, so that it
  may hold a computation, `(WIDTH-1|HEIGHT-1)`, and a sign stands there because the prefix level is
  tighter than the chain. A new level is a
  change of the core and the spec, by design.
- The evaluator: tree in, one of four results out. `Value`; `Partial` (a smaller tree in which
  stand only names a later phase knows and operations that answered "not yet", with the kind of the
  result derived from the signatures); `Error` (a reason and the node's position; the caller
  reports); `Reported` (no value, nothing to say, because the error stands at the definition — a
  `.REQUIRE VALUE` without binding and default). Names are answered by a resolution the caller
  supplies: a value, "later" with the kind and the qualified name, "reported", or "not usable" with
  an explanation; `@` is answered "later, a position" by every frontend resolution and with the
  cursor by the layout's. The result, the answer and the context are types of this package.
- `compiler/expression/parser`: tokens in, tree out. It knows the forms `OP a`, `a OP b`,
  `NAME CALL_PARENTHESIS … )`, `a[b]`, `(a)` and the chain, and asks the registry what a token means;
  a word operator or a function may arrive as an `OPCODE` token (`NOT` is a mnemonic) and is read
  like a name; a prefix word before a `CALL_PARENTHESIS` is the prefix on the parenthesis; a symbol
  the registry does not know is "Unexpected '…'.". `[]` is the one form with a fixed name, the way
  Python maps `a[b]` to `__getitem__`. A molecule type is recognised through the instruction set, so
  `DATA:5` is a typed literal and `FOO:5` with no type `FOO` is a name and a colon.
- `compiler/expression/symbols`: the bridge from the symbol table to the evaluator's answers, used
  by every frontend handler that evaluates. It follows a chain of names through `IIdentifierBinding`
  first, as `bindingOf` does, and answers from where the chain ends: at an `IJumpTarget`, a label
  reference — a position in a computation, the label's name in a label slot; at a definition whose
  recorded value is `Partial`, "later" with that kind and the definition's qualified name; at a
  definition with a value, the value; at a binding to a register, "not usable"; at anything else,
  "cannot be used as a value". A name is never qualified to a definition that is only an alias.

**The `expression` feature** (`features/expression`, the sixteenth standard feature) registers the
lexer symbols `+`, `*`, `/`; the five operation groups — `Arithmetic` (`+ - * / MOD` and the sign,
with the full rule as three signatures each), `Bitwise` (`AND OR XOR SHL SHR` on `UNORDERED`, `NOT`
as prefix), `Vector` (`|` as a chain with the repeated signature "number…", `[]`, vector with vector,
vector with number), `Typing` (`:`, `TYPE`, `NUMBER`), `Position` (position with vector, position
with position, `[]` on a position, `POSITION`). It registers no phase handler: the core phases read
the recorded result of an `ExpressionNode` themselves. Without the feature no computation inside parentheses is accepted; it
is indispensable, as the instruction feature is, and the probe of the core boundary names it so.

**The tokenizer** gets fixed token types `OPEN_PARENTHESIS`, `CLOSE_PARENTHESIS`, `OPEN_BRACKET`,
`CLOSE_BRACKET`, `COMMA`, and `CALL_PARENTHESIS` for a `(` that touches the word before it; `-` is
always an operator token and numbers carry no sign; `@` alone is a name token, not a name character,
and `@` leaves the alphabet of registrable symbols; the name scan stops before `..`; registered
symbols are matched before the fixed cases, as today, and the registry rejects a symbol equal to a
fixed character of the tokenizer (one constant, `.` `-` `( ) [ ] , | : ; # "`), in place of today's
two literal strings. `org` and `dir` register `+` instead of `@+` and `@-`: their handlers read the
marker as the tokens `@` and `+` or `-`. Everything else — `..`, `*`, `^`, the comparison symbols of
`conditional` — is registered as today. `ColonLabelHandler` recognises a label by its position in
place of today's "no number follows the colon": `NAME :` is a label when the token before `NAME` is
a statement end, an `EXPORT` after one, the start of the stream, or a token of another file or line
(the `.PUSH_CTX` an inclusion puts before a file's first line, the separator a repetition puts
between two bodies); `L1: NOP; L2: NOP` keeps both labels, `DATA:(WIDTH-1)` and `STATE:-1` are not
rewritten.

**Phase by phase.**

- *The lexer finds the files.* Phase 0 goes; the `Lexer` phase (`frontend/lexer`) reads the main
  file as tokens through the `Tokenizer` (today's `Lexer` class, characters to tokens), once per file
  as today, cached by path as the text is today, walks the lines of each placement as a cursor over
  token lines and, for every line that holds a directive word a scan handler is registered under,
  wherever the word stands in the line, calls the `IDependencyScanHandler` with the `DirectiveLine`;
  the handler checks that the directive begins a statement (`startsStatement`) as the preprocessor does. A handler that
  finds an `.IMPORT` or `.SOURCE` asks the context to read that file, which tokenizes it if it has
  not been, and scans its placement the same way, as `scanNestedModule` does today with text. The
  result is the dependency graph and the token streams of all files. `frontend/module` merges into
  `frontend/lexer`; the scan handlers of `conditional`, `importdir` and `source` lose their regexes
  and `Condition` keeps one parser; the branch logic (`ScanBlocks`) works on lines of tokens. The
  flag store stays, at first, where it is today: one per read-time phase in the state the feature
  keeps on the context, spanning the whole scan. When computing at read time comes (step 9), it
  becomes a class of the phase-free `frontend` root package, created by the core for each read-time
  phase and seeded from the configured flags, so that both phases start alike and pass the same
  `.DEFINE` and `.UNDEF` in the same order; `conditional` fills them and reports invalid configured
  names. Both contexts then offer `valueLength(tokens)`, the length of the one value at the start of
  the tokens, by the rule of what a computation is that the parser uses too, and `evaluate(tokens)`:
  parser and evaluator with a resolution over the store, `Partial` an error; the lexer's form reports nothing, the preprocessor's reports, as the
  scan reports nothing about conditionals today. `.DEFINE`, `.IFDEF`, `.REPEAT` and `^` call it in
  the preprocessor, the scan handlers of `.DEFINE` and `.IFDEF` in the lexer, so that a sign is read
  by the one parser everywhere. Three behaviours change on purpose, each with a test: `;` separates
  statements for the scan as it does for the preprocessor; `#` inside a string is no comment; a
  string over several lines is read whole.
- *Phase 3:* `Parser.expression()` stays the one place operands are read. Register, literal and
  name are read as today; a call, an index, a parenthesis, a typed literal whose value is one of
  these, and a bare `@` become the fourth operand form, `ExpressionNode(Expression, SourceInfo of the
  operand)`, which ends where its tree ends, as the tree's nodes carry their end; the bracketed part
  cut to the matching bracket and handed to the expression parser. `.PLACE` reads its literal with `expression()` first, as today, and then
  cuts its placements at its separators (`,`, `|`, `..`, `:`), outside brackets, with a method of
  `DirectiveLine`; `.ORG` and `.DIR` cut at `|`; each piece is read with `readOperand`, the
  reporting form of `operandOf`, which reports through the parser's diagnostics and ends the piece
  with an end-of-file token; `.ORG BODY_NW` is one piece whose value is a position.
  `MacroExpansionHandler` reads each argument in the forms of an operand, by the preprocessor
  context's `valueLength`: a minus before a value is its sign, and a vector is one argument.
- *Phase 4:* the analysis handlers of `instruction`, `proc`, `constdir`, `importdir` and the new
  ones of `org`, `dir` and `place` evaluate their operands through the bridge, with the registry they
  took at registration, check the kind against their slot with today's messages, and record the
  result per `ExpressionNode` in the symbol table (`recordResult`/`resultOf`, by node identity, the
  pattern of `registerNodeScope`); a definition records its value at its symbol. A handler says
  whether `Partial` is allowed: in a definition and in the arguments of a layout directive yes, in an
  instruction operand no; a label slot takes a label reference and rejects a layout value. A
  `.REQUIRE VALUE` whose binding is a computation with a `Partial` result records as its value its
  own qualified name with that kind — "my value lies under my name" — because the import line
  computes and records it; a binding that is a plain name or a value, a default and a configured
  value record what they are. `failOnErrors` after the phase keeps every later phase from seeing a
  rejected expression.
- *Phase 5:* `TokenMapGenerator.visit` gets a branch for `ExpressionNode` next to its branch for
  `IdentifierNode`: every `NameExpression` of the tree is classified as an `IdentifierNode` is, at the
  `SourceInfo` the node carries, and the notes `[=value]` are written at the ends of the expressions
  from the result phase 4 recorded, as the note rule in `compiler/expression` names them. The rule that nobody but the token map reads the kind of a
  symbol holds without a service, and the visualizer shows the value behind `WIDTH` inside
  `(WIDTH-1)` as it does behind a bare `WIDTH`.
- *Phase 6:* `AstPostProcessor.collectPass` replaces each `ExpressionNode` by its recorded result,
  through `resultOf`, as it replaces an `IdentifierNode` through `bindingOf`: a `LiteralNode`, or the
  `Partial` tree with qualified names. A name bound to a definition is replaced
  by what the chain of names ends at: a label's qualified name, a definition's value, or, when that
  value is `Partial`, the definition's qualified name, as a label is; `bindingOf` returns that name
  for such a definition. The post-process handler of `importdir` replaces the whole
  `ValueRequirementNode` by one that carries what the symbol table recorded for it — the self-name,
  or the tree of its default or configured value — through a method of the post-process context that
  reads a definition's recorded value; replacing the whole node, not its value slot, keeps the core's
  own replacement of the slot's name from overriding it, and a configured value reaches phase 6 that
  way although it is no child. `ImportNode`, `OrgNode`, `DirNode` and `PlaceNode` get
  `reconstructWithChildren`, so that the replacements reach them.
- *Phase 7:* `IrValue.Expression(Expression)` is the variant for a `Partial` in a directive
  argument; the note takes its place from the end the tree's nodes carry. `IrGenContext.convertValue(OperandNode)`
  maps a literal, a qualified name and an `ExpressionNode` to `IrValue`s; the directive converters
  call it. `convertOperand` treats an `ExpressionNode` as a defect. `const_value` and
  `importdir value` carry the `Partial` of a layout-valued definition or the self-name of a bound
  requirement; the import converter emits a directive at the import line carrying each binding that
  is still an `ExpressionNode` after phase 6, under the qualified name of the requirement it binds,
  built with `QualifiedNames.join` as the alias chain is. `PlacementListVal` and
  `model/ir/placement` go; `place` builds its placements from `ListVal` and `MapVal`. The backend
  reads `model/expression` and the root of `compiler/expression` and nothing of the frontend; the IR
  stays what a frontend of any syntax would produce.
- *Phase 9:* `LayoutContext` takes the operation registry from the compiler and keeps the named
  layout values — the cell of every label, recorded by the engine where it records label addresses
  today, and the value of every layout-valued definition, recorded by the feature's layout handler, a
  second record under one name being an error — and offers `evaluate(IrValue)`: it builds the
  evaluation context from the cursor and the origin the line counts from, resolves a label to the
  position of its cell, a definition to its recorded value, `@` to the cursor's position, a name not
  yet recorded to "Forward reference", and returns a position as a vector from the program's origin.
  The handlers of `org`, `dir` and `place` evaluate their arguments and add their base to a vector
  only, never to a position; the layout handlers of `constdir` and `importdir` evaluate a
  layout-valued definition where its directive stands and record the value, the import line's
  directive first, so that the requirement's line finds the value under its name and records nothing
  when its value is the self-name. A `.SOURCE`d file keeps the module's placement, so its labels are
  positions of the program like any other, while a vector or a `POSITION(v)` in it counts from the
  file's own origin, as the spec says today for `.ORG`. Errors are thrown at the node.
- *Emission and notes:* the emitter hands the named layout values of the `LayoutResult`,
  undeciphered, to the `EmissionContext`; the contributors of `constdir` and `importdir`, meeting a
  directive whose value is an `IrValue.Expression`, read the value under the definition's name there
  and write `constantValues` as they do today. The token-map generator writing `[=value]` at the ends
  the note rule names from the recorded results, `DebugInfo` carrying the notes; `LayoutContext.evaluate` writing a note for every
  value it computes, `LayoutResult` carrying them; the emitter merging the notes into the
  `SourceFile`s and, for a note inside a macro instance, into the `Expansion` its number names,
  equal notes kept once. Copies a `.REPEAT` makes carry no instance, so the notes of a repeated body
  share their positions: equal notes are kept once, different ones all shown. Proto and
  renderer are unchanged.
- *Configuration:* `operandOf` reads a configured value through the same operand reader and returns
  the operand or the reason it is none, the parser's first diagnostic; configured flags stay numbers.

**Rules and tests.** AGENTS.md: the shared types are `SourceInfo` and `Expression`; four operand
forms (register, literal, name, expression) and three IR operand forms (register, literal, label
reference); "One registry per phase" becomes "one registry per phase, and the operation registry,
which every phase that computes reads"; a handler uses the package of its phase, earlier phases
only as input, `model` and the phase-free packages; "Results, Not Side Channels" is the rule phase 6
follows by reading the symbol table; a phase uses only its own logic, from earlier phases only their
results, which are data, and logic that phases share lives outside the phases. `CompilerArchitectureRulesTest`: `compiler/expression..`
references no phase, and its root neither its sub-packages nor `model.token..`, `model.ast..` nor
`model.symbols..`; `model.expression..` references no other data format and nothing of
`compiler/expression`; `model.symbols..` references nothing of `compiler/expression`; a class
implementing a handler interface of phase *n* imports no package of a later phase; a class of a phase
package that another phase, or a handler of another phase, references is a record or an interface; the backend test
also covers every class implementing `IOperation` or `IOperationGroup`; `Expression`, `Value`,
`Kind`, the operation's result, the evaluator's result, the answer and the context join the
exceptions of the naming test and `IOperation` and `IOperationGroup` carry the prefix; the phase
lists lose `frontend.module`; the backend test forbids `compiler.expression.parser..` and
`compiler.expression.symbols..`. Documents: `ASSEMBLY_SPEC.md` gets a section "Compile-Time Expressions" of its own,
with sub-sections on values, operators, functions, names, where an expression stands, positions and
computing with flags; "Labels as Literals" goes, the directives
name an operand, `.ORG` names the space before the marker's sign; `COMPILER_IR_SPEC.md` lists
`Literal` and `Expression` and drops the placement types; `COMPILER_CORE_BOUNDARY.md` updates the
introduction of what the core holds (expressions join the symbol table and the instruction set, the
flag stores when step 9 moves them), the alphabet (`@` leaves it; `( ) [ ]` are fixed), the row of
`conditional` and the two places that say the core reads the defines only in `ProgramIdentity` (in
step 9 the core also seeds the flag stores, without giving the flags a meaning), the row of the labels
(the layout keeps the named layout values in its context, the emitter hands them on), the phases,
the probe (`expression` indispensable) and "what cannot be added" (a feature can now add an
operation; not a value kind, a level or a node kind); `CLI_USAGE.md` describes a computed `--value`.

## Consequences for programs and experiments

Every program under `assembly/` and in the tests compiles unchanged and to the same artifact,
with these exceptions, each of them a defect made visible: a number beyond 20 bits is an error
instead of a silent truncation; a label where a vector is expected is a type error instead of a
wrong cell; a number without a type that reaches an operand through a constant is an error, as the
written one is today; a `#` inside a string of an import path is part of the path. A program that
defined a name an operation has, or `@`, would be reported; none does. The default primordial
changes on purpose in step 12. Configurations keep working.
Flags change from `int` to `long` with step 2.

New for the programmer: computed constants, operands, places and repeat counts; typed constants in
`.PLACE` and vector constants in `.DIR`; places that a module can be given, hand on and export; the
cursor as a value; a module that owns its shell. New for the experiment: nothing changes at run
time; the compiler is exact where the runtime is tolerant, and the two never compute the same thing.

## Pitfalls

- **`WIDTH-1|HEIGHT-1` without parentheses** is no computation: outside parentheses a minus before
  a number is its sign, so the line holds more operands than the instruction takes, and the argument
  count reports it. Nothing is read silently as a different computation; precedence exists only
  inside parentheses.
- **`(BODY_NW - 1|1)` and `(@ + 0|2)`** read as `(BODY_NW - 1) | 1` and `(@ + 0) | 2` because `|` is
  the widest level, and report "Invalid operands for '-': position and number."; the vector is
  written `(BODY_NW - (1|1))`, `(@ + (0|2))`.
- **`(0|@+2)`** is `0 | (@ + 2)`, position plus number, an error; the cursor's row is `@[1]`:
  `(0|(@[1]+2))`. The syntax `.ORG 0|@+2` is unaffected.
- **`NW|5` with a vector constant `NW`** is an error, not a three-dimensional vector; the chain's
  signature wants numbers. That is the case that decided the chain notation.
- **`.ORG 0|0` and `.ORG BODY_NW`** are different places in a module whose importer hands it
  `BODY_NW` as `POSITION(0|0)`: a vector, and a `POSITION(v)` written in the module, count from the
  module's origin, while a position handed in is the place the importer named. In a `.SOURCE`d file
  a vector and a `POSITION(v)` count from the file's own origin, as `.ORG` does.
- **`EXPORT .CONST TOP (OUT_NW[1])`** is a number, the row of the corner relative to the module's
  origin; used in the main file it is that number and nothing else, as `.CONST ROWS 70` would be.
  `USING VALUE ROW (@[1])` is the cursor's row relative to the importer's origin, and in the module
  `ROW` is that number in the module's frame. The spec says what `P[i]` is relative to.
- **`OUT_NW[0]` is computed in the layout,** not in phase 4, because the component depends on the
  origin of the line; a `.PLACE` with it in a module imported below `0|0` is the test of step 8, as
  is `.CONST X (@ + (0|2))` used by `.ORG X` in such a module.
- **A `.ORG` to a position leaves the cursor there.** `.PLACE` moves no cursor, but each corner
  label needs a cell, so the `.ORG` before it does; after the last corner the cursor stands beside
  the north-west corner, inside the body. The behavior returns to its own origin with `.ORG 0|0`
  before `STEP`. `.POP_CTX` restores the base and the direction of the importer, not the cursor, so
  code placed after an import line continues from where the module left the cursor, as it does
  today after an included file.
- **Node identity.** Results are recorded per `ExpressionNode` instance; phase 6 walks the AST phase
  4 saw and looks up before it rebuilds, and phase 5 rebuilds nothing. A future phase between them
  that rebuilt the tree would lose the entries — the same constraint `getNodeScope` has today.
- **`IrValue.Expression` in a directive the layout evaluates** must be evaluated by its handler; a
  handler that forgets fails in its own type check. In `const_value` and `importdir value` it travels
  to the emitter on purpose, where the contributors read the value under the name.
- **`A - 1` outside parentheses** is two operands `A` and `-1`, because `-` is a prefix there; an
  argument count catches it in instructions, constants and macros.
- **Two flag stores** exist, one per read-time phase, as two `Flags` exist today; they agree because
  one parser reads `.DEFINE` and `.IFDEF` for both and both pass the directives in the same order.
  The assemblers that evaluate once have no scan before their preprocessor; folding phase 2 into the
  reading phase would be the way there, and is not part of this proposal.
- **The smallest 64-bit number** cannot be written as a literal, because its magnitude does not fit;
  C has the same edge.

## Implementation

Two pull requests. The first holds what stands without expressions and is worth having on its own;
the second holds the expressions. After every step `gw test` is green, `AssemblyProgramsCompileTest`
and `CompilerOutputEquivalenceTest` included; a step that changes the artifact on purpose
regenerates the reference and says so. Which steps are reviewed on their own after they are built,
the maintainer decides when the code exists.

### Pull request 1: prerequisites

1. **The label fix.** `InstructionAnalysisHandler`: a label at a `VECTOR` slot is a type error, an
   unknown name there is reported, a number without a type reached through a constant is reported
   as the written one is. `CallAnalysisHandler`: `VAL` takes a register, a literal or a vector
   (written or through a constant), `LVAL` requires the name to exist. `CallerMarshallingRule`:
   `PUSV` for a vector `VAL`. Spec: "Labels as Literals" removed, `SETV` takes `<Vector>`, `VAL`
   names the vector. Tests: `DataInstructionCompilerTest` (its two "no error" tests become
   "reported"), `EmissionCallerMarshallingTest`, `CallAnalysisHandlerTypeSafetyTest`,
   `SemanticAnalyzerTest`. Done when a label at a vector slot, a `VAL` of a label and an untyped
   constant are reported, and a vector `VAL` through a constant emits `PUSV`.
2. **One value type, 64 bits throughout.** `model/expression/Value` and `Kind` (with the type and
   position kinds unused yet); `LiteralNode` replaces `NumberLiteralNode`, `TypedLiteralNode`,
   `VectorLiteralNode`; `IrLiteral` replaces `IrImm`, `IrTypedImm`, `IrVec`; `IrValue.Literal`
   replaces `Int64` and `Vector`; `IntegerLiteral.parse` returns `OptionalLong`, the number token
   holds a `Long`; `OptionalInt` becomes `OptionalLong` in `CompilerOptions`, `DebugInfo`,
   `ProgramIdentity`, `Flags`, `Condition`, `DefineHandler`, `CompileCommand` and
   `SimulationEngine.readDefines`; `OperandEncoder` and `PlaceLayoutHandler` check the cell range
   through a new `IInstructionSet` method, the layout checks coordinates, `repeat` checks counts; the
   users in the features and the backend, about forty production and fifteen test files. Verify: `gw
   test`, `CompilerOutputEquivalenceTest` unchanged (the reference program sets no flag). Done when
   the three shapes exist once and flags are `long`.
3. **Phases share only data.** `CompilerArchitectureRulesTest` gets the rule that a class of a phase
   package which another phase, or a handler of another phase, references is a record or an interface
   (a phase context, seen only by its phase and its handlers, stays a class); AGENTS.md gets the
   sentence. The two places that break it today move: `ModuleContextTracker` (in the package of
   phase 0, used by phases 4, 5 and 6) and `ScopeTracker` (in the package of phase 4, used by phase 6)
   go to the `frontend` root next to `TreeWalker`; the linker returns a `LinkingResult` record (the
   linked program and the call-site bindings) in place of the frozen `LinkingContext` the emitter reads
   today, with the rules of `proc` seeing the context as before. Verify: `gw test`,
   `CompilerOutputEquivalenceTest` unchanged. Done when the rule holds without an exception.
4. **The lexer finds the files.** `Tokenizer` (today's `Lexer`; about thirty test files name the
   class), the new `Lexer` phase class on the bones of `DependencyScanner`, tokens cached by path,
   `frontend/module` merged into `frontend/lexer` (the trackers left it in step 3), `Compiler.java` with one phase in place of two,
   `IDependencyScanHandler` with a token line in place of a regex match and its six handlers, the
   scan handlers of `conditional`, `importdir` and `source` dispatched by directive word, `Condition`
   with one parser, `ScanBlocks` on token lines, `Flags` kept by the feature as today,
   `PHASE_CLASSES` and `PHASE_PACKAGES`, AGENTS.md and `COMPILER_CORE_BOUNDARY.md` with eleven
   phases. No computing yet. Tests: `DependencyScannerTest`, `ConditionalDependencyScanTest`,
   `ConditionalCompilationTest`, `LeftOutBranchesTest`, `ModulePlacementIntegrationTest`,
   `ImportPatternTest`, `LexerTest` for the renamed class, one test for each of the three changed
   behaviours, one for a `.DEFINE` before an `.IMPORT` seen in the imported file, one for a file
   imported twice reporting a tokenizer error once. Done when every program compiles to the same
   artifact and no regex reads an operand.

### Pull request 2: expressions

5. **The tokenizer and the label handler.** The fixed token types including `CALL_PARENTHESIS`; `-`
   always an operator token; `@` alone a name token; the name scan stops before `..`;
   `FeatureRegistry` rejects a symbol equal to a fixed character, read from the tokenizer's
   constant, and `@` leaves the alphabet; `org` and `dir` register `+` and read the marker as `@` and
   an operator; a sign before a number is read as two tokens by `Parser.expression()`,
   `PlaceDirectiveHandler`, `OrgDirectiveHandler`, `MacroExpansionHandler`, `ColonLabelHandler`, and,
   through a small helper of `frontend` that step 9 removes again, by `DefineHandler`, `Condition`,
   `RepeatDirectiveHandler`, `CaretDirectiveHandler` and the scan handler of `.DEFINE`;
   `ColonLabelHandler` recognises a label by its position as written above; `DirectiveLine` gets
   "next separator outside brackets"; `MacroExpansionHandler` groups arguments with the extended
   patterns; `place` stops registering `,`. Tests: `LexerTest` (the assertions on negative numbers
   and on `@+`/`@-`), `ParserTest`, `FeatureRegistryTest` (the symbol rules), `MacroExpansionTest`
   (typed, signed and vector arguments), `LayoutDirectiveTest` (the markers, `@ -3` with a space),
   `ConditionalExpansionTest` (`.DEFINE OFFSET -16`), a test for `(T:5)` and `STATE:-1`, one for a
   label as a module's first statement after `.PUSH_CTX`, one for `L1: NOP; L2: NOP`. Done when
   every program compiles unchanged and `gw test` is green.
6. **The expression machinery.** `model/expression` (the records), `compiler/expression` (evaluator,
   result, context, `IOperation`, `IOperationGroup`, registry, scale; `parser`; `symbols`),
   `features/expression` with the five groups, `operation(IOperation)` and `operations()` on the
   registration context and the checks the registry runs after all features registered, the reserved
   names handed to the symbol table and the flag stores, the new architecture tests, unit tests for
   each class, a test feature registering a function of its own, `StandardFeaturesTest` counting
   sixteen. Called by no phase yet. Done when the machinery is tested in isolation.
7. **Computing in operands and constants.** `Parser.expression()` with `ExpressionNode`, the typed
   form with a computed value and the bare `@`; the reporting `operandOf`; the analysis handlers
   taking the registry at registration, the label slot rejecting a layout value;
   `SymbolTable.recordResult`/`resultOf`, definition values with their kinds, and the qualified name
   `bindingOf` returns for a `Partial` definition after following the chain of names; `AstPostProcessor` replacing an `ExpressionNode` by its recorded result; the post-process
   context's definition-value method and the post-process handler of `importdir` (replacing the whole
   `ValueRequirementNode`); the token-map generator's branch for `ExpressionNode`; `reconstructWithChildren` on `ImportNode`, `OrgNode`, `DirNode`,
   `PlaceNode`; `convertValue`, `IrValue.Expression`, `OperandText` for a tree; the macro check
   against reserved names, for macro names and parameters; the messages. Tests: `ParserTest`,
   `SemanticAnalyzerTest`, `AstPostProcessorTest`, `IrGeneratorTest`, `ConstDirectiveTest`,
   `RequireDirectiveTest`, `ModuleValuesTest` (unchanged: a value bound to a label stays an alias, a
   value bound to a literal stays a value, the constant inside a computation keeps its token-map
   entry), `MacroDirectiveTest`, a test with `JMPI X` for a layout-valued `X`, a new test per
   message. Done when `SETI %DR0 DATA:(WIDTH+1)` and `.CONST AREA (W*H)` compile and every message
   of the list is produced by a test.
8. **Computing in the layout.** `.ORG`, `.DIR`, `.PLACE` cut and read operands and get analysis
   handlers; the placement nodes of `place` (`features/place/placement`) carry operands in place of
   numbers; the `Position` group and the evaluation context; `LayoutContext` taking the registry,
   with the named layout values and `evaluate`; the three layout handlers evaluate and add their base
   to vectors only; the layout handlers of `constdir` and `importdir`, the import line's directive
   and the self-name of a bound requirement; `place` on `ListVal`/`MapVal`, `model/ir/placement`
   deleted; `COMPILER_IR_SPEC.md`. Tests: `LayoutDirectiveTest`, `LayoutEngineTest`,
   `PlaceDirectiveExtensionTest`, `ModulePlacementIntegrationTest`, a test with a module imported
   below `0|0` that writes a shell around positions it is given and returns to its origin, one that
   hands a position down two levels, one that exports a position, one with a `.SOURCE`d file that
   uses a label, a given position and a vector `.ORG`, one with `.CONST X (@ + (0|2))` used by
   `.ORG X` in a module below `0|0`, one with `.CONST A B` for a layout-valued `B` and `.ORG A`, one
   with `USING VALUE X (@ + (0|2))`, one with a `.REQUIRE VALUE` default and one with a configured
   value that need the layout. Done when the import line above places the shell at the main file's
   origin.
9. **Computing at read time.** The flag store moves to the core: a class of `frontend`, one per
   read-time phase, seeded by the core, the reserved names handed to it; `evaluate(tokens)` on both
   read-time contexts with the registry from the compiler; `.DEFINE`, `.IFDEF`, `.REPEAT`, `^` and
   the scan handlers, the helper of step 5 removed; configured values through the operand reader;
   reporting against the configuration; `COMPILER_CORE_BOUNDARY.md` on the flag stores. Tests: the
   conditional tests, `RepeatDirectiveTest`, `ConditionalExpansionTest`, a test for `.IFDEF (A+1)`
   alone, computable and not. Done when `.REPEAT (ROWS*2)` expands.
10. **The visualizer.** The token-map generator writing notes, `LayoutContext.evaluate` writing notes, `LayoutResult` with notes and named values,
   the emission context carrying the named values, the contributors of `constdir` and `importdir`
   reading them, the emitter merging notes into source files and expansions. Tests: a token-map test
   for the notes, `EmissionIntegrationTest`, `ConstantValuesTest` for a layout-valued constant; a
   look in the browser. Done when `[=99|69]` stands behind the computation.
11. **Documents.** `ASSEMBLY_SPEC.md`, `COMPILER_IR_SPEC.md`, `COMPILER_CORE_BOUNDARY.md`,
    `CLI_USAGE.md`, AGENTS.md, under the documentation rules of AGENTS.md, the diff read by the
    maintainer before the commit.
12. **The brief for the primordial.** Carried out on the branch of the second pull request: the
    reproduction behavior owns the shell and the four corner labels, placed before its
    own code and followed by a vector `.ORG` back to its origin, `STEP REF RESULT` loads the corners
    itself (`LRLI %PLR0..3`), the main file starts its code at `1|0`, imports with
    `USING VALUE BODY_NW POSITION(0|0) VALUE BODY_SE POSITION(WIDTH-1|HEIGHT-1)` as its last line or
    sets its cursor after it, and keeps `.CONST WIDTH` and `.CONST HEIGHT`; the behavior contract
    (`assembly/lib/behavior/README.md`) gets "what the behavior places outside its extent, and the
    condition it puts on the main file"; the module header says the four corner cells have to be
    empty.

## Decisions recorded

The language and architecture rows above, and: `Value` as the one literal; the sign as an operator;
`long` throughout with the range checked where a value becomes a cell, a coordinate or a count;
`@` alone a name token and a predefined name; the reserved names; two flag stores, seeded by the
core from step 9 on; `IOperation` and `IOperationGroup` with the `I` prefix, `Expression`, `Value`,
`Kind` and the result types without it as sealed data families; the error-message convention; the
two annotation channels kept, by name where a value is the same everywhere, by position where it
depends on the place; token types written as whole words. Rejected with reason: every "not taken"
cell above; `SIZEOF`/`STARTOF`; a rotation function now; a `$` marker for flag names (parked, issue
#210); renaming "flag" (kept; "symbol" and "macro" clash with the project's words); "Lexing" as the
phase's name (phases are named after their class).

## Outcome

Implemented in the twelve steps above, the first four as the pull request of the prerequisites,
steps 5 to 12 on one branch, every step verified by the session before its commit. A full review
by four agents followed the last step; its findings were decided one by one with the maintainer.
What the built code does beyond the solution text:

- **Operands outside parentheses.** A typed literal takes a name, a call, an index or a parenthesis
  for its type or its number (`DATA:AREA`, `TYPE(UNIT):7`), and a vector takes them for any of its
  parts (`WIDTH|HEIGHT`, `1|(HEIGHT-1)`); only a molecule type with a number and a vector of numbers
  stay the literals of today. A macro argument is read in the same forms, so a vector is one
  argument and a minus before a value its sign.
- **Read time.** `.DEFINE`, `.IFDEF`, `.REPEAT` and `^` read one value, by the rule of what a
  computation is that the parser uses too; what follows it is reported ("Unexpected '-'."). A
  position in a computation with flags is reported ("A position cannot be used in a computation
  with flags."), and a flag name is noted wherever a directive reads it.
- **Positions in instruction arguments.** A position, and a number or a vector computed from one,
  is rejected in an instruction argument and as a `VAL` argument ("Argument 2 for instruction 'SETI'
  is computed from a position.", "Cannot pass 'D' as VAL argument: it is computed from a position.");
  a `CALL` target that is no name, written or computed, is of the wrong type. The reason stands in
  the row "Labels".
- **Messages.** "Invalid vector component: 'NW' is a vector." names the text and the kind of the
  part (a number, a typed value, a vector, a molecule type, a position); "Expected a number after
  '|'." comes from the parser. A configured value that is no operand carries the parser's reason
  ("Configured value 'W' = (1: expected ')'."), and every error of the configuration is printed
  without a file or line. A range of `.PLACE` with a step that is not positive, or an end below its
  start, is reported ("End 1 of a range must be at least its start 5."). A vector argument is
  checked against the world's dimensions where it becomes cells, and the emitter's own check reports
  an internal error.
- **Notes.** Every node of the tree carries its end, so a note stands right after the token it
  belongs to; the renderer no longer scans for the place. A typing shows notes only at its parts
  that compute.
- **The tokenizer.** A number with a base prefix ends before `..`, so a range can be written in
  hexadecimal (`0x1F..0x20`).
- **The post-processor.** Its replacements are keyed by node identity, as the symbol table's
  records are, so two equal nodes stay two places in the tree.
- **Documents.** The specification has a section "Compile-Time Expressions"; `.PLACE` counts its
  coordinates from the origin of the current file; a name in an expression stands for the cell its
  label stands on; the shift right instructions are described as arithmetic, as the runtime does.
- **Programs.** The reference program of the compiler's output-equivalence test computes in every
  form, with flags and values set as the configuration sets them, in a world of `128x128`. The
  default primordial's reproduction behavior owns the shell and the four corner labels
  (`NW_CORNER`, `NE_CORNER`, `SE_CORNER`, `SW_CORNER`), placed from the two positions the main file
  hands it; `STEP` loads the corners into location registers itself.
