package org.evochora.server.indexer;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MoleculeTypeUtils.
 * Tests the conversion of molecule type IDs to human-readable names.
 */
@Tag("unit")
class MoleculeTypeUtilsTest {

    @Test
    void testTypeIdToNameWithValidTypes() {
        assertEquals("CODE", MoleculeTypeUtils.typeIdToName(Config.TYPE_CODE));
        assertEquals("DATA", MoleculeTypeUtils.typeIdToName(Config.TYPE_DATA));
        assertEquals("ENERGY", MoleculeTypeUtils.typeIdToName(Config.TYPE_ENERGY));
        assertEquals("STRUCTURE", MoleculeTypeUtils.typeIdToName(Config.TYPE_STRUCTURE));
    }

    @Test
    void testTypeIdToNameWithUnknownType() {
        assertEquals("UNKNOWN", MoleculeTypeUtils.typeIdToName(999999));
        assertEquals("UNKNOWN", MoleculeTypeUtils.typeIdToName(-1));
        assertEquals("UNKNOWN", MoleculeTypeUtils.typeIdToName(0xFFFF0000));
    }

    @Test
    void testTypeIdToNameWithZero() {
        assertEquals("CODE", MoleculeTypeUtils.typeIdToName(0));
    }

    @Test
    void testTypeIdToNameWithLargeValues() {
        assertEquals("UNKNOWN", MoleculeTypeUtils.typeIdToName(Integer.MAX_VALUE));
        assertEquals("UNKNOWN", MoleculeTypeUtils.typeIdToName(Integer.MIN_VALUE));
    }
}
