# Behaviors

A behavior is something an organism does — harvesting energy, reproducing — written so that a
main file can take it into its loop without knowing how it works. Each behavior has a directory
of its own, and each method of carrying it out is one module in it:

```
behavior/<behavior>/<method>.evo
```

This file is the contract every behavior keeps. The header of a behavior says only what fills
the contract in or departs from it.

## The entry

A behavior exports one procedure, `STEP`, whose first parameter is a reference to the result.
Further parameters follow when the behavior needs them:

```
EXPORT .PROC STEP REF RESULT ...
```

A call does one bounded step of work and returns, so that the main loop decides again after every
step. What a behavior has to remember from one step to the next it keeps in its static registers.

Before a step a behavior checks what the step needs to be carried out — the energy it costs, a
position it depends on. When that is missing it returns before acting on it, and leaves what it has
done so far for the next call to go on from.
When the step is worth taking is not the behavior's decision: the thresholds that choose between
behaviors stand in the main file.
A behavior whose gates need values it requires exports them, `EXPORT .REQUIRE VALUE`, so that a
gate reads the value the behavior received, as `<ALIAS>.<NAME>`. A state the gates need and only
the behavior knows, it hands back through a further reference parameter.

## The result

| Value | Meaning |
|---|---|
| `DATA:0` | The behavior acted; the main loop starts its round again. |
| any other value | By default, the behavior did not act; the main loop goes on to the next behavior. |

The main loop sets the result to `CODE:0` before every call, so a behavior that sets nothing
counts as not having acted. A behavior may give further values a meaning of its own — why it did
not act, say — as exported constants; the main loop may handle them and otherwise treats them as
not acted.

## What a behavior leaves behind

On every return:

- the marker register is 0,
- the active data pointer is DP0,
- the data and location stacks are as high as at the call,
- no global register has changed but those handed in by reference,

and every behavior finds the marker 0 and DP0 active when it is called.

The positions of the data pointers are free: a behavior may leave them anywhere, and none may
rely on finding a data pointer where it left it. A behavior that needs a position again keeps it
in a static location register.

## The header

The header of a behavior names what someone needs to take it into a main loop, and nothing else:

- the parameters after the result,
- the result values with a meaning of their own,
- the flags and values it reads, and how they depend on each other,
- what the position of a data pointer means after a return, where it means something,
- what the behavior requires of the main file,
- what it places outside its extent, in or around the body, and the condition that puts on the main file,
- its extent in the body, for each variant the flags select.

A behavior that a main file places last, so that its rows close the body, exports the rows of its
extent as `ROWS`.

## Names

A flag that concerns the whole organism has a plain name, such as `AGGRESSIVE`; a flag that
concerns one behavior only begins with the behavior's name, because a flag holds for the whole
compilation. A value a behavior requires needs no prefix, since it belongs to the behavior's
placement.

## Macros

A macro that only one behavior uses is defined in that behavior's module; it moves to
[`../include/`](../include/) once a second module needs it.
