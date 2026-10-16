package io.github.sanskar121543.payments.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TransferTest {

    @Test
    void startsPendingAndCompletesOnce() {
        Transfer transfer = Transfer.pending(1, 2, 100, "corr");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);

        transfer.complete();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThatThrownBy(transfer::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.fail("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failingRecordsTheReason() {
        Transfer transfer = Transfer.pending(1, 2, 100, "corr");

        transfer.fail(Transfer.INSUFFICIENT_FUNDS);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureReason()).isEqualTo(Transfer.INSUFFICIENT_FUNDS);
    }
}
