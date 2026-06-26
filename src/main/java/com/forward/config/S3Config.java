package com.forward.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Wires an S3Client bean.
 *
 * When aws.localstack.enabled=true the client points at LocalStack (localhost:4566)
 * with path-style access enabled — required because LocalStack does not support
 * virtual-hosted-style bucket addressing by default.
 *
 * When aws.localstack.enabled=false (production) the client uses the standard
 * AWS SDK credential chain (env vars, instance profile, etc.) and the configured region.
 */
@Configuration
public class S3Config {

    @Value("${aws.localstack.enabled:false}")
    private boolean localstackEnabled;

    @Value("${aws.localstack.endpoint:http://localhost:4566}")
    private String localstackEndpoint;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.accessKeyId:test}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey:test}")
    private String secretAccessKey;

    @Bean
    public S3Client s3Client() {
        Region region = Region.of(awsRegion);

        if (localstackEnabled) {
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║  S3Client → LocalStack  " + localstackEndpoint + "  ║");
            System.out.println("╚══════════════════════════════════════════════╝");

            return S3Client.builder()
                    .region(region)
                    .endpointOverride(URI.create(localstackEndpoint))
                    // LocalStack requires path-style: http://localhost:4566/<bucket>/<key>
                    // Virtual-hosted-style (http://<bucket>.localhost:4566) does not resolve locally
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                    .build();
        }

        // Production — use the default credential chain (env vars / EC2 role / etc.)
        return S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
