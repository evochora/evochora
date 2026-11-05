package org.evochora.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The walk along an exception's causes: its links, the innermost cause, the outermost cause of a
 * type, and the one line a known failure is logged with, every message of the chain, nothing twice.
 */
@Tag("unit")
class CauseChainTest {

    @Test
    void joinsTheMessagesOutermostFirst() {
        Exception chain = new IllegalStateException("cannot start console",
                new IOException("bind failed", new RuntimeException("Address already in use")));

        assertThat(CauseChain.messages(chain))
                .isEqualTo("cannot start console; caused by: bind failed; caused by: Address already in use");
    }

    @Test
    void aCauseWithoutAMessageContributesItsClassName() {
        assertThat(CauseChain.messages(new IllegalStateException("outer", new NullPointerException())))
                .isEqualTo("outer; caused by: NullPointerException");
    }

    @Test
    void lineBreaksInAMessageBecomeSpaces() {
        Exception chain = new IllegalStateException("statement failed\nSELECT 1",
                new IOException("line one\r\nline two"));

        assertThat(CauseChain.messages(chain))
                .isEqualTo("statement failed SELECT 1; caused by: line one  line two")
                .doesNotContain("\n", "\r");
    }

    @Test
    void aWrapperThatRepeatsItsCauseIsNotDoubled() {
        IOException cause = new IOException("disk full");
        assertThat(CauseChain.messages(new RuntimeException("disk full", cause))).isEqualTo("disk full");
    }

    @Test
    void theLinksRunFromTheOutermostToTheInnermost() {
        IOException innermost = new IOException("innermost");
        RuntimeException middle = new RuntimeException("middle", innermost);
        IllegalStateException outer = new IllegalStateException("outer", middle);

        assertThat(CauseChain.links(outer)).containsExactly(outer, middle, innermost);
        assertThat(CauseChain.root(outer)).isSameAs(innermost);
        assertThat(CauseChain.root(innermost)).as("a failure without a cause is its own root").isSameAs(innermost);
    }

    /**
     * The outermost link of a type is found, not an inner one of the same type: a failure that was
     * given a reason on its way out carries the exception it explains as its own cause.
     */
    @Test
    void findReturnsTheOutermostLinkOfTheType() {
        IllegalArgumentException technical = new IllegalArgumentException("no enum constant 42");
        IllegalArgumentException explained = new IllegalArgumentException("unknown token type 42", technical);
        RuntimeException wrapper = new RuntimeException("failed to create instance", explained);

        assertThat(CauseChain.find(wrapper, IllegalArgumentException.class)).isSameAs(explained);
        assertThat(CauseChain.find(wrapper, IOException.class)).as("a type that does not occur").isNull();
    }

    /**
     * Java rejects a throwable given itself as its cause, but not a ring of two: the second is
     * constructed with the first as its cause, and the first is then given the second. Every walk
     * passes each link of the ring once and ends.
     */
    @Test
    // In its own thread: a walk that does not end would otherwise hang the suite rather than fail it,
    // because the default timeout is only read once the test returns.
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRingOfCausesIsWalkedOnce() {
        Exception first = new Exception("A");
        Exception second = new Exception("B", first);
        first.initCause(second);

        assertThat(CauseChain.links(first)).containsExactly(first, second);
        assertThat(CauseChain.root(first)).isSameAs(second);
        assertThat(CauseChain.find(first, IOException.class)).isNull();
        assertThat(CauseChain.messages(first)).isEqualTo("A; caused by: B");
    }
}
