package io.github.sanskar121543.payments.transfer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findAllByOrderByIdDesc(Limit limit);

    boolean existsByReversesTransferIdAndStatus(long reversesTransferId, TransferStatus status);

    Optional<Transfer> findFirstByReversesTransferIdAndStatus(long reversesTransferId, TransferStatus status);

    List<Transfer> findByReversesTransferIdInAndStatus(Collection<Long> reversesTransferIds, TransferStatus status);
}
