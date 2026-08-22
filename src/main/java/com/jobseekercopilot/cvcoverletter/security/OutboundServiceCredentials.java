package com.jobseekercopilot.cvcoverletter.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboundServiceCredentials {

    static final int MINIMUM_TOKEN_BYTES = 32;
    private static final String CONFIGURATION_ERROR =
            "Outbound service credentials must contain at least 32 bytes and be pairwise distinct.";

    private final String documentStoreProducerToken;
    private final String applicationTrackerProducerToken;
    private final String paymentServiceToken;

    public OutboundServiceCredentials(
            @Value("${cv-cover-letter.security.document-store-producer-token}")
            String documentStoreProducerToken,
            @Value("${cv-cover-letter.security.application-tracker-producer-token}")
            String applicationTrackerProducerToken,
            @Value("${cv-cover-letter.security.payment-service-token}")
            String paymentServiceToken,
            CvCoverLetterGatewayCredentials gatewayCredentials) {
        requireStrong(documentStoreProducerToken);
        requireStrong(applicationTrackerProducerToken);
        requireStrong(paymentServiceToken);
        if (List.of(
                        documentStoreProducerToken,
                        applicationTrackerProducerToken,
                        paymentServiceToken,
                        gatewayCredentials.gatewayToken())
                .stream()
                .distinct()
                .count() != 4) {
            throw new IllegalStateException(CONFIGURATION_ERROR);
        }
        this.documentStoreProducerToken = documentStoreProducerToken;
        this.applicationTrackerProducerToken = applicationTrackerProducerToken;
        this.paymentServiceToken = paymentServiceToken;
    }

    private void requireStrong(String token) {
        if (token == null
                || token.isBlank()
                || token.getBytes(StandardCharsets.UTF_8).length < MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(CONFIGURATION_ERROR);
        }
    }

    public String documentStoreProducerToken() {
        return documentStoreProducerToken;
    }

    public String applicationTrackerProducerToken() {
        return applicationTrackerProducerToken;
    }

    public String paymentServiceToken() {
        return paymentServiceToken;
    }
}
