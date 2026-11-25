package com.openstudy.modules.account.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.account.service.AvatarService;
import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;

/** Own profile photo and app-served viewing (#120, account-collab-api.md "프로필 사진"). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AvatarController {

    private static final String FILE_PART = "file";
    private static final String CACHE_BUSTING_QUERY = "v";

    private final AvatarService avatarService;
    private final SessionBoundaryPolicy sessionBoundaryPolicy;

    /** multipart/form-data with exactly one part, {@code file}; no other part, field or query. */
    @PutMapping("/me/avatar")
    public ResponseEntity<CurrentUser> uploadMyAvatar(
            Authentication authentication,
            HttpServletRequest request
    ) {
        if (!(request instanceof MultipartHttpServletRequest multipart)) {
            throw ApiException.validation(FILE_PART, "multipart/form-data로 사진 파일을 보내 주세요.");
        }

        if (!multipart.getParameterMap().isEmpty()) {
            throw ApiException.validation(
                    multipart.getParameterNames().nextElement(),
                    "허용되지 않은 입력입니다."
            );
        }

        MultiValueMap<String, MultipartFile> files = multipart.getMultiFileMap();
        List<MultipartFile> fileParts = files.get(FILE_PART);

        boolean exactlyOneFile = files.size() == 1
                && fileParts != null
                && fileParts.size() == 1;

        if (!exactlyOneFile) {
            throw ApiException.validation(FILE_PART, "사진 파일 하나를 file로 보내 주세요.");
        }

        MultipartFile file = files.getFirst(FILE_PART);

        if (file.getSize() > AvatarService.MAX_BYTES) {
            throw AvatarService.tooLarge();
        }

        byte[] bytes = readBytes(file);
        String sessionMode = sessionBoundaryPolicy.mode(request.getSession(false));
        String requestId = ApiRequestFilter.requestId(request);

        CurrentUser currentUser = avatarService.upload(actorId(authentication), sessionMode, bytes, requestId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentUser);
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<CurrentUser> deleteMyAvatar(
            Authentication authentication,
            HttpServletRequest request
    ) {
        rejectQuery(request);

        String sessionMode = sessionBoundaryPolicy.mode(request.getSession(false));
        String requestId = ApiRequestFilter.requestId(request);

        CurrentUser currentUser = avatarService.remove(actorId(authentication), sessionMode, requestId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentUser);
    }

    /** The bytes as stored. The {@code v} query only separates cache entries and is not used to decide anything. */
    @GetMapping("/employees/{id}/avatar")
    public ResponseEntity<byte[]> getEmployeeAvatar(
            Authentication authentication,
            @PathVariable long id,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        for (String name : request.getParameterMap().keySet()) {
            if (!CACHE_BUSTING_QUERY.equals(name)) {
                throw ApiException.validation(name, "허용되지 않은 쿼리입니다.");
            }
        }

        AvatarService.Photo photo = avatarService.view(actorId(authentication), id);

        // The API filter marks every /api response no-store; a photo path changes with the photo,
        // so it may be cached privately.
        response.setHeader("Cache-Control", "private, max-age=86400");
        response.setHeader("X-Content-Type-Options", "nosniff");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.bytes());
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Avatar upload read failed", exception);
        }
    }

    private static void rejectQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.validation(
                    request.getParameterNames().nextElement(),
                    "허용되지 않은 쿼리입니다."
            );
        }
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
