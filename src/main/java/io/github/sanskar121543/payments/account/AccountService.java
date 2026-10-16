package io.github.sanskar121543.payments.account;

import io.github.sanskar121543.payments.ledger.LedgerEntry;
import io.github.sanskar121543.payments.ledger.LedgerEntryRepository;
import io.github.sanskar121543.payments.web.NotFoundException;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;

    public AccountService(AccountRepository accounts, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @Transactional
    public Account create(String name) {
        return accounts.save(Account.customer(name));
    }

    @Transactional(readOnly = true)
    public Account get(long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("account-not-found", "Account " + id + " does not exist"));
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entries(long accountId, int limit) {
        get(accountId);
        return entries.findByAccountIdOrderByIdDesc(accountId, Limit.of(limit));
    }
}
