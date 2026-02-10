package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GenomeHasher.
 * <p>
 * Tests the genome hash computation for organisms, ensuring correct filtering
 * of molecule types and deterministic hash generation.
 */
@Tag("unit")
class GenomeHasherTest {

    private Environment env;
    private static final int ORGANISM_ID = 1;
    private static final int[] INITIAL_POSITION = new int[]{5, 5};

    @BeforeEach
    void setUp() {
        // 20x20 environment
        env = new Environment(new int[]{20, 20}, false);
    }

    @Test
    void testEmptyGenome_returnsZero() {
        // No molecules owned by organism
        long hash = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);
        assertThat(hash).isEqualTo(0L);
    }

    @Test
    void testOnlyDataMolecules_returnsZero() {
        // Place only DATA molecules - should be excluded from genome
        Molecule dataMol = new Molecule(Config.TYPE_DATA, 42, 0);
        env.setMolecule(dataMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(dataMol, ORGANISM_ID, new int[]{5, 6});

        long hash = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);
        assertThat(hash).isEqualTo(0L);
    }

    @Test
    void testCodeMolecule_returnsNonZero() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hash = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);
        assertThat(hash).isNotEqualTo(0L);
    }

    @Test
    void testAllRelevantTypesIncluded() {
        // Place one of each relevant type
        env.setMolecule(new Molecule(Config.TYPE_CODE, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashCode = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place LABEL
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashLabel = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place LABELREF
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashLabelRef = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place REGISTER
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_REGISTER, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashRegister = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place STRUCTURE
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_STRUCTURE, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashStructure = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place ENERGY
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_ENERGY, 1, 0), ORGANISM_ID, new int[]{5, 5});
        long hashEnergy = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

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
    void testDataMoleculesIgnored() {
        // Place CODE and DATA molecules
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule dataMol = new Molecule(Config.TYPE_DATA, 42, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(dataMol, ORGANISM_ID, new int[]{5, 6});

        long hashWithData = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place only CODE
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hashWithoutData = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Hashes should be the same - DATA is ignored
        assertThat(hashWithData).isEqualTo(hashWithoutData);
    }

    @Test
    void testSameGenomeDifferentAbsolutePosition_sameHash() {
        // Place CODE at (5,5) with initial position (5,5) -> relative (0,0)
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{5, 5});

        // Reset and place CODE at (10,10) with initial position (10,10) -> relative (0,0)
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{10, 10});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{10, 10});

        // Same relative position, same molecule -> same hash
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testDifferentRelativePosition_differentHash() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);

        // Place CODE at relative position (0,0)
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{5, 5});

        // Reset and place CODE at relative position (1,0)
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{6, 5});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, new int[]{5, 5});

        // Different relative position -> different hash
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void testDifferentMoleculeValue_differentHash() {
        // Place CODE with value 10
        env.setMolecule(new Molecule(Config.TYPE_CODE, 10, 0), ORGANISM_ID, new int[]{5, 5});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place CODE with value 20
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_CODE, 20, 0), ORGANISM_ID, new int[]{5, 5});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

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
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);
        long hash3 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // All should be identical
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash2).isEqualTo(hash3);
    }

    @Test
    void testMoleculeOrderDoesNotMatter() {
        Molecule codeMol = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule labelMol = new Molecule(Config.TYPE_LABEL, 20, 0);

        // Place in one order
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(labelMol, ORGANISM_ID, new int[]{5, 6});
        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place in reverse order
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(labelMol, ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});
        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Same molecules at same positions -> same hash regardless of insertion order
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testSameGenomeWithDifferentLabelNamespace_sameHash() {
        // Organism A: LABEL=100, LABELREF=105, CODE=42
        env.setMolecule(new Molecule(Config.TYPE_CODE, 42, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100, 0), ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 105, 0), ORGANISM_ID, new int[]{5, 7});
        long hashA = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Organism B: same genome but XOR-rewritten with mask=0x1234
        int mask = 0x1234;
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_CODE, 42, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100 ^ mask, 0), ORGANISM_ID, new int[]{5, 6});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 105 ^ mask, 0), ORGANISM_ID, new int[]{5, 7});
        long hashB = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        assertThat(hashA).as("Same genome with different label namespace should have identical hash")
                .isEqualTo(hashB);
    }

    @Test
    void testMutatedLabelRef_differentHash() {
        // Original: LABEL=100, LABELREF=105
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 105, 0), ORGANISM_ID, new int[]{5, 6});
        long hashOriginal = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Mutated: LABEL=100, LABELREF=999 (individual mutation, not uniform XOR)
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 100, 0), ORGANISM_ID, new int[]{5, 5});
        env.setMolecule(new Molecule(Config.TYPE_LABELREF, 999, 0), ORGANISM_ID, new int[]{5, 6});
        long hashMutated = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

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
            env = new Environment(new int[]{20, 20}, false);
            env.setMolecule(new Molecule(Config.TYPE_LABEL, label1 ^ masks[i], 0), ORGANISM_ID, new int[]{5, 5});
            env.setMolecule(new Molecule(Config.TYPE_LABEL, label2 ^ masks[i], 0), ORGANISM_ID, new int[]{5, 6});
            env.setMolecule(new Molecule(Config.TYPE_LABELREF, labelRef ^ masks[i], 0), ORGANISM_ID, new int[]{5, 7});
            hashes[i] = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);
        }

        assertThat(hashes[0]).isEqualTo(hashes[1]);
        assertThat(hashes[1]).isEqualTo(hashes[2]);
    }

    @Test
    void testToroidalWrapping_sameGenomeDifferentSide_sameHash() {
        // Genome spans across the toroidal boundary. Parent and child have
        // identical code but one copy wraps across the world edge while
        // the other does not. The hash must be identical.
        // World size: 20x20 (even), so half-distance = 10.
        Environment toroidal = new Environment(new int[]{20, 20}, true);

        // Organism A: initialPosition [5,5], cells from [5,5] to [15,5] (distance 0..10)
        Molecule code1 = new Molecule(Config.TYPE_CODE, 10, 0);
        Molecule code2 = new Molecule(Config.TYPE_CODE, 20, 0);
        for (int x = 5; x <= 15; x++) {
            toroidal.setMolecule(x == 15 ? code2 : code1, ORGANISM_ID, new int[]{x, 5});
        }
        long hashA = GenomeHasher.computeGenomeHash(toroidal, ORGANISM_ID, new int[]{5, 5});

        // Organism B: initialPosition [15,5], cells from [15,5] to [5,5] (wrapping: 15..19,0..5)
        // Same relative layout but wraps across x=0 boundary.
        toroidal = new Environment(new int[]{20, 20}, true);
        int orgB = 2;
        for (int i = 0; i <= 10; i++) {
            int x = (15 + i) % 20; // 15,16,17,18,19,0,1,2,3,4,5
            toroidal.setMolecule(i == 10 ? code2 : code1, orgB, new int[]{x, 5});
        }
        long hashB = GenomeHasher.computeGenomeHash(toroidal, orgB, new int[]{15, 5});

        assertThat(hashA).as("Identical genome wrapping across toroidal boundary must produce same hash")
                .isEqualTo(hashB);
    }

    @Test
    void testToroidalWrapping_anchorLabelSelectedByRelativePosition() {
        // The anchor label must be chosen by relative position, not flat index.
        // When a genome wraps across the boundary, the flat index ordering differs
        // from the relative position ordering, which would pick a different anchor
        // and change the XOR normalization.
        Molecule code = new Molecule(Config.TYPE_CODE, 42, 0);
        Molecule label1 = new Molecule(Config.TYPE_LABEL, 100, 0);
        Molecule label2 = new Molecule(Config.TYPE_LABEL, 200, 0);
        Molecule labelRef = new Molecule(Config.TYPE_LABELREF, 150, 0);

        // Organism A: initialPos [2,0], genome at [2..6], labels at relPos +1 and +3
        Environment torA = new Environment(new int[]{20, 20}, true);
        torA.setMolecule(code, ORGANISM_ID, new int[]{2, 0});
        torA.setMolecule(label1, ORGANISM_ID, new int[]{3, 0});  // relPos +1
        torA.setMolecule(code, ORGANISM_ID, new int[]{4, 0});
        torA.setMolecule(label2, ORGANISM_ID, new int[]{5, 0});  // relPos +3
        torA.setMolecule(labelRef, ORGANISM_ID, new int[]{6, 0});
        long hashA = GenomeHasher.computeGenomeHash(torA, ORGANISM_ID, new int[]{2, 0});

        // Organism B: initialPos [18,0], same genome wrapping across x=0
        // Cells at [18,19,0,1,2] — label1 at [19] (relPos +1), label2 at [1] (relPos +3)
        // Flat index of [1] = 1*20+0 = 20, flat index of [19] = 19*20+0 = 380
        // Without fix: anchor = label2 at flatIndex 20 (wrong!), with fix: anchor = label1 at relPos +1
        int orgB = 2;
        Environment torB = new Environment(new int[]{20, 20}, true);
        torB.setMolecule(code, orgB, new int[]{18, 0});
        torB.setMolecule(label1, orgB, new int[]{19, 0});   // relPos +1
        torB.setMolecule(code, orgB, new int[]{0, 0});
        torB.setMolecule(label2, orgB, new int[]{1, 0});    // relPos +3
        torB.setMolecule(labelRef, orgB, new int[]{2, 0});
        long hashB = GenomeHasher.computeGenomeHash(torB, orgB, new int[]{18, 0});

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

        long hash1 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Reset and place only the molecule owned by organism 1
        env = new Environment(new int[]{20, 20}, false);
        env.setMolecule(codeMol, ORGANISM_ID, new int[]{5, 5});

        long hash2 = GenomeHasher.computeGenomeHash(env, ORGANISM_ID, INITIAL_POSITION);

        // Other organism's molecules should not affect hash
        assertThat(hash1).isEqualTo(hash2);
    }
}
