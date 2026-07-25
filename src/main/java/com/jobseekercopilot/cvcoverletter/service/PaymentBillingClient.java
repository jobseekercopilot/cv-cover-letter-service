package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.exception.DownstreamServiceException;
import com.jobseekercopilot.cvcoverletter.exception.PaymentRequiredException;
import com.jobseekercopilot.cvcoverletter.security.OutboundServiceCredentials;
import java.util.UUID;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PaymentBillingClient {
    private static final Logger log = LoggerFactory.getLogger(PaymentBillingClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String OWNER_HEADER = "X-Payment-Owner";

    private final RestClient restClient;
    private final OutboundServiceCredentials credentials;

    public PaymentBillingClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.payment-service.base-url:http://localhost:8099}") String paymentServiceBaseUrl,
            OutboundServiceCredentials credentials) {
        this.restClient = restClientBuilder.baseUrl(paymentServiceBaseUrl).build();
        this.credentials = credentials;
    }

    public ReservationResponse reserve(String userId, ReservationRequest request) {
        long startedAt = System.nanoTime();
        log.info("Calling payment-service reservation userId={} feature={} estimatedTokens={}",
                userId,
                request.feature(),
                request.estimatedTokens());
        try {
            ReservationResponse response = restClient.post()
                    .uri("/api/v1/payments/reservations")
                    .header(SERVICE_TOKEN_HEADER, credentials.paymentServiceToken())
                    .header(OWNER_HEADER, userId)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 402,
                            (ignoredRequest, ignoredResponse) -> {
                                throw new PaymentRequiredException("Insufficient AI Credit");
                            })
                    .body(ReservationResponse.class);
            log.info("payment-service reservation returned reservationId={} reservedTokens={} durationMs={}",
                    response == null ? null : response.reservationId(),
                    response == null ? null : response.reservedTokens(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return response;
        } catch (PaymentRequiredException exception) {
            log.warn("payment-service reservation rejected insufficient balance userId={} durationMs={}",
                    userId,
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw exception;
        } catch (RestClientException exception) {
            log.warn("payment-service reservation failed userId={} durationMs={} error={}",
                    userId,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    exception.getClass().getSimpleName(),
                    exception);
            throw new DownstreamServiceException("Payment service reservation failed", exception);
        }
    }

    public void commit(String userId, UUID reservationId, CommitReservationRequest request) {
        long startedAt = System.nanoTime();
        log.info("Calling payment-service commit userId={} reservationId={} actualTokens={}",
                userId,
                reservationId,
                request.actualTokens());
        try {
            restClient.post()
                    .uri("/api/v1/payments/reservations/{reservationId}/commit", reservationId)
                    .header(SERVICE_TOKEN_HEADER, credentials.paymentServiceToken())
                    .header(OWNER_HEADER, userId)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError,
                            (ignoredRequest, ignoredResponse) -> {
                                throw new DownstreamServiceException("Payment service commit failed", null);
                            })
                    .toBodilessEntity();
            log.info("payment-service commit returned reservationId={} durationMs={}",
                    reservationId,
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (DownstreamServiceException exception) {
            log.warn("payment-service commit failed userId={} reservationId={} durationMs={}",
                    userId,
                    reservationId,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    exception);
            throw exception;
        } catch (RestClientException exception) {
            log.warn("payment-service commit failed userId={} reservationId={} durationMs={} error={}",
                    userId,
                    reservationId,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    exception.getClass().getSimpleName(),
                    exception);
            throw new DownstreamServiceException("Payment service commit failed", exception);
        }
    }

    public void release(String userId, UUID reservationId, String reason) {
        long startedAt = System.nanoTime();
        log.info("Calling payment-service release userId={} reservationId={} reason={}", userId, reservationId, reason);
        try {
            restClient.post()
                    .uri("/api/v1/payments/reservations/{reservationId}/release", reservationId)
                    .header(SERVICE_TOKEN_HEADER, credentials.paymentServiceToken())
                    .header(OWNER_HEADER, userId)
                    .body(new ReleaseReservationRequest(reason))
                    .retrieve()
                    .toBodilessEntity();
            log.info("payment-service release returned reservationId={} durationMs={}",
                    reservationId,
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RestClientException exception) {
            log.warn("payment-service release failed userId={} reservationId={} durationMs={} error={}",
                    userId,
                    reservationId,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    exception.getClass().getSimpleName(),
                    exception);
            throw new DownstreamServiceException("Payment service release failed", exception);
        }
    }

    @Builder
    public record ReservationRequest(
            String feature,
            long estimatedTokens,
            String referenceType,
            String referenceId) {
    }

    public record ReservationResponse(
            UUID reservationId,
            String userId,
            long reservedTokens,
            long balanceAfterReservation,
            String status) {
    }

    @Builder
    public record CommitReservationRequest(
            long actualTokens,
            String provider,
            String model,
            Long inputTokens,
            Long outputTokens,
            String description) {
    }

    public record ReleaseReservationRequest(String reason) {
    }
}
