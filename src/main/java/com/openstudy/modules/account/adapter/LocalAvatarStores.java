package com.openstudy.modules.account.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.openstudy.modules.account.service.AvatarStore;
import com.openstudy.modules.employee.dto.AvatarKeys;

/** Stand-ins for S3 in local development (a directory) and tests (memory). Both accept well-formed keys only. */
public final class LocalAvatarStores {

    private LocalAvatarStores() {}

    static String checked(String key) {
        if (AvatarKeys.parse(key).isEmpty()) {
            throw new IllegalArgumentException("Not an avatar key");
        }

        return key;
    }

    /** One file per key under a root directory; the key shape keeps every path inside it. */
    public static final class Directory implements AvatarStore {

        private final Path root;

        public Directory(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            try {
                Path file = resolveFile(key);
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            } catch (IOException exception) {
                throw new UncheckedIOException("Avatar write failed", exception);
            }
        }

        @Override
        public Optional<byte[]> get(String key) {
            try {
                return Optional.of(Files.readAllBytes(resolveFile(key)));
            } catch (NoSuchFileException missing) {
                return Optional.empty();
            } catch (IOException exception) {
                throw new UncheckedIOException("Avatar read failed", exception);
            }
        }

        @Override
        public void delete(String key) {
            try {
                Files.deleteIfExists(resolveFile(key));
            } catch (IOException exception) {
                throw new UncheckedIOException("Avatar delete failed", exception);
            }
        }

        private Path resolveFile(String key) {
            Path file = root.resolve(checked(key)).normalize();

            if (!file.startsWith(root)) {
                throw new IllegalArgumentException("Not an avatar key");
            }

            return file;
        }
    }

    public static final class Memory implements AvatarStore {

        private final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            files.put(checked(key), bytes.clone());
        }

        @Override
        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(files.get(checked(key)))
                    .map(byte[]::clone);
        }

        @Override
        public void delete(String key) {
            files.remove(checked(key));
        }

        public int size() {
            return files.size();
        }
    }
}
