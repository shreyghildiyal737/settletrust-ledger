package com.settletrust.ledger.chain;

import java.nio.charset.StandardCharsets;

/**
 * How an invoice id travels as the contract's {@code bytes32}.
 *
 * <p>The id is carried as its own UTF-8 bytes, right-padded with zeros. The watcher can
 * therefore attribute a deposit from the log alone, with no local state and no lookup
 * table, which is what lets it re-scan from any block on a fresh database and arrive at
 * the same answer. A watcher that needed a table to interpret an event would be a watcher
 * whose interpretation could be lost.
 *
 * <p><b>Why not a hash of the id.</b> Hashing would stop anyone reading the id off a
 * public chain, and at first glance that looks like the safer choice for trade finance,
 * where who trades with whom is commercially sensitive. It buys nothing here. The same
 * event indexes the buyer and the seller addresses, which is the relationship, and the id
 * itself is an opaque token that names no party. Hashing it would hide a meaningless
 * string while leaving the meaningful pair in plain sight, in exchange for a mapping that
 * must be kept for ever and a class of deposit nobody can attribute once it is lost.
 *
 * <p>Keeping the counterparties private is a real problem and a different one. It is a
 * question about addresses, answered by not reusing them, and not by the encoding of this
 * field.
 *
 * <p>The cost is a hard limit of 32 bytes, against an {@code invoice.id} column that
 * allows 64 characters. An id that does not fit is refused here rather than truncated: two
 * invoices whose ids agree in the first 32 bytes would otherwise share an escrow, and the
 * money in it would belong to whichever of them asked first.
 */
public final class InvoiceRef {

    private static final int MAX_BYTES = 32;

    private InvoiceRef() {
    }

    /** The {@code bytes32} a deposit for this invoice must carry, without a 0x prefix. */
    public static String toBytes32(String invoiceId) {
        byte[] utf8 = requireUsable(invoiceId).getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "invoice id is " + utf8.length + " bytes and the contract holds "
                            + MAX_BYTES + ": " + invoiceId);
        }
        return Hex.bytes32From(utf8);
    }

    /**
     * The invoice id a log's {@code bytes32} topic names.
     *
     * <p>A topic that is not valid UTF-8, or that is entirely padding, is a deposit for an
     * invoice this platform did not issue. It is reported as such rather than guessed at:
     * money did arrive, and calling it an empty id would file it against whatever an empty
     * id happens to match.
     */
    public static String fromBytes32(String topic) {
        byte[] bytes = Hex.unpadBytes32(topic);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("a deposit arrived carrying no invoice id");
        }

        String decoded = new String(bytes, StandardCharsets.UTF_8);
        // Round-tripping is the check. UTF-8 decoding substitutes a replacement character
        // for bytes it cannot read rather than failing, so comparing the bytes back is the
        // only way to tell a real id from a mangled one.
        if (!Hex.bytes32From(decoded.getBytes(StandardCharsets.UTF_8)).equals(
                Hex.stripPrefix(topic).toLowerCase())) {
            throw new IllegalArgumentException(
                    "a deposit arrived carrying an invoice id that is not UTF-8: " + topic);
        }
        return decoded;
    }

    private static String requireUsable(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            throw new IllegalArgumentException("invoice id must not be blank");
        }
        return invoiceId;
    }
}
