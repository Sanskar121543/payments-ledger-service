package io.github.sanskar121543.payments.account;

import io.github.sanskar121543.payments.idempotency.IdempotencyService;
import io.github.sanskar121543.payments.idempotency.RequestFingerprint;
import io.github.sanskar121543.payments.idempotency.ResponseFactory;
import io.github.sanskar121543.payments.idempotency.StoredResponse;
import io.github.sanskar121543.payments.ledger.LedgerEntry;
import io.github.sanskar121543.payments.ledger.LedgerEntryRepository;
import io.github.sanskar121543.payments.web.NotFoundException;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final IdempotencyService idempotency;
    private final ResponseFactory responses;

    public AccountService(AccountRepository accounts, LedgerEntryRepository entries, IdempotencyService idempotency,
            ResponseFactory responses) {
        this.accounts = accounts;
        this.entries = entries;
        this.idempotency = idempotency;
        this.responses = responses;
    }

    @Transactional
    public StoredResponse create(String clientId, String idempotencyKey, CreateAccountRequest request) {
        String fingerprint = RequestFingerprint.of("POST", "/v1/accounts", request.name());
        return idempotency.execute(clientId, idempotencyKey, fingerprint, () -> {
            Account account = accounts.save(Account.customer(request.name()));
            return responses.json(HttpStatus.CREATED.value(), AccountResponse.from(account));
        });
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
