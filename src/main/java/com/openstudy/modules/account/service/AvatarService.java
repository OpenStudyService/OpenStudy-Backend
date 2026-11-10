package com.openstudy.modules.account.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.employee.dto.AvatarKeys;
import com.openstudy.modules.employee.service.EmployeeAccountService;

/**
 * Own profile photo upload, removal and app-served viewing (#120, account-collab-api.md "프로필 사진", M14-D03·D10·D13·D14).
 * The file is checked by its signature only, written under a new key, then the key is saved; the replaced file is removed
 * after that commit. A failed save removes the new file, so a stored file always has a row or is about to.
 */
@Service
@RequiredArgsConstructor
public class AvatarService {
    public static final int MAX_BYTES = 5_242_880;
    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private final AvatarStore store;
    private final EmployeeAccountService employees;
    private final CurrentUserService users;
    private final AccountCredentialRepository repository;
    private final AuthorizationService policy;

    /** The photo bytes and their content type. */
    public record Photo(byte[] bytes, String contentType) {}

    public CurrentUser upload(long actor, String sessionMode, byte[] bytes, String requestId) {
        requireWorkspace(sessionMode);
        if (bytes.length > MAX_BYTES) throw tooLarge();
        var format = format(bytes).orElseThrow(() -> ApiException.invalid("AVATAR_TYPE_UNSUPPORTED",
                "JPG, PNG, WebP 이미지만 올릴 수 있습니다.", "file", "이미지 파일을 선택해 주세요."));
        String key = AvatarKeys.newKey(actor, format);
        store.put(key, bytes, format.contentType);
        String previous;
        try {
            previous = employees.changeAvatar(actor, key, requestId);
        } catch (RuntimeException failure) {
            removeQuietly(key);
            throw failure;
        }
        removeQuietly(previous);
        return users.getCurrentUser(actor, sessionMode);
    }

    public CurrentUser remove(long actor, String sessionMode, String requestId) {
        requireWorkspace(sessionMode);
        removeQuietly(employees.changeAvatar(actor, null, requestId));
        return users.getCurrentUser(actor, sessionMode);
    }

    /**
     * GET /employees/{id}/avatar: the employee, an owner, or an active employee sharing an active branch membership with the
     * target (M14-D14). Out of reach, no photo and no employee are all 404, so nothing tells whether a photo exists.
     */
    public Photo view(long actor, long target) {
        var state = policy.current(actor);
        boolean allowed = actor == target || state.owner() || shareActiveBranch(actor, target);
        if (!allowed) throw notFound();
        String key = repository.avatarKey(target).orElseThrow(AvatarService::notFound);
        var parsed = AvatarKeys.parse(key).filter(found -> found.employeeId() == target).orElseThrow(AvatarService::notFound);
        byte[] bytes = store.get(key).orElseThrow(AvatarService::notFound);
        return new Photo(bytes, parsed.format().contentType);
    }

    /** JPG (FF D8 FF), PNG (the 8-byte signature) or WebP (RIFF....WEBP); the name and the declared type are not used. */
    static Optional<AvatarKeys.Format> format(byte[] bytes) {
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(AvatarKeys.Format.JPG);
        if (startsWith(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of(AvatarKeys.Format.PNG);
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) return Optional.of(AvatarKeys.Format.WEBP);
        return Optional.empty();
    }

    public static ApiException tooLarge() {
        return ApiException.invalid("AVATAR_TOO_LARGE", "사진은 5MB 이하여야 합니다.", "file", "5MB 이하의 이미지를 선택해 주세요.");
    }

    private boolean shareActiveBranch(long actor, long target) {
        var mine = repository.memberships(actor).stream().filter(m -> "ACTIVE".equals(m.status()))
                .map(AccountCredentialRepository.Membership::branchId).toList();
        return repository.memberships(target).stream().anyMatch(m -> "ACTIVE".equals(m.status()) && mine.contains(m.branchId()));
    }

    private static void requireWorkspace(String sessionMode) {
        if (!SessionBoundaryPolicy.WORKSPACE.equals(sessionMode)) throw AuthorizationService.forbidden();
    }

    /** A file left behind is unreachable (no row names it), so a failed removal is logged and not an error. */
    private void removeQuietly(String key) {
        if (key == null || AvatarKeys.parse(key).isEmpty()) return;
        try {
            store.delete(key);
        } catch (RuntimeException failure) {
            log.warn("Avatar file removal failed type={}", failure.getClass().getSimpleName());
        }
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((bytes[offset + i] & 0xFF) != expected[i]) return false;
        return true;
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
