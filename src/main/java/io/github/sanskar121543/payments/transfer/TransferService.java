package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.account.Account;
import io.github.sanskar121543.payments.account.AccountRepository;
import io.github.sanskar121543.payments.common.InstanceInfo;
import io.github.sanskar121543.payments.events.TransferCompletedEvent;
import io.github.sanskar121543.payments.idempotency.IdempotencyService;
import io.github.sanskar121543.payments.idempotency.RequestFingerprint;
import io.github.sanskar121543.payments.idempotency.StoredResponse;
import io.github.sanskar121543.payments.ledger.LedgerEntry;
import io.github.sanskar121543.payments.ledger.LedgerEntryRepository;
import io.github.sanskar121543.payments.outbox.OutboxWriter;
import io.github.sanskar121543.payments.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository entries;
    private final IdempotencyService idempotency;
    private final TransferResponses responses;
    private final InstanceInfo instance;
    private final OutboxWriter outbox;

    TransferService(AccountRepository accounts, TransferRepository transfers, LedgerEntryRepository entries,
            IdempotencyService idempotency, TransferResponses responses, InstanceInfo instance,
            OutboxWriter outbox) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
        this.idempotency = idempotency;
        this.responses = responses;
        this.instance = instance;
        this.outbox = outbox;
    }

    @Transactional
    public StoredResponse transfer(String clientId, String idempotencyKey, TransferRequest request) {
        String fingerprint = RequestFingerprint.of("POST", "/v1/transfers",
                request.fromAccount(), request.toAccount(), request.amountCents());
        return idempotency.execute(clientId, idempotencyKey, fingerprint, () -> moveMoney(request));
    }

    @Transactional
    public StoredResponse reverse(String clientId, String idempotencyKey, long transferId) {
        String fingerprint = RequestFingerprint.of("POST", "/v1/transfers/" + transferId + "/reversals");
        return idempotency.execute(clientId, idempotencyKey, fingerprint, () -> reverseTransfer(transferId));
    }

    private StoredResponse moveMoney(TransferRequest request) {
        LockedAccounts locked = lockInOrder(request.fromAccount(), request.toAccount());
        if (locked.missingAccountId() != null) {
            return responses.accountNotFound(locked.missingAccountId());
        }
        Transfer transfer = transfers.save(Transfer.pending(request.fromAccount(), request.toAccount(),
                request.amountCents(), instance.id(), MDC.get(CorrelationIdFilter.MDC_KEY)));
        return settle(transfer, locked.from(), locked.to());
    }

    private StoredResponse reverseTransfer(long originalId) {
        Optional<Transfer> found = transfers.findById(originalId);
        if (found.isEmpty()) {
            return responses.transferNotFound(originalId);
        }
        Transfer original = found.get();
        if (!original.isReversible()) {
            return responses.notReversible(original);
        }
        LockedAccounts locked = lockInOrder(original.getToAccountId(), original.getFromAccountId());
        // Checked only after locking: a concurrent reversal of the same transfer must wait for these locks,
        // so by the time we get them, its COMPLETED row is committed and visible here.
        if (transfers.existsByReversesTransferIdAndStatus(originalId, TransferStatus.COMPLETED)) {
            return responses.alreadyReversed(original);
        }
        Transfer reversal = transfers.save(
                Transfer.pendingReversal(original, instance.id(), MDC.get(CorrelationIdFilter.MDC_KEY)));
        return settle(reversal, locked.from(), locked.to());
    }

    private StoredResponse settle(Transfer transfer, Account from, Account to) {
        long amount = transfer.getAmountCents();
        // A failed transfer is returned, not thrown: throwing would roll back and erase the FAILED record.
        if (!from.canDebit(amount)) {
            transfer.fail(Transfer.INSUFFICIENT_FUNDS);
            return responses.insufficientFunds(transfer);
        }
        LedgerEntry debit = entries.save(LedgerEntry.debit(transfer.getId(), from.getId(), amount));
        LedgerEntry credit = entries.save(LedgerEntry.credit(transfer.getId(), to.getId(), amount));
        from.debit(amount);
        to.credit(amount);
        transfer.complete();
        // Same transaction as the entries: the event exists if and only if the money moved.
        outbox.write(completedEvent(transfer));
        return responses.created(transfer, List.of(debit, credit));
    }

    private static TransferCompletedEvent completedEvent(Transfer transfer) {
        return new TransferCompletedEvent(UUID.randomUUID(), transfer.getId(), transfer.getKind().name(),
                transfer.getFromAccountId(), transfer.getToAccountId(), transfer.getAmountCents(),
                transfer.getServedBy(), transfer.getCorrelationId(), Instant.now());
    }

    /**
     * Locks both account rows (SELECT ... FOR NO KEY UPDATE) smallest id first. Because every transfer
     * uses the same order, two transfers can never each hold one row while waiting for the other.
     */
    private LockedAccounts lockInOrder(long fromId, long toId) {
        Map<Long, Account> locked = new HashMap<>();
        for (long id : lockOrder(fromId, toId)) {
            Optional<Account> account = accounts.findByIdForUpdate(id);
            if (account.isEmpty()) {
                return new LockedAccounts(null, null, id);
            }
            locked.put(id, account.get());
        }
        return new LockedAccounts(locked.get(fromId), locked.get(toId), null);
    }

    static long[] lockOrder(long first, long second) {
        return first < second ? new long[] {first, second} : new long[] {second, first};
    }

    private record LockedAccounts(Account from, Account to, Long missingAccountId) {
    }
}
