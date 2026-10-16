package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.ledger.LedgerEntry;
import io.github.sanskar121543.payments.ledger.LedgerEntryRepository;
import io.github.sanskar121543.payments.web.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferQueries {

    private final TransferRepository transfers;
    private final LedgerEntryRepository entries;

    public TransferQueries(TransferRepository transfers, LedgerEntryRepository entries) {
        this.transfers = transfers;
        this.entries = entries;
    }

    @Transactional(readOnly = true)
    public TransferResponse get(long id) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> new NotFoundException("transfer-not-found", "Transfer " + id + " does not exist"));
        return TransferResponse.of(transfer, entries.findByTransferIdOrderByIdAsc(id));
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> recent(int limit) {
        List<Transfer> latest = transfers.findAllByOrderByIdDesc(Limit.of(limit));
        Map<Long, List<LedgerEntry>> entriesByTransfer = entries
                .findByTransferIdInOrderByIdAsc(latest.stream().map(Transfer::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(LedgerEntry::getTransferId));
        return latest.stream()
                .map(t -> TransferResponse.of(t, entriesByTransfer.getOrDefault(t.getId(), List.of())))
                .toList();
    }
}
