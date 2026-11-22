Plugins
=======

The jars in this directory are on Evochora's classpath, after its own, so that a plugin adds
classes and replaces none. A plugin is a class against one of Evochora's plugin interfaces; the
configuration names it by its class name, and Evochora creates it at start. How to build one
is described in the user guide, docs/USER_GUIDE.md, in the chapter "Extend Evochora with
Plugins".

A plugin that brings a library Evochora also has gets Evochora's version of it.
