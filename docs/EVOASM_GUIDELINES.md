# EvoASM Guidelines

The [Assembly Specification](ASSEMBLY_SPEC.md) says what the language and every instruction do.
This document is about writing programs that hold up in the simulation: programs that leave
room for evolution, that act sensibly in a world other organisms act in too, whose procedures
can be relied on, and that can be checked. Each practice comes with its reason, so that a reader
who knows why it does not apply to their case can leave it out.

## Leave room for evolution

### Empty cells inside the body

Mutation adds code only where there is room: mutations that insert or duplicate code write
into runs of empty cells that lie inside the body, between the organism's own cells. A body with
no empty cells inside it can lose code and have code altered, but it can never gain any. `NOP`
padding is that room; `NOP^4` after an instruction leaves four empty cells behind it:

```
MAIN_LOOP:
  NRG %ER; NOP^4                          # read the energy, then room for four cells
  NTR %SR; NOP^4                          # read the entropy
```

An empty row between two rows of code serves the same purpose, and is long enough for a
duplicated block to land in. A relative `.ORG` starts the next row two cells below the current
one:

```
  .ORG ~0|~2
NEXT_SECTION:
  ...
```

Padding does not slow a program down, because the instruction pointer passes over empty cells
without spending a tick; what it costs is space, and the work of copying it when the organism
replicates its own body.

Pad between statements, never between a conditional and the instruction it guards. Put the
longer runs behind unconditional jumps, where new code breaks no path, and shorter ones into the
flow, where new code runs at once.

### Redundant jumps and returns

A mutation that lands on a jump or a return cuts the path that runs through it. A second jump
behind the first, and more than one return at the end of a procedure, keep the path open when
one of them is hit; a jump back into the main loop at the end of every row catches an
instruction pointer that has run off its row:

```
  JMPI MAIN_LOOP; NOP^12; JMPI MAIN_LOOP  # one jump survives a hit on the other
```

```
  NOP^8; RET; NOP^20; RET; NOP^4; RET     # the end of a procedure
```

### Who needs it

All of this is for programs meant to reproduce and evolve. A procedure written as a tool, or a
driver for an experiment, whose body no mutation operator will ever touch, does not need it.

## Expect the world to change under you

### Check that a step or a write happened

A program sees the world as it was when the tick began, and other organisms act in the same
tick. A write that loses out to another organism's write is retried by the machine on its own;
what the program has to handle is an instruction that fails for a reason of its own, a write
into a cell that turned out to be occupied, a step into a cell that is not the organism's to
enter. You can ask after a step or a write whether it happened, and decide what to do if not:

```
  SEEK %DIR                               # step forward
  INER                                    # if the step succeeded...
    JMPI NEXT_CELL                        # ...go on
  PEEK %TMP %DIR                          # otherwise take what is in the way, and pay for it
```

Test as close to the action as you can; the world may change in between. Write with `PPK` what
has to stand there whatever was in the way, with `POKE` what must destroy nothing.

### Decide about what is in the way

Decide explicitly what to do about a cell that is in the way, because the decision shapes what a
population becomes. A program that stops at a foreign cell never takes another organism's
molecules; a program that removes what is in the way, and pays the price of taking a foreign
molecule, gets through. The cell tests tell own, foreign, unowned and passable apart:

```
  IFFR %DIR                               # a foreign cell ahead?
    JMPI ABANDON                          # a defensive program stops here
  INPR %DIR                               # still not passable, so unowned but occupied?
    PEEK %TMP %DIR                        # remove it, paying for it
  POKE %VALUE %DIR                        # now the cell is free
```

One program can decide differently at different steps: the primordial organism that ships with
the repository clears its own body of anything that is not its own, but abandons a copy at the
first foreign cell in the way.

### The marker register and what a child inherits

Handle the marker register with care, because it decides what a child inherits: a fork hands on
the molecules that carry the marker the register holds at that moment. Set the register before
writing the child's body, reset it afterwards, and when a half-written copy is abandoned remove
its cells with `CMR`, so that they do not go to the next child.

