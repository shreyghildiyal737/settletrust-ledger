package com.settletrust.ledger;

import org.testcontainers.containers.PostgreSQLContainer;

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
