package com.wondrx.txledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class TransactionResponse {

    private UUID transactionId;
    private UUID userId;
    private String status;
    private BigDecimal newBalance;

    public TransactionResponse() {
    }

    public TransactionResponse(UUID transactionId, UUID userId, String status, BigDecimal newBalance) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.status = status;
        this.newBalance = newBalance;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getNewBalance() {
        return newBalance;
    }
}
