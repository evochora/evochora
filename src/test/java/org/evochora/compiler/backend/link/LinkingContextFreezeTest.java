package org.evochora.compiler.backend.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class LinkingContextFreezeTest {

    private LinkingContext context;

    @BeforeEach
    void setUp() {
        context = new LinkingContext(null, null);
        context.pushAliasChain("ROOT");
        context.nextAddress();
        context.callSiteBindings().put(0, new HashMap<>(Map.of(1024, 1, 1025, 2)));
        context.freeze();
    }

    @Test
    void pushAliasChain_throwsAfterFreeze() {
        assertThatThrownBy(() -> context.pushAliasChain("MOD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void popAliasChain_throwsAfterFreeze() {
        assertThatThrownBy(() -> context.popAliasChain())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void nextAddress_throwsAfterFreeze() {
        assertThatThrownBy(() -> context.nextAddress())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void callSiteBindings_outerMapUnmodifiableAfterFreeze() {
        assertThatThrownBy(() -> context.callSiteBindings().put(99, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void callSiteBindings_innerMapUnmodifiableAfterFreeze() {
        assertThatThrownBy(() -> context.callSiteBindings().get(0).put(1026, 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void currentAddress_allowedAfterFreeze() {
        assertThat(context.currentAddress()).isEqualTo(1);
    }

    @Test
    void currentAliasChain_allowedAfterFreeze() {
        assertThat(context.currentAliasChain()).isEqualTo("ROOT");
    }

    @Test
    void callSiteBindings_readableAfterFreeze() {
        assertThat(context.callSiteBindings()).containsKey(0);
    }
}
