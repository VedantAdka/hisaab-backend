package com.vedant.hisaab.repository;

import com.vedant.hisaab.entity.SettlementRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {
    List<SettlementRecord> findByGroupId(Long groupId);
}
