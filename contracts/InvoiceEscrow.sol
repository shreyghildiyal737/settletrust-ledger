// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

/**
 * @title InvoiceEscrow
 * @notice Holds a stablecoin payment for a SettleTrust invoice until the platform
 *         releases it to the seller or refunds it to the buyer.
 *
 * @dev The contract holds money and nothing else. It does not model the invoice
 *      lifecycle, because that lifecycle already exists off chain and duplicating it here
 *      would create two authorities that can disagree. What it guarantees is narrower and
 *      more useful: funds for an invoice can be deposited once, can only leave to the
 *      seller or back to the buyer, and cannot leave twice.
 *
 *      Every state-changing function emits an event carrying the invoice id, because the
 *      off-chain watcher identifies work by transaction hash and log index and needs the
 *      invoice id in the log to know what the money was for.
 */
contract InvoiceEscrow is ReentrancyGuard {
    using SafeERC20 for IERC20;

    /// @dev Kept deliberately small: money in, money out, and who may move it.
    struct Escrow {
        address buyer;
        address seller;
        uint256 amount;
        bool released;
        bool refunded;
    }

    /// @notice The address allowed to release or refund. The platform's settlement signer.
    address public immutable settler;

    /// @notice The token every escrow is denominated in, fixed at deployment.
    IERC20 public immutable token;

    /// @notice invoiceId => the money held against it.
    mapping(bytes32 => Escrow) public escrows;

    event EscrowDeposited(
        bytes32 indexed invoiceId,
        address indexed buyer,
        address indexed seller,
        uint256 amount
    );
    event EscrowReleased(bytes32 indexed invoiceId, address indexed seller, uint256 amount);
    event EscrowRefunded(bytes32 indexed invoiceId, address indexed buyer, uint256 amount);

    error NotSettler();
    error AlreadyFunded();
    error NothingHeld();
    error AlreadyClosed();
    error ZeroAmount();
    error ZeroAddress();

    modifier onlySettler() {
        if (msg.sender != settler) revert NotSettler();
        _;
    }

    constructor(address settler_, IERC20 token_) {
        if (settler_ == address(0) || address(token_) == address(0)) revert ZeroAddress();
        settler = settler_;
        token = token_;
    }

    /**
     * @notice Funds the escrow for an invoice. The caller must have approved this contract
     *         for `amount` beforehand.
     * @dev One deposit per invoice. Allowing top-ups would mean the watcher had to sum
     *      several events to know what is held, and a partial payment is a commercial
     *      conversation rather than something to resolve in a contract.
     *
     *      The transfer happens before the event so that a token which fails, or which
     *      takes a fee and delivers less than `amount`, cannot produce a log the watcher
     *      would believe. SafeERC20 turns a non-reverting failure into a revert.
     */
    function deposit(bytes32 invoiceId, address seller, uint256 amount) external nonReentrant {
        if (amount == 0) revert ZeroAmount();
        if (seller == address(0)) revert ZeroAddress();

        Escrow storage escrow = escrows[invoiceId];
        if (escrow.amount != 0) revert AlreadyFunded();

        escrow.buyer = msg.sender;
        escrow.seller = seller;
        escrow.amount = amount;

        token.safeTransferFrom(msg.sender, address(this), amount);

        emit EscrowDeposited(invoiceId, msg.sender, seller, amount);
    }

    /**
     * @notice Releases the held amount to the seller.
     * @dev Only the settler may call this, and only once. The off-chain service decides
     *      when delivery has been confirmed; this contract only enforces that the decision
     *      cannot be acted on twice.
     */
    function release(bytes32 invoiceId) external onlySettler nonReentrant {
        Escrow storage escrow = escrows[invoiceId];
        uint256 amount = escrow.amount;
        if (amount == 0) revert NothingHeld();
        if (escrow.released || escrow.refunded) revert AlreadyClosed();

        // Marked closed before the transfer, so a token with a callback cannot re-enter
        // and be paid twice. The guard makes this belt and braces; the ordering is the belt.
        escrow.released = true;
        address seller = escrow.seller;

        token.safeTransfer(seller, amount);

        emit EscrowReleased(invoiceId, seller, amount);
    }

    /// @notice Returns the held amount to the buyer, for a dispute or a cancelled invoice.
    function refund(bytes32 invoiceId) external onlySettler nonReentrant {
        Escrow storage escrow = escrows[invoiceId];
        uint256 amount = escrow.amount;
        if (amount == 0) revert NothingHeld();
        if (escrow.released || escrow.refunded) revert AlreadyClosed();

        escrow.refunded = true;
        address buyer = escrow.buyer;

        token.safeTransfer(buyer, amount);

        emit EscrowRefunded(invoiceId, buyer, amount);
    }

    /// @notice What is held against an invoice, and whether it has already been closed out.
    function heldFor(bytes32 invoiceId)
        external
        view
        returns (uint256 amount, bool released, bool refunded)
    {
        Escrow storage escrow = escrows[invoiceId];
        return (escrow.amount, escrow.released, escrow.refunded);
    }
}
