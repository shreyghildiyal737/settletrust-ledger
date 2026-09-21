package com.settletrust.ledger.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.settletrust.ledger.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain client, against a real node running a real deployment of the real contract.
 *
 * <p>Everything else about the on-chain rail is proved against {@link FakeChain}, because
 * confirmation depth and reorganisation are logic and a real chain will not reorganise
 * when a test asks it to. None of that proves the node is being asked the right questions
 * or that its answers are being read correctly, and no fake can: a fake returns whatever
 * shape the code that wrote it expected. That is the gap this closes.
 *
 * <p>It also, incidentally, is the first thing that runs {@code InvoiceEscrow} at all.
 * A contract that holds money and has never been executed is a design document.
 *
 * <p>Skipped rather than failed without a chain: {@code LEDGER_TEST_ETH_RPC} and a forge
 * build are a toolchain, and their absence says nothing about the ledger. The README has
 * the two commands.
 */
@EnabledIf("chainIsRunning")
class EthereumChainSourceTest {

    private static final long AMOUNT = 2_500_000L;

    static boolean chainIsRunning() {
        return Anvil.available();
    }

    private Anvil chain;
    private String token;
    private String escrow;
    private String buyer;
    private String seller;
    private EthereumChainSource deposits;
    private EscrowContractReserves reserves;

    /**
     * A fresh token and a fresh escrow per test, on a chain that other tests have already
     * used. Filtering by contract address is the isolation, and it is also the thing worth
     * testing: on a shared chain a source that ignored the address would pick up
     * everybody's deposits.
     */
    @BeforeEach
    void deployAnEscrowOfItsOwn() {
        chain = new Anvil();
        buyer = chain.account(1);
        seller = chain.account(2);

        token = chain.deploy("TestStablecoin", "");
        escrow = chain.deploy("InvoiceEscrow",
                Hex.addressWord(chain.account(0)) + Hex.addressWord(token));

        deposits = new EthereumChainSource(chain.rpc(), escrow, "USDC");
        reserves = new EscrowContractReserves(chain.rpc(), token, escrow, "USDC");
    }

    @AfterEach
    void hangUp() {
        if (chain != null) {
            chain.close();
        }
    }

    @Test
    @DisplayName("a deposit is read back as the chain reported it")
    void aDepositIsReadBackFaithfully() {
        String invoiceId = anInvoiceId();
        String txHash = fund(invoiceId, AMOUNT);
        long landedIn = Hex.toLong(chain.receiptOf(txHash).get("blockNumber").asText());

        List<ChainDeposit> found = deposits.depositsFrom(0, deposits.headBlockNumber()).deposits();

        assertAll(
                () -> assertEquals(1, found.size(), () -> "expected one deposit, got " + found),
                () -> assertEquals(invoiceId, found.getFirst().invoiceId(),
                        "the invoice id survived the round trip through bytes32"),
                () -> assertEquals(Money.of(AMOUNT, "USDC"), found.getFirst().amount()),
                () -> assertEquals(txHash, found.getFirst().txHash()),
                () -> assertEquals(landedIn, found.getFirst().blockNumber()),
                // The deposit transaction emits the token's Transfer first and
                // EscrowDeposited second, so this is also the check that the right log was
                // picked out of a transaction that produced more than one. logIndex counts
                // across the whole block rather than within the transaction, which is what
                // makes the (hash, index) pair unique and is easy to assume the other way.
                () -> assertEquals(logIndexOfDepositIn(txHash), found.getFirst().logIndex()));
    }

    /** Where the EscrowDeposited log actually sat, according to the receipt itself. */
    private int logIndexOfDepositIn(String txHash) {
        for (JsonNode log : chain.receiptOf(txHash).get("logs")) {
            if (EthereumChainSource.DEPOSITED_TOPIC.equals(log.get("topics").get(0).asText())) {
                return (int) Hex.toLong(log.get("logIndex").asText());
            }
        }
        throw new AssertionError("the transaction emitted no EscrowDeposited log at all");
    }

    @Test
    @DisplayName("the block hash on a deposit is the hash of the block it landed in")
    void theBlockHashIsTheBlocksOwn() {
        fund(anInvoiceId(), AMOUNT);
        ChainDeposit deposit = deposits.depositsFrom(0, deposits.headBlockNumber()).deposits().getFirst();

        assertAll(
                () -> assertEquals(deposit.blockHash(),
                        deposits.blockHashAt(deposit.blockNumber()).orElseThrow(),
                        "the two ways of asking must agree, or reorg detection compares "
                                + "a hash against a different question"),
                () -> assertTrue(deposits.blockHashAt(chain.headBlockNumber() + 100).isEmpty(),
                        "a height the chain has not reached has no hash"));
    }

    @Test
    @DisplayName("the head moves as blocks are mined, which is what depth is measured from")
    void theHeadMoves() {
        long before = deposits.headBlockNumber();
        chain.mineEmpty(5);

        assertEquals(before + 5, deposits.headBlockNumber());
    }

