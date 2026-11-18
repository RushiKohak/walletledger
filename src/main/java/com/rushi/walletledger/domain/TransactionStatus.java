package com.rushi.walletledger.domain;

/**
 * Lifecycle of a ledger transaction. A transaction row is only ever written when the
 * business decision succeeded and the whole thing committed, so POSTED is the only
 * state that exists today; a failed attempt rolls back and leaves no row behind.
 */
public enum TransactionStatus {
    POSTED
}
