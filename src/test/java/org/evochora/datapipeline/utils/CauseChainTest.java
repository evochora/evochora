package org.evochora.datapipeline.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The one line a known failure is logged with: every message of the chain, nothing twice. */
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
}
