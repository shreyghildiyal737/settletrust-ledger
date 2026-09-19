package com.settletrust.ledger;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * Where the integration tests get a Postgres.
 *
 * <p>Testcontainers is the default and what CI uses. The environment overrides exist
 * because a developer machine can have a perfectly healthy Docker daemon that
 * Testcontainers still cannot reach, and a suite that cannot be run is a suite nobody
 * runs. Set {@code LEDGER_TEST_JDBC_URL} and the suite uses that database instead.
 *
 * <p>One container is shared by every test class, started on first use. Tests isolate
 * themselves by generating their own account ids rather than by truncating tables, which
 * they could not do anyway: the append-only triggers forbid it.
 *
 * <p>{@link #isolated(String)} exists for the tests that cannot work that way. A
 * reconciliation check is a statement about the whole book, so it cannot be scoped to one
 * test's own ids, and every other test class's fixtures would be inside its answer. Those
 * tests get a schema to themselves instead.
 */
public final class TestDatabases {

    private static final String URL_OVERRIDE = "LEDGER_TEST_JDBC_URL";
    private static final String USER_OVERRIDE = "LEDGER_TEST_DB_USER";
    private static final String PASSWORD_OVERRIDE = "LEDGER_TEST_DB_PASSWORD";

    private static PostgreSQLContainer<?> container;

    private TestDatabases() {
    }

    public record Target(String url, String username, String password) {
    }

    /**
     * A target pointing at a freshly created, empty schema, for tests whose subject is
     * the state of the whole database rather than of rows they wrote themselves.
     *
     * <p>The generated jOOQ classes name no schema, so everything resolves through the
     * connection's {@code search_path} and the same code runs unchanged against it.
     *
     * @param label a short word identifying the owning test in the schema name
     */
    public static Target isolated(String label) {
        Target base = resolve();
        String schema = schemaName(label);

        try (Connection connection =
                     DriverManager.getConnection(base.url(), base.username(), base.password());
             Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not create test schema " + schema, failure);
        }

        String separator = base.url().contains("?") ? "&" : "?";
        return new Target(
                base.url() + separator + "currentSchema=" + schema,
                base.username(),
                base.password());
    }

    /** Drops a schema made by {@link #isolated(String)}, and everything a test put in it. */
    public static void drop(Target target) {
        String schema = schemaOf(target);
        Target base = resolve();

        try (Connection connection =
                     DriverManager.getConnection(base.url(), base.username(), base.password());
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        } catch (SQLException failure) {
            throw new IllegalStateException("could not drop test schema " + schema, failure);
        }
    }

    /**
     * Schema names are built here and never taken from a caller verbatim, because a
     * schema name cannot be a bind parameter: it has to be concatenated into the DDL, and
     * concatenating anything unvalidated into SQL is how injection happens even in a test
     * harness.
     */
    private static String schemaName(String label) {
        String cleaned = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("label must contain a letter or a digit");
        }
        return "test_" + cleaned + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String schemaOf(Target target) {
        int marker = target.url().indexOf("currentSchema=");
        if (marker < 0) {
            throw new IllegalArgumentException("not an isolated target: " + target.url());
        }
        String tail = target.url().substring(marker + "currentSchema=".length());
        int end = tail.indexOf('&');
        return end < 0 ? tail : tail.substring(0, end);
    }

    public static synchronized Target resolve() {
        String url = System.getenv(URL_OVERRIDE);
        if (url != null && !url.isBlank()) {
            return new Target(
                    url,
                    System.getenv().getOrDefault(USER_OVERRIDE, "postgres"),
                    System.getenv().getOrDefault(PASSWORD_OVERRIDE, "postgres"));
        }

        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("settletrust")
                    .withUsername("settletrust")
                    .withPassword("settletrust");
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        }
        return new Target(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
