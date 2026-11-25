package com.openstudy.config;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.openstudy.modules.employee.service.EmployeeAccountService;

/** Explicit local opt-in only. Existing owner credentials are never overwritten. */
@Component
@Profile("local")
public class LocalOwnerBootstrap implements CommandLineRunner {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private final EmployeeAccountService employeeAccountService;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public LocalOwnerBootstrap(
            EmployeeAccountService employeeAccountService,
            PasswordEncoder passwordEncoder,
            @Value("${BOOTSTRAP_OWNER_EMAIL:}") String email,
            @Value("${BOOTSTRAP_OWNER_PASSWORD:}") String password
    ) {
        this.employeeAccountService = employeeAccountService;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (email.isBlank() && password.isBlank()) {
            return;
        }

        boolean validEmail = email.length() <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.matcher(email).matches();
        boolean validPassword = password.length() >= MIN_PASSWORD_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;

        if (!validEmail || !validPassword) {
            throw new IllegalStateException("로컬 관리자 이메일과 비밀번호(8자 이상, UTF-8 72바이트 이하)를 확인하세요.");
        }

        employeeAccountService.createLocalOwnerIfAbsent(email.trim(), passwordEncoder.encode(password));
    }
}
