package org.evochora.datapipeline.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A failure in the statement is a defect; a failure of the database or its surroundings is not. */
@Tag("unit")
class JdbcFailuresTest {

    @Test
    void aMalformedOrUnsupportedStatementIsTheStatementsFault() {
        assertTrue(JdbcFailures.isFaultOfTheStatement(new SQLSyntaxErrorException("Syntax error")));
        assertTrue(JdbcFailures.isFaultOfTheStatement(new SQLFeatureNotSupportedException("unsupported")));
    }

    @Test
    void aFailureOfTheDatabaseOrItsSurroundingsIsNot() {
        assertFalse(JdbcFailures.isFaultOfTheStatement(new SQLTransientConnectionException("pool timed out")));
        assertFalse(JdbcFailures.isFaultOfTheStatement(new SQLNonTransientConnectionException("refused")));
        assertFalse(JdbcFailures.isFaultOfTheStatement(new SQLException("Database may be already in use")));
    }
}
