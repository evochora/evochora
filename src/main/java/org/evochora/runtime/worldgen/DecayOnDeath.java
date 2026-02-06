package org.evochora.runtime.worldgen;

import com.typesafe.config.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.DeathContext;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IRandomProvider;

/**
 * A death handler that replaces all molecules of a dying organism with a configured molecule.
 * <p>
 * When an organism dies, this handler iterates over all cells owned by the organism
 * and replaces non-empty molecules with the configured replacement molecule.
 * </p>
 *
 * <h2>Configuration</h2>
 * <pre>{@code
 * {
 *   className = "org.evochora.runtime.worldgen.DecayOnDeath"
 *   options {
 *     replacement = "ENERGY:100"
 *   }
 * }
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li>{@code "ENERGY:100"} - Convert dead organism to energy (nutrient recycling)</li>
 *   <li>{@code "CODE:0"} - Clear all molecules (cells become empty)</li>
 * </ul>
 *
 * <h2>Valid Types</h2>
 * CODE, DATA, ENERGY, STRUCTURE, LABEL, LABELREF
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>Empty cells are left unchanged</li>
 *   <li>Non-empty cells are replaced with the configured molecule</li>
 *   <li>After all handlers complete, ownership is automatically cleared by the simulation</li>
 * </ul>
 *
 * @see IDeathHandler
 */
public class DecayOnDeath implements IDeathHandler {

    private final Molecule replacementMolecule;

    /**
     * Creates a new decay handler with the specified configuration.
     *
     * @param randomProvider Source of randomness (unused, required by plugin interface)
     * @param config Configuration containing {@code replacement} in format "TYPE:VALUE"
     */
    public DecayOnDeath(IRandomProvider randomProvider, Config config) {
        this.replacementMolecule = parseMolecule(config.getString("replacement"));
    }

    /**
     * Test constructor for unit tests.
     *
     * @param replacementMolecule The molecule to replace dead organism's molecules with
     */
    DecayOnDeath(Molecule replacementMolecule) {
        this.replacementMolecule = replacementMolecule;
    }

    @Override
    public void onDeath(DeathContext ctx) {
        ctx.forEachOwnedCell(() -> {
            Molecule mol = ctx.getMolecule();
            if (!mol.isEmpty()) {
                ctx.setMolecule(replacementMolecule);
            }
        });
    }

    @Override
    public byte[] saveState() {
        // Stateless - no state to serialize
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
        // Stateless - nothing to restore
    }

    /**
     * Parses a molecule specification string in format "TYPE:VALUE".
     *
     * @param spec The molecule specification (e.g., "ENERGY:100", "CODE:0")
     * @return The parsed Molecule
     * @throws IllegalArgumentException if the format is invalid or type is unknown
     */
    private static Molecule parseMolecule(String spec) {
        String[] parts = spec.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Invalid molecule format: '" + spec + "'. Expected 'TYPE:VALUE' (e.g., 'ENERGY:100')");
        }

        String typeStr = parts[0].toUpperCase().trim();
        int value;
        try {
            value = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value in molecule spec: '" + spec + "'. Value must be an integer.");
        }

        int type = switch (typeStr) {
            case "CODE" -> org.evochora.runtime.Config.TYPE_CODE;
            case "DATA" -> org.evochora.runtime.Config.TYPE_DATA;
            case "ENERGY" -> org.evochora.runtime.Config.TYPE_ENERGY;
            case "STRUCTURE" -> org.evochora.runtime.Config.TYPE_STRUCTURE;
            case "LABEL" -> org.evochora.runtime.Config.TYPE_LABEL;
            case "LABELREF" -> org.evochora.runtime.Config.TYPE_LABELREF;
            default -> throw new IllegalArgumentException(
                "Unknown molecule type: '" + typeStr + "'. Valid types: CODE, DATA, ENERGY, STRUCTURE, LABEL, LABELREF");
        };

        return new Molecule(type, value);
    }
}
