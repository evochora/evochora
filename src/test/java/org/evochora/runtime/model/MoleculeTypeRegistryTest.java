package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for MoleculeTypeRegistry.
 * <p>
 * Tests the bidirectional conversion between molecule type integers and string names,
 * the registration order the registry exposes, and the completeness of the registration
 * against the type constants declared in {@link Config}.
 */
@Tag("unit")
class MoleculeTypeRegistryTest {

    /**
     * The {@code TYPE_}-prefixed fields of {@link Config} that describe the molecule encoding
     * rather than a molecule type. Every other {@code TYPE_} field names a type that must be
     * registered.
     */
    private static final Set<String> ENCODING_FIELDS = Set.of("TYPE_BITS", "TYPE_SHIFT", "TYPE_MASK");
    
    @Test
    void testTypeToNameWithValidTypes() {
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_CODE)).isEqualTo("CODE");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_DATA)).isEqualTo("DATA");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_ENERGY)).isEqualTo("ENERGY");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_STRUCTURE)).isEqualTo("STRUCTURE");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_LABEL)).isEqualTo("LABEL");
    }
    
    @Test
    void testTypeToNameWithUnknownType() {
        // Tolerant conversion: returns "UNKNOWN" for unrecognized types
        assertThat(MoleculeTypeRegistry.typeToName(999999)).isEqualTo("UNKNOWN");
        assertThat(MoleculeTypeRegistry.typeToName(-1)).isEqualTo("UNKNOWN");
        assertThat(MoleculeTypeRegistry.typeToName(0xFFFF0000)).isEqualTo("UNKNOWN");
    }
    
    @Test
    void testTypeToNameWithZero() {
        // Zero is TYPE_CODE (special case in Molecule.fromInt)
        assertThat(MoleculeTypeRegistry.typeToName(0)).isEqualTo("CODE");
    }
    
    @Test
    void testNameToTypeWithValidStrings() {
        assertThat(MoleculeTypeRegistry.nameToType("CODE")).isEqualTo(Config.TYPE_CODE);
        assertThat(MoleculeTypeRegistry.nameToType("DATA")).isEqualTo(Config.TYPE_DATA);
        assertThat(MoleculeTypeRegistry.nameToType("ENERGY")).isEqualTo(Config.TYPE_ENERGY);
        assertThat(MoleculeTypeRegistry.nameToType("STRUCTURE")).isEqualTo(Config.TYPE_STRUCTURE);
        assertThat(MoleculeTypeRegistry.nameToType("LABEL")).isEqualTo(Config.TYPE_LABEL);
    }
    
    @Test
    void testNameToTypeWithCaseInsensitive() {
        // Case-insensitive input
        assertThat(MoleculeTypeRegistry.nameToType("code")).isEqualTo(Config.TYPE_CODE);
        assertThat(MoleculeTypeRegistry.nameToType("CODE")).isEqualTo(Config.TYPE_CODE);
        assertThat(MoleculeTypeRegistry.nameToType("Code")).isEqualTo(Config.TYPE_CODE);
        assertThat(MoleculeTypeRegistry.nameToType("CoDe")).isEqualTo(Config.TYPE_CODE);
        
        assertThat(MoleculeTypeRegistry.nameToType("data")).isEqualTo(Config.TYPE_DATA);
        assertThat(MoleculeTypeRegistry.nameToType("DATA")).isEqualTo(Config.TYPE_DATA);
        assertThat(MoleculeTypeRegistry.nameToType("Data")).isEqualTo(Config.TYPE_DATA);
        
        assertThat(MoleculeTypeRegistry.nameToType("energy")).isEqualTo(Config.TYPE_ENERGY);
        assertThat(MoleculeTypeRegistry.nameToType("ENERGY")).isEqualTo(Config.TYPE_ENERGY);
        
        assertThat(MoleculeTypeRegistry.nameToType("structure")).isEqualTo(Config.TYPE_STRUCTURE);
        assertThat(MoleculeTypeRegistry.nameToType("STRUCTURE")).isEqualTo(Config.TYPE_STRUCTURE);

        assertThat(MoleculeTypeRegistry.nameToType("label")).isEqualTo(Config.TYPE_LABEL);
        assertThat(MoleculeTypeRegistry.nameToType("LABEL")).isEqualTo(Config.TYPE_LABEL);
    }
    
    @Test
    void testNameToTypeWithUnknownString() {
        // Strict conversion: throws exception for unknown types
        assertThatThrownBy(() -> MoleculeTypeRegistry.nameToType("FOOD"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown molecule type: 'FOOD'");
        
        assertThatThrownBy(() -> MoleculeTypeRegistry.nameToType("UNKNOWN"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown molecule type: 'UNKNOWN'");
    }
    
    @Test
    void testNameToTypeWithNull() {
        assertThatThrownBy(() -> MoleculeTypeRegistry.nameToType(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Molecule type name cannot be null or empty");
    }
    
    @Test
    void testNameToTypeWithEmptyString() {
        assertThatThrownBy(() -> MoleculeTypeRegistry.nameToType(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Molecule type name cannot be null or empty");
        
        assertThatThrownBy(() -> MoleculeTypeRegistry.nameToType("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Molecule type name cannot be null or empty");
    }
    
    @Test
    void testRoundTripConversion() {
        // Test that converting from type to name and back preserves the original value
        assertThat(MoleculeTypeRegistry.nameToType(MoleculeTypeRegistry.typeToName(Config.TYPE_CODE)))
            .isEqualTo(Config.TYPE_CODE);
        assertThat(MoleculeTypeRegistry.nameToType(MoleculeTypeRegistry.typeToName(Config.TYPE_DATA)))
            .isEqualTo(Config.TYPE_DATA);
        assertThat(MoleculeTypeRegistry.nameToType(MoleculeTypeRegistry.typeToName(Config.TYPE_ENERGY)))
            .isEqualTo(Config.TYPE_ENERGY);
        assertThat(MoleculeTypeRegistry.nameToType(MoleculeTypeRegistry.typeToName(Config.TYPE_STRUCTURE)))
            .isEqualTo(Config.TYPE_STRUCTURE);
        assertThat(MoleculeTypeRegistry.nameToType(MoleculeTypeRegistry.typeToName(Config.TYPE_LABEL)))
            .isEqualTo(Config.TYPE_LABEL);
    }
    
    @Test
    void testIsRegistered() {
        assertThat(MoleculeTypeRegistry.isRegistered(Config.TYPE_CODE)).isTrue();
        assertThat(MoleculeTypeRegistry.isRegistered(Config.TYPE_DATA)).isTrue();
        assertThat(MoleculeTypeRegistry.isRegistered(Config.TYPE_ENERGY)).isTrue();
        assertThat(MoleculeTypeRegistry.isRegistered(Config.TYPE_STRUCTURE)).isTrue();
        assertThat(MoleculeTypeRegistry.isRegistered(Config.TYPE_LABEL)).isTrue();

        assertThat(MoleculeTypeRegistry.isRegistered(999999)).isFalse();
        assertThat(MoleculeTypeRegistry.isRegistered(-1)).isFalse();
    }
    
    @Test
    void testTypeToNameOutputIsUppercase() {
        // All type names should be uppercase
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_CODE)).isEqualTo("CODE");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_DATA)).isEqualTo("DATA");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_ENERGY)).isEqualTo("ENERGY");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_STRUCTURE)).isEqualTo("STRUCTURE");
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_LABEL)).isEqualTo("LABEL");

        // Verify uppercase
        String codeName = MoleculeTypeRegistry.typeToName(Config.TYPE_CODE);
        assertThat(codeName).isEqualTo(codeName.toUpperCase());
    }
    
    @Test
    void testErrorMessageContainsValidTypes() {
        // Error message should list all valid types for helpful debugging
        assertThatThrownBy(() -> MoleculeTypeRegistry.nameToType("INVALID"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown molecule type: 'INVALID'")
            .hasMessageContaining("Valid types:");
    }

    @Test
    void testStateIsRegisteredAndResolvesInBothDirections() {
        assertThat(MoleculeTypeRegistry.isRegistered(Config.TYPE_STATE)).isTrue();
        assertThat(MoleculeTypeRegistry.typeToName(Config.TYPE_STATE)).isEqualTo("STATE");
        assertThat(MoleculeTypeRegistry.nameToType("STATE")).isEqualTo(Config.TYPE_STATE);
        assertThat(MoleculeTypeRegistry.nameToType("state")).isEqualTo(Config.TYPE_STATE);
    }

    @Test
    void testOrderedTypesAgreeWithCountAndIndex() {
        List<Integer> ordered = MoleculeTypeRegistry.orderedTypes();

        assertThat(ordered).hasSize(MoleculeTypeRegistry.typeCount());
        assertThat(ordered).doesNotHaveDuplicates();
        for (int index = 0; index < ordered.size(); index++) {
            assertThat(MoleculeTypeRegistry.indexOf(ordered.get(index))).isEqualTo(index);
            assertThat(MoleculeTypeRegistry.isRegistered(ordered.get(index))).isTrue();
        }
    }

    @Test
    void testIndexOfUnregisteredTypeIsMinusOne() {
        assertThat(MoleculeTypeRegistry.indexOf(999999)).isEqualTo(-1);
        assertThat(MoleculeTypeRegistry.indexOf(-1)).isEqualTo(-1);
    }

    @Test
    void testOrderedTypesIsUnmodifiable() {
        List<Integer> ordered = MoleculeTypeRegistry.orderedTypes();
        assertThatThrownBy(() -> ordered.add(Config.TYPE_DATA))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testEveryConfigTypeConstantIsRegistered() throws IllegalAccessException {
        List<String> unregistered = new ArrayList<>();
        int inspected = 0;
        for (Field field : Config.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            boolean isPublicStaticFinalInt = Modifier.isPublic(modifiers)
                && Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers)
                && field.getType() == int.class;
            if (!isPublicStaticFinalInt || !field.getName().startsWith("TYPE_")) {
                continue;
            }
            if (ENCODING_FIELDS.contains(field.getName())) {
                continue;
            }
            inspected++;
            if (!MoleculeTypeRegistry.isRegistered(field.getInt(null))) {
                unregistered.add(field.getName());
            }
        }

        assertThat(inspected).isEqualTo(MoleculeTypeRegistry.typeCount());
        assertThat(unregistered)
            .as("Config type constants missing a register(...) line in MoleculeTypeRegistry")
            .isEmpty();
    }
}
