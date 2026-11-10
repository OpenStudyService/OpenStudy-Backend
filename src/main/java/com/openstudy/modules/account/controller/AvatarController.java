package com.openstudy.modules.account.controller;

import java.io.IOException;
import java.io.UncheckedIOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.account.service.AvatarService;
import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;

/** Own profile photo and app-served viewing (#120, account-collab-api.md "프로필 사진"). */
@RestController
@RequiredArgsConstructor
public class AvatarController {
    private final AvatarService service;
    private final SessionBoundaryPolicy sessions;

    /** multipart/form-data with exactly one part, {@code file}; no other part, field or query. */
    @PutMapping("/api/me/avatar")
    public ResponseEntity<CurrentUser> upload(Authentication actor, HttpServletRequest request) {
        if (!(request instanceof MultipartHttpServletRequest multipart))
            throw ApiException.validation("file", "multipart/form-data로 사진 파일을 보내 주세요.");
        if (!multipart.getParameterMap().isEmpty())
            throw ApiException.validation(multipart.getParameterNames().nextElement(), "허용되지 않은 입력입니다.");
        var files = multipart.getMultiFileMap();
        if (files.size() != 1 || !files.containsKey("file") || files.get("file").size() != 1)
            throw ApiException.validation("file", "사진 파일 하나를 file로 보내 주세요.");
        var file = files.getFirst("file");
        if (file.getSize() > AvatarService.MAX_BYTES) throw AvatarService.tooLarge();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Avatar upload read failed", exception);
        }
        var user = service.upload(actor(actor), sessions.mode(request.getSession(false)), bytes, ApiRequestFilter.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
    }

    @DeleteMapping("/api/me/avatar")
    public ResponseEntity<CurrentUser> remove(Authentication actor, HttpServletRequest request) {
        noQuery(request);
        var user = service.remove(actor(actor), sessions.mode(request.getSession(false)), ApiRequestFilter.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
    }

    /** The bytes as stored. The {@code v} query only separates cache entries and is not used to decide anything. */
    @GetMapping("/api/employees/{id}/avatar")
    public ResponseEntity<byte[]> view(Authentication actor, @PathVariable long id, HttpServletRequest request,
            HttpServletResponse response) {
        for (String name : request.getParameterMap().keySet())
            if (!"v".equals(name)) throw ApiException.validation(name, "허용되지 않은 쿼리입니다.");
        var photo = service.view(actor(actor), id);
        // The API filter marks every /api response no-store; a photo path changes with the photo, so it may be cached privately.
        response.setHeader("Cache-Control", "private, max-age=86400");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType())).body(photo.bytes());
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty())
            throw ApiException.validation(request.getParameterNames().nextElement(), "허용되지 않은 쿼리입니다.");
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
