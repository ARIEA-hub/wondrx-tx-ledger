package com.wondrx.txledger.service.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    private final UUID userId;
    private final BigDecimal balance;
    private final BigDecimal requested;

    public InsufficientFundsException(UUID userId, BigDecimal balance, BigDecimal requested) {
        super("Insufficient funds for user " + userId + ": balance=" + balance + ", requested=" + requested);
        this.userId = userId;
        this.balance = balance;
        this.requested = requested;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}
