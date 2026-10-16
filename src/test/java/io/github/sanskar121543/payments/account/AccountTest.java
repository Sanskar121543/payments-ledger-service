package io.github.sanskar121543.payments.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void customerCannotBeDebitedBelowZero() {
        Account account = Account.customer("alice");
        account.credit(500);

        assertThat(account.canDebit(500)).isTrue();
        assertThat(account.canDebit(501)).isFalse();
        assertThatThrownBy(() -> account.debit(501)).isInstanceOf(IllegalStateException.class);
        assertThat(account.getBalanceCents()).isEqualTo(500);
    }

    @Test
    void debitAndCreditMoveTheBalance() {
        Account account = Account.customer("bob");
        account.credit(1_000);
        account.debit(400);

        assertThat(account.getBalanceCents()).isEqualTo(600);
    }
}
