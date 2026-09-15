package com.wondrx.txledger.service;

import com.wondrx.txledger.dto.TransactionRequest;
import com.wondrx.txledger.dto.TransactionResponse;
import com.wondrx.txledger.model.Wallet;
import com.wondrx.txledger.repository.WalletRepository;
import com.wondrx.txledger.service.exception.InsufficientFundsException;
import com.wondrx.txledger.service.exception.WalletNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Its own bean, same reason as IdempotencyService: findByIdForUpdate() takes a
 * database row lock (SELECT ... FOR UPDATE) that must stay held for the whole
 * read-check-write sequence below. That only happens if debit() runs inside a
 * single transaction opened by Spring's @Transactional proxy. If TransactionService
 * called a method like this on *itself* (this.debit(...)), the proxy would be
 * skipped, Spring Data would silently open-and-close its own short transaction
 * just for the findByIdForUpdate() call, the lock would be released the instant
 * that query returned, and two concurrent debits could both read the same
 * starting balance before either writes - exactly the negative-balance bug this
 * method exists to prevent. See DECISIONS.md.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public TransactionResponse debit(TransactionRequest request) {
        Wallet wallet = walletRepository.findByIdForUpdate(request.getUserId())
                .orElseThrow(() -> new WalletNotFoundException(request.getUserId()));

        BigDecimal newBalance = wallet.getBalance().subtract(request.getAmount());
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(request.getUserId(), wallet.getBalance(), request.getAmount());
        }

        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        return new TransactionResponse(
                request.getTransactionId(),
                request.getUserId(),
                "SUCCESS",
                newBalance);
    }
}