    @Test
    @DisplayName("only deposits into this escrow are ours")
    void anotherEscrowsDepositsAreNotOurs() {
        String other = chain.deploy("InvoiceEscrow",
                Hex.addressWord(chain.account(0)) + Hex.addressWord(token));

        String mine = anInvoiceId();
        fund(mine, AMOUNT);

        String theirs = anInvoiceId();
        chain.approve(token, buyer, other, AMOUNT);
        chain.mint(token, buyer, AMOUNT);
        chain.deposit(other, buyer, theirs, seller, AMOUNT);

        List<ChainDeposit> found = deposits.depositsFrom(0, deposits.headBlockNumber()).deposits();

        assertAll(
                () -> assertEquals(1, found.size(),
                        () -> "the address filter let somebody else's deposit through: " + found),
                () -> assertEquals(mine, found.getFirst().invoiceId()));
    }

    @Test
    @DisplayName("the reserve check reads the token's balance, not the escrow's own opinion")
    void theReservesAreWhatTheTokenSays() {
        assertEquals(List.of(Money.zero("USDC")), reserves.heldOnChain(chain.headBlockNumber()),
                "a contract holding nothing still answers, or a clean run cannot be told "
                        + "from an unanswered one");

        fund(anInvoiceId(), AMOUNT);
        fund(anInvoiceId(), AMOUNT);

        assertEquals(List.of(Money.of(2 * AMOUNT, "USDC")), reserves.heldOnChain(chain.headBlockNumber()));
    }

    @Test
    @DisplayName("money sent straight to the contract counts, and is what a surplus is made of")
    void tokensSentDirectlyAreStillHeld() {
        fund(anInvoiceId(), AMOUNT);
        // No deposit call, so no event: exactly the case the reconciler cannot see from
        // its own records and raises as an unexplained surplus.
        chain.mint(token, escrow, 7L);

        assertAll(
                () -> assertEquals(List.of(Money.of(AMOUNT + 7L, "USDC")), reserves.heldOnChain(chain.headBlockNumber())),
                () -> assertEquals(1, deposits.depositsFrom(0, deposits.headBlockNumber()).deposits().size(),
                        "and the watcher rightly knows nothing about it"));
    }

    @Test
    @DisplayName("nothing before the contract existed is asked for")
    void theScanStartsWhereTheContractWasDeployed() {
        long deployedAt = chain.headBlockNumber();
        fund(anInvoiceId(), AMOUNT);

        EthereumChainSource floored =
                new EthereumChainSource(chain.rpc(), escrow, "USDC", deployedAt, 10_000);

        assertAll(
                () -> assertEquals(1, floored.depositsFrom(0, floored.headBlockNumber()).deposits().size(),
                        "a cursor at zero must still find the deposit, from the floor"),
                () -> assertEquals(1, deposits.depositsFrom(0, deposits.headBlockNumber()).deposits().size(),
                        "and the unfloored source agrees about what is there"));
    }

    @Test
    @DisplayName("a range wider than one call may cover is paged, not truncated")
    void aWideRangeIsPaged() {
        fund(anInvoiceId(), AMOUNT);
        chain.mineEmpty(8);
        fund(anInvoiceId(), AMOUNT);
        chain.mineEmpty(8);

        // One block per call, so the scan has to page many times to reach the head. A
        // client that asked once and stopped would see the first deposit and miss the
        // second, which is what a provider's block-span cap does to an unpaged one.
        EthereumChainSource paged =
                new EthereumChainSource(chain.rpc(), escrow, "USDC", 0, 1);

        assertEquals(2, paged.depositsFrom(0, paged.headBlockNumber()).deposits().size(),
                () -> "paging lost a deposit: " + paged.depositsFrom(0, paged.headBlockNumber()).deposits());
    }

    @Test
    @DisplayName("a token address with no ERC-20 behind it says so")
    void aMisconfiguredTokenAddressIsNamed() {
        // An ordinary account, holding no code. eth_call against it does not fail: there
        // is nothing to run, so it returns empty, and the empty answer would otherwise
        // become a hex parsing error several frames from the setting that caused it.
        // (An address that does hold code but has no balanceOf reverts instead, and the
        // node's own refusal already says so.)
        EscrowContractReserves misconfigured =
                new EscrowContractReserves(chain.rpc(), chain.account(5), escrow, "USDC");

        JsonRpc.ChainUnavailable refused =
                assertThrows(JsonRpc.ChainUnavailable.class, () -> misconfigured.heldOnChain(chain.headBlockNumber()));

        assertTrue(refused.getMessage().contains("token-address"),
                () -> "the message should point at the setting to fix: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("an invoice id too long for the contract is refused rather than truncated")
    void anOversizedInvoiceIdIsRefused() {
        String tooLong = "inv-" + "x".repeat(30);

        assertThrows(IllegalArgumentException.class, () -> InvoiceRef.toBytes32(tooLong),
                "two invoices agreeing in their first 32 bytes would share an escrow");
    }

    private String anInvoiceId() {
        return "inv-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Mints the buyer the money, lets the escrow take it, and deposits it. */
    private String fund(String invoiceId, long amount) {
        chain.mint(token, buyer, amount);
        chain.approve(token, buyer, escrow, amount);
        return chain.deposit(escrow, buyer, invoiceId, seller, amount);
    }
}
