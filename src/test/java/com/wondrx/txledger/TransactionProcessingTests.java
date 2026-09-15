package com.wondrx.txledger;

import com.wondrx.txledger.dto.TransactionRequest;
import com.wondrx.txledger.model.Wallet;
import com.wondrx.txledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the idempotent transaction processor.
 *
 * Deliberately uses a real HTTP client (TestRestTemplate) against a randomly
 * assigned port rather than MockMvc, and a real thread pool, so the
 * "concurrent" tests exercise actual concurrent database transactions against
 * the in-memory H2 instance - not just concurrent Java method calls sharing
 * one thread's transaction context. That distinction is exactly what this
 * assignment is testing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionProcessingTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/transactions/process";
    }

    private UUID seedWallet(BigDecimal startingBalance) {
        UUID userId = UUID.randomUUID();
        walletRepository.save(new Wallet(userId, startingBalance));
        return userId;
    }

    @BeforeEach
    void logSeparator() {
        System.out.println();
        System.out.println("========================================================");
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void processesSingleValidDebitTransactionSuccessfully() {
        System.out.println("INTENT: submit exactly one DEBIT of 250.00 against a wallet that " +
                "starts with a 1000.00 balance, and expect it to succeed with the balance reduced accordingly.");

        UUID userId = seedWallet(new BigDecimal("1000.00"));
        TransactionRequest request = new TransactionRequest(
                UUID.randomUUID(), userId, new BigDecimal("250.00"), "DEBIT");

        ResponseEntity<TransactionResponseView> response =
                restTemplate.postForEntity(baseUrl(), request, TransactionResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status).isEqualTo("SUCCESS");
        assertThat(response.getBody().newBalance).isEqualByComparingTo("750.00");

        Wallet wallet = walletRepository.findById(userId).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("750.00");

        System.out.println("RESULT: PASS - single debit succeeded, balance went 1000.00 -> " + wallet.getBalance());
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void sendsThreeIdenticalTransactionIdsSimultaneously_balanceDeductedOnce() throws InterruptedException {
        System.out.println("INTENT: fire 3 requests carrying the SAME transactionId at each other as close to " +
                "simultaneously as possible (simulating a payment gateway's duplicate webhook retries), and expect " +
                "exactly ONE of them to actually succeed and deduct the balance - the other two must be rejected " +
                "with 409 Conflict and must NOT touch the balance a second or third time.");

        UUID userId = seedWallet(new BigDecimal("500.00"));
        UUID sharedTransactionId = UUID.randomUUID();
        BigDecimal debitAmount = new BigDecimal("100.00");

        int attempts = 3;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<HttpStatus>> futures = IntStream.range(0, attempts)
                .mapToObj(i -> pool.submit(() -> {
                    TransactionRequest request = new TransactionRequest(
                            sharedTransactionId, userId, debitAmount, "DEBIT");
                    ready.countDown();
                    go.await();
                    ResponseEntity<TransactionResponseView> response =
                            restTemplate.postForEntity(baseUrl(), request, TransactionResponseView.class);
                    return response.getStatusCode();
                }))
                .collect(Collectors.toList());

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        List<HttpStatus> outcomes = futures.stream().map(f -> {
            try {
                return f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
        pool.shutdown();

        long successCount = outcomes.stream().filter(s -> s == HttpStatus.OK).count();
        long conflictCount = outcomes.stream().filter(s -> s == HttpStatus.CONFLICT).count();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(2);

        Wallet wallet = walletRepository.findById(userId).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("400.00");

        System.out.println("RESULT: PASS - outcomes=" + outcomes + ", successes=" + successCount +
                ", conflicts=" + conflictCount + ", final balance=" + wallet.getBalance() +
                " (expected exactly 1 success, 2 conflicts, balance 500.00 -> 400.00)");
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of Rs100 for a wallet with a Rs500 balance. " +
            "Ensures the final balance is exactly Rs0 and 5 requests fail with insufficient funds.")
    void sendsTenConcurrentDebitsAgainstFiveHundredBalance_exactlyFiveSucceedFiveFail() throws InterruptedException {
        System.out.println("INTENT: fire 10 concurrent DEBIT requests of 100.00 each (DIFFERENT transactionIds, " +
                "so idempotency does not apply here) against a single wallet that starts at 500.00, and expect " +
                "the pessimistic wallet lock to serialize them so that exactly 5 succeed, exactly 5 are rejected " +
                "with 422 Insufficient Funds, and the balance never dips below zero and ends at exactly 0.00.");

        UUID userId = seedWallet(new BigDecimal("500.00"));
        BigDecimal debitAmount = new BigDecimal("100.00");
        int attempts = 10;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<HttpStatus>> futures = IntStream.range(0, attempts)
                .mapToObj(i -> pool.submit(() -> {
                    TransactionRequest request = new TransactionRequest(
                            UUID.randomUUID(), userId, debitAmount, "DEBIT");
                    ready.countDown();
                    go.await();
                    ResponseEntity<TransactionResponseView> response =
                            restTemplate.postForEntity(baseUrl(), request, TransactionResponseView.class);
                    return response.getStatusCode();
                }))
                .collect(Collectors.toList());

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientFundsCount = new AtomicInteger();
        List<HttpStatus> outcomes = futures.stream().map(f -> {
            try {
                HttpStatus status = f.get(10, TimeUnit.SECONDS);
                if (status == HttpStatus.OK) successCount.incrementAndGet();
                if (status == HttpStatus.UNPROCESSABLE_ENTITY) insufficientFundsCount.incrementAndGet();
                return status;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
        pool.shutdown();

        Wallet wallet = walletRepository.findById(userId).orElseThrow();

        assertThat(successCount.get()).isEqualTo(5);
        assertThat(insufficientFundsCount.get()).isEqualTo(5);
        assertThat(wallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(wallet.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        System.out.println("RESULT: PASS - outcomes=" + outcomes + ", successes=" + successCount.get() +
                ", insufficientFunds=" + insufficientFundsCount.get() + ", final balance=" + wallet.getBalance() +
                " (expected exactly 5 successes, 5 insufficient-funds rejections, balance 500.00 -> 0.00)");
    }

    /**
     * Minimal mirror of TransactionResponse for JSON deserialization in tests,
     * so the test module doesn't need to depend on internal DTO field ordering.
     */
    static class TransactionResponseView {
        public UUID transactionId;
        public UUID userId;
        public String status;
        public BigDecimal newBalance;
    }
}
