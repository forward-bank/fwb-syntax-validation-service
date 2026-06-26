package com.forward.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Downloads files from S3 (or LocalStack) given a bucket-relative file path.
 *
 * The bucket name is fixed per environment via {@code aws.s3.bucket} in
 * {@code application.properties}. The incoming {@code paymentFilePath} is
 * treated directly as the S3 object key — no URI parsing is needed.
 *
 * Example:
 *   bucket          : fwb-payments-dev
 *   paymentFilePath : FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/file.xml
 *   resolved key    : FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/file.xml
 *   effective URI   : s3://fwb-payments-dev/FWB_DIRECT_DEBIT/.../file.xml
 */
@Component
public class S3FileDownloader {

    private final S3Client s3Client;
    private final String   bucket;

    public S3FileDownloader(S3Client s3Client,
                            @Value("${aws.s3.bucket:fwb-payments-dev}") String bucket) {
        this.s3Client = s3Client;
        this.bucket   = bucket;
    }

    /**
     * Downloads the object at the given bucket-relative path and returns its bytes.
     *
     * @param paymentFilePath  S3 object key, e.g.
     *     {@code FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/file.xml}
     * @return raw file bytes
     * @throws S3DownloadException if the path is blank, the object does not exist,
     *                             or any AWS SDK error occurs
     */
    public byte[] download(String paymentFilePath) {
        if (paymentFilePath == null || paymentFilePath.isBlank()) {
            throw new S3DownloadException("paymentFilePath must not be null or blank");
        }

        // Strip a leading slash if present — S3 keys must not start with "/"
        String key = paymentFilePath.startsWith("/")
                ? paymentFilePath.substring(1)
                : paymentFilePath;

        System.out.println("  [S3FileDownloader] downloading s3://" + bucket + "/" + key);

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
            byte[] bytes = response.asByteArray();

            System.out.println("  [S3FileDownloader] ✓ downloaded " + bytes.length + " bytes"
                    + " from s3://" + bucket + "/" + key);
            return bytes;

        } catch (NoSuchKeyException e) {
            throw new S3DownloadException(
                    "S3 object not found: s3://" + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new S3DownloadException(
                    "Failed to download s3://" + bucket + "/" + key + ": " + e.getMessage(), e);
        }
    }

    // ── Typed exception ───────────────────────────────────────────────────────

    public static class S3DownloadException extends RuntimeException {
        public S3DownloadException(String message) {
            super(message);
        }
        public S3DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
