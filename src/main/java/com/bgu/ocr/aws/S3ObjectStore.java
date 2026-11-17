package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.nio.charset.StandardCharsets;

/** {@link ObjectStore} backed by one S3 bucket. */
public final class S3ObjectStore implements ObjectStore {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** Uses the SDK's default credentials: the instance profile on EC2, ~/.aws/credentials locally. */
    public static S3ObjectStore withDefaultClient() {
        return new S3ObjectStore(S3Client.builder().region(Region.of(Config.REGION)).build(), Config.BUCKET);
    }

    /** Creates the bucket on first use. Called by the Local App before its first upload. */
    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));   // us-east-1 needs no location constraint
        }
    }

    @Override
    public void put(String key, String text) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("text/plain; charset=utf-8"),
                RequestBody.fromString(text, StandardCharsets.UTF_8));
    }

    /** @throws ObjectMissingException if there is no object under {@code key} */
    @Override
    public String get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asUtf8String();
        } catch (NoSuchKeyException e) {
            throw new ObjectMissingException(key, e);
        }
    }
}
