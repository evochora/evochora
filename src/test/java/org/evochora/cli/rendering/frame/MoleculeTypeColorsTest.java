package org.evochora.cli.rendering.frame;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MoleculeTypeColors}.
 * <p>
 * The table is the only place the frame renderers take a molecule colour from, so a type the
 * registry knows and the table does not would be drawn as UNKNOWN without anything else noticing.
 */
@Tag("unit")
class MoleculeTypeColorsTest {

    @Test
    void holdsOneSlotPerRegisteredTypeAndOneForUnknown() {
        assertThat(MoleculeTypeColors.slotCount()).isEqualTo(MoleculeTypeRegistry.typeCount() + 1);
        assertThat(MoleculeTypeColors.unknownSlot()).isEqualTo(MoleculeTypeRegistry.typeCount());
    }

    @Test
    void everyRegisteredTypeHasItsOwnSlotAndAColour() {
        Set<Integer> slots = new HashSet<>();
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            int slot = MoleculeTypeColors.slotOf(type);
            assertThat(slot)
                    .as("slot of %s", MoleculeTypeRegistry.typeToName(type))
                    .isNotEqualTo(MoleculeTypeColors.unknownSlot());
            assertThat(slots.add(slot))
                    .as("slot of %s is not shared", MoleculeTypeRegistry.typeToName(type))
                    .isTrue();
            assertThat(MoleculeTypeColors.colorOf(type))
                    .as("colour of %s", MoleculeTypeRegistry.typeToName(type))
                    .isNotZero();
        }
        assertThat(slots).hasSize(MoleculeTypeRegistry.typeCount());
    }

    @Test
    void anUnregisteredTypeIsUnknownRatherThanTheEmptyBackground() {
        int unregistered = 0xFF << Config.TYPE_SHIFT;

        assertThat(MoleculeTypeColors.slotOf(unregistered)).isEqualTo(MoleculeTypeColors.unknownSlot());
        assertThat(MoleculeTypeColors.colorOf(unregistered))
                .isEqualTo(MoleculeTypeColors.colorOfSlot(MoleculeTypeColors.unknownSlot()))
                .isNotEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
    }

    @Test
    void keepsTheColoursTheRenderersDrewBeforeStateExisted() {
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_CODE)).isEqualTo(0x3c5078);
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_DATA)).isEqualTo(0x32323c);
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_ENERGY)).isEqualTo(0xffe664);
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_STRUCTURE)).isEqualTo(0xff7878);
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_LABEL)).isEqualTo(0xa0a0a8);
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_LABELREF)).isEqualTo(0xa0a0a8);
        assertThat(MoleculeTypeColors.colorOf(Config.TYPE_REGISTER)).isEqualTo(0x506080);
    }
}
