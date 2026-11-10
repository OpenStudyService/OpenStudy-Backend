package com.openstudy.modules.account.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.account.adapter.LocalAvatarStores;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Membership;
import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.employee.dto.AvatarKeys;
import com.openstudy.modules.employee.service.EmployeeAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #120 without a database, S3 or HTTP: signature-only type checks and the 5 MiB bound (M14-D10), keys and paths that never
 * expose the storage key (M14-D13), replacing and removing files around the save, who may view a photo (M14-D14), and the
 * local stores. The S3 adapter and the HTTP layer are left to the milestone run.
 */
class AvatarUnitTests {
    static final byte[] JPG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3);
    static final byte[] PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0);
    static final byte[] WEBP = concat("RIFF".getBytes(StandardCharsets.US_ASCII), bytes(1, 2, 3, 4), "WEBPVP8 ".getBytes(StandardCharsets.US_ASCII));

    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, out, at, part.length); at += part.length; }
        return out;
    }

    static String code(Executable call) { return assertThrows(ApiException.class, call).error("t").code(); }

    // ---- type and keys -----------------------------------------------------------------------------------------------

    @Test void theTypeComesFromTheSignatureOnly() {
        assertThat(AvatarService.format(JPG)).contains(AvatarKeys.Format.JPG);
        assertThat(AvatarService.format(PNG)).contains(AvatarKeys.Format.PNG);
        assertThat(AvatarService.format(WEBP)).contains(AvatarKeys.Format.WEBP);
        // A text file renamed to .png, a GIF, a RIFF that is not WebP and a too-short file are all refused.
        assertThat(AvatarService.format("hello.png".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(AvatarService.format("GIF89a....".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(AvatarService.format(concat("RIFF".getBytes(StandardCharsets.US_ASCII), bytes(0, 0, 0, 0),
                "WAVE".getBytes(StandardCharsets.US_ASCII)))).isEmpty();
        assertThat(AvatarService.format(bytes(0xFF, 0xD8))).isEmpty();
        assertThat(AvatarService.format(new byte[0])).isEmpty();
    }

    @Test void keysStayOnTheServerAndPathsChangeWithThePhoto() {
        String key = AvatarKeys.newKey(7, AvatarKeys.Format.PNG);
        assertThat(key).matches("avatars/7/[0-9a-f]{32}\\.png");
        var parsed = AvatarKeys.parse(key).orElseThrow();
        assertThat(AvatarKeys.path(7, key)).isEqualTo("/api/employees/7/avatar?v=" + parsed.version()).doesNotContain("avatars/");
        assertThat(AvatarKeys.path(7, AvatarKeys.newKey(7, AvatarKeys.Format.PNG))).isNotEqualTo(AvatarKeys.path(7, key));
        // Another employee's key, no key, and a legacy URL show no photo.
        assertThat(AvatarKeys.path(8, key)).isEmpty();
        assertThat(AvatarKeys.path(7, null)).isEmpty();
        assertThat(AvatarKeys.path(7, "https://cdn.example.com/a.png")).isEmpty();
        assertThat(AvatarKeys.parse("avatars/7/../../etc/passwd")).isEmpty();
    }

    // ---- upload, replace and remove ----------------------------------------------------------------------------------

    final LocalAvatarStores.Memory store = new LocalAvatarStores.Memory();
    final EmployeeAccountService employees = mock(EmployeeAccountService.class);
    final CurrentUserService users = mock(CurrentUserService.class);
    final AccountCredentialRepository repository = mock(AccountCredentialRepository.class);
    final AuthorizationService policy = mock(AuthorizationService.class);
    final AvatarService service = new AvatarService(store, employees, users, repository, policy);

    @Test void anUploadStoresANewKeyAndRemovesTheReplacedFile() {
        String old = AvatarKeys.newKey(7, AvatarKeys.Format.JPG);
        store.put(old, JPG, "image/jpeg");
        when(employees.changeAvatar(eq(7L), anyString(), eq("req"))).thenReturn(old);
        service.upload(7, SessionBoundaryPolicy.WORKSPACE, PNG, "req");
        var saved = ArgumentCaptor.forClass(String.class);
        verify(employees).changeAvatar(eq(7L), saved.capture(), eq("req"));
        assertThat(saved.getValue()).endsWith(".png");
        assertThat(store.get(saved.getValue())).hasValueSatisfying(stored -> assertThat(stored).isEqualTo(PNG));
        assertThat(store.get(old)).as("the replaced photo is no longer served").isEmpty();
        verify(users).getCurrentUser(7, SessionBoundaryPolicy.WORKSPACE);
    }

    @Test void aFailedSaveLeavesNoNewFile() {
        when(employees.changeAvatar(anyLong(), anyString(), any())).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> service.upload(7, SessionBoundaryPolicy.WORKSPACE, JPG, "r")).isInstanceOf(IllegalStateException.class);
        assertThat(store.size()).isZero();
    }

    @Test void wrongTypesOversizeAndRestrictedSessionsStoreNothing() {
        assertThat(code(() -> service.upload(7, SessionBoundaryPolicy.WORKSPACE, "plain text".getBytes(StandardCharsets.UTF_8), "r")))
                .isEqualTo("AVATAR_TYPE_UNSUPPORTED");
        byte[] large = Arrays.copyOf(JPG, AvatarService.MAX_BYTES + 1);
        assertThat(code(() -> service.upload(7, SessionBoundaryPolicy.WORKSPACE, large, "r"))).isEqualTo("AVATAR_TOO_LARGE");
        when(employees.changeAvatar(anyLong(), anyString(), any())).thenReturn(null);
        service.upload(7, SessionBoundaryPolicy.WORKSPACE, Arrays.copyOf(JPG, AvatarService.MAX_BYTES), "r");
        assertThat(store.size()).as("exactly 5 MiB is allowed").isEqualTo(1);
        assertThrows(AccessDeniedException.class, () -> service.upload(7, SessionBoundaryPolicy.APPROVAL_ONLY, JPG, "r"));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test void removingClearsTheKeyAndTheFile() {
        String old = AvatarKeys.newKey(7, AvatarKeys.Format.WEBP);
        store.put(old, WEBP, "image/webp");
        when(employees.changeAvatar(7, null, "r")).thenReturn(old);
        service.remove(7, SessionBoundaryPolicy.WORKSPACE, "r");
        assertThat(store.size()).isZero();
        // No photo is still a success.
        when(employees.changeAvatar(7, null, "r")).thenReturn(null);
        service.remove(7, SessionBoundaryPolicy.WORKSPACE, "r");
    }

    // ---- who may view (M14-D14) --------------------------------------------------------------------------------------

    String photoOf(long employee) {
        String key = AvatarKeys.newKey(employee, AvatarKeys.Format.PNG);
        store.put(key, PNG, "image/png");
        when(repository.avatarKey(employee)).thenReturn(Optional.of(key));
        return key;
    }

    void viewer(long id, boolean owner, List<Membership> mine) {
        when(policy.current(id)).thenReturn(new AuthorizationState(id, owner, "ACTIVE", Map.of(), Map.of()));
        when(repository.memberships(id)).thenReturn(mine);
    }

    @Test void selfOwnersAndActiveColleaguesSeeThePhoto() {
        photoOf(7);
        when(repository.memberships(7)).thenReturn(List.of(new Membership(1, "ACTIVE"), new Membership(2, "REVOKED")));
        viewer(7, false, List.of(new Membership(1, "ACTIVE"), new Membership(2, "REVOKED")));
        var self = service.view(7, 7);
        assertThat(self.contentType()).isEqualTo("image/png");
        assertThat(self.bytes()).isEqualTo(PNG);
        viewer(1, true, List.of());
        assertThat(service.view(1, 7).bytes()).isEqualTo(PNG);
        viewer(3, false, List.of(new Membership(1, "ACTIVE")));
        assertThat(service.view(3, 7).bytes()).isEqualTo(PNG);
    }

    @Test void everythingElseIsTheSame404() {
        photoOf(7);
        when(repository.memberships(7)).thenReturn(List.of(new Membership(1, "ACTIVE"), new Membership(2, "REVOKED")));
        // Another branch only, or a branch shared only through a revoked or pending membership.
        viewer(3, false, List.of(new Membership(9, "ACTIVE")));
        assertThrows(ResponseStatusException.class, () -> service.view(3, 7));
        viewer(4, false, List.of(new Membership(2, "ACTIVE")));
        assertThrows(ResponseStatusException.class, () -> service.view(4, 7));
        viewer(5, false, List.of(new Membership(1, "PENDING")));
        assertThrows(ResponseStatusException.class, () -> service.view(5, 7));
        // No photo, a legacy value, a missing file, and no employee look the same to an owner.
        viewer(1, true, List.of());
        when(repository.avatarKey(8)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.view(1, 8));
        when(repository.avatarKey(8)).thenReturn(Optional.of("https://cdn.example.com/a.png"));
        assertThrows(ResponseStatusException.class, () -> service.view(1, 8));
        when(repository.avatarKey(8)).thenReturn(Optional.of(AvatarKeys.newKey(8, AvatarKeys.Format.JPG)));
        assertThrows(ResponseStatusException.class, () -> service.view(1, 8));
        verify(repository, never()).avatarKey(3);
    }

    // ---- local stores --------------------------------------------------------------------------------------------------

    @Test void theDirectoryStoreKeepsFilesUnderItsRoot(@TempDir Path root) throws Exception {
        var directory = new LocalAvatarStores.Directory(root);
        String key = AvatarKeys.newKey(7, AvatarKeys.Format.JPG);
        directory.put(key, JPG, "image/jpeg");
        assertThat(Files.exists(root.resolve(key))).isTrue();
        assertThat(directory.get(key)).hasValueSatisfying(stored -> assertThat(stored).isEqualTo(JPG));
        directory.delete(key);
        directory.delete(key);
        assertThat(directory.get(key)).isEmpty();
        assertThatThrownBy(() -> directory.put("../outside.jpg", JPG, "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get("avatars/7/not-a-uuid.jpg")).isInstanceOf(IllegalArgumentException.class);
    }
}
