package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.ledger.LedgerEntry;
import java.util.List;

public sealed interface TransferOutcome {

    record Completed(Transfer transfer, List<LedgerEntry> entries) implements TransferOutcome {
    }

    record Failed(Transfer transfer) implements TransferOutcome {
    }

    record AccountNotFound(long accountId) implements TransferOutcome {
    }
}
