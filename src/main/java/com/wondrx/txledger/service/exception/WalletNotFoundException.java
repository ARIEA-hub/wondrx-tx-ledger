package com.wondrx.txledger.service.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(UUID userId) {
        super("No wallet found for user " + userId);
    }
}
