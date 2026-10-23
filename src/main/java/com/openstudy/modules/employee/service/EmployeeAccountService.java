package com.openstudy.modules.employee.service;

import java.util.List;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeMembership;
import com.openstudy.modules.employee.repository.EmployeeAccountRepository;

@Service
@Transactional(readOnly = true)
public class EmployeeAccountService {
    private final EmployeeAccountRepository repository;

    public EmployeeAccountService(EmployeeAccountRepository repository) {
        this.repository = repository;
    }

    public EmployeeAccount byEmail(String email) {
        return repository.findByEmail(email.trim()).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public EmployeeAccount byId(long id) {
        return repository.findById(id).orElseThrow(() -> new UsernameNotFoundException("Invalid account"));
    }

    public List<EmployeeMembership> memberships(long id) {
        return repository.memberships(id);
    }

    @Transactional
    public void recordLogin(long id) {
        repository.recordLogin(id);
    }

    @Transactional
    public void createLocalOwnerIfAbsent(String email, String encodedPassword) {
        if (!repository.ownerExists()) repository.createOwner(email, encodedPassword);
    }
}
