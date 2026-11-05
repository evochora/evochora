package org.evochora.datapipeline.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The one place that tells a failure with a known cause from a possible defect. */
@Tag("unit")
class OperationalExceptionTest {

    @Test
    void aKnownCauseIsToldFromTheOperationalExceptionOnEvenUnderAWrapper() {
        RuntimeException wrapped = new RuntimeException("Indexing failed", new OperationalException(
            "database 'index' is unreachable", new java.sql.SQLException("Connection refused")));

        assertEquals("database 'index' is unreachable; caused by: Connection refused",
            OperationalException.reasonOf(wrapped));
    }

    @Test
    void aCheckedExceptionCaughtStraightFromACallIsAKnownCause() {
        assertEquals("disk unmounted", OperationalException.reasonOf(new IOException("disk unmounted")));
    }

    @Test
    void anUncheckedExceptionWithoutAKnownCauseMayBeADefect() {
        assertNull(OperationalException.reasonOf(new IllegalStateException("defect")));
        assertNull(OperationalException.reasonOf(new LinkageError("defect")));
    }

    /** Reflection wraps a defect in a constructor in a checked exception, which must not hide it. */
    @Test
    void aCheckedExceptionDeeperInTheChainDoesNotMakeADefectKnown() {
        RuntimeException startFailure = new RuntimeException("Failed to create an instance",
            new InvocationTargetException(new NullPointerException("defect")));

        assertNull(OperationalException.reasonOf(startFailure));
    }
}
