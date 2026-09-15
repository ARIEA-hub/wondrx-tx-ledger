package com.wondrx.txledger.controller;

import com.wondrx.txledger.dto.TransactionRequest;
import com.wondrx.txledger.dto.TransactionResponse;
import com.wondrx.txledger.service.TransactionService;
import com.wondrx.txledger.service.exception.DuplicateTransactionException;
import com.wondrx.txledger.service.exception.InsufficientFundsException;
import com.wondrx.txledger.service.exception.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> process(@RequestBody TransactionRequest request) {
        TransactionResponse response = transactionService.process(request);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateTransactionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "DUPLICATE_TRANSACTION",
                "transactionId", e.getTransactionId(),
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "INSUFFICIENT_FUNDS",
                "userId", e.getUserId(),
                "balance", e.getBalance(),
                "requested", e.getRequested()
        ));
    }

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWalletNotFound(WalletNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "WALLET_NOT_FOUND",
                "message", e.getMessage()
        ));
    }
}
