package com.settletrust.ledger;

import java.sql.SQLException;

/**
 * Reading what Postgres actually said.
 *
 * <p>SQL state codes rather than message text, because the message is for a person and
 * changes with the server's locale and version, while {@code 23505} has meant "unique
 * violation" since the standard was written.
 */
public final class SqlErrors {

    private static final String UNIQUE_VIOLATION = "23505";

    private SqlErrors() {
    }

    /**
     * True if this failure, or anything that caused it, was a unique constraint violation.
     * The walk up the chain is needed because jOOQ and the pool each wrap the driver's
     * exception before it reaches us.
     */
    public static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
