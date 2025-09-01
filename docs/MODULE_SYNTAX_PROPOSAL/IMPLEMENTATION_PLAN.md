### **Objective**

This document outlines the necessary architectural changes to refactor the compiler's frontend. The primary goals are:

1. **Implement a Modern Module System:** Replace the current, ambiguous scoping and file inclusion directives with a robust system for defining and using modules, inspired by modern programming languages. This must support namespacing to prevent symbol collisions between different files.

2. **Generate Correct Debug Information:** Ensure that the debug information (tokenMap) correctly identifies qualified and aliased symbols by their original source text and maps them to a canonical representation, moving all resolution logic from the debugger into the compiler.

This is a breaking change that will replace the old syntax (.SCOPE, .REQUIRE, .INCLUDE) with a new, more powerful set of directives.


### **Key Architectural Concepts**

1. **Module as the Core Unit:** Each assembly file (.s) is a self-contained **module**. A module acts as a namespace for all symbols defined within it.

2. **Canonical Naming:** Every module is identified by a unique, canonical name (e.g., std.math), which is decoupled from its file path.

3. **Explicit Visibility:** Symbols within a module are **private by default**. They must be explicitly marked for export to be visible to other modules.

4. **Symbol Identity vs. Name:** The core of the new system is the separation of a symbol's name from its identity. Every symbol (procedure, label, etc.) will be assigned a **globally unique, immutable ID**. All later compiler phases and the debugger will refer to symbols by this ID, not by their potentially ambiguous names or aliases.


### **New Language Syntax**

|               |                             |                                                                                                                                                         |
| ------------- | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Directive** | **Example**                 | **Purpose**                                                                                                                                             |
| **.MODULE**   | .MODULE std.math            | Declares the canonical, globally unique name for the current file/module. Must appear once at the top of a file.                                        |
| **.EXPORT**   | .EXPORT my\_proc, my\_const | Makes the specified symbols public. All other symbols in the module remain private.                                                                     |
| **.IMPORT**   | .IMPORT std.math AS Math    | Imports a module and assigns it a local alias. All references to the module's exported symbols must be qualified with this alias (e.g., Math.my\_proc). |


### **Implementation Plan**

#### **Phase 1: Evolve Core Data Structures**

1. **Symbol Object:**

- Enhance the Symbol class to include a new field for a **unique, immutable Symbol ID** (e.g., a UUID generated upon creation).

- The Symbol should store the symbol's **non-qualified, canonical name** (e.g., add\_func).

2. **SymbolTable Object:**

- Redesign the internal storage to be **module-aware**. The primary data structure should be a nested map, mapping from a ModuleName (String) to an inner map of SymbolName (String) to the Symbol object.

- Implement a secondary lookup map that provides direct access to any Symbol via its unique **Symbol ID**.

- The SymbolTable's methods for adding and looking up symbols must be updated to operate within the context of the current module. It should correctly prevent duplicate definitions _within the same module_ but allow them across different modules.

3. **Debugger-Facing Data Structures (ProgramArtifact):**

- Modify the TokenInfo record. It must now store the **original source text** of the token (e.g., "Math.add\_func") and the resolved, canonical **Symbol ID** of the symbol it refers to.

- Add a new map to the ProgramArtifact: a SymbolMetadataMap. This map will serve as a glossary for the debugger, mapping a Symbol ID to a new object containing the symbol's canonical information (e.g., its non-qualified name, its type, its parent module's name, and any parameters).


#### **Phase 2: Update the Frontend**

1. **Parser:**

- Remove the logic for the old directives: .SCOPE, .REQUIRE, and .INCLUDE.

- Implement parsing logic for the new directives: .MODULE, .EXPORT, and .IMPORT. These should produce new, specific nodes in the Abstract Syntax Tree (AST), such as ModuleNode, ExportNode, and ImportNode.

- The parser's responsibility ends with creating a syntactically correct AST. It should **not** perform any name resolution.

2. **Semantic Analyzer:**

- This phase is now responsible for understanding the module system. It must be enhanced to manage the context of the current module being compiled.

- It must process the new AST nodes:

* **ModuleNode:** Sets the canonical name for the current compilation unit.

* **ExportNode:** Marks the corresponding symbols in the SymbolTable as public.

* **ImportNode:** Creates a local mapping from the alias (e.g., "Math") to the canonical module name ("std.math").

- When defining symbols (e.g., procedures), it must register them in the SymbolTable under the **current module's scope**.

- When resolving identifiers, it must handle both qualified names (e.g., "Math.add\_func") by using the import aliases to look in the correct module, and non-qualified names by searching first in the current module and then in the global scope.

3. **Token Map Generator:**

- This component will run _after_ the SemanticAnalyzer has successfully built the SymbolTable.

- When it traverses the AST and encounters an identifier (e.g., "Math.add\_func"), it will use the SemanticAnalyzer's resolution logic to find the corresponding Symbol object.

- It will then extract the unique **Symbol ID** from that object.

- Finally, it will create a TokenInfo entry containing both the original source text ("Math.add\_func") and the resolved canonical **Symbol ID**.


#### **Phase 3: Update Compiler Orchestration**

1. **Module Discovery:**

- The main compiler entry point must be updated to handle module discovery. It will accept one or more library search paths via configuration in config.jsonc

- Before compilation begins, it will perform a pre-scan of these paths to find all source files, read their .MODULE directives, and build an in-memory map of ModuleName -> FilePath.

2. **Compilation Flow:**

- When the SemanticAnalyzer encounters an .IMPORT std.math directive, the compiler will use the pre-scanned map to find the corresponding file and trigger its compilation if it hasn't been compiled already.

- The compiler will execute the phases in the correct order, ensuring the TokenMapGenerator runs after the SemanticAnalyzer has fully populated the SymbolTable.
