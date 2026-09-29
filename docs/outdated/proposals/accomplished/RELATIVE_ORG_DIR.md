# Relative `.ORG` and `.DIR`

**Status: ACCOMPLISHED — implemented on branch `feature/relative-org-dir`, together with the
assembly programs rewritten to use the relative form.**

## Problem

### 1. Absolute positions are maintained by hand

Every `.ORG` states an absolute coordinate, so the position of a routine is written down in the
directive rather than following from where the routine stands in the source. Inserting a block,
deleting one or moving one means recomputing the directives that follow it.

`assembly/primordial/lib/reproduce.evo` has 26 `.ORG` directives, one per section, at consecutive
even rows up to `0|58`; `lib/energy.evo` has 11. A section inserted at the top shifts all of them.

The same arithmetic is done across module boundaries. `main.evo` places the two imported modules
by hand:

```
.ORG 0|8
.IMPORT "lib/energy.evo" AS ENERGY
.ORG 0|31                            ; where energy.evo happens to end, plus a gap
.IMPORT "lib/reproduce.evo" AS REPRODUCE
.ORG 0|91                            ; where reproduce.evo happens to end, plus a gap
```

Writing this requires knowing how many rows each module occupies, and editing a module means
revisiting the file that imports it.

### 2. `.DIR` has only absolute direction vectors

`.DIR` takes a direction vector and nothing else. Changing the direction therefore means knowing
the current one and writing down the new one, where "turn by 90 degrees" is what is meant.

`.DIR` and the runtime's `TURN`/`TRNI`/`TRNS` are unused today because turning does not fit the
mutation plugins. They are kept for a later attempt, so `.DIR` gains the relative form together
with `.ORG`: a language in which one half of the layout can be written relative to the current
state and the other cannot teaches an exception that has no reason.

## Solution

### The relative marker `@+` and `@-`

The lexer reads `@+` and `@-` as one token each, the way it reads `..`. The sign is mandatory:

- `@` followed by neither `+` nor `-` stays a lexical error. The character is thereby free for a
  later meaning that does not start with a sign, such as `@NAME`.
- `+` on its own never becomes a token, so a plus sign does not become valid anywhere else. This
  keeps `.ORG 0|+2` an error rather than an absolute position that reads like a relative one.

The number after the marker carries no sign of its own, so both directions have the same shape:
marker, then an unsigned number. A negative number after a marker (`@+-2`) is rejected by the
directive handler.

Without a marker, values are absolute and mean exactly what they mean today, negative ones
included.

### `.ORG`: each component absolute or relative

Each component of the vector is independently absolute or relative:

```
.ORG 0|6            ; absolute: x = 0, y = 6
.ORG 0|@+2          ; x = 0 absolute, y two rows further
.ORG @+2|@-3        ; x two further, y three back
.ORG -3|0           ; absolute, negative: unchanged behaviour
```

The number of components must match the dimensionality of the world, as it does today.

**Relative to what.** A relative component is relative to the layout cursor: the cell the next
placed cell would occupy. After a row of code the cursor stands at the end of that row, so the
form that advances by rows keeps the column absolute, `.ORG 0|@+2`.

The cursor is not restored when an included file ends, and this is what makes the form useful
across module boundaries:

```
.IMPORT "lib/energy.evo" AS ENERGY
.ORG 0|@+2                           ; two rows below the module's last row
```

The writer of the importing file no longer needs to know how many rows the module occupies.

Precisely, `@+2` is two rows below the row in which the module placed its **last** cell, which is
its lowest row unless the module jumps back up at its end. A module that does leaves the cursor
higher, and the code that follows lands inside it; the layout's address-conflict check reports
that as an occupied cell, naming both positions.

### `.DIR`: absolute vector or rotation

**Absolute (unchanged):** a vector with one component per dimension sets the direction.

```
.DIR 1|0
.DIR 0|1|0
```

**Relative (new):** a rotation by 90 degrees in the plane spanned by two axes. The plane is always
named, in every dimensionality:

```
.DIR @+0|1          ; rotates axis 0 towards axis 1
.DIR @-0|1          ; rotates axis 1 towards axis 0
.DIR @+1|2          ; in the (1, 2) plane
```

`@+i|j` and `@-j|i` are the same rotation.

Naming the plane in two-dimensional worlds as well, where `0|1` is the only plane there is, keeps
one rule for every dimensionality and lets the same source be laid out in a world of another
dimensionality.

Applied to the direction vector, with all other components unchanged:

- `@+i|j`: `v[i], v[j] = -v[j], v[i]`
- `@-i|j`: `v[i], v[j] = v[j], -v[i]`

