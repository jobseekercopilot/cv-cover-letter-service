package com.jobseekercopilot.cvcoverletter.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    @Test
    void classifiesRejectedModelOutputAsUnprocessable() {
        HttpServletRequest request =
                mock(HttpServletRequest.class);
        when(request.getRequestURI())
                .thenReturn("/api/v1/cv-cover-letter/drafts");

        var response = new GlobalExceptionHandler()
                .invalidModelOutput(
                        new InvalidLlmResponseException(
                                "Output was not grounded."),
                        request);

        assertEquals(
                HttpStatus.UNPROCESSABLE_ENTITY,
                response.getStatusCode());
        assertEquals(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                response.getBody().getStatus());
    }

    @Test
    void keepsTransportAndProviderFailuresAsBadGateway() {
        HttpServletRequest request =
                mock(HttpServletRequest.class);
        when(request.getRequestURI())
                .thenReturn("/api/v1/cv-cover-letter/drafts");

        var response = new GlobalExceptionHandler()
                .badGateway(
                        new DownstreamServiceException(
                                "Provider unavailable.",
                                null),
                        request);

        assertEquals(
                HttpStatus.BAD_GATEWAY,
                response.getStatusCode());
    }
}
