package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A chain that can be made to misbehave on demand.
 *
 * <p>This exists because the behaviour worth testing is the behaviour a real testnet will
 * not produce when asked: a block being taken back, a transaction reappearing at a
 * different height, an event arriving twice. Here a reorganisation is one method call.
 *
 * <p>Block numbers are positions in the list, so rolling back and mining again produces
 * exactly what a reorganisation produces: the same height, a different block.
 */
final class FakeChain implements ChainSource {

    private record Block(String hash, List<ChainDeposit> deposits) {
    }

    private final List<Block> blocks = new ArrayList<>();
    private int minted;

    FakeChain() {
        mineEmpty(1);
    }

    @Override
    public long headBlockNumber() {
        return blocks.size() - 1L;
    }

    @Override
    public List<ChainDeposit> depositsFrom(long fromBlock) {
        List<ChainDeposit> found = new ArrayList<>();
        for (int number = (int) Math.max(0, fromBlock); number < blocks.size(); number++) {
            found.addAll(blocks.get(number).deposits());
        }
        return found;
    }

    @Override
    public Optional<String> blockHashAt(long blockNumber) {
        if (blockNumber < 0 || blockNumber >= blocks.size()) {
            return Optional.empty();
        }
        return Optional.of(blocks.get((int) blockNumber).hash());
    }

    /** Mines {@code count} blocks containing nothing, to bury what came before them. */
    void mineEmpty(int count) {
        for (int i = 0; i < count; i++) {
            blocks.add(new Block(nextHash(), new ArrayList<>()));
        }
    }

    /** Mines one block containing a single deposit, and returns it as the chain reports it. */
    ChainDeposit mineDeposit(String txHash, String invoiceId, Money amount) {
        String blockHash = nextHash();
        long number = blocks.size();
        ChainDeposit deposit = new ChainDeposit(txHash, 0, number, blockHash, invoiceId, amount);
        blocks.add(new Block(blockHash, new ArrayList<>(List.of(deposit))));
        return deposit;
    }

    /**
     * Discards every block above {@code blockNumber}, as a reorganisation does. Whatever
     * was in them is gone until something mines it again.
     */
    void rollBackTo(long blockNumber) {
        while (blocks.size() - 1 > blockNumber) {
            blocks.remove(blocks.size() - 1);
        }
    }

    private String nextHash() {
        return String.format("0xblock%058d", ++minted);
    }
}
