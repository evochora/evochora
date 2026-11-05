package org.evochora.datapipeline.utils;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;

/**
 * Tells a failure of a database call that lies in the statement itself from one that lies in the
 * database or its surroundings.
 * <p>
 * JDBC names the first kind by subclass: a statement the database rejects as malformed, or one
 * that asks for something the driver does not support, is a defect of the code that wrote it.
 * Every other {@link SQLException}, a connection that fails, a pool that times out, a locked file,
 * data that does not fit, has a cause outside the code.
 * <p>
 * The test reaches only as far as a driver uses the subclasses. H2 does; DuckDB reports what its
 * engine rejects, a syntax error included, as a plain {@code SQLException}, which this cannot
 * tell from a failure of the surroundings.
 */
public final class JdbcFailures {

    private JdbcFailures() {
    }

    /**
     * Whether the failure lies in the statement itself and is therefore a defect.
     *
     * @param failure the exception a database call failed with
     * @return {@code true} for a malformed or unsupported statement
     */
    public static boolean isFaultOfTheStatement(SQLException failure) {
        return failure instanceof SQLSyntaxErrorException || failure instanceof SQLFeatureNotSupportedException;
    }
}
