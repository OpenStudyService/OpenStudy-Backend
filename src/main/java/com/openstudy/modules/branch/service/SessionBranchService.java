package com.openstudy.modules.branch.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.branch.dto.SessionBranch;
import com.openstudy.modules.branch.entity.Branch;
import com.openstudy.modules.branch.entity.BranchStatus;
import com.openstudy.modules.branch.repository.SessionBranchRepository;

@Service
public class SessionBranchService {
    private final SessionBranchRepository repository;

    public SessionBranchService(SessionBranchRepository repository) {
        this.repository = repository;
    }

    public List<SessionBranch> accessible(long employeeId, boolean owner) {
        return repository.findAccessible(employeeId, owner).stream().map(SessionBranchService::toSessionBranch).toList();
    }

    /** Names for screens shown before branch access exists (e.g. approval status); no authorization is implied. */
    public Map<Long, String> names(Collection<Long> branchIds) {
        if (branchIds.isEmpty()) return Map.of();
        return repository.findByIdIn(branchIds).stream().collect(Collectors.toMap(Branch::getId, Branch::getName));
    }

    private static SessionBranch toSessionBranch(Branch b) {
        String id = Long.toString(b.getId());
        return new SessionBranch(id, id, ApiVersion.fromDatabase(b.getVersion()), b.getCreatedAt().toString(),
                b.getUpdatedAt().toString(), b.getName(), b.getAddress(), b.getPhone() == null ? "" : b.getPhone(),
                "", b.getStatus() == BranchStatus.ACTIVE ? "운영 중" : "운영 중지");
    }
}
