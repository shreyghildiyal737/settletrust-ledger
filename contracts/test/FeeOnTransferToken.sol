// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/**
 * @title FeeOnTransferToken
 * @notice An ERC-20 that delivers less than it was asked to move.
 *
 * @dev Not a hypothetical. Plenty of real tokens skim a fee on every transfer, and they
 *      are ordinary ERC-20s otherwise: the transfer succeeds, returns true, and the
 *      recipient is simply short. Nothing in the standard forbids it and nothing in
 *      SafeERC20 notices it.
 *
 *      It exists here to answer one question about InvoiceEscrow: when the contract is
 *      told to take 100 and receives 97, what does it then tell the watcher it holds.
 */
contract FeeOnTransferToken is ERC20 {
    /// @dev Somewhere for the skim to go that is not the zero address, so it is a
    ///      transfer rather than a burn and behaves like the real thing.
    address public constant COLLECTOR = address(0xFEE);

    uint256 public immutable feeBips;

    constructor(uint256 feeBips_) ERC20("Fee Coin", "FEEC") {
        feeBips = feeBips_;
    }

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function _update(address from, address to, uint256 value) internal override {
        // Mints and burns move the full amount; only a transfer between holders is skimmed.
        if (from == address(0) || to == address(0) || feeBips == 0) {
            super._update(from, to, value);
            return;
        }

        uint256 fee = (value * feeBips) / 10_000;
        super._update(from, COLLECTOR, fee);
        super._update(from, to, value - fee);
    }
}
