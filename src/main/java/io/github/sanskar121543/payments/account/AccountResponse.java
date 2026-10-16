package io.github.sanskar121543.payments.account;

import java.time.Instant;

public record AccountResponse(long id, String name, AccountType type, String currency, long balanceCents,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getName(), account.getType(), account.getCurrency(),
                account.getBalanceCents(), account.getCreatedAt());
    }
}
