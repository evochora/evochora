# Assembly Compile System Usage

## Overview
The Evochora project includes a working assembly compilation system that allows AI assistants to compile and analyze assembly files.

## Usage

### Gradle Task (Recommended)
```bash
# Basic compilation (uses default 1000x1000:toroidal)
./gradlew compile -Pfile="path/to/assembly.s"

# With custom environment
./gradlew compile -Pfile="path/to/assembly.s" -Penv="2000x2000:flat"
./gradlew compile -Pfile="path/to/assembly.s" -Penv="1000x1000x100:toroidal"
```

### JAR (Alternative)
```bash
# Build the JAR first
./gradlew cliJar

# Then use it
java -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar compile path/to/assembly.s --env=2000x2000:flat
```

## Environment Parameters

### Syntax
- `--env=<dimensions>[:<toroidal>]`
- `dimensions`: World dimensions (e.g., `1000x1000` or `1000x1000x100`)
- `toroidal`: Optional, `toroidal` (default) or `flat`

### Examples
- `1000x1000` → 1000x1000 world, toroidal
- `2000x2000:flat` → 2000x2000 world, flat
- `1000x1000x100:toroidal` → 3D world, toroidal

## JSON Output

The compilation produces a JSON ProgramArtifact containing:

- **`programId`**: Unique identifier
- **`sources`**: Source file contents
- **`machineCodeLayout`**: Generated machine code (linear address → instruction)
- **`labelAddressToName`**: Label addresses and names
- **`registerAliasMap`**: Register aliases (e.g., `%COUNTER` → `0`)
- **`procNameToParamNames`**: Procedures and their parameters
- **`sourceMap`**: Source mapping for debugging
- **`tokenMap`**: Token information for syntax highlighting
- **`envProps`**: Environment properties (worldShape, isToroidal)

## Exit Codes
- `0`: Compilation successful
- `1`: Compilation error (syntax, semantic, etc.)
- `2`: System error (file not found, invalid parameters)

## For AI Assistants

This system allows AI assistants to:
1. **Compile assembly files** and get detailed JSON output
2. **Analyze machine code** generation
3. **Debug compilation issues** using source mapping
4. **Understand program structure** via labels and procedures
5. **Help with assembly programming** by providing compilation feedback

## Example Assembly Files

- **`example.s`** - Simple example demonstrating basic assembly syntax (recommended for testing)
- **`examples.s`** - Comprehensive example showing advanced features like procedures, loops, and world interaction

## Notes

- The system requires `Instruction.init()` to be called before compilation
- Environment properties are used for layout validation and wildcard expansion
- The compiler supports the full Evochora assembly language as documented in `ASSEMBLY_SPEC.md`
