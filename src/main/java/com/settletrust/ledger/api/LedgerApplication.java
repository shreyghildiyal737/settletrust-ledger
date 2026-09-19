package com.settletrust.ledger.api;

import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.Transfers;
import com.settletrust.ledger.invoice.PostgresInvoices;
import com.settletrust.ledger.reconciliation.PostgresReconciliationRuns;
import com.settletrust.ledger.reconciliation.Reconciler;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@EnableScheduling
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

    /**
     * Declared by its concrete type, not the {@link Transfers} port. The controller still
     * receives it as the port; settlement needs the implementation, because joining a
     * transfer to an outer transaction is something only the Postgres one can do.
     */
    @Bean
    PostgresTransferService transfers(DSLContext dsl, Clock clock) {
        return new PostgresTransferService(dsl, clock);
    }

    @Bean
    PostgresInvoices invoices(DSLContext dsl, Clock clock) {
        return new PostgresInvoices(dsl, clock);
    }

    @Bean
    PostgresReconciliationRuns reconciliationRuns(DSLContext dsl) {
        return new PostgresReconciliationRuns(dsl);
    }

    @Bean
    Reconciler reconciler(DSLContext dsl, Clock clock, PostgresReconciliationRuns runs) {
        return new Reconciler(dsl, clock, runs);
    }

    @Bean
    InvoiceSettlement settlement(
            DSLContext dsl,
            PostgresLedger ledger,
            PostgresInvoices invoices,
            PostgresTransferService transfers) {
        return new InvoiceSettlement(dsl, ledger, invoices, transfers);
    }
}
