package com.wondrx.txledger.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per transactionId we have ever seen. transactionId is the primary key,
 * so the database itself enforces "only one row per transactionId" - that's what
 * makes idempotency claiming atomic even under concurrent requests, without an
 * application-level lock.
 *
 * Implements Persistable on purpose: with a manually-assigned @Id (no
 * @GeneratedValue) and no @Version, Spring Data JPA's default isNew() check
 * looks at whether the id is null - and it never is here, we always set
 * transactionId in the constructor. Without this, save() would route through
 * entityManager.merge() instead of persist() even for a brand-new row, and
 * merge() on a transactionId that already exists just UPDATEs it instead of
 * throwing a constraint violation - silently defeating the whole idempotency
 * check for the one case it exists to catch. isNew is a transient flag that
 * starts true (a freshly-constructed instance really is new) and flips to
 * false the moment JPA has persisted or loaded it, so save()->persist() only
 * ever fires for a genuinely new transactionId. See DECISIONS.md.
 */
@Entity
@Table(name = "processed_transactions")
public class ProcessedTransaction implements Persistable<UUID> {

    @Id
    private UUID transactionId;

    private UUID userId;

    private BigDecimal amount;

    private String type;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedTransaction() {
        // JPA
    }

    public ProcessedTransaction(UUID transactionId, UUID userId, BigDecimal amount, String type) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PROCESSING;
        this.processedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
        this.processedAt = Instant.now();
    }

    public enum TransactionStatus {
        PROCESSING,
        SUCCESS,
        INSUFFICIENT_FUNDS
    }
}
