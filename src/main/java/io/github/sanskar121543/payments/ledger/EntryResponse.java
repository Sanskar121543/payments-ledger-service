package io.github.sanskar121543.payments.ledger;

import java.time.Instant;

public record EntryResponse(long id, long transferId, long accountId, long amountCents, String direction,
        Instant createdAt) {

    public static EntryResponse from(LedgerEntry entry) {
        return new EntryResponse(entry.getId(), entry.getTransferId(), entry.getAccountId(), entry.getAmountCents(),
                entry.getAmountCents() < 0 ? "DEBIT" : "CREDIT", entry.getCreatedAt());
    }
}
