package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterGatewayCredentials;
import com.jobseekercopilot.cvcoverletter.security.OutboundServiceCredentials;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class PaymentBillingClientTest {
    private static final String PAYMENT_TOKEN =
            "test-only-cv-payment-service-token-32-bytes";

    private MockRestServiceServer server;
    private PaymentBillingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
                RestClient.builder().baseUrl("https://payment.example.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PaymentBillingClient(
                builder,
                "https://payment.example.test",
                new OutboundServiceCredentials(
                        "test-only-document-store-producer-token-32-bytes",
                        "test-only-application-tracker-producer-token-32-bytes",
                        PAYMENT_TOKEN,
                        new CvCoverLetterGatewayCredentials(
                                "test-only-cv-gateway-service-token-32-bytes")));
    }

    @Test
    void reservationUsesDedicatedServiceIdentityAndTrustedOwner() {
        server.expect(requestTo("https://payment.example.test/api/v1/payments/reservations"))
                .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                .andExpect(header("X-Payment-Owner", "owner-123"))
                .andExpect(noHeader("X-User-Id"))
                .andExpect(content().json("""
                        {
                          "feature": "CV_COVER_LETTER",
                          "estimatedTokens": 5000,
                          "operationKey": "cv-generation:operation-1",
                          "referenceType": "APPLICATION",
                          "referenceId": "job-123"
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"reservationId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"userId\":\"owner-123\",\"reservedTokens\":5000,"
                                + "\"balanceAfterReservation\":40000,\"status\":\"RESERVED\"}",
                        MediaType.APPLICATION_JSON));

        PaymentBillingClient.ReservationResponse response = client.reserve(
                "owner-123",
                new PaymentBillingClient.ReservationRequest(
                        "CV_COVER_LETTER",
                        5000,
                        "cv-generation:operation-1",
                        "APPLICATION",
                        "job-123"));

        assertEquals("owner-123", response.userId());
        server.verify();
    }

    @Test
    void lostReservationResponseRetriesTheSameOperationKey() {
        String reservationUrl =
                "https://payment.example.test/api/v1/payments/reservations";
        RequestMatcher operationKey = content().json("""
                {
                  "feature": "CV_COVER_LETTER",
                  "estimatedTokens": 5000,
                  "operationKey": "cv-generation:lost-response",
                  "referenceType": "APPLICATION",
                  "referenceId": "job-123"
                }
                """);
        server.expect(requestTo(reservationUrl))
                .andExpect(operationKey)
                .andRespond(withServerError());
        server.expect(requestTo(reservationUrl))
                .andExpect(operationKey)
                .andRespond(withSuccess(
                        "{\"reservationId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"userId\":\"owner-123\",\"reservedTokens\":5000,"
                                + "\"balanceAfterReservation\":40000,\"status\":\"RESERVED\"}",
                        MediaType.APPLICATION_JSON));

        PaymentBillingClient.ReservationResponse response = client.reserve(
                "owner-123",
                new PaymentBillingClient.ReservationRequest(
                        "CV_COVER_LETTER",
                        5000,
                        "cv-generation:lost-response",
                        "APPLICATION",
                        "job-123"));

        assertEquals(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                response.reservationId());
        server.verify();
    }

    @Test
    void commitAndReleasePreserveTheSameBoundary() {
        UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        server.expect(requestTo(
                        "https://payment.example.test/api/v1/payments/reservations/"
                                + reservationId + "/commit"))
                .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                .andExpect(header("X-Payment-Owner", "owner-123"))
                .andExpect(noHeader("X-User-Id"))
                .andRespond(withSuccess());
        server.expect(requestTo(
                        "https://payment.example.test/api/v1/payments/reservations/"
                                + reservationId + "/release"))
                .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                .andExpect(header("X-Payment-Owner", "owner-123"))
                .andExpect(noHeader("X-User-Id"))
                .andRespond(withSuccess());

        client.commit(
                "owner-123",
                reservationId,
                PaymentBillingClient.CommitReservationRequest.builder()
                        .actualTokens(4200)
                        .description("CV generation")
                        .build());
        client.release("owner-123", reservationId, "test release");

        server.verify();
    }

    @Test
    void ambiguousCommitIsResolvedFromOwnerScopedLifecycle() {
        UUID reservationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");
        String commitUrl = "https://payment.example.test/api/v1/payments/reservations/"
                + reservationId + "/commit";
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(commitUrl))
                    .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                    .andExpect(header("X-Payment-Owner", "owner-123"))
                    .andRespond(withServerError());
        }
        server.expect(requestTo(
                        "https://payment.example.test/api/v1/payments/reservations/"
                                + reservationId))
                .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                .andExpect(header("X-Payment-Owner", "owner-123"))
                .andRespond(withSuccess(
                        "{\"reservationId\":\"" + reservationId
                                + "\",\"userId\":\"owner-123\",\"status\":\"COMMITTED\"}",
                        MediaType.APPLICATION_JSON));

        client.commit(
                "owner-123",
                reservationId,
                PaymentBillingClient.CommitReservationRequest.builder()
                        .actualTokens(4200)
                        .build());

        server.verify();
    }

    @Test
    void ambiguousReleaseIsResolvedFromOwnerScopedLifecycle() {
        UUID reservationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");
        String releaseUrl = "https://payment.example.test/api/v1/payments/reservations/"
                + reservationId + "/release";
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(releaseUrl))
                    .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                    .andExpect(header("X-Payment-Owner", "owner-123"))
                    .andRespond(withServerError());
        }
        server.expect(requestTo(
                        "https://payment.example.test/api/v1/payments/reservations/"
                                + reservationId))
                .andExpect(header("X-Service-Token", PAYMENT_TOKEN))
                .andExpect(header("X-Payment-Owner", "owner-123"))
                .andRespond(withSuccess(
                        "{\"reservationId\":\"" + reservationId
                                + "\",\"userId\":\"owner-123\",\"status\":\"RELEASED\"}",
                        MediaType.APPLICATION_JSON));

        client.release("owner-123", reservationId, "generation failed");

        server.verify();
    }

    @Test
    void unresolvedReleaseRemainsAFirstClassFailure() {
        UUID reservationId =
                UUID.fromString("00000000-0000-0000-0000-000000000001");
        String releaseUrl = "https://payment.example.test/api/v1/payments/reservations/"
                + reservationId + "/release";
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo(releaseUrl))
                    .andRespond(withServerError());
        }
        server.expect(requestTo(
                        "https://payment.example.test/api/v1/payments/reservations/"
                                + reservationId))
                .andRespond(withSuccess(
                        "{\"reservationId\":\"" + reservationId
                                + "\",\"userId\":\"owner-123\",\"status\":\"RESERVED\"}",
                        MediaType.APPLICATION_JSON));

        assertThrows(
                com.jobseekercopilot.cvcoverletter.exception.DownstreamServiceException.class,
                () -> client.release(
                        "owner-123", reservationId, "generation failed"));

        server.verify();
    }

    private static RequestMatcher noHeader(String name) {
        return request -> assertFalse(request.getHeaders().containsKey(name));
    }
}
