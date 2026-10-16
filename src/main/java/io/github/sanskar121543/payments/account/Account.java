package io.github.sanskar121543.payments.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType type;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    private Account(String name, AccountType type) {
        this.name = name;
        this.type = type;
        this.currency = "USD";
        this.createdAt = Instant.now();
    }

    public static Account customer(String name) {
        return new Account(name, AccountType.CUSTOMER);
    }

    public boolean canDebit(long amountCents) {
        return type == AccountType.FUNDING || balanceCents >= amountCents;
    }

    public void debit(long amountCents) {
        if (!canDebit(amountCents)) {
            throw new IllegalStateException("Account " + id + " cannot cover " + amountCents + " cents");
        }
        balanceCents = Math.subtractExact(balanceCents, amountCents);
    }

    public void credit(long amountCents) {
        balanceCents = Math.addExact(balanceCents, amountCents);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
