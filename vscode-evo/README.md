# Evochora Assembly - VS Code Extension

This directory contains the source code for the Evochora Assembly (`.evo`) syntax highlighting extension for Visual Studio Code and compatible editors like Cursor.

## How to Build

The extension can be built locally for testing or installation.

**Prerequisites:**
*   [Node.js and npm](https://nodejs.org/en/) must be installed.

**Steps:**

1.  **Install Dependencies:**
    Navigate to this directory (`vscode-evo`) in your terminal and run the following command to install the packaging tool (`vsce`):
    ```bash
    npm install
    ```

2.  **Build the Extension:**
    Run the build script to create the installable `.vsix` file:
    ```bash
    npm run build
    ```

The command will create a file named `evochora-syntax.vsix` inside this directory. This file can then be installed in VS Code/Cursor via `Extensions: Install from VSIX...`.
