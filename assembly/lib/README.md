# Library

Code that organisms share, so that a primordial is written as one main file that picks what it
needs instead of carrying all of its logic itself. The library is a source root of its own with
the prefix `LIB`; every path into it, from a main file and from the library itself, is written
with that prefix:

```
.IMPORT "LIB:behavior/<behavior>/<method>.evo" AS <ALIAS>
```

```hocon
compiler.source-roots = [
  { path = "assembly/primordial" }
  { path = "assembly/lib", prefix = "LIB" }
]
```

| Directory | Holds |
|---|---|
| [`behavior/`](behavior/) | Behaviors a main loop calls |
| [`include/`](include/) | Everything else that behaviors and main files include |
