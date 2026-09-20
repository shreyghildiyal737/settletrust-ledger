package com.settletrust.ledger.chain;

import java.math.BigInteger;

/**
 * The hex conventions JSON-RPC and the ABI use, which are not the same as each other.
 *
 * <p>A <b>quantity</b> is a number: {@code 0x1f}, no leading zeros, {@code 0x0} for zero.
 * That is what {@code eth_getBlockByNumber} and the block range of {@code eth_getLogs}
 * expect, and a node will reject {@code 0x01f}.
 *
 * <p>An <b>ABI word</b> is 32 bytes, always, left-padded for numbers and addresses and
 * right-padded for fixed byte strings. Topics and call data are made of these.
 *
 * <p>Both directions are here rather than spread through the callers, because getting the
 * padding the wrong way round produces a value that decodes without complaint and is
 * wrong, which is the failure that a type system cannot help with and a name can.
 */
final class Hex {

    private static final int WORD_BYTES = 32;
    private static final int WORD_CHARS = WORD_BYTES * 2;

    private Hex() {
    }

    /** A block number or other number, as a node expects to be given one. */
    static String quantity(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("a quantity cannot be negative: " + value);
        }
        return "0x" + Long.toHexString(value);
    }

    static String stripPrefix(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("expected hex, got nothing");
        }
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

    static BigInteger toBigInteger(String hex) {
        String digits = stripPrefix(hex);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("expected hex digits, got " + hex);
        }
        // Unsigned: the ABI has no sign bit here, and BigInteger(String, 16) would read a
        // word with the top bit set as a negative number.
        return new BigInteger(digits, 16);
    }

    static long toLong(String hex) {
        return toBigInteger(hex).longValueExact();
    }

    /**
     * A uint256 as the ledger's minor units, refusing anything that will not fit.
     *
     * <p>The chain counts in 256 bits and the ledger's {@code amount_minor} is a
     * {@code bigint}. Rounding an amount that overflows would be inventing a number, and
     * truncating it would be losing money quietly, so the boundary refuses instead. In
     * practice this fires when the contract is pointed at a token with eighteen decimals,
     * where a handful of tokens already exceeds a long, and that is a misconfiguration
     * worth stopping on rather than absorbing.
     */
    static long toMinorUnits(String hex, String what) {
        BigInteger value = toBigInteger(hex);
        try {
            return value.longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new JsonRpc.ChainUnavailable(
                    what + " is " + value + ", which the ledger cannot hold in a bigint");
        }
    }

    /** An address as a 32 byte ABI word, left-padded, for call data. */
    static String addressWord(String address) {
        String digits = stripPrefix(address).toLowerCase();
        if (digits.length() != 40 || !digits.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("not an address: " + address);
        }
        return "0".repeat(WORD_CHARS - digits.length()) + digits;
    }

    /** Right-pads bytes to a 32 byte word, which is how the ABI carries {@code bytesN}. */
    static String bytes32From(byte[] value) {
        if (value.length > WORD_BYTES) {
            throw new IllegalArgumentException(
                    "a bytes32 holds " + WORD_BYTES + " bytes, not " + value.length);
        }
        StringBuilder hex = new StringBuilder(WORD_CHARS);
        for (byte b : value) {
            hex.append(String.format("%02x", b));
        }
        while (hex.length() < WORD_CHARS) {
            hex.append("00");
        }
        return hex.toString();
    }

    /** The bytes of a 32 byte word, with the right-hand zero padding removed. */
    static byte[] unpadBytes32(String word) {
        String digits = stripPrefix(word);
        if (digits.length() != WORD_CHARS) {
            throw new IllegalArgumentException(
                    "expected a 32 byte word, got " + digits.length() / 2 + " bytes");
        }

        int end = WORD_BYTES;
        while (end > 0 && digits.startsWith("00", (end - 1) * 2)) {
            end--;
        }

        byte[] bytes = new byte[end];
        for (int i = 0; i < end; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
