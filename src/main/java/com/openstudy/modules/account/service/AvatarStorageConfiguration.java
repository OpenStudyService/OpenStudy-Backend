package com.openstudy.modules.account.service;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import com.openstudy.modules.account.adapter.LocalAvatarStores;
import com.openstudy.modules.account.adapter.S3AvatarStore;

/**
 * openstudy.avatar.storage picks the photo store (#120):
 * {@code s3} in operations (bucket and region required),
 * {@code directory} for local development (default, under openstudy.avatar.directory)
 * and {@code memory} for tests.
 */
@Configuration(proxyBeanMethods = false)
public class AvatarStorageConfiguration {

    @Bean
    AvatarStore avatarStore(
            @Value("${openstudy.avatar.storage:directory}") String storage,
            @Value("${openstudy.avatar.directory:./data/avatars}") String directory,
            @Value("${openstudy.avatar.s3.bucket:}") String bucket,
            @Value("${openstudy.avatar.s3.region:}") String region
    ) {
        return switch (storage) {
            case "s3" -> createS3Store(bucket, region);
            case "directory" -> new LocalAvatarStores.Directory(Path.of(directory));
            case "memory" -> new LocalAvatarStores.Memory();
            default -> throw new IllegalArgumentException("openstudy.avatar.storage must be s3, directory or memory");
        };
    }

    private static AvatarStore createS3Store(String bucket, String region) {
        if (region.isBlank()) {
            throw new IllegalArgumentException("openstudy.avatar.s3.region is required");
        }

        S3Client client = S3Client.builder()
                .region(Region.of(region))
                .build();

        return new S3AvatarStore(client, bucket);
    }
}
