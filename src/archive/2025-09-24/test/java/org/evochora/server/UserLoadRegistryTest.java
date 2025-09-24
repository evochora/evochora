package org.evochora.server;

import org.evochora.server.engine.UserLoadRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains unit tests for the {@link UserLoadRegistry}.
 * The UserLoadRegistry is a static class used to manage starting positions for user-loaded programs.
 * These tests are unit tests and do not require external resources.
 */
class UserLoadRegistryTest {

    /**
     * Verifies that the {@link UserLoadRegistry#clearAll()} method successfully removes
     * all registered program starting positions.
     * This is a unit test for the registry's state management.
     */
    @Test
    @Tag("unit")
    void clearAll_removes_all_registered_positions() {
        String pid = "prog-123";
        int[] pos = new int[]{1,2};
        UserLoadRegistry.registerDesiredStart(pid, pos);
        assertThat(UserLoadRegistry.getDesiredStart(pid)).containsExactly(1,2);

        UserLoadRegistry.clearAll();
        assertThat(UserLoadRegistry.getDesiredStart(pid)).isNull();
    }
}
