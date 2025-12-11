# Visualizer Load Tests

This directory contains k6 load test scripts for the Evochora Visualizer and Analyzer web interfaces.
These tests are intended for infrastructure validation and capacity planning of demo instances.

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) installed on your machine.

## Usage

Run the load test against a local instance (default `http://localhost:8080`):

```bash
k6 run web-frontend-load.js
```

Run against a specific target host:

```bash
k6 run -e TARGET_URL=https://<your-host> web-frontend-load.js
```

## Scenarios

The script simulates a typical user flow:
1. Loading simulation metadata
2. Fetching available tick ranges
3. Randomly viewing environment and organism data for specific ticks
4. Occasionally inspecting individual organism details (10% chance)
