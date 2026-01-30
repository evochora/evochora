#!/bin/bash
# =============================================================================
# Evochora Welcome Script
# =============================================================================

# Colors
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

clear

echo ""
echo -e "${CYAN}${BOLD}"
echo "  Welcome to..."
echo ""
echo "  ■■■■■  ■   ■   ■■■    ■■■   ■   ■   ■■■   ■■■■     ■   "
echo "  ■      ■   ■  ■   ■  ■   ■  ■   ■  ■   ■  ■   ■   ■ ■  "
echo "  ■      ■   ■  ■   ■  ■      ■   ■  ■   ■  ■   ■  ■   ■ "
echo "  ■■■■    ■ ■   ■   ■  ■      ■■■■■  ■   ■  ■■■■   ■   ■ "
echo "  ■       ■ ■   ■   ■  ■      ■   ■  ■   ■  ■ ■    ■■■■■ "
echo "  ■       ■ ■   ■   ■  ■   ■  ■   ■  ■   ■  ■  ■   ■   ■ "
echo "  ■■■■■    ■     ■■■    ■■■   ■   ■   ■■■   ■   ■  ■   ■ "
echo -e "${NC}"
echo -e "  ${DIM}Simulator for Foundational Artificial Life Research${NC}"
echo ""

# System info
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}System Resources${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

CPUS=$(nproc)
MEM_GB=$(free -g | awk '/^Mem:/{print $2}')
DISK_GB=$(df -BG / | awk 'NR==2 {gsub("G",""); print $4}')
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}')

echo -e "  CPUs:        ${CYAN}${CPUS} cores${NC}"
echo -e "  Memory:      ${CYAN}${MEM_GB} GB${NC}"
echo -e "  Disk Free:   ${CYAN}${DISK_GB} GB${NC}"
echo -e "  Java:        ${CYAN}${JAVA_VERSION}${NC}"
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Quick Start${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${GREEN}1.${NC} Start the simulation:"
echo ""
echo -e "     ${CYAN}./gradlew run --args=\"node run\"${NC}"
echo ""
echo -e "  ${GREEN}2.${NC} Open the Visualizer:"
echo ""
echo -e "     Go to the ${BOLD}PORTS${NC} tab below and click the globe icon for port ${CYAN}8081${NC}"
echo ""
echo -e "  ${GREEN}3.${NC} Stop the simulation: Press ${CYAN}Ctrl+C${NC}"
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Customization${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${BOLD}Configuration:${NC}     ${DIM}evochora.conf${NC}"
echo -e "  ${BOLD}Primordial Code:${NC}   ${DIM}assembly/primordial/main.evo${NC}"
echo -e "  ${BOLD}Assembly Docs:${NC}     ${DIM}docs/ASSEMBLY_SPEC.md${NC}"
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Codespaces Limitations${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${YELLOW}Note:${NC} Codespaces has limited resources. Memory and disk requirements"
echo -e "  depend heavily on your simulation configuration (environment size,"
echo -e "  number of organisms, tick recording settings, etc.)."
echo ""
echo -e "  For longer or larger experiments, consider local installation."
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Useful Commands${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${CYAN}./gradlew run --args=\"node run\"${NC}     Start simulation"
echo -e "  ${CYAN}./gradlew run --args=\"--help\"${NC}       Show CLI help"
echo -e "  ${CYAN}./gradlew test${NC}                       Run all tests"
echo -e "  ${CYAN}./gradlew build${NC}                      Full build"
echo ""

echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${BOLD}Links${NC}"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "  ${CYAN}Live Demo:${NC}      https://evochora.org"
echo -e "  ${CYAN}Discord:${NC}        https://discord.gg/t9yEJc4MKX"
echo ""
