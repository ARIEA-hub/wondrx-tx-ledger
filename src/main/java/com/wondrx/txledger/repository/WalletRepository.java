package com.wondrx.txledger.repository;

import com.wondrx.txledger.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * SELECT ... FOR UPDATE. Takes a database row lock on this wallet for the
     * duration of the caller's transaction, so concurrent debits against the
     * same wallet are serialized instead of racing each other - that's what
     * stops the balance from ever going negative under concurrent load.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByIdForUpdate(@Param("userId") UUID userId);
}
