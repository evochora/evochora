# Assembly Compile System Usage

## Overview
The Evochora project includes a working assembly compilation system that allows AI assistants to compile and analyze assembly files.

## Usage

The compiler can be invoked in three equivalent ways:

### Option 1: Gradle Task (convenient for quick compilation)
```bash
# Basic compilation (uses default 100x100:toroidal)
./gradlew compile -Pfile="path/to/assembly.evo"

# With custom environment
./gradlew compile -Pfile="path/to/assembly.evo" -Penv="2000x2000:flat"
./gradlew compile -Pfile="path/to/assembly.evo" -Penv="1000x1000x100:toroidal"
```

### Option 2: Gradle run with args (flexible)
```bash
# Basic compilation
./gradlew run --args="compile --file=path/to/assembly.evo"

# With custom environment
./gradlew run --args="compile --file=path/to/assembly.evo" --env="2000x2000:flat"
./gradlew run --args="compile --file=path/to/assembly.evo" --env="1000x1000x100:toroidal"
```

### Option 3: JAR (standalone, no Gradle required)
```bash
# Build the JAR first
./gradlew jar

# Then use it
java -jar build/libs/evochora.jar compile --file=path/to/assembly.evo
java -jar build/libs/evochora.jar compile --file=path/to/assembly.evo" --env=2000x2000:flat
```

## Environment Parameters

### Syntax
- `--env=<dimensions>[:<toroidal>]`
- `dimensions`: World dimensions (e.g., `1000x1000` or `1000x1000x100`)
- `toroidal`: Optional, `toroidal` (default) or `flat`

### Examples
- `100x100` → 100x100 world, toroidal (default)
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

- **`assembly/examples/simple.evo`**  - Simple example demonstrating basic assembly syntax (recommended for testing)
- **`assembly/examples/complex.evo`** - Comprehensive example showing advanced features like procedures, loops, and world interaction

## Notes

- The system requires `Instruction.init()` to be called before compilation
- Environment properties are used for layout validation and wildcard expansion
- The compiler supports the full Evochora assembly language as documented in `ASSEMBLY_SPEC.md`
