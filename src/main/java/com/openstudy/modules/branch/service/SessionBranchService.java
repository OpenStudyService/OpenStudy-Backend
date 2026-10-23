package com.openstudy.modules.branch.service;

import java.util.List;
import org.springframework.stereotype.Service;

import com.openstudy.modules.branch.dto.SessionBranch;
import com.openstudy.modules.branch.repository.SessionBranchRepository;

@Service
public class SessionBranchService {
    private final SessionBranchRepository repository;

    public SessionBranchService(SessionBranchRepository repository) {
        this.repository = repository;
    }

    public List<SessionBranch> accessible(long employeeId, boolean owner) {
        return repository.accessible(employeeId, owner);
    }
}
