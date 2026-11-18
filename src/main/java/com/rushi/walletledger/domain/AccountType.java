package com.rushi.walletledger.domain;

/**
 * USER accounts belong to a customer and may never hold a negative balance.
 * SYSTEM accounts (the treasury) are the counterparty of every deposit and may go negative.
 */
public enum AccountType {
    USER,
    SYSTEM
}
