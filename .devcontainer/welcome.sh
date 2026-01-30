#!/bin/bash
# =============================================================================
# Evochora Welcome Script
# Runs every time a terminal is opened
# =============================================================================

# Colors
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m' # No Color

clear

echo ""
echo -e "${CYAN}${BOLD}"
cat << 'EOF'
  ███████╗██╗   ██╗ ██████╗  ██████╗██╗  ██╗ ██████╗ ██████╗  █████╗
  ██╔════╝██║   ██║██╔═══██╗██╔════╝██║  ██║██╔═══██╗██╔══██╗██╔══██╗
  █████╗  ██║   ██║██║   ██║██║     ███████║██║   ██║██████╔╝███████║
  ██╔══╝  ╚██╗ ██╔╝██║   ██║██║     ██╔══██║██║   ██║██╔══██╗██╔══██║
  ███████╗ ╚████╔╝ ╚██████╔╝╚██████╗██║  ██║╚██████╔╝██║  ██║██║  ██║
  ╚══════╝  ╚═══╝   ╚═════╝  ╚═════╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝
EOF
echo -e "${NC}"
echo -e "  ${DIM}Simulator for Foundational Artificial Life Research${NC}"
echo ""
echo -e "  ${GREEN}Welcome to your Evochora development environment!${NC}"
echo ""

# System info
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}System Resources${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Get system info
CPUS=$(nproc)
MEM_GB=$(free -g | awk '/^Mem:/{print $2}')
DISK_GB=$(df -BG / | awk 'NR==2 {gsub("G",""); print $4}')
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}')

echo -e "  CPUs:        ${CYAN}${CPUS} cores${NC}"
echo -e "  Memory:      ${CYAN}${MEM_GB} GB${NC}"
echo -e "  Disk Free:   ${CYAN}${DISK_GB} GB${NC}"
echo -e "  Java:        ${CYAN}${JAVA_VERSION}${NC}"
echo ""

# Resource warning
if [ "$MEM_GB" -lt 16 ]; then
    echo -e "  ${YELLOW}Warning: Less than 16GB RAM. Simulations may be slow.${NC}"
    echo -e "  ${DIM}Consider using a larger Codespaces machine (8-core/32GB recommended).${NC}"
    echo ""
fi

if [ "$DISK_GB" -lt 50 ]; then
    echo -e "  ${YELLOW}Warning: Less than 50GB disk space available.${NC}"
    echo -e "  ${DIM}Simulations generate ~10-20 GB per 100K ticks. Plan accordingly.${NC}"
    echo ""
fi

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Quick Start${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${GREEN}1.${NC} Start the simulation:"
echo ""
echo -e "     ${CYAN}./gradlew run --args=\"node run\"${NC}"
echo ""
echo -e "  ${GREEN}2.${NC} Open the Visualizer:"
echo ""
echo -e "     When you see \"Server started\", click the popup notification"
echo -e "     or go to the ${BOLD}PORTS${NC} tab and click on port ${CYAN}8081${NC}"
echo ""
echo -e "  ${GREEN}3.${NC} Stop the simulation:"
echo ""
echo -e "     Press ${CYAN}Ctrl+C${NC} in the terminal"
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Customization${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${BOLD}Configuration:${NC}     ${DIM}evochora.conf${NC}"
echo -e "  ${BOLD}Primordial Code:${NC}   ${DIM}assembly/primordial/main.evo${NC}"
echo -e "  ${BOLD}Documentation:${NC}     ${DIM}docs/ASSEMBLY_SPEC.md${NC}"
echo ""
echo -e "  Edit these files to experiment with different parameters and organisms."
echo -e "  The simulation auto-compiles assembly on startup."
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Important Notes${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${YELLOW}Disk Space:${NC} Codespaces has limited storage (~64GB max)."
echo -e "             This is suitable for ${BOLD}short test runs${NC} (< 500K ticks)."
echo -e "             For longer experiments, use local installation or a VM."
echo ""
echo -e "  ${YELLOW}Timeout:${NC}    Codespaces may stop after inactivity."
echo -e "             Your work is saved, but running simulations will stop."
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Useful Commands${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${CYAN}./gradlew run --args=\"node run\"${NC}     Start simulation"
echo -e "  ${CYAN}./gradlew run --args=\"--help\"${NC}       Show CLI help"
echo -e "  ${CYAN}./gradlew test${NC}                      Run all tests"
echo -e "  ${CYAN}./gradlew build${NC}                     Full build with tests"
echo ""
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Links${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${CYAN}Live Demo:${NC}      https://evochora.org"
echo -e "  ${CYAN}Documentation:${NC}  https://github.com/evochora/evochora#readme"
echo -e "  ${CYAN}Discord:${NC}        https://discord.gg/t9yEJc4MKX"
echo ""
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${GREEN}Ready to evolve some digital life? Let's go!${NC}"
echo ""
