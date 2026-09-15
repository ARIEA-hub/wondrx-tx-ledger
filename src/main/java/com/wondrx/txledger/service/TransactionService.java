package com.wondrx.txledger.service;

import com.wondrx.txledger.dto.TransactionRequest;
import com.wondrx.txledger.dto.TransactionResponse;
import com.wondrx.txledger.model.ProcessedTransaction;
import com.wondrx.txledger.service.exception.InsufficientFundsException;
import org.springframework.stereotype.Service;

/**
 * Idempotent transaction processor - the orchestrator. It deliberately holds
 * no @Transactional method of its own and does no direct repository access:
 * every step that needs a transaction boundary (claiming the idempotency key,
 * debiting the wallet, updating status) is delegated to a separate bean
 * (IdempotencyService / WalletService) so those beans' own @Transactional
 * proxies are always the ones actually invoked - never bypassed by an
 * in-class "this.foo()" call. See DECISIONS.md for why that distinction
 * matters here.
 *
 * The two concurrency problems in the brief are handled by two different
 * mechanisms, on purpose, because they are actually two different problems:
 *
 *  1. "Don't process the same transactionId twice" (idempotency) is solved by a
 *     database PRIMARY KEY constraint on ProcessedTransaction.transactionId,
 *     claimed in its own short REQUIRES_NEW transaction over in IdempotencyService.
 *     A constraint violation is the database itself telling us, atomically,
 *     "someone already got here first" - no application-level lock can race with it.
 *
 *  2. "Don't let concurrent debits push the balance negative" (the race condition
 *     test) is a completely different transactionId each time, so a unique
 *     constraint can't help - instead WalletService takes a pessimistic row lock
 *     (SELECT ... FOR UPDATE) on the wallet for the duration of the debit, which
 *     serializes concurrent debits against the *same wallet*.
 */
@Service
public class TransactionService {

    private final WalletService walletService;
    private final IdempotencyService idempotencyService;

    public TransactionService(WalletService walletService,
                               IdempotencyService idempotencyService) {
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
    }

    public TransactionResponse process(TransactionRequest request) {
        // Step 1: atomically claim this transactionId. If someone else already
        // claimed it (a genuine concurrent duplicate, or a retry of a request
        // we already handled), this throws DuplicateTransactionException and we
        // stop here - the wallet is never touched for a duplicate.
        idempotencyService.claim(request);

        // Step 2: we own this transactionId. Do the actual debit under a wallet-level lock.
        try {
            TransactionResponse response = walletService.debit(request);
            idempotencyService.markStatus(request.getTransactionId(), ProcessedTransaction.TransactionStatus.SUCCESS);
            return response;
        } catch (InsufficientFundsException e) {
            idempotencyService.markStatus(request.getTransactionId(), ProcessedTransaction.TransactionStatus.INSUFFICIENT_FUNDS);
            throw e;
        }
    }
}
