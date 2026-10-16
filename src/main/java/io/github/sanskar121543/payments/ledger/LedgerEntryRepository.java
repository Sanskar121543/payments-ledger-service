package io.github.sanskar121543.payments.ledger;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransferIdOrderByIdAsc(long transferId);

    List<LedgerEntry> findByTransferIdInOrderByIdAsc(Collection<Long> transferIds);

    List<LedgerEntry> findByAccountIdOrderByIdDesc(long accountId, Limit limit);
}
