#!/bin/bash
# =============================================================================
# Evochora Codespaces Setup Script
# Runs once after container creation
# =============================================================================

set -e

echo ""
echo "=============================================="
echo "  Setting up Evochora Development Environment"
echo "=============================================="
echo ""

# -----------------------------------------------------------------------------
# 1. Install EvoASM Syntax Highlighting Extension
# -----------------------------------------------------------------------------
echo "[1/3] Installing EvoASM syntax highlighting..."

VSIX_PATH="extensions/vscode/src/evochora-syntax.vsix"
if [ -f "$VSIX_PATH" ]; then
    code --install-extension "$VSIX_PATH" --force 2>/dev/null || true
    echo "      EvoASM syntax highlighting installed!"
else
    echo "      Warning: VSIX not found at $VSIX_PATH"
fi

# -----------------------------------------------------------------------------
# 2. Download Gradle Dependencies
# -----------------------------------------------------------------------------
echo ""
echo "[2/3] Downloading Gradle dependencies (this may take a few minutes)..."

./gradlew dependencies --no-daemon -q

echo "      Dependencies downloaded!"

# -----------------------------------------------------------------------------
# 3. Pre-compile for faster first run
# -----------------------------------------------------------------------------
echo ""
echo "[3/3] Pre-compiling project..."

./gradlew compileJava --no-daemon -q

echo "      Project compiled!"

# -----------------------------------------------------------------------------
# Done
# -----------------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  Setup complete!"
echo "=============================================="
echo ""
