Plugins
=======

The jars in this directory are on Evochora's classpath, after its own, so that a plugin adds
classes and replaces none. A plugin is a class against one of Evochora's plugin interfaces; the
configuration names it by its class name, and Evochora creates it at start. README.md in the
directory above, section "Extending the Platform", says how to build one.

A plugin that brings a library Evochora also has gets Evochora's version of it.
