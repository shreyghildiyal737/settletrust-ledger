package com.settletrust.ledger.api;

import com.settletrust.ledger.Account;
import com.settletrust.ledger.Entry;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.Transfer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * What crosses the wire. Deliberately separate from the domain types: an HTTP contract
 * changes for reasons that have nothing to do with the ledger, and the two should be able
 * to change independently.
 *
 * <p>Amounts are minor units in JSON as well, named {@code amountMinor} so that no client
 * can mistake 2500 for twenty-five hundred euro.
 */
final class LedgerDtos {

    private LedgerDtos() {
    }

    record OpenAccountRequest(
            @NotBlank(message = "id is required")
            String id,

            @NotBlank(message = "currency is required")
            @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter code")
            String currency,

            @Pattern(regexp = "CUSTOMER|HOUSE", message = "kind must be CUSTOMER or HOUSE")
            String kind) {

        Account.Kind resolvedKind() {
            return kind == null || kind.isBlank()
                    ? Account.Kind.CUSTOMER
                    : Account.Kind.valueOf(kind);
        }
    }

    record TransferRequest(
            @NotBlank(message = "from is required")
            String from,

            @NotBlank(message = "to is required")
            String to,

            long amountMinor,

            @NotBlank(message = "currency is required")
            @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter code")
            String currency) {
    }

    record AccountView(String id, String currency, String kind, long balanceMinor) {

        static AccountView of(Account account, Money balance) {
            return new AccountView(
                    account.id().value(),
                    account.currency(),
                    account.kind().name(),
                    balance.minorUnits());
        }
    }

    record EntryView(
            UUID entryId,
            UUID transferId,
            String accountId,
            long amountMinor,
            String currency,
            Instant recordedAt) {

        static EntryView of(Entry entry) {
            return new EntryView(
                    entry.entryId(),
                    entry.transferId(),
                    entry.accountId().value(),
                    entry.amount().minorUnits(),
                    entry.amount().currency(),
                    entry.recordedAt());
        }
    }

    record TransferView(
            UUID transferId,
            String idempotencyKey,
            String from,
            String to,
            long amountMinor,
            String currency,
            Instant completedAt,
            boolean replayed) {

        static TransferView of(Transfer transfer) {
            return new TransferView(
                    transfer.transferId(),
                    transfer.idempotencyKey(),
                    transfer.from().value(),
                    transfer.to().value(),
                    transfer.amount().minorUnits(),
                    transfer.amount().currency(),
                    transfer.completedAt(),
                    transfer.replayed());
        }
    }

    /** {@code reason} is the machine-readable half; {@code message} is for a human log. */
    record ErrorView(String reason, String message) {
    }
}
