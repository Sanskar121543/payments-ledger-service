package io.github.sanskar121543.payments.transfer;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findAllByOrderByIdDesc(Limit limit);
}
