# Primordial organisms

A primordial is a self-replicating EvoASM program that a simulation starts with. This directory
holds them side by side, so that a run can start several and so that a new one can begin as a copy
of an existing one. What a particular primordial does stands in the header of its `main.evo`.

## Layout

```
assembly/primordial/          <- the source root
  shared/                     <- modules more than one primordial uses
  <name>/                     <- one directory per primordial
    main.evo                  <- the program a simulation is pointed at
    lib/                      <- modules only this primordial uses
```

## Writing paths

The compiler resolves every path in `.IMPORT` and `.SOURCE` against the source root, never against
the file that names it. Write every path from `assembly/primordial`, even when the file sits right
beside the one importing it:

```
.IMPORT "shell-first/lib/energy.evo" AS ENERGY
```

## Starting one

A simulation names the program against the same root:

```hocon
compiler.source-roots = [ { path = "assembly/primordial" } ]
organisms = [
  { program = "shell-first/main.evo", initialEnergy = 100000, placement { positions = [1536, 576] } }
]
```

Give each of them room: a replicator needs three times its own extent in every direction to place
a child on any side.

## Starting a new one from an existing one

1. Copy a directory.
2. In its `main.evo`, change the import paths to the new directory's name. This is the step that is
   easy to forget: a path still naming the old directory keeps loading that primordial's modules,
   and the program compiles — with the wrong body.
3. Add an organism entry with `program = "<name>/main.evo"`.
