package io.github.sanskar121543.payments.activity;

import io.github.sanskar121543.payments.common.InstanceInfo;
import io.github.sanskar121543.payments.common.LedgerMetrics;
import io.github.sanskar121543.payments.events.TransferCompletedEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private static final String SELECT_ITEM = """
            SELECT id, event_id, transfer_id, kind, from_account_id, to_account_id, amount_cents, served_by,
                   consumed_by, correlation_id, occurred_at, received_at, large_amount, high_velocity
            FROM activity_feed
            """;

    private final JdbcTemplate jdbc;
    private final InstanceInfo instance;
    private final LedgerMetrics metrics;
    private final long largeAmountCents;
    private final int velocityMaxPerMinute;

    public ActivityService(JdbcTemplate jdbc, InstanceInfo instance, LedgerMetrics metrics,
            @Value("${payments.rules.large-amount-cents}") long largeAmountCents,
            @Value("${payments.rules.velocity-max-per-minute}") int velocityMaxPerMinute) {
        this.jdbc = jdbc;
        this.instance = instance;
        this.metrics = metrics;
        this.largeAmountCents = largeAmountCents;
        this.velocityMaxPerMinute = velocityMaxPerMinute;
    }

    /**
     * Records an event in the feed and evaluates the rules. Kafka delivers at least once, so the unique
     * event_id turns a redelivery into a no-op. Returns false for a duplicate.
     */
    @Transactional
    public boolean record(TransferCompletedEvent event) {
        boolean largeAmount = event.amountCents() >= largeAmountCents;
        // All of a sender's events share one partition, so they are processed one at a time, in order.
        Long sentInLastMinute = jdbc.queryForObject("""
                SELECT COUNT(*) FROM activity_feed
                WHERE from_account_id = ? AND occurred_at > ? AND occurred_at <= ? AND event_id <> ?
                """, Long.class, event.fromAccountId(), utc(event.occurredAt().minusSeconds(60)),
                utc(event.occurredAt()), event.eventId());
        boolean highVelocity = sentInLastMinute != null && sentInLastMinute + 1 > velocityMaxPerMinute;
        int inserted = jdbc.update("""
                INSERT INTO activity_feed (event_id, transfer_id, kind, from_account_id, to_account_id, amount_cents,
                                           served_by, consumed_by, correlation_id, occurred_at, large_amount,
                                           high_velocity)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.transferId(), event.kind(), event.fromAccountId(), event.toAccountId(),
                event.amountCents(), event.servedBy(), instance.id(), event.correlationId(), utc(event.occurredAt()),
                largeAmount, highVelocity);
        if (inserted == 1) {
            if (largeAmount) {
                metrics.ruleHit("large_amount");
            }
            if (highVelocity) {
                metrics.ruleHit("high_velocity");
            }
        }
        return inserted == 1;
    }

    /**
     * Rows newer than the cursor that are at least a second old. Ids are handed out at insert time but
     * become visible at commit, so a brand-new row could still have a smaller id than one we have already
     * shown; waiting a second lets those in-flight inserts commit first.
     */
    @Transactional(readOnly = true)
    public List<ActivityItem> settledAfter(long cursor, int limit) {
        return jdbc.query(SELECT_ITEM + " WHERE id > ? AND received_at < now() - interval '1 second' ORDER BY id LIMIT ?",
                ActivityService::map, cursor, limit);
    }

    @Transactional(readOnly = true)
    public long latestSettledId() {
        Long id = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM activity_feed WHERE received_at < now() - interval '1 second'",
                Long.class);
        return id == null ? 0 : id;
    }

    @Transactional(readOnly = true)
    public List<ActivityItem> latest(int limit) {
        return jdbc.query(SELECT_ITEM + " ORDER BY id DESC LIMIT ?", ActivityService::map, limit);
    }

    @Transactional(readOnly = true)
    public List<ActivityItem> forTransfer(long transferId) {
        return jdbc.query(SELECT_ITEM + " WHERE transfer_id = ? ORDER BY id", ActivityService::map, transferId);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static ActivityItem map(ResultSet rs, int row) throws SQLException {
        return new ActivityItem(rs.getLong("id"), rs.getObject("event_id", UUID.class), rs.getLong("transfer_id"),
                rs.getString("kind"), rs.getLong("from_account_id"), rs.getLong("to_account_id"),
                rs.getLong("amount_cents"), rs.getString("served_by"), rs.getString("consumed_by"),
                rs.getString("correlation_id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(), rs.getBoolean("large_amount"),
                rs.getBoolean("high_velocity"));
    }
}
