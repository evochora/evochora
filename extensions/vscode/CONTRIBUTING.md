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

The build creates the file `evochora-syntax.vsix` in `src/`. Git ignores it there.

## Updating the Version

1. Update the version in `src/extension/package.json`
2. Rebuild the extension
3. Move the built file to this directory under a name that includes the version: `evochora-syntax-X.Y.Z.vsix`
4. Delete the `.vsix` file of the previous version
5. Commit both changes

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


