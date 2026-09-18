package com.settletrust.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;

/**
 * The connection pool, the migrations and the jOOQ context, created together because a
 * pool handed out before the schema exists is a pool that will be used too early.
 *
 * <p>Migrations run on construction rather than by hand. The same {@code db/migration}
 * scripts are also what jOOQ generates its classes from at build time, so the schema has
 * one definition and a column renamed there breaks compilation rather than production.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource pool;
    private final DSLContext dsl;

    public Database(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("settletrust-ledger");
        // Each transfer holds a row lock for the length of its transaction, so a caller
        // waiting on a busy account should wait on the lock rather than on the pool.
        config.setMaximumPoolSize(16);
        config.setAutoCommit(true);

        this.pool = new HikariDataSource(config);
        migrate(pool);
        this.dsl = DSL.using(pool, SQLDialect.POSTGRES);
    }

    private static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public void close() {
        pool.close();
    }
}
