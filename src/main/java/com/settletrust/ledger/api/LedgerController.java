package com.settletrust.ledger.api;

import com.settletrust.ledger.Account;
import com.settletrust.ledger.AccountId;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.Transfer;
import com.settletrust.ledger.Transfers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

import static com.settletrust.ledger.api.LedgerDtos.AccountView;
import static com.settletrust.ledger.api.LedgerDtos.EntryView;
import static com.settletrust.ledger.api.LedgerDtos.OpenAccountRequest;
import static com.settletrust.ledger.api.LedgerDtos.TransferRequest;
import static com.settletrust.ledger.api.LedgerDtos.TransferView;

/**
 * The HTTP edge. It translates and delegates; every rule it appears to enforce is
 * enforced below it, and the only thing decided here is which status code a given
 * outcome deserves.
 */
@RestController
@RequestMapping(path = "/api/v1")
class LedgerController {

    private final PostgresLedger ledger;
    private final Transfers transfers;

    LedgerController(PostgresLedger ledger, Transfers transfers) {
        this.ledger = ledger;
        this.transfers = transfers;
    }

    @PostMapping("/accounts")
    ResponseEntity<AccountView> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        Account account = fromClient(() -> new Account(
                AccountId.of(request.id()), request.currency(), request.resolvedKind()));
        ledger.open(account);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(AccountView.of(account, Money.zero(account.currency())));
    }

    @GetMapping("/accounts/{id}")
    AccountView account(@PathVariable("id") String id) {
        AccountId accountId = fromClient(() -> AccountId.of(id));
        Account account = ledger.require(accountId);
        return AccountView.of(account, ledger.balanceOf(accountId));
    }

    @GetMapping("/accounts/{id}/entries")
    List<EntryView> entries(@PathVariable("id") String id) {
        AccountId accountId = fromClient(() -> AccountId.of(id));
        ledger.require(accountId);
        return ledger.entriesOf(accountId).stream().map(EntryView::of).toList();
    }

    /**
     * The idempotency key arrives as a header rather than in the body, following the
     * convention every payments API has settled on: it describes the request, not the
     * money, and a client retrying blindly should not have to reconstruct the payload
     * to reuse it.
     *
     * <p>A new transfer is 201. A replay is 200 with the original transfer, so a client
     * can tell that its retry was recognised without the answer changing.
     */
    @PostMapping("/transfers")
    ResponseEntity<TransferView> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (idempotencyKey.isBlank()) {
            throw new MalformedRequest("Idempotency-Key must not be blank");
        }

        AccountId from = fromClient(() -> AccountId.of(request.from()));
        AccountId to = fromClient(() -> AccountId.of(request.to()));
        Money amount = fromClient(() -> Money.of(request.amountMinor(), request.currency()));

        Transfer transfer = transfers.transfer(from, to, amount, idempotencyKey);

        return ResponseEntity
                .status(transfer.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(TransferView.of(transfer));
    }

    /**
     * Builds a domain value from what the client sent, turning a rejection by the value
     * object into a 400. Deliberately narrow: only the conversion runs inside it, so a
     * failure anywhere else stays the server error it is instead of being reported as
     * the caller's fault.
     */
    private static <T> T fromClient(Supplier<T> conversion) {
        try {
            return conversion.get();
        } catch (IllegalArgumentException rejected) {
            throw new MalformedRequest(rejected.getMessage());
        }
    }
}
