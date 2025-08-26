package org.evochora.server;

import org.evochora.server.engine.UserLoadRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

class UserLoadRegistryTest {

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


