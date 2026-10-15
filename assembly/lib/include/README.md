# Includes

Files that behaviors and main files include and that are not behaviors themselves: modules,
brought in with `.IMPORT` or handed in with `.REQUIRE MODULE`, and text brought in with `.SOURCE`.
The header of each file says which of the two it is.

A macro comes here once a second module needs it; until then it stays in the module of its only
user. For a module, whether it comes here, and whether its users import it or have it handed in,
is decided for each one.
