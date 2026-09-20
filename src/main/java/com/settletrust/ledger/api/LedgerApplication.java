package com.settletrust.ledger.api;

import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.Transfers;
import com.settletrust.ledger.chain.EscrowContractReserves;
import com.settletrust.ledger.chain.EscrowReserves;
import com.settletrust.ledger.chain.EscrowWatcher;
import com.settletrust.ledger.chain.EthereumChainSource;
import com.settletrust.ledger.chain.JsonRpc;
import com.settletrust.ledger.chain.PostgresChainObservations;
import com.settletrust.ledger.invoice.PostgresInvoices;
import com.settletrust.ledger.reconciliation.PostgresReconciliationRuns;
import com.settletrust.ledger.reconciliation.Reconciler;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

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

    /**
     * The node, when one is configured.
     *
     * <p>Every chain bean hangs off {@code ledger.chain.rpc-url} being set, and none of
     * them exists when it is not. That is deliberate rather than defensive: a deployment
     * with no chain still runs the bank rail, the invoice lifecycle and the reconciler,
     * and the reconciler reports {@code reservesChecked: false} so nobody reads its clean
     * verdict as having verified money on chain.
     *
     * <p>Closed by Spring on shutdown, which is why the type is returned rather than the
     * interface: the context closes an {@link AutoCloseable} bean it can see one on.
     */
    @Bean
    @ConditionalOnProperty("ledger.chain.rpc-url")
    JsonRpc chainRpc(@Value("${ledger.chain.rpc-url}") String rpcUrl) {
        return new JsonRpc(rpcUrl);
    }

    @Bean
    @ConditionalOnProperty("ledger.chain.rpc-url")
    EthereumChainSource chainSource(
            JsonRpc rpc,
            @Value("${ledger.chain.escrow-address}") String escrowAddress,
            @Value("${ledger.chain.currency}") String currency,
            @Value("${ledger.chain.deployed-at-block:0}") long deployedAtBlock,
            @Value("${ledger.chain.max-block-span:10000}") long maxBlockSpan) {
        return new EthereumChainSource(
                rpc, escrowAddress, currency, deployedAtBlock, maxBlockSpan);
    }

    @Bean
    @ConditionalOnProperty("ledger.chain.rpc-url")
    EscrowReserves escrowReserves(
            JsonRpc rpc,
            @Value("${ledger.chain.token-address}") String tokenAddress,
            @Value("${ledger.chain.escrow-address}") String escrowAddress,
            @Value("${ledger.chain.currency}") String currency) {
        return new EscrowContractReserves(rpc, tokenAddress, escrowAddress, currency);
    }

    /**
     * @param confirmations how deep a deposit must be before its money is credited. The
     *                      number is the whole risk position of the chain rail: too few
     *                      and a reorganisation takes back money already paid out, too
     *                      many and a buyer waits for an invoice that is already funded.
     */
    @Bean
    @ConditionalOnProperty("ledger.chain.rpc-url")
    EscrowWatcher escrowWatcher(
            DSLContext dsl,
            Clock clock,
            InvoiceSettlement settlement,
            @Value("${ledger.chain.confirmations:12}") int confirmations) {
        return new EscrowWatcher(
                dsl, new PostgresChainObservations(dsl, clock), settlement, confirmations);
    }

    /**
     * Takes a chain to ask about reserves if one is configured, and none otherwise. A
     * deployment without {@code ledger.chain.rpc-url} has no {@link EscrowReserves} bean,
     * and the reconciler records that its reports were produced without one rather than
     * letting them read as verified.
     */
    @Bean
    Reconciler reconciler(
            DSLContext dsl,
            Clock clock,
            PostgresReconciliationRuns runs,
            ObjectProvider<EscrowReserves> reserves,
            @Value("${ledger.reconciliation.deep-interval:PT24H}") Duration deepInterval) {
        return new Reconciler(dsl, clock, runs, reserves.getIfAvailable(), deepInterval);
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
