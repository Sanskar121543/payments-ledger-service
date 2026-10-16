package io.github.sanskar121543.payments.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LockOrderTest {

    @Test
    void smallerIdIsAlwaysLockedFirstWhateverTheDirection() {
        assertThat(TransferService.lockOrder(7, 3)).containsExactly(3, 7);
        assertThat(TransferService.lockOrder(3, 7)).containsExactly(3, 7);
    }
}