### Energy

Check the energy before starting something expensive, a copy of the body above all, and pause
rather than die halfway; a failed instruction costs a penalty on top. Where a decision should be
a dial rather than a switch, write the threshold as a probability (`PGTI`, `PLTI`), which
evolution can turn.

## Know what a comparison compares

Equality compares the molecule, order compares the number. `IFR` is met only by the same type
with the same value, so it tells a `STRUCTURE:100` shell cell from a `CODE:100` instruction, and
`INR` is met by everything else, a different type included. `GTR`, `LTR` and their kin look at
the numbers alone: `ENERGY:5` is greater than `DATA:3`. A comparison that has to be sure of the
type therefore says so with a type test (`IFTR`, `INTR`) before it orders; `LETI %TMP ENERGY:0`
does not check that `%TMP` holds energy. `DATA` and `STATE` count as one type. You meet that when
a program keeps values in the world: a `DATA` value written with the marker register at 0 is
stored as `STATE`, comes back as `STATE` when it is read, and comparing it with `DATA` works as
expected:

```
  SETI %TMP DATA:5
  POKI %TMP 1|0                           # with marker 0 the cell now holds STATE:5
  SCNI %TMP 1|0                           # reads STATE:5
  IFI %TMP DATA:5                         # met: DATA and STATE compare as one type
```

## Make a procedure predictable

### What stays changed after a return

The machine looks after the procedure-local, formal and static registers across a call; everything
else a procedure touches is still changed when it returns, a data pointer it moved, the marker
register it set, a global register it wrote, what it pushed on a stack. Often that is the point
of the procedure. What matters is to know what it changes, to say it in its header, and to put
back on every exit path, the abandon paths included, what it did not mean to change: the
location stack keeps a data pointer, `GMR` and `SMR` keep the marker register. An abandon path
from the middle of a procedure takes off what was pushed up to there; a position needed only
briefly is better kept in a `%PLRx`. What the caller cannot see for itself, whether the work
was done at all, say, goes through a reference parameter:

```
  ADPI DATA:0
  DPLS                                    # remember where DP0 was
  GMR %SAVED                              # and the marker register
  ...
DONE:
  SETI RESULT DATA:1
  JMPI RESTORE
ABANDON:
  SETI RESULT DATA:0
RESTORE:
  SMR %SAVED                              # the marker register as it was
  SKLS                                    # DP0 where it was
  RET
```

### Positions instead of labels

Give a procedure a position rather than a label when the label may stand more than once in the
world, as it does once the program has copied a structure with labels in it; a label that
stands twice can resolve to either copy. Take the position into a location register before the
copy exists.

### Rows of code

Write a long procedure in rows, each begun by its own `.ORG` and ended by a jump to the next,
so that no row runs past the width of the world it is meant for; inside an imported module the
origin is relative to the place of the import:

```
  .ORG 0|2
FIND_EDGES:
  ...
  JMPI ORIENT
  .ORG 0|4
ORIENT:
  ...
```

## Check what a program does

The compiler checks syntax, layout and references; it cannot check what a program does. Run it
in a small world with nothing in it but the program, without energy sources and without
mutation, so that everything that happens is the program's doing. `assembly/example.conf` is
such a configuration; it starts the program named by its `example-dir` and pauses after a fixed
number of ticks:

```bash
bin/evochora --config assembly/example.conf node run
```

The node serves a web front end at `http://localhost:8081/visualizer/` that steps through the
run tick by tick and shows, for every organism, the instruction it executed, its registers and
stacks, the cells around it, and the source line the instruction came from; what went wrong can
be followed back to the line that caused it, and the world at the pause shows whether the
program did what it should.

## Write it so it can be read

The primordial organism that ships with the repository is the reference: a header per file and
per procedure that says what it does, what it assumes and what it leaves behind; register
aliases named for their role in each phase; sections introduced by a comment that names their
purpose; and a comment at the end of every line that says what the instruction is for, aligned
in a column. The name a module is imported under must not be the mnemonic of an instruction.
