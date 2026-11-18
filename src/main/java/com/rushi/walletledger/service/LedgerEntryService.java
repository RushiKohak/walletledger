package com.rushi.walletledger.service;

import com.rushi.walletledger.error.AccountNotFoundException;
import com.rushi.walletledger.error.InvalidRequestException;
import com.rushi.walletledger.repository.AccountRepository;
import com.rushi.walletledger.repository.LedgerEntryRepository;
import com.rushi.walletledger.repository.LedgerEntryView;
import com.rushi.walletledger.web.dto.LedgerEntryResponse;
import com.rushi.walletledger.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries over an account's ledger history.
 *
 * <p>Nothing here moves money: it is one paged read, so it joins the read-only transaction shape
 * every other query endpoint uses and never touches the locking path.
 */
@Service
public class LedgerEntryService {

    /** The largest page a client may ask for, so one request cannot pull the whole ledger. */
    static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerEntryService(AccountRepository accountRepository,
                              LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * One page of an account's entries, newest first.
     *
     * <p>The range check lives here rather than on the method parameters so the answer is the same
     * error body as every other rejection, instead of a framework-specific binding message.
     *
     * <p>The account is looked up before the entries are read: an account with no history and an
     * account that does not exist are different answers, and only the first one is an empty page.
     */
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> entries(long accountId, int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        Page<LedgerEntryView> views =
                ledgerEntryRepository.findViewsByAccountId(accountId, PageRequest.of(page, size));
        return PageResponse.of(views.map(LedgerEntryResponse::from).getContent(),
                views.getNumber(), views.getSize(), views.getTotalElements(), views.getTotalPages());
    }
}
