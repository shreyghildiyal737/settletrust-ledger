package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link EscrowReserves} against a real node: the token's own answer to what the escrow
 * contract holds.
 *
 * <p>This is the one question in the system the chain is the authority for. Every other
 * check the reconciler makes compares the ledger against {@code chain_observation}, and
 * the watcher wrote both, so a watcher that misread the chain produces two records that
 * agree and are both wrong. The token contract was not written by us and does not care
 * what our watcher believed.
 *
 * <p>So the balance is asked of the <b>token</b>, not of the escrow. Adding a
 * {@code totalHeld()} getter to {@code InvoiceEscrow} would have been easier to call and
 * worthless to trust: it would be the escrow's own running total, which is another number
 * kept by the same kind of bookkeeping this exists to check. {@code balanceOf} is the
 * token's ledger, and it is the one that decides whether the money can actually be paid
 * out.
 */
public class EscrowContractReserves implements EscrowReserves {

    /**
     * {@code keccak256("balanceOf(address)")[0..4]}, the standard ERC-20 selector.
     *
     * <p>Fixed by the ERC-20 standard rather than by anything here, and reproducible with
     * {@code cast sig "balanceOf(address)"}. See {@link EthereumChainSource#DEPOSITED_TOPIC}
     * for why these are constants and not computed.
     */
    private static final String BALANCE_OF = "0x70a08231";

    private final JsonRpc rpc;
    private final String tokenAddress;
    private final String escrowAddress;
    private final String currency;

    /**
     * @param tokenAddress  the ERC-20 the escrow settles in
     * @param escrowAddress the contract whose balance is being asked about
     * @param currency      the ledger's code for that token, configured rather than read
     *                      from it, for the reason given on {@link EthereumChainSource}
     */
    public EscrowContractReserves(
            JsonRpc rpc, String tokenAddress, String escrowAddress, String currency) {

        this.rpc = Objects.requireNonNull(rpc, "rpc must not be null");
        this.tokenAddress = EthereumChainSource.requireAddress(tokenAddress);
        this.escrowAddress = EthereumChainSource.requireAddress(escrowAddress);
        this.currency = Money.zero(currency).currency();
    }

    /**
     * {@inheritDoc}
     *
     * <p>One currency, because one deployment of {@code InvoiceEscrow} is bound to one
     * token at construction and cannot hold another. A platform settling in two
     * stablecoins runs two escrows and two of these, and the reconciler sums the list.
     *
     * <p>Read at the block the caller names rather than at {@code latest}, so the answer
     * describes the same height as the records it will be compared against. See
     * {@link EscrowReserves#heldOnChain(long)} for what asking at {@code latest} cost.
     */
    @Override
    public List<Money> heldOnChain(long asOfBlock) {
        if (asOfBlock < 0) {
            throw new IllegalArgumentException("a block number cannot be negative");
        }

        String balance = rpc.call("eth_call", Map.of(
                "to", tokenAddress,
                "data", BALANCE_OF + Hex.addressWord(escrowAddress)),
                Hex.quantity(asOfBlock)).asText();

        // An eth_call against an address holding no code returns empty rather than
        // failing, and so does a contract with no balanceOf. Left to fall through it
        // becomes a hex parsing error several frames away from the configuration that
        // caused it, and the reconciler would report the reserves as unreadable rather
        // than as unconfigured.
        if (Hex.stripPrefix(balance).isEmpty()) {
            throw new JsonRpc.ChainUnavailable(
                    "no ERC-20 answered balanceOf at " + tokenAddress
                            + "; check ledger.chain.token-address");
        }

        long held = Hex.toMinorUnits(
                balance, "the " + currency + " balance of escrow " + escrowAddress);
        return List.of(Money.of(held, currency));
    }
}
