package com.wondrx.txledger.repository;

import com.wondrx.txledger.model.ProcessedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedTransactionRepository extends JpaRepository<ProcessedTransaction, UUID> {
}
