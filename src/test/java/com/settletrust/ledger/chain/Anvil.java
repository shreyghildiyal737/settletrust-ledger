package com.settletrust.ledger.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The writing half of a local chain, for the tests that need something to read.
 *
 * <p>The service only ever reads, so everything here is test-only on purpose: deploying a
 * contract, minting a token and sending a deposit are things this system does not do and
 * must not learn how to do. Putting them behind a test class keeps that boundary visible.
 *
 * <p>Transactions are sent with {@code eth_sendTransaction} against one of anvil's own
 * unlocked accounts, so there is no key handling and no signing library anywhere in this
 * repository. On a real network the same calls would be signed by whoever is paying.
 *
 * <p>Requires {@code LEDGER_TEST_ETH_RPC} and the compiled artifacts under
 * {@code contracts/out}. Both are absent on a machine that has not been set up for chain
 * tests, and {@link #available()} is how those tests decide to skip rather than fail:
 * a missing toolchain is not a broken ledger.
 */
final class Anvil implements AutoCloseable {

    private static final String RPC_URL = "LEDGER_TEST_ETH_RPC";

    /** Selectors, from {@code cast sig}. See EthereumChainSource.DEPOSITED_TOPIC. */
    private static final String MINT = "0x40c10f19";
    private static final String APPROVE = "0x095ea7b3";
    private static final String DEPOSIT = "0xd954863c";
    private static final String RELEASE = "0x67d42a8b";

    private static final Path ARTIFACTS = Path.of("contracts", "out");

    private static final Duration MINING_PATIENCE = Duration.ofSeconds(10);

    private final JsonRpc rpc;
    private final ObjectMapper json = new ObjectMapper();

    static boolean available() {
        return System.getenv(RPC_URL) != null && Files.isDirectory(ARTIFACTS);
    }

    static String rpcUrl() {
        return System.getenv(RPC_URL);
    }

    Anvil() {
        this.rpc = new JsonRpc(rpcUrl());
    }

    JsonRpc rpc() {
        return rpc;
    }

    /** Anvil's nth unlocked account. Zero is the deployer and settler by convention here. */
    String account(int index) {
        JsonNode all = rpc.call("eth_accounts");
        return all.get(index).asText();
    }

    /**
     * Deploys a contract from its forge artifact and returns its address.
     *
     * @param constructorArgs already ABI encoded, without a 0x prefix
     */
    String deploy(String contractName, String constructorArgs) {
        String bytecode = bytecodeOf(contractName);
        String receipt = send(Map.of(
                "from", account(0),
                "data", bytecode + constructorArgs,
                "gas", Hex.quantity(6_000_000)));
        return receiptOf(receipt).get("contractAddress").asText();
    }

    void mint(String token, String to, long amount) {
        send(Map.of(
                "from", account(0),
                "to", token,
                "data", MINT + Hex.addressWord(to) + word(amount)));
    }

    void approve(String token, String owner, String spender, long amount) {
        send(Map.of(
                "from", owner,
                "to", token,
                "data", APPROVE + Hex.addressWord(spender) + word(amount)));
    }

    /** Funds an escrow, and answers with the hash of the transaction that did it. */
    String deposit(String escrow, String buyer, String invoiceId, String seller, long amount) {
        return send(Map.of(
                "from", buyer,
                "to", escrow,
                "gas", Hex.quantity(1_000_000),
                "data", DEPOSIT
                        + InvoiceRef.toBytes32(invoiceId)
                        + Hex.addressWord(seller)
                        + word(amount)));
    }

    /**
     * Pays an escrow out to its seller, as the settler.
     *
     * <p>Only here so a test can make the contract and the ledger disagree the way a
     * premature or duplicated payout would. The service never calls this: releasing is a
     * signed decision made where the settler key lives, and the watcher can only read.
     */
    void release(String escrow, String invoiceId) {
        send(Map.of(
                "from", account(0),
                "to", escrow,
                "gas", Hex.quantity(1_000_000),
                "data", RELEASE + InvoiceRef.toBytes32(invoiceId)));
    }

    /** Mines empty blocks, which is how a test buries a deposit deep enough to confirm. */
    void mineEmpty(int count) {
        // anvil_mine answers null on success: it is an instruction, not a question.
        rpc.callAllowingNull("anvil_mine", Hex.quantity(count));
    }

    long headBlockNumber() {
        return Hex.toLong(rpc.call("eth_blockNumber").asText());
    }

    /**
     * Waits for a transaction to be mined and returns its receipt.
     *
     * <p>Anvil mines on send, but {@code eth_sendTransaction} answers when the transaction
     * is accepted rather than when its block exists, so the receipt is a moment behind.
     * The wait is bounded: a transaction that has not been mined in this long on a chain
     * that mines instantly is not slow, it is gone.
     *
     * <p>A reverted transaction is raised here rather than left for a later assertion. The
     * failure would otherwise surface as a deposit that never appeared, which reads like a
     * bug in the code under test instead of a broken fixture.
     */
    JsonNode receiptOf(String txHash) {
        long giveUpAt = System.nanoTime() + MINING_PATIENCE.toNanos();
        while (true) {
            // Null while the transaction is still pending, which is the whole reason
            // this polls. That is an answer, not a silence.
            JsonNode receipt = rpc.callAllowingNull("eth_getTransactionReceipt", txHash);
            if (!receipt.isNull()) {
                if (!"0x1".equals(receipt.get("status").asText())) {
                    throw new IllegalStateException("transaction " + txHash + " reverted");
                }
                return receipt;
            }
            if (System.nanoTime() > giveUpAt) {
                throw new IllegalStateException(txHash + " was never mined");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + txHash, interrupted);
            }
        }
    }

    /**
     * Sends a transaction and does not return until it is mined.
     *
     * <p>Every write here is fixture setup for an assertion made straight afterwards, so
     * returning before the block exists would make each test race the chain and fail
     * intermittently on a machine under load.
     */
    private String send(Map<String, Object> transaction) {
        String txHash = rpc.call("eth_sendTransaction", transaction).asText();
        receiptOf(txHash);
        return txHash;
    }

    private static String word(long value) {
        String digits = BigInteger.valueOf(value).toString(16);
        return "0".repeat(64 - digits.length()) + digits;
    }

    private String bytecodeOf(String contractName) {
        Path artifact = ARTIFACTS.resolve(contractName + ".sol").resolve(contractName + ".json");
        try {
            JsonNode compiled = json.readTree(Files.readString(artifact));
            return compiled.get("bytecode").get("object").asText();
        } catch (Exception missing) {
            throw new IllegalStateException(
                    "no compiled artifact at " + artifact.toAbsolutePath()
                            + "; run the forge build in the README first", missing);
        }
    }

    @Override
    public void close() {
        rpc.close();
    }
}
