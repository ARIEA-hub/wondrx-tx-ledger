package com.wondrx.txledger.service.exception;

import java.util.UUID;

public class DuplicateTransactionException extends RuntimeException {
    private final UUID transactionId;

    public DuplicateTransactionException(UUID transactionId) {
        super("Duplicate transactionId, already processed or in flight: " + transactionId);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
