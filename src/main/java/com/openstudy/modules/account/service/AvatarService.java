package com.openstudy.modules.account.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Membership;
import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.employee.dto.AvatarKeys;
import com.openstudy.modules.employee.service.EmployeeAccountService;

/**
 * Own profile photo upload, removal and app-served viewing
 * (#120, account-collab-api.md "프로필 사진", M14-D03·D10·D13·D14).
 *
 * <p>The file is checked by its signature only, written under a new key, then the key is saved;
 * the replaced file is removed after that commit.
 * A failed save removes the new file, so a stored file always has a row or is about to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    public static final int MAX_BYTES = 5_242_880;

    private static final int[] JPG_SIGNATURE = {0xFF, 0xD8, 0xFF};
    private static final int[] PNG_SIGNATURE = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int[] WEBP_RIFF_SIGNATURE = {'R', 'I', 'F', 'F'};
    private static final int[] WEBP_FORMAT_SIGNATURE = {'W', 'E', 'B', 'P'};
    private static final int WEBP_FORMAT_OFFSET = 8;

    private final AvatarStore avatarStore;
    private final EmployeeAccountService employeeAccountService;
    private final CurrentUserService currentUserService;
    private final AccountCredentialRepository accountCredentialRepository;
    private final AuthorizationService authorizationService;

    /** The photo bytes and their content type. */
    public record Photo(byte[] bytes, String contentType) {}

    public CurrentUser upload(long actor, String sessionMode, byte[] bytes, String requestId) {
        requireWorkspace(sessionMode);

        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }

        AvatarKeys.Format format = detectFormat(bytes)
                .orElseThrow(() -> ApiException.invalid(
                        "AVATAR_TYPE_UNSUPPORTED",
                        "JPG, PNG, WebP 이미지만 올릴 수 있습니다.",
                        "file",
                        "이미지 파일을 선택해 주세요."
                ));

        String key = AvatarKeys.newKey(actor, format);
        avatarStore.put(key, bytes, format.contentType);

        String previousKey;
        try {
            previousKey = employeeAccountService.changeAvatar(actor, key, requestId);
        } catch (RuntimeException failure) {
            removeQuietly(key);
            throw failure;
        }

        removeQuietly(previousKey);

        return currentUserService.getCurrentUser(actor, sessionMode);
    }

    public CurrentUser remove(long actor, String sessionMode, String requestId) {
        requireWorkspace(sessionMode);

        String previousKey = employeeAccountService.changeAvatar(actor, null, requestId);
        removeQuietly(previousKey);

        return currentUserService.getCurrentUser(actor, sessionMode);
    }

    /**
     * GET /employees/{id}/avatar: the employee, an owner,
     * or an active employee sharing an active branch membership with the target (M14-D14).
     * Out of reach, no photo and no employee are all 404, so nothing tells whether a photo exists.
     */
    public Photo view(long actor, long target) {
        AuthorizationState state = authorizationService.current(actor);

        boolean allowed = actor == target
                || state.owner()
                || sharesActiveBranch(actor, target);

        if (!allowed) {
            throw notFound();
        }

        String key = accountCredentialRepository.findAvatarKey(target)
                .orElseThrow(AvatarService::notFound);

        AvatarKeys.Parsed parsed = AvatarKeys.parse(key)
                .filter(found -> found.employeeId() == target)
                .orElseThrow(AvatarService::notFound);

        byte[] bytes = avatarStore.get(key)
                .orElseThrow(AvatarService::notFound);

        return new Photo(bytes, parsed.format().contentType);
    }

    /** JPG (FF D8 FF), PNG (the 8-byte signature) or WebP (RIFF....WEBP); the name and the declared type are not used. */
    static Optional<AvatarKeys.Format> detectFormat(byte[] bytes) {
        if (startsWith(bytes, 0, JPG_SIGNATURE)) {
            return Optional.of(AvatarKeys.Format.JPG);
        }

        if (startsWith(bytes, 0, PNG_SIGNATURE)) {
            return Optional.of(AvatarKeys.Format.PNG);
        }

        boolean webp = startsWith(bytes, 0, WEBP_RIFF_SIGNATURE)
                && startsWith(bytes, WEBP_FORMAT_OFFSET, WEBP_FORMAT_SIGNATURE);
        if (webp) {
            return Optional.of(AvatarKeys.Format.WEBP);
        }

        return Optional.empty();
    }

    public static ApiException tooLarge() {
        return ApiException.invalid(
                "AVATAR_TOO_LARGE",
                "사진은 5MB 이하여야 합니다.",
                "file",
                "5MB 이하의 이미지를 선택해 주세요."
        );
    }

    private boolean sharesActiveBranch(long actor, long target) {
        List<Long> actorBranchIds = accountCredentialRepository.findMemberships(actor).stream()
                .filter(membership -> "ACTIVE".equals(membership.status()))
                .map(Membership::branchId)
                .toList();

        return accountCredentialRepository.findMemberships(target).stream()
                .anyMatch(membership -> "ACTIVE".equals(membership.status())
                        && actorBranchIds.contains(membership.branchId()));
    }

    private static void requireWorkspace(String sessionMode) {
        if (!SessionBoundaryPolicy.WORKSPACE.equals(sessionMode)) {
            throw AuthorizationService.forbidden();
        }
    }

    /** A file left behind is unreachable (no row names it), so a failed removal is logged and not an error. */
    private void removeQuietly(String key) {
        if (key == null || AvatarKeys.parse(key).isEmpty()) {
            return;
        }

        try {
            avatarStore.delete(key);
        } catch (RuntimeException failure) {
            log.warn("Avatar file removal failed type={}", failure.getClass().getSimpleName());
        }
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }

        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }

        return true;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
