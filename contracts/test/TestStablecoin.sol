// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/**
 * @title TestStablecoin
 * @notice A mintable ERC-20 standing in for USDC on a local chain.
 *
 * @dev Six decimals, because that is what the stablecoins this settles in actually use
 *      and the off-chain ledger reads the token's own decimals to decide what a minor
 *      unit is. A mock with eighteen would let a scaling bug pass unnoticed here and fail
 *      against the real thing.
 *
 *      Anyone may mint. This never leaves a local chain, and a test that has to be an
 *      owner to fund a buyer is a test about ownership.
 */
contract TestStablecoin is ERC20 {
    constructor() ERC20("Test USD Coin", "USDC") {}

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }
}
