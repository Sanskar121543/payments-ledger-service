package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.account.Account;
import io.github.sanskar121543.payments.account.AccountRepository;
import io.github.sanskar121543.payments.ledger.LedgerEntry;
import io.github.sanskar121543.payments.ledger.LedgerEntryRepository;
import io.github.sanskar121543.payments.web.CorrelationIdFilter;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository entries;

    public TransferService(AccountRepository accounts, TransferRepository transfers, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
    }

    @Transactional
    public TransferOutcome transfer(TransferRequest request) {
        Optional<Account> from = accounts.findById(request.fromAccount());
        if (from.isEmpty()) {
            return new TransferOutcome.AccountNotFound(request.fromAccount());
        }
        Optional<Account> to = accounts.findById(request.toAccount());
        if (to.isEmpty()) {
            return new TransferOutcome.AccountNotFound(request.toAccount());
        }
        long amount = request.amountCents();
        Transfer transfer = transfers.save(Transfer.pending(request.fromAccount(), request.toAccount(), amount,
                MDC.get(CorrelationIdFilter.MDC_KEY)));

        // A failed transfer is returned, not thrown: throwing would roll back and erase the FAILED record.
        if (!from.get().canDebit(amount)) {
            transfer.fail(Transfer.INSUFFICIENT_FUNDS);
            return new TransferOutcome.Failed(transfer);
        }

        LedgerEntry debit = entries.save(LedgerEntry.debit(transfer.getId(), request.fromAccount(), amount));
        LedgerEntry credit = entries.save(LedgerEntry.credit(transfer.getId(), request.toAccount(), amount));
        from.get().debit(amount);
        to.get().credit(amount);
        transfer.complete();
        return new TransferOutcome.Completed(transfer, List.of(debit, credit));
    }
}
