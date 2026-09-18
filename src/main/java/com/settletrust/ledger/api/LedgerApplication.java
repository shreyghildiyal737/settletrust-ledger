package com.settletrust.ledger.api;

import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.Transfers;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * The application, and the only place the domain is wired to anything.
 *
 * <p>Spring lives at the edge of this service: HTTP in {@code api}, the beans below, and
 * nothing else. No class in {@code com.settletrust.ledger} carries a Spring annotation,
 * so the ledger's rules are testable without a context and would survive the edge being
 * replaced. Flyway and the connection pool are Spring Boot's to manage, because that is
 * the part it is genuinely good at.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }

    @Bean
    DSLContext dslContext(DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    /** Injected rather than called statically, so time is controllable in a test. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PostgresLedger ledger(DSLContext dsl, Clock clock) {
        return new PostgresLedger(dsl, clock);
    }

    @Bean
    Transfers transfers(DSLContext dsl, Clock clock) {
        return new PostgresTransferService(dsl, clock);
    }
}
