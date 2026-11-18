package com.rushi.walletledger.domain;

/** Which side of the double entry an amount sits on. Debits must equal credits per transaction. */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
