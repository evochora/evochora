# Contributing to the VS Code Extension

This guide is for contributors who want to modify the extension source code.

## Prerequisites

- Node.js and npm installed

## Building the Extension

After making changes to the extension source:

```bash
cd src
npm install
npm run build
```

The built `.vsix` file will be created in the parent directory as `evochora-syntax.vsix`.

## Updating the Version

1. Update the version in `src/extension/package.json`
2. Rebuild the extension
3. Rename the output file to include the version: `evochora-syntax-X.Y.Z.vsix`
4. Commit the new `.vsix` file

## Testing

Install the built extension in VS Code/Cursor:

```bash
code --install-extension evochora-syntax.vsix
```

Or use the "Install from VSIX..." command in the Extensions view.

## Source Structure

```
src/
├── extension/           # Extension source
│   ├── package.json     # Extension manifest
│   ├── language-configuration.json
│   └── syntaxes/
│       └── evochora.tmLanguage.json
├── package.json         # Build dependencies
└── package-lock.json
```


