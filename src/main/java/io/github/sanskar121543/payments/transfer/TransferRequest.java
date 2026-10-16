package io.github.sanskar121543.payments.transfer;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotNull @Positive Long fromAccount,
        @NotNull @Positive Long toAccount,
        @NotNull @Positive @Max(MAX_AMOUNT_CENTS) Long amountCents) {

    public static final long MAX_AMOUNT_CENTS = 100_000_000L;

    @AssertTrue(message = "fromAccount and toAccount must be different accounts")
    public boolean isDifferentAccounts() {
        return fromAccount == null || !fromAccount.equals(toAccount);
    }
}