Starting from `1|0|0` in a three-dimensional world, `@+0|1` gives `0|1|0`, `@+0|2` gives `0|0|1`,
and `@-0|1` gives `0|-1|0`.

Rotating a direction that has no component in the named plane leaves it unchanged. That is a
consequence of the arithmetic, not an error.

### What the compiler rejects

The parser does not know the dimensionality of the world; it accepts both forms and the layout
phase, which has the world shape, validates:

- an axis index that is not a dimension of the world, or two equal axis indices;
- a rotation in a one-dimensional world, where no plane exists. Reversing the single axis is
  written as the absolute `.DIR -1`.

### Structure

`org` and `dir` stay separate features. What they share is the marker, which is two tokens and a
sign to remember; everything after that differs, because a position is a place and a direction is
an orientation. The few lines stand in both features rather than in a place both depend on.

The token types the lexer gains name the characters, not their meaning, as `STAR`, `DOT_DOT` and
`COMMA` do, which the `place` feature is the only reader of.

### Not part of this proposal

An earlier version of this document proposed a compile-time bounds check for non-toroidal grids.
That check belongs to the start of a simulation, not to the compiler: layout coordinates are
relative to the program origin, and one artifact can be placed at several start positions. It was
implemented there instead — an initial organism whose cells fall outside a bounded world, or onto
a cell that is not empty, stops the start with an error (commit `5cacf4b5`).

## Implementation Steps

Two steps. Step 2 depends on step 1 for the marker tokens.

### Step 1: Relative `.ORG`

**Lexer** (`frontend/lexer/`): `TokenType` gains `AT_PLUS` and `AT_MINUS`; `scanToken` gains a
case for `@` that looks at the next character, like the case for `.`, and reports a lexical error
when no sign follows.

**`OrgNode`** (`features/org/`): carries, besides the vector, one flag per component saying
whether it was marked. As a record it holds a `List<Boolean>`, not an array, so that two nodes
with the same components are equal.

**`OrgDirectiveHandler`**: parses the vector itself instead of asking the parser for an
expression, reading an optional marker before each component. A negative number after a marker is
reported as an error.

**`OrgNodeConverter`**: emits the flags alongside the position in the `core:org` directive args.

**`OrgLayoutHandler`**: computes the new position per component — a marked component is added to
the cursor, an unmarked one to the base position, which is what `.ORG` does today.

**Tests:**
- `.ORG 0|6` and `.ORG -3|0` place where they place today
- `.ORG 0|@+2` after a row of code starts a row two below it, whatever the row's length
- `.ORG @+2|@-3` from a known cursor position
- `.ORG 0|@+2` after `.SOURCE` and after `.IMPORT` continues below the included code
- `.ORG @+0|@+0` places where the cursor stands
- a marked component with a sign of its own (`@+-2`) is an error
- `@` without a sign is a lexical error
- a wrong number of components is an error

### Step 2: Relative `.DIR`

**`DirNode`** (`features/dir/`): carries either the absolute vector or a rotation with its sign
and its two axes.

**`DirDirectiveHandler`**: reads a marker followed by two axis indices, or a vector as before.

**`DirNodeConverter`**: emits either the direction or the rotation into the `core:dir` args.

**`DirLayoutHandler`**: applies the rotation to the current direction and reports the errors
listed under "What the compiler rejects".

**Tests:**
- `.DIR 0|1` and `.DIR -1|0` set the direction they set today
- 2D: `@+0|1` from `1|0` gives `0|1`; four of them return to the start
- 2D: `@-0|1` from `1|0` gives `0|-1`
- 3D: `@+0|1`, `@+0|2` and `@-0|1` from `1|0|0`
- `@+0|1` and `@-1|0` give the same direction
- a rotation in a plane the direction has no component in changes nothing
- equal axes, an axis index outside the world, and a rotation in a one-dimensional world are
  errors
- code placed after a rotation runs in the rotated direction

### Documentation that changes with the implementation

- `docs/ASSEMBLY_SPEC.md`: the `.ORG` and `.DIR` entries
- `docs/COMPILER_CORE_BOUNDARY.md`: the `org` and `dir` rows of the feature table
- `docs/COMPILER_IR_SPEC.md`: the args of `core:org` and `core:dir`

The reference program under `src/test/resources/org/evochora/compiler/reference/` uses every
feature of the compiler once, so it gains the new forms. Its checked-in artifact is regenerated
with them, and the pull request says so — `CompilerOutputEquivalenceTest` compares against it, and
the absolute forms have to compile to the same cells as before.
