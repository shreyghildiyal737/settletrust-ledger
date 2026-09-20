package com.settletrust.ledger.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.settletrust.ledger.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ChainSource} against a real node.
 *
 * <p>The counterpart of the fake the watcher's logic is proved against. Confirmation
 * depth and reorganisation handling are tested there, because a real chain will not
 * reorganise on request; what is tested here is narrower and cannot be faked: that the
 * node is asked the right questions and its answers are decoded into the right values.
 *
 * <p>Nothing in this class decides anything. It reports what the chain says, and every
 * judgement about whether to believe it yet belongs to {@link EscrowWatcher}.
 */
public class EthereumChainSource implements ChainSource {

    /**
     * {@code keccak256("EscrowDeposited(bytes32,address,address,uint256)")}, the first
     * topic of every log this cares about.
     *
     * <p>A constant rather than a computed value, because Java has no Keccak-256: the
     * JDK's SHA3-256 is the NIST variant with different padding and would silently produce
     * a hash that matches nothing. Adding a crypto library to hash one fixed string, whose
     * input is in the contract next door and cannot change without a redeployment, is not
     * a trade worth making. Regenerate with:
     *
     * <pre>cast sig-event "EscrowDeposited(bytes32,address,address,uint256)"</pre>
     *
     * <p>If it is ever wrong the symptom is unmistakable rather than subtle: no deposit is
     * ever seen at all. {@code EthereumChainSourceTest} asserts it against a log the
     * deployed contract really emitted.
     */
    static final String DEPOSITED_TOPIC =
            "0xcc40be07b95c3742b1185187814fe5ab97a9599e564db0423cdc3b8c4b9e87d1";

    /**
     * How many blocks one {@code eth_getLogs} may span.
     *
     * <p>Ten thousand because that is the cap the common hosted providers apply, and a
     * request over a wider range is refused outright rather than answered slowly. A node
     * of one's own has no such limit and pays nothing for the paging.
     */
    private static final long DEFAULT_MAX_BLOCK_SPAN = 10_000;

    private final JsonRpc rpc;
    private final String escrowAddress;
    private final String currency;
    private final long deployedAtBlock;
    private final long maxBlockSpan;

    /**
     * @param escrowAddress the deployed {@code InvoiceEscrow}
     * @param currency      the ledger's code for what this contract settles in.
     *                      <p><b>Configured, never read from the token.</b> An ERC-20
     *                      reports its own symbol, and anyone can deploy a contract that
     *                      calls itself USDC. Taking the code from the token would let
     *                      whoever deployed it choose which book the money lands in, and
     *                      which balances it is then netted against.
     */
    public EthereumChainSource(JsonRpc rpc, String escrowAddress, String currency) {
        this(rpc, escrowAddress, currency, 0L, DEFAULT_MAX_BLOCK_SPAN);
    }

    /**
     * @param deployedAtBlock the block the escrow was deployed in. Nothing below it can
     *                        contain a deposit into a contract that did not exist yet, so
     *                        this is where a first scan starts. Zero is right for a chain
     *                        created for the occasion and wrong for every real one: a
     *                        watcher with an empty cursor would otherwise ask for every
     *                        log since genesis, which a hosted provider refuses and a node
     *                        of one's own answers slowly enough to look like a hang.
     * @param maxBlockSpan    the widest range asked for in one call
     */
    public EthereumChainSource(
            JsonRpc rpc,
            String escrowAddress,
            String currency,
            long deployedAtBlock,
            long maxBlockSpan) {

        this.rpc = Objects.requireNonNull(rpc, "rpc must not be null");
        this.escrowAddress = requireAddress(escrowAddress);
        // Through Money, so the code is validated and uppercased by the one rule that
        // decides what a currency code is anywhere in this service.
        this.currency = Money.zero(currency).currency();
        if (deployedAtBlock < 0) {
            throw new IllegalArgumentException("a block number cannot be negative");
        }
        if (maxBlockSpan < 1) {
            throw new IllegalArgumentException("a scan must be allowed at least one block");
        }
        this.deployedAtBlock = deployedAtBlock;
        this.maxBlockSpan = maxBlockSpan;
    }

    @Override
    public long headBlockNumber() {
        return Hex.toLong(rpc.call("eth_blockNumber").asText());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Filtered by address and topic at the node, so a busy chain does not become this
     * process's problem, and never below the block the contract was deployed in.
     *
     * <p>The head is read once and the scan ends there, rather than asking for
     * {@code latest} on each page. Paging to a moving target would return a set of logs
     * that belongs to no single height, and the report of how far the chain was read would
     * name a block the earlier pages had not covered. A deposit that lands during the scan
     * is simply picked up next pass, and would not have been deep enough to act on anyway.
     *
     * <p>Paged because a hosted provider caps the span of a single {@code eth_getLogs} and
     * refuses anything wider outright. In the steady state the cursor is a few blocks
     * behind the head and this is one request; the paging is for the first scan after a
     * deployment and for catching up after an outage, which are exactly the moments the
     * unpaged version would fail.
     */
    @Override
    public List<ChainDeposit> depositsFrom(long fromBlock) {
        long head = headBlockNumber();
        long from = Math.max(fromBlock, deployedAtBlock);

        List<ChainDeposit> deposits = new ArrayList<>();
        while (from <= head) {
            long to = Math.min(head, from + maxBlockSpan - 1);
            collectDeposits(from, to, deposits);
            from = to + 1;
        }
        return deposits;
    }

    private void collectDeposits(long fromBlock, long toBlock, List<ChainDeposit> into) {
        JsonNode logs = rpc.call("eth_getLogs", Map.of(
                "fromBlock", Hex.quantity(fromBlock),
                "toBlock", Hex.quantity(toBlock),
                "address", escrowAddress,
                "topics", List.of(DEPOSITED_TOPIC)));

        for (JsonNode log : logs) {
            // A log still in the mempool has no position yet. The watcher only acts on
            // confirmations, so an entry with nothing to confirm against is not yet news.
            if (log.path("removed").asBoolean(false) || log.path("blockNumber").isNull()) {
                continue;
            }
            into.add(toDeposit(log));
        }
    }

    @Override
    public Optional<String> blockHashAt(long blockNumber) {
        if (blockNumber < 0) {
            return Optional.empty();
        }
        // false: the transaction bodies are never read here and a full block on a busy
        // chain is megabytes of them.
        JsonNode block = rpc.call("eth_getBlockByNumber", Hex.quantity(blockNumber), false);
        if (block.isNull()) {
            return Optional.empty();
        }
        return Optional.of(block.get("hash").asText());
    }

    private ChainDeposit toDeposit(JsonNode log) {
        JsonNode topics = log.get("topics");
        String invoiceId = InvoiceRef.fromBytes32(topics.get(1).asText());

        // The only parameter the event does not index, so the data is exactly one word.
        long amount = Hex.toMinorUnits(log.get("data").asText(), "a deposit for " + invoiceId);

        return new ChainDeposit(
                log.get("transactionHash").asText(),
                (int) Hex.toLong(log.get("logIndex").asText()),
                Hex.toLong(log.get("blockNumber").asText()),
                log.get("blockHash").asText(),
                invoiceId,
                Money.of(amount, currency));
    }

    static String requireAddress(String address) {
        if (address == null || !Hex.stripPrefix(address).matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("not a contract address: " + address);
        }
        return address.toLowerCase();
    }
}
