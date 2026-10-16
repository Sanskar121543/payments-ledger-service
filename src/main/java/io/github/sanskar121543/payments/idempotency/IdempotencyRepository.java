package io.github.sanskar121543.payments.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * Returns 1 if this call claimed the key, 0 if it already existed. If another transaction has
     * inserted the same key but not committed yet, Postgres makes this statement wait for it.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (client_id, idem_key, request_hash)
            VALUES (:clientId, :key, :requestHash)
            ON CONFLICT (client_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("clientId") String clientId, @Param("key") String key,
            @Param("requestHash") String requestHash);

    Optional<IdempotencyRecord> findByClientIdAndIdemKey(String clientId, String idemKey);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
