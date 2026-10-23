package com.openstudy.config;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.openstudy.modules.employee.service.EmployeeAccountService;

// Explicit local opt-in only. Existing owner credentials are never overwritten.
@Component
@Profile("local")
public class LocalOwnerBootstrap implements CommandLineRunner {
    private final EmployeeAccountService employees;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public LocalOwnerBootstrap(EmployeeAccountService employees, PasswordEncoder encoder,
            @Value("${BOOTSTRAP_OWNER_EMAIL:}") String email,
            @Value("${BOOTSTRAP_OWNER_PASSWORD:}") String password) {
        this.employees = employees;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (email.isBlank() && password.isBlank()) return;
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("로컬 관리자 이메일과 비밀번호(8자 이상, UTF-8 72바이트 이하)를 확인하세요.");
        }
        employees.createLocalOwnerIfAbsent(email.trim(), encoder.encode(password));
    }
}
