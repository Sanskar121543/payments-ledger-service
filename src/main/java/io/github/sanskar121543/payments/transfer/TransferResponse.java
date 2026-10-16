package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.ledger.EntryResponse;
import io.github.sanskar121543.payments.ledger.LedgerEntry;
import java.time.Instant;
import java.util.List;

public record TransferResponse(
        long id,
        TransferStatus status,
        long fromAccount,
        long toAccount,
        long amountCents,
        String failureReason,
        String correlationId,
        Instant createdAt,
        List<EntryResponse> entries) {

    public static TransferResponse of(Transfer transfer, List<LedgerEntry> entries) {
        return new TransferResponse(transfer.getId(), transfer.getStatus(), transfer.getFromAccountId(),
                transfer.getToAccountId(), transfer.getAmountCents(), transfer.getFailureReason(),
                transfer.getCorrelationId(), transfer.getCreatedAt(),
                entries.stream().map(EntryResponse::from).toList());
    }
}
