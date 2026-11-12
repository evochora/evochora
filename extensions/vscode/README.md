# VS Code Extension for EvoASM

Syntax highlighting for EvoASM files (`.evo`) in Visual Studio Code and compatible editors such as Cursor.

## Installation

The extension is the `.vsix` file in this directory. Download it, or use the one in your copy of the repository.

1. Open the Command Palette with `Ctrl+Shift+P` (`Cmd+Shift+P` on macOS).
2. Run "Extensions: Install from VSIX...".
3. Select the `.vsix` file.

Alternatively, install it from the command line:

```bash
code --install-extension evochora-syntax-<version>.vsix
```

The editor lists the extension as "Evochora Assembly Syntax".

## What It Does

In `.evo` files, the extension highlights instructions, directives, registers, labels, literals with their molecule type, vectors, strings and comments. It also lets the editor comment lines out with `#` and close brackets and quotes automatically.

To change the extension, see [CONTRIBUTING.md](CONTRIBUTING.md).
