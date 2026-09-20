// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";

import {InvoiceEscrow} from "../src/InvoiceEscrow.sol";
import {TestStablecoin} from "./TestStablecoin.sol";
import {FeeOnTransferToken} from "./FeeOnTransferToken.sol";

/**
 * @title InvoiceEscrowTest
 * @notice What the contract guarantees, checked one guarantee at a time.
 *
 * @dev The Java suite drives this contract end to end against a local node, which proves
 *      the happy path and the wiring around it. It proves nothing about the cases that
 *      matter most here: a release by somebody who is not the settler, a refund after a
 *      release, a token that does not behave. Those are the reasons a contract holding
 *      money is worth writing carefully, and none of them is reachable from the Java side
 *      without deliberately misusing the settler key.
 */
contract InvoiceEscrowTest is Test {
    bytes32 private constant INVOICE = bytes32("inv-00000001");
    uint256 private constant AMOUNT = 2_500_000;

    TestStablecoin private token;
    InvoiceEscrow private escrow;

    address private settler = address(this);
    address private buyer = makeAddr("buyer");
    address private seller = makeAddr("seller");
    address private stranger = makeAddr("stranger");

    function setUp() public {
        token = new TestStablecoin();
        escrow = new InvoiceEscrow(settler, IERC20(address(token)));

        token.mint(buyer, AMOUNT * 10);
        vm.prank(buyer);
        token.approve(address(escrow), type(uint256).max);
    }

    function test_depositMovesTheMoneyAndSaysSo() public {
        vm.expectEmit(true, true, true, true);
        emit InvoiceEscrow.EscrowDeposited(INVOICE, buyer, seller, AMOUNT);

        vm.prank(buyer);
        escrow.deposit(INVOICE, seller, AMOUNT);

        (uint256 held, bool released, bool refunded) = escrow.heldFor(INVOICE);
        assertEq(token.balanceOf(address(escrow)), AMOUNT, "the contract holds the money");
        assertEq(held, AMOUNT, "and says it holds it");
        assertFalse(released);
        assertFalse(refunded);
    }

    function test_oneDepositPerInvoice() public {
        fund();

        vm.prank(buyer);
        vm.expectRevert(InvoiceEscrow.AlreadyFunded.selector);
        escrow.deposit(INVOICE, seller, AMOUNT);
    }

    function test_depositOfNothingIsRefused() public {
        vm.prank(buyer);
        vm.expectRevert(InvoiceEscrow.ZeroAmount.selector);
        escrow.deposit(INVOICE, seller, 0);
    }

    function test_depositToNobodyIsRefused() public {
        vm.prank(buyer);
        vm.expectRevert(InvoiceEscrow.ZeroAddress.selector);
        escrow.deposit(INVOICE, address(0), AMOUNT);
    }

    function test_releasePaysTheSeller() public {
        fund();

        vm.expectEmit(true, true, true, true);
        emit InvoiceEscrow.EscrowReleased(INVOICE, seller, AMOUNT);
        escrow.release(INVOICE);

        (, bool released,) = escrow.heldFor(INVOICE);
        assertEq(token.balanceOf(seller), AMOUNT, "the seller has the money");
        assertEq(token.balanceOf(address(escrow)), 0, "and the contract has none");
        assertTrue(released);
    }

    /**
     * @dev The check that makes the settler key worth protecting. Everything else the
     *      contract enforces is about sequence; this is the only thing standing between a
     *      stranger and everybody's escrows.
     */
    function test_onlyTheSettlerMayRelease() public {
        fund();

        vm.prank(stranger);
        vm.expectRevert(InvoiceEscrow.NotSettler.selector);
        escrow.release(INVOICE);

        vm.prank(buyer);
        vm.expectRevert(InvoiceEscrow.NotSettler.selector);
        escrow.release(INVOICE);

        vm.prank(seller);
        vm.expectRevert(InvoiceEscrow.NotSettler.selector);
        escrow.release(INVOICE);
    }

    function test_onlyTheSettlerMayRefund() public {
        fund();

        vm.prank(buyer);
        vm.expectRevert(InvoiceEscrow.NotSettler.selector);
        escrow.refund(INVOICE);
    }

    function test_refundRepaysTheBuyer() public {
        uint256 before = token.balanceOf(buyer);
        fund();

        vm.expectEmit(true, true, true, true);
        emit InvoiceEscrow.EscrowRefunded(INVOICE, buyer, AMOUNT);
        escrow.refund(INVOICE);

        (, , bool refunded) = escrow.heldFor(INVOICE);
        assertEq(token.balanceOf(buyer), before, "the buyer is where they started");
        assertTrue(refunded);
    }

    /**
     * @dev The four ways the money could leave twice, each of which is somebody paid out
     *      of an escrow that is already empty.
     */
    function test_moneyLeavesOnlyOnce() public {
        fund();
        escrow.release(INVOICE);

        vm.expectRevert(InvoiceEscrow.AlreadyClosed.selector);
        escrow.release(INVOICE);

        vm.expectRevert(InvoiceEscrow.AlreadyClosed.selector);
        escrow.refund(INVOICE);

        bytes32 second = bytes32("inv-00000002");
        vm.prank(buyer);
        escrow.deposit(second, seller, AMOUNT);
        escrow.refund(second);

        vm.expectRevert(InvoiceEscrow.AlreadyClosed.selector);
        escrow.refund(second);

        vm.expectRevert(InvoiceEscrow.AlreadyClosed.selector);
        escrow.release(second);
    }

    function test_anEscrowThatWasNeverFundedHoldsNothing() public {
        vm.expectRevert(InvoiceEscrow.NothingHeld.selector);
        escrow.release(bytes32("inv-never"));

        vm.expectRevert(InvoiceEscrow.NothingHeld.selector);
        escrow.refund(bytes32("inv-never"));
    }

    function test_theContractRefusesToBeDeployedWithoutASettlerOrAToken() public {
        vm.expectRevert(InvoiceEscrow.ZeroAddress.selector);
        new InvoiceEscrow(address(0), IERC20(address(token)));

        vm.expectRevert(InvoiceEscrow.ZeroAddress.selector);
        new InvoiceEscrow(settler, IERC20(address(0)));
    }

    /**
     * @notice A token that skims a fee must not be able to make the contract claim it
     *         holds more than it does.
     *
     * @dev The one case where the contract's word and the token's word can come apart.
     *      SafeERC20 reverts on a transfer that fails and says nothing about a transfer
     *      that succeeds for less than it was asked, so without a check the escrow records
     *      the requested amount, emits it, and the watcher credits an invoice for money
     *      that is not in the contract. That is a reserve shortfall created at the moment
     *      of deposit, and the reconciler would eventually report it as though somebody
     *      had been paid out early.
     *
     *      Refused rather than absorbed, because the contract already takes the position
     *      that a partial payment is a commercial conversation and not something to
     *      resolve here.
     */
    function test_aFeeTakingTokenCannotOverstateTheDeposit() public {
        FeeOnTransferToken skimmed = new FeeOnTransferToken(300);
        InvoiceEscrow feeEscrow = new InvoiceEscrow(settler, IERC20(address(skimmed)));

        skimmed.mint(buyer, AMOUNT);
        vm.prank(buyer);
        skimmed.approve(address(feeEscrow), type(uint256).max);

        vm.prank(buyer);
        vm.expectRevert(InvoiceEscrow.AmountNotReceived.selector);
        feeEscrow.deposit(INVOICE, seller, AMOUNT);

        (uint256 held,,) = feeEscrow.heldFor(INVOICE);
        assertEq(held, 0, "nothing was recorded for money that never arrived");
        assertEq(skimmed.balanceOf(address(feeEscrow)), 0);
    }

    function fund() private {
        vm.prank(buyer);
        escrow.deposit(INVOICE, seller, AMOUNT);
    }
}
