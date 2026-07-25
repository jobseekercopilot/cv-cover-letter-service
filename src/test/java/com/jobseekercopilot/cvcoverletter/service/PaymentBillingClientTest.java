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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
                .andRespond(withSuccess(
                        "{\"reservationId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"userId\":\"owner-123\",\"reservedTokens\":5000,"
                                + "\"balanceAfterReservation\":40000,\"status\":\"RESERVED\"}",
                        MediaType.APPLICATION_JSON));

        PaymentBillingClient.ReservationResponse response = client.reserve(
                "owner-123",
                new PaymentBillingClient.ReservationRequest(
                        "CV_COVER_LETTER", 5000, "APPLICATION", "job-123"));

        assertEquals("owner-123", response.userId());
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

    private static RequestMatcher noHeader(String name) {
        return request -> assertFalse(request.getHeaders().containsKey(name));
    }
}
