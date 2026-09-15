package com.wondrx.txledger.service;

import com.wondrx.txledger.model.ProcessedTransaction;
import com.wondrx.txledger.repository.ProcessedTransactionRepository;
import com.wondrx.txledger.service.exception.DuplicateTransactionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wondrx.txledger.dto.TransactionRequest;

import java.util.UUID;

/**
 * Deliberately its own Spring bean, not a couple of private methods on
 * TransactionService. Each method here needs to run in its own short,
 * independently-committed REQUIRES_NEW transaction (see class javadoc on
 * TransactionService for why). Spring's @Transactional is implemented as a
 * proxy wrapping the bean; a call from *inside* the same class
 * (this.claimTransactionId(...)) skips that proxy entirely and the
 * propagation setting is silently ignored. Splitting this into a separate
 * bean that TransactionService calls through means the proxy - and the
 * transaction boundary it enforces - is actually honoured. (This is exactly
 * the mistake documented in DECISIONS.md: the first draft had these methods
 * on TransactionService itself, calling one another directly.)
 */
@Service
public class IdempotencyService {

    private final ProcessedTransactionRepository transactionRepository;

    public IdempotencyService(ProcessedTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(TransactionRequest request) {
        try {
            ProcessedTransaction pt = new ProcessedTransaction(
                    request.getTransactionId(),
                    request.getUserId(),
                    request.getAmount(),
                    request.getType());
            transactionRepository.saveAndFlush(pt);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateTransactionException(request.getTransactionId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStatus(UUID transactionId, ProcessedTransaction.TransactionStatus status) {
        transactionRepository.findById(transactionId).ifPresent(pt -> {
            pt.setStatus(status);
            transactionRepository.save(pt);
        });
    }
}
