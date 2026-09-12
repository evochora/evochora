package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GenomeHasher.
 * <p>
 * Tests the genome hash computation for organisms, ensuring correct filtering
 * of molecule types and deterministic hash generation. Unless a test states otherwise it hashes
 * under {@link GenomeRule#DEFAULT_ENTRIES}, the rule a run uses without further configuration.
 */
@Tag("unit")
class GenomeHasherTest {

    private Environment env;
    private static final int ORGANISM_ID = 1;
    private static final int[] INITIAL_POSITION = new int[]{5, 5};

    /** The rule a run uses unless its configuration names other molecules. */
    private static final GenomeRule DEFAULT_RULE = new GenomeRule(GenomeRule.DEFAULT_ENTRIES);

    @BeforeEach
    void setUp() {
        // 32x32 environment
        env = new Environment(new int[]{32, 32}, false);
    }

    @Test
    void testEmptyGenome_returnsZero() {
        // No molecules owned by organism
        long hash = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        assertThat(hash).isEqualTo(0L);
    }

    @Test
    void testOnlyStateMolecules_returnsZero() {
        // Place only STATE molecules - they are the organism's own memory and carry no genome
        Molecule stateMol = new Molecule(Config.TYPE_STATE, 42, 0);
        env.setMolecule(stateMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(stateMol, ORGANISM_ID, new int[]{5, 6});

        long hash = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        assertThat(hash).isEqualTo(0L);
    }

    @Test
    void testCodeMolecule_returnsNonZero() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hash = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        assertThat(hash).isNotEqualTo(0L);
    }

    @Test
    void testAllRelevantTypesIncluded() {
        // Place one of each relevant type
        env.setMolecule(new Molecule(Config.TYPE_CODE, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashCode = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place LABEL
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashLabel = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place LABELREF
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashLabelRef = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place REGISTER
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_REGISTER, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashRegister = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place STRUCTURE
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashStructure = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place ENERGY
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_ENERGY, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashEnergy = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // All should produce non-zero hashes
        assertThat(hashCode).isNotEqualTo(0L);
        assertThat(hashLabel).isNotEqualTo(0L);
        assertThat(hashLabelRef).isNotEqualTo(0L);
        assertThat(hashRegister).isNotEqualTo(0L);
        assertThat(hashStructure).isNotEqualTo(0L);
        assertThat(hashEnergy).isNotEqualTo(0L);

        // Each type should produce a different hash (different type bits)
        assertThat(hashCode).isNotEqualTo(hashLabel);
        assertThat(hashCode).isNotEqualTo(hashLabelRef);
        assertThat(hashCode).isNotEqualTo(hashRegister);
        assertThat(hashCode).isNotEqualTo(hashStructure);
        assertThat(hashCode).isNotEqualTo(hashEnergy);
    }

    @Test
    void testStateMoleculesIgnored() {
        // Place CODE and STATE molecules
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule stateMol = new Molecule(Config.TYPE_STATE, 42, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(stateMol, ORGANISM_ID, new int[]{5, 6});

        long hashWithState = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place only CODE
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hashWithoutState = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Hashes should be the same - STATE is ignored
        assertThat(hashWithState).isEqualTo(hashWithoutState);
    }

    @Test
    void testDataMoleculesChangeTheHash() {
        // Place CODE and DATA molecules - DATA operands are part of the genome
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule dataMol = new Molecule(Config.TYPE_DATA, 42, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(dataMol, ORGANISM_ID, new int[]{5, 6});

        long hashWithData = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place only CODE
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hashWithoutData = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        assertThat(hashWithData).isNotEqualTo(hashWithoutData);

        // A different DATA value produces a different hash again
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_DATA, 43, 0), ORGANISM_ID, new int[]{5, 6});

        long hashWithOtherData = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        assertThat(hashWithOtherData).isNotEqualTo(hashWithData);
    }

    @Test
    void testSameGenomeDifferentAbsolutePosition_sameHash() {
        // Place CODE at (5,5) with initial position (5,5) -> relative (0,0)
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{5, 5}, DEFAULT_RULE);

        // Reset and place CODE at (10,10) with initial position (10,10) -> relative (0,0)
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{10, 10});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{10, 10}, DEFAULT_RULE);

        // Same relative position, same molecule -> same hash
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testDifferentRelativePosition_differentHash() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);

        // Place CODE at relative position (0,0)
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{5, 5}, DEFAULT_RULE);

        // Reset and place CODE at relative position (1,0)
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{6, 5});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{5, 5}, DEFAULT_RULE);

        // Different relative position -> different hash
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void testDifferentMoleculeValue_differentHash() {
        // Place CODE with value 10
        env.setMolecule(new Molecule(Config.TYPE_CODE, 10, 0), ORGANISM_ID, new int[]{5, 5});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place CODE with value 20
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_CODE, 20, 0), ORGANISM_ID, new int[]{5, 5});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Different value -> different hash
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void testHashIsDeterministic() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule labelMol = new Molecule(Config.TYPE_LABEL, 20, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(labelMol, ORGANISM_ID, new int[]{5, 6});

        // Compute hash multiple times
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        long hash3 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // All should be identical
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash2).isEqualTo(hash3);
    }

    @Test
    void hashIsLayoutInvariant() {
        // The same genome, wide enough to span several tiles, in a world stored row-major and
        // in one stored in production tiles: the owned set iterates differently, the hash must not.
        long[] hashes = new long[2];
        int[] tileSides = {1, Environment.TILE_SIDE};
        for (int i = 0; i < tileSides.length; i++) {
            Environment world = new Environment(new EnvironmentProperties(new int[]{64, 64}, true),
                    new org.evochora.runtime.label.PreExpandedHammingStrategy(), tileSides[i]);
            world.setMolecule(new Molecule(Config.TYPE_LABEL, 20, 0), ORGANISM_ID, new int[]{5, 5});
            for (int x = 6; x < 60; x += 7) {
                world.setMolecule(new Molecule(Config.TYPE_CODE, x, 0), ORGANISM_ID, new int[]{x, 5});
                world.setMolecule(new Molecule(Config.TYPE_REGISTER, x, 0), ORGANISM_ID, new int[]{x, 40});
            }
            hashes[i] = GenomeHasher.computeGenomeHash(world, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        }
        assertThat(hashes[0]).isNotZero();
        assertThat(hashes[1]).as("tile side 1 vs " + Environment.TILE_SIDE).isEqualTo(hashes[0]);
    }

    @Test
    void testMoleculeOrderDoesNotMatter() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule labelMol = new Molecule(Config.TYPE_LABEL, 20, 0);

        // Place in one order
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(labelMol, ORGANISM_ID, new int[]{5, 6});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place in reverse order
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(labelMol, ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Same molecules at same positions -> same hash regardless of insertion order
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testSameGenomeWithDifferentLabelNamespace_sameHash() {
        // Organism A: LABEL=100, LABELREF=105, CODE=42
        env.setMolecule(new Molecule(Config.TYPE_CODE, 42, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100, 0), ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 105, 0), ORGANISM_ID, new int[]{5, 7});
        long hashA = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Organism B: same genome but XOR-rewritten with mask=0x1234
        int mask = 0x1234;
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_CODE, 42, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100 ^ mask, 0), ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 105 ^ mask, 0), ORGANISM_ID, new int[]{5, 7});
        long hashB = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        assertThat(hashA).as("Same genome with different label namespace should have identical hash")
                .isEqualTo(hashB);
    }

    @Test
    void testMutatedLabelRef_differentHash() {
        // Original: LABEL=100, LABELREF=105
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 105, 0), ORGANISM_ID, new int[]{5, 6});
        long hashOriginal = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Mutated: LABEL=100, LABELREF=999 (individual mutation, not uniform XOR)
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 999, 0), ORGANISM_ID, new int[]{5, 6});
        long hashMutated = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        assertThat(hashOriginal).as("Mutated LABELREF should produce different hash")
                .isNotEqualTo(hashMutated);
    }

    @Test
    void testMultipleMasksProduceSameHash() {
        // Same genome with three different XOR masks should all produce the same hash
        int label1 = 500;
        int label2 = 700;
        int labelRef = 510;

        long[] hashes = new long[3];
        int[] masks = {0, 0x3A7F, 0x7FFFF};

        for (int i = 0; i < masks.length; i++) {
            env = new Environment(new int[]{32, 32}, false);
            env.setMolecule(new Molecule(Config.TYPE_LABEL, label1 ^ masks[i], 0), ORGANISM_ID, new int[]{5, 5});
            env.setMolecule(new Molecule(Config.TYPE_LABEL, label2 ^ masks[i], 0), ORGANISM_ID, new int[]{5, 6});
            env.setMolecule(new Molecule(Config.TYPE_LABELREF, labelRef ^ masks[i], 0), ORGANISM_ID, new int[]{5, 7});
            hashes[i] = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        }

        assertThat(hashes[0]).isEqualTo(hashes[1]);
        assertThat(hashes[1]).isEqualTo(hashes[2]);
    }

    @Test
    void testToroidalWrapping_sameGenomeDifferentSide_sameHash() {
        // Genome spans across the toroidal boundary. Parent and child have
        // identical code but one copy wraps across the world edge while
        // the other does not. The hash must be identical.
        // World size: 32x32 (even), so half-distance = 16.
        Environment toroidal = new Environment(new int[]{32, 32}, true);

        // Organism A: initialPosition [5,5], cells from [5,5] to [15,5] (distance 0..10)
        Molecule code1 = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule code2 = new Molecule(Config.TYPE_CODE, 20, 0);
        for (int x = 5; x <= 15; x++) {
            toroidal.setMolecule(x == 15 ? code2 : code1, ORGANISM_ID, new int[]{x, 5});
        }
        long hashA = GenomeHasher.computeGenomeHash(toroidal, ORGANISM_ID, new int[]{5, 5}, DEFAULT_RULE);

        // Organism B: initialPosition [27,5], cells from [27,5] to [5,5] (wrapping: 27..31,0..5)
        // Same relative layout but wraps across x=0 boundary.
        toroidal = new Environment(new int[]{32, 32}, true);
        int orgB = 2;
        for (int i = 0; i <= 10; i++) {
            int x = (27 + i) % 32; // 27,28,29,30,31,0,1,2,3,4,5
            toroidal.setMolecule(i == 10 ? code2 : code1, orgB, new int[]{x, 5});
        }
        long hashB = GenomeHasher.computeGenomeHash(toroidal, orgB, new int[]{27, 5}, DEFAULT_RULE);

        assertThat(hashA).as("Identical genome wrapping across toroidal boundary must produce same hash")
                .isEqualTo(hashB);
    }

    @Test
    void testToroidalWrapping_anchorLabelSelectedByRelativePosition() {
        // The anchor label must be chosen by relative position, not by the cell's absolute
        // position in either numbering. When a genome wraps across the boundary, that ordering differs
        // from the relative position ordering, which would pick a different anchor
        // and change the XOR normalization.
        Molecule code = new Molecule(Config.TYPE_CODE, 42, 0);
        Molecule label1 = new Molecule(Config.TYPE_LABEL, 100, 0);
        Molecule label2 = new Molecule(Config.TYPE_LABEL, 200, 0);
        Molecule labelRef = new Molecule(Config.TYPE_LABELREF, 150, 0);

        // Organism A: initialPos [2,0], genome at [2..6], labels at relPos +1 and +3
        Environment torA = new Environment(new int[]{32, 32}, true);
        torA.setMolecule(code, ORGANISM_ID, new int[]{2, 0});
        torA.setMolecule(label1, ORGANISM_ID, new int[]{3, 0});  // relPos +1
        torA.setMolecule(code, ORGANISM_ID, new int[]{4, 0});
        torA.setMolecule(label2, ORGANISM_ID, new int[]{5, 0});  // relPos +3
        torA.setMolecule(labelRef, ORGANISM_ID, new int[]{6, 0});
        long hashA = GenomeHasher.computeGenomeHash(torA, ORGANISM_ID, new int[]{2, 0}, DEFAULT_RULE);

        // Organism B: initialPos [30,0], same genome wrapping across x=0 of the 32-wide world
        // Cells at [30,31,0,1,2] — label1 at [31] (relPos +1), label2 at [1] (relPos +3)
        // In cell-index order label2 at [1] comes long before label1 at [31]; only the relative
        // position makes label1 the anchor, as in organism A.
        int orgB = 2;
        Environment torB = new Environment(new int[]{32, 32}, true);
        torB.setMolecule(code, orgB, new int[]{30, 0});
        torB.setMolecule(label1, orgB, new int[]{31, 0});   // relPos +1
        torB.setMolecule(code, orgB, new int[]{0, 0});
        torB.setMolecule(label2, orgB, new int[]{1, 0});    // relPos +3
        torB.setMolecule(labelRef, orgB, new int[]{2, 0});
        long hashB = GenomeHasher.computeGenomeHash(torB, orgB, new int[]{30, 0}, DEFAULT_RULE);

        assertThat(hashA).as("Anchor label must be selected by relative position, not flat index")
                .isEqualTo(hashB);
    }

    @Test
    void testOnlyOwnedMoleculesIncluded() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);

        // Place molecule owned by organism 1
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        // Place molecule owned by organism 2 at nearby position
        env.setMolecule(codeMol, 2, new int[]{5, 6});

        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Reset and place only the molecule owned by organism 1
        env = new Environment(new int[]{32, 32}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);

        // Other organism's molecules should not affect hash
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void differentRulesOverTheSameCellsGiveDifferentHashes() {
        // One cell set, three rules that disagree on which of its cells belong to the genome.
        env.setMolecule(new Molecule(Config.TYPE_CODE, 10, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_STATE, 42, 0), ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 100, 0), ORGANISM_ID, new int[]{5, 7});

        long underDefault = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        long underStateOnly = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION,
                new GenomeRule(List.of("STATE")));
        long underNothingExcluded = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION,
                new GenomeRule(List.of()));

        assertThat(underDefault).as("CODE only").isNotEqualTo(underStateOnly);
        assertThat(underStateOnly).as("CODE and the shell cell").isNotEqualTo(underNothingExcluded);
        assertThat(underDefault).as("CODE only vs every cell").isNotEqualTo(underNothingExcluded);
    }

    @Test
    void structureShellIsDroppedByTheDefaultRuleAndKeptWithoutIt() {
        // A body enclosed in a STRUCTURE:100 shell, as the primordial program builds it, and the
        // same body without the shell.
        Molecule shell = new Molecule(Config.TYPE_STRUCTURE, 100, 0);
        for (int x = 5; x <= 8; x++) {
            env.setMolecule(new Molecule(Config.TYPE_CODE, x, 0), ORGANISM_ID, new int[]{x, 5});
        }
        for (int x = 4; x <= 9; x++) {
            env.setMolecule(shell, ORGANISM_ID, new int[]{x, 4});
        }
        long withShell = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        long withShellStateOnly = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION,
                new GenomeRule(List.of("STATE")));

        env = new Environment(new int[]{32, 32}, false);
        for (int x = 5; x <= 8; x++) {
            env.setMolecule(new Molecule(Config.TYPE_CODE, x, 0), ORGANISM_ID, new int[]{x, 5});
        }
        long withoutShell = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION, DEFAULT_RULE);
        long withoutShellStateOnly = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION,
                new GenomeRule(List.of("STATE")));

        assertThat(withShell).as("the default rule leaves the shell out").isEqualTo(withoutShell);
        assertThat(withShellStateOnly).as("a rule that keeps the shell sees the difference")
                .isNotEqualTo(withoutShellStateOnly);
    }
}
