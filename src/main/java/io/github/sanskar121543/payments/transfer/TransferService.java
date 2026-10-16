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

    /**
     * Moves money between two accounts, at most once per Idempotency-Key.
     *
     * TODO(practice) rules:
     * - One transaction for everything: the idempotency key, the transfer row, both ledger entries, both
     *   balances and the outbox event commit or roll back together.
     * - Fingerprint the validated request with RequestFingerprint.of("POST", "/v1/transfers", from, to, amount)
     *   and do the work inside idempotency.execute(...).
     * - Lock both account rows with accounts.findByIdForUpdate BEFORE reading a balance, in the order lockOrder
     *   gives. A missing account is responses.accountNotFound(id).
     * - Save Transfer.pending(...) (servedBy = instance.id(), correlation id = MDC.get(CorrelationIdFilter.MDC_KEY)).
     * - Not enough money: transfer.fail(Transfer.INSUFFICIENT_FUNDS), return responses.insufficientFunds(transfer).
     *   Return it, do not throw: a rollback would erase the FAILED record.
     * - Otherwise: save LedgerEntry.debit and LedgerEntry.credit, update both balances, transfer.complete(),
     *   outbox.write(completedEvent(transfer)), return responses.created(transfer, List.of(debit, credit)).
     */
    @Transactional
    public StoredResponse transfer(String clientId, String idempotencyKey, TransferRequest request) {
        throw new UnsupportedOperationException("practice: write TransferService.transfer");
    }

    /**
     * Sends the money of a COMPLETED transfer back, at most once.
     *
     * TODO(practice) rules:
     * - Same idempotency wrapping as transfer; fingerprint ("POST", "/v1/transfers/" + transferId + "/reversals").
     * - Unknown id: responses.transferNotFound(id). Not reversible: responses.notReversible(original).
     * - Lock the two accounts in the same global order as a transfer does.
     * - Only once you hold the locks, check transfers.existsByReversesTransferIdAndStatus(id, COMPLETED) and
     *   return responses.alreadyReversed(original) if so. Why only then? Think of two reversals arriving together.
     * - Save Transfer.pendingReversal(original, ...) and settle it exactly like a transfer (to -> from).
     */
    @Transactional
    public StoredResponse reverse(String clientId, String idempotencyKey, long transferId) {
        throw new UnsupportedOperationException("practice: write TransferService.reverse");
    }

    private static TransferCompletedEvent completedEvent(Transfer transfer) {
        return new TransferCompletedEvent(UUID.randomUUID(), transfer.getId(), transfer.getKind().name(),
                transfer.getFromAccountId(), transfer.getToAccountId(), transfer.getAmountCents(),
                transfer.getServedBy(), transfer.getCorrelationId(), Instant.now());
    }

    /**
     * TODO(practice): the two account ids in the order every transaction must lock them, so that two transfers
     * can never each hold one row while waiting for the other. LockOrderTest checks it.
     */
    static long[] lockOrder(long first, long second) {
        throw new UnsupportedOperationException("practice: write TransferService.lockOrder");
    }
}
