# Primordial organisms

A primordial is a self-replicating EvoASM program that a simulation starts with. This directory
holds them side by side, so that a run can start several and so that a new one can begin as a copy
of an existing one. What a particular primordial does stands in the header of its `main.evo`.

## Layout

```
assembly/primordial/          <- the source root
  <name>/                     <- one directory per primordial
    main.evo                  <- the program a simulation is pointed at
    lib/                      <- modules only this primordial uses
```

What more than one primordial uses lives in the [library](../lib/), a source root of its own. A
primordial built from it, such as `default`, consists of its `main.evo` alone.

## Writing paths

The compiler resolves every path in `.IMPORT` and `.SOURCE` against a source root, never against
the file that names it. Write a path into this directory from `assembly/primordial`, even when the
file sits right beside the one importing it, and a path into the library with its prefix `LIB`:

```
.IMPORT "shell-first/lib/energy.evo" AS ENERGY
.IMPORT "LIB:behavior/harvest/random-run.evo" AS HARVEST
```

## Starting one

A simulation names the program against the same root, and names the library's root as well:

```hocon
compiler.source-roots = [
  { path = "assembly/primordial" }
  { path = "assembly/lib", prefix = "LIB" }
]
organisms = [
  { program = "default/main.evo", initialEnergy = 100000, placement { positions = [1536, 576] } }
]
```

Give each of them room: a replicator needs three times its own extent in every direction to place
a child on any side.

## Starting a new one from an existing one

1. Copy a directory.
2. In its `main.evo`, change the import paths to the new directory's name. This is the step that is
   easy to forget: a path still naming the old directory keeps loading that primordial's modules,
   and the program compiles — with the wrong body. Paths into the library stay as they are.
3. Add an organism entry with `program = "<name>/main.evo"`.
