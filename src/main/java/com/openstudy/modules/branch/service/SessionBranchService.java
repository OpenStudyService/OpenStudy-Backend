package com.openstudy.modules.branch.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.branch.dto.SessionBranch;
import com.openstudy.modules.branch.entity.Branch;
import com.openstudy.modules.branch.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class SessionBranchService {

    private final BranchRepository branchRepository;

    public List<SessionBranch> accessible(long employeeId, boolean owner) {
        return branchRepository.findAccessible(employeeId, owner)
                .stream()
                .map(SessionBranchService::toSessionBranch)
                .toList();
    }

    /** Names for screens shown before branch access exists (e.g. approval status); no authorization is implied. */
    public Map<Long, String> names(Collection<Long> branchIds) {
        if (branchIds.isEmpty()) {
            return Map.of();
        }

        return branchRepository.findByIdIn(branchIds)
                .stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));
    }

    /**
     * Every branch's name, for matching a branch named in an AI question (#93·#94). Server-side only: the AI never
     * shows a name for a branch the requester cannot read, so no authorization is implied here either.
     */
    public Map<Long, String> allNames() {
        return branchRepository.findAll()
                .stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));
    }

    private static SessionBranch toSessionBranch(Branch branch) {
        String id = Long.toString(branch.getId());

        return new SessionBranch(
                id,
                id,
                ApiVersion.fromDatabase(branch.getVersion()),
                branch.getCreatedAt().toString(),
                branch.getUpdatedAt().toString(),
                branch.getName(),
                branch.getAddress(),
                BranchViews.toPhone(branch.getPhone()),
                "",
                BranchViews.toStatusLabel(branch.getStatus())
        );
    }
}
