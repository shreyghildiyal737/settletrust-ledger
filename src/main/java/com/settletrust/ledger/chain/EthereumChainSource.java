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

    private final JsonRpc rpc;
    private final String escrowAddress;
    private final String currency;

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
        this.rpc = Objects.requireNonNull(rpc, "rpc must not be null");
        this.escrowAddress = requireAddress(escrowAddress);
        // Through Money, so the code is validated and uppercased by the one rule that
        // decides what a currency code is anywhere in this service.
        this.currency = Money.zero(currency).currency();
    }

    @Override
    public long headBlockNumber() {
        return Hex.toLong(rpc.call("eth_blockNumber").asText());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Filtered by address and topic at the node, so a busy chain does not become this
     * process's problem. The range ends at {@code latest} rather than at a height read
     * beforehand, which means the head may move while the call is in flight; that is safe
     * because the watcher decides what is confirmed from a depth it reads separately, and
     * a deposit that arrives one pass early is simply not deep enough yet.
     *
     * <p>A hosted node will cap either the block span or the number of logs returned. This
     * asks for one open-ended range because the watcher keeps a cursor and never falls far
     * behind; against a provider with a cap, the cursor is what a paged version would be
     * built on.
     */
    @Override
    public List<ChainDeposit> depositsFrom(long fromBlock) {
        JsonNode logs = rpc.call("eth_getLogs", Map.of(
                "fromBlock", Hex.quantity(Math.max(0, fromBlock)),
                "toBlock", "latest",
                "address", escrowAddress,
                "topics", List.of(DEPOSITED_TOPIC)));

        List<ChainDeposit> deposits = new ArrayList<>();
        for (JsonNode log : logs) {
            // A log still in the mempool has no position yet. The watcher only acts on
            // confirmations, so an entry with nothing to confirm against is not yet news.
            if (log.path("removed").asBoolean(false) || log.path("blockNumber").isNull()) {
                continue;
            }
            deposits.add(toDeposit(log));
        }
        return deposits;
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
