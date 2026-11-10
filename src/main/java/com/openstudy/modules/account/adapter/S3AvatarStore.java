package com.openstudy.modules.account.adapter;

import java.util.Optional;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import com.openstudy.modules.account.service.AvatarStore;

/**
 * Profile photos in a private S3 bucket (M14-D03). Objects are never public: the app reads and serves them (M14-D13). Each
 * object asks for S3-managed encryption; the bucket policy, Block Public Access and IAM are requested from the infra team
 * (docs/deployment/avatar-bucket-request.md). Credentials come from the default AWS chain (an instance or task role).
 */
public final class S3AvatarStore implements AvatarStore {
    private final S3Client client;
    private final String bucket;

    public S3AvatarStore(S3Client client, String bucket) {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("openstudy.avatar.s3.bucket is required");
        this.client = client;
        this.bucket = bucket;
    }

    @Override public void put(String key, byte[] bytes, String contentType) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(LocalAvatarStores.checked(key)).contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(bytes));
    }

    @Override public Optional<byte[]> get(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(LocalAvatarStores.checked(key))
                    .build()).asByteArray());
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        }
    }

    @Override public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(LocalAvatarStores.checked(key)).build());
    }
}
