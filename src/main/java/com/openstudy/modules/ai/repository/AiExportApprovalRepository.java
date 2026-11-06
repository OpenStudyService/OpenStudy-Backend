package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiExportApprovalEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiExportApprovalRepository extends JpaRepository<AiExportApprovalEntity, String> {
    List<AiExportApprovalEntity> findAllByOrderByRequestedAtDesc();
    List<AiExportApprovalEntity> findByRequesterEmployeeIdOrderByRequestedAtDesc(long requesterEmployeeId);
}
