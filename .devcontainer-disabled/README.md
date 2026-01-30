# Codespaces Support (Experimental)

> **Warning:** Codespaces is **not recommended** for Evochora. Use local installation instead.

## Known Limitations

1. **Resources:** Codespaces machines have limited RAM, CPU, and disk space. Evochora requires significant resources depending on your simulation configuration. You will likely encounter out-of-memory errors with default settings.

2. **Port Forwarding:** Internal links in the Evochora UI may not work correctly because Codespaces encodes ports into the URL differently than localhost. You may need to manually adjust URLs.

3. **Performance:** Cloud-based VMs are significantly slower than local hardware for I/O-intensive simulations.

## If You Still Want to Try

1. Select the **largest available machine** when creating the Codespace
2. Use a **minimal simulation configuration** (small environment, small batch sizes, etc.)
3. Be prepared to manually fix port numbers in URLs (change `:8081` to the Codespaces URL format)

## Recommended Alternative

For a proper experience, use local installation:

```bash
# Download release from GitHub Releases, or:
git clone https://github.com/evochora/evochora.git
cd evochora
./gradlew run --args="node run"
```

See the main [README.md](../README.md) for full instructions.
