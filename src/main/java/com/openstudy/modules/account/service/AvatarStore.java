package com.openstudy.modules.account.service;

import java.util.Optional;

/**
 * Where profile photo bytes live (#120, M14-D03). Operations use S3 (a private bucket); local development and tests use a
 * directory or memory behind the same interface. Keys come from {@code AvatarKeys} only, never from a request.
 */
public interface AvatarStore {
    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);

    /** Removing a missing key is not an error. */
    void delete(String key);
}
