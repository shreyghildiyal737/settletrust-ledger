package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A chain that can be made to misbehave on demand.
 *
 * <p>This exists because the behaviour worth testing is the behaviour a real testnet will
 * not produce when asked: a block being taken back, a transaction reappearing at a
 * different height, an event arriving twice. Here a reorganisation is one method call.
 *
 * <p>Public rather than package-private because the reconciliation tests drive it too:
 * proving the two rails agree after a reorganisation needs a chain that will reorganise.
 *
 * <p>Block numbers are positions in the list, so rolling back and mining again produces
 * exactly what a reorganisation produces: the same height, a different block.
 */
public final class FakeChain implements ChainSource, EscrowReserves {

    private record Block(String hash, List<ChainDeposit> deposits) {
    }

    private final List<Block> blocks = new ArrayList<>();

    /**
     * What the contract holds beyond the deposits it was told about, so a test can make
     * the chain and the ledger disagree in either direction.
     */
    private final Map<String, Long> offChainBook = new HashMap<>();

    private int minted;

    public FakeChain() {
        mineEmpty(1);
    }

    @Override
    public long headBlockNumber() {
        return blocks.size() - 1L;
    }

    @Override
    public List<ChainDeposit> depositsFrom(long fromBlock, long toBlock) {
        List<ChainDeposit> found = new ArrayList<>();
        long last = Math.min(toBlock, blocks.size() - 1L);
        for (int number = (int) Math.max(0, fromBlock); number <= last; number++) {
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

    /**
     * The sum of every deposit on the canonical chain at or below {@code asOfBlock}.
     * Blocks dropped by a reorganisation take their deposits with them, which is what the
     * real contract balance would do too.
     *
     * <p>Honouring the block rather than ignoring it is the point: a fake that answered
     * for the head whatever it was asked could not reproduce the surplus a real node
     * reports for a deposit the watcher has not read yet.
     */
    @Override
    public List<Money> heldOnChain(long asOfBlock) {
        Map<String, Long> totals = new HashMap<>(offChainBook);
        for (int number = 0; number < blocks.size() && number <= asOfBlock; number++) {
            Block block = blocks.get(number);
            for (ChainDeposit deposit : block.deposits()) {
                totals.merge(
                        deposit.amount().currency(), deposit.amount().minorUnits(), Long::sum);
            }
        }
        return totals.entrySet().stream()
                .map(held -> Money.of(held.getValue(), held.getKey()))
                .toList();
    }

    /**
     * Moves the contract balance without an event, the way a direct transfer to the
     * contract address or a watcher that misread the chain would.
     */
    public void adjustHeldOnChain(Money delta) {
        offChainBook.merge(delta.currency(), delta.minorUnits(), Long::sum);
    }

    /** Mines {@code count} blocks containing nothing, to bury what came before them. */
    public void mineEmpty(int count) {
        for (int i = 0; i < count; i++) {
            blocks.add(new Block(nextHash(), new ArrayList<>()));
        }
    }

    /** Mines one block containing a single deposit, and returns it as the chain reports it. */
    public ChainDeposit mineDeposit(String txHash, String invoiceId, Money amount) {
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
    public void rollBackTo(long blockNumber) {
        while (blocks.size() - 1 > blockNumber) {
            blocks.remove(blocks.size() - 1);
        }
    }

    private String nextHash() {
        return String.format("0xblock%058d", ++minted);
    }
}
