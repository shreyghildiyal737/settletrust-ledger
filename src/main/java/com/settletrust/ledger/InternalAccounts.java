package com.settletrust.ledger;

/**
 * The account id prefixes the platform reserves for its own books.
 *
 * <p>These are not a naming convention. An account id is the only thing a {@code chain:EURC}
 * row carries to say what it is for, so reconciliation recognises the platform's own
 * accounts by their prefix and nothing else. That makes the prefix part of the schema, and
 * a prefix that anybody may claim is not part of anything.
 *
 * <p>Kept here, in the ledger package, rather than beside the code that names these accounts:
 * the rule has to be applied at the edge where an outside caller chooses an id, and that
 * edge cannot depend on settlement without turning the dependency round.
 */
public final class InternalAccounts {

    /** Holds one invoice's escrowed funds, opened the first time that invoice is funded. */
    public static final String ESCROW_PREFIX = "escrow:";

    /** The platform's own side of the chain rail, one per currency. */
    public static final String CHAIN_PREFIX = "chain:";

    /** Absorbs a chain payout that arrived after the money had already been released. */
    public static final String CHAIN_SHORTFALL_PREFIX = "chain-shortfall:";

    private static final String[] RESERVED = {
        ESCROW_PREFIX, CHAIN_PREFIX, CHAIN_SHORTFALL_PREFIX
    };

    private InternalAccounts() {
    }

    /** Whether this id belongs to a namespace only the platform may open accounts in. */
    public static boolean isReserved(String accountId) {
        for (String prefix : RESERVED) {
            if (accountId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
