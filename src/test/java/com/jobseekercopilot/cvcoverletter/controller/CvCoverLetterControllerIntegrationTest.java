package com.jobseekercopilot.cvcoverletter.controller;

import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterGatewayCredentials;
import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterIdentityFilter;
import com.jobseekercopilot.cvcoverletter.service.CvCoverLetterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = CvCoverLetterController.class,
        properties = "cv-cover-letter.security.gateway-token="
                + "test-only-cv-gateway-service-token-32-bytes")
@Import({CvCoverLetterGatewayCredentials.class, CvCoverLetterIdentityFilter.class})
class CvCoverLetterControllerIntegrationTest {

    private static final String SERVICE_TOKEN =
            "test-only-cv-gateway-service-token-32-bytes";

    @Autowired private MockMvc mockMvc;
    @MockBean private CvCoverLetterService cvCoverLetterService;

    @Test
    void validRequestReturnsGeneratedResponse() throws Exception {
        when(cvCoverLetterService.generate(any())).thenReturn(GenerateCvCoverLetterResponse.builder()
                .applicationId("application-1").cvDocumentId("cv-1")
                .coverLetterDocumentId("letter-1").cvContent("CV content").build());

        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Service-Token", SERVICE_TOKEN)
                        .header("X-Document-Owner", "owner-123")
                        .header("X-User-Id", "attacker-selected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("application-1"))
                .andExpect(jsonPath("$.cvDocumentId").value("cv-1"));

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(cvCoverLetterService).generate(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "owner-123",
                request.getValue().getUserProfile().getUserId());
    }

    @Test
    void invalidRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Service-Token", SERVICE_TOKEN)
                        .header("X-Document-Owner", "owner-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userProfile\":{},\"job\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingOrInvalidServiceIdentityFailsClosedBeforeGeneration() throws Exception {
        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Document-Owner", "owner-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SERVICE_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Service-Token", "not-the-gateway")
                        .header("X-Document-Owner", "owner-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SERVICE_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Service-Token", SERVICE_TOKEN, SERVICE_TOKEN)
                        .header("X-Document-Owner", "owner-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SERVICE_AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(cvCoverLetterService);
    }

    @Test
    void missingOrDuplicateOwnerFailsClosedBeforeGeneration() throws Exception {
        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Service-Token", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_OWNER_REQUIRED"));

        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .header("X-Service-Token", SERVICE_TOKEN)
                        .header("X-Document-Owner", "owner-123", "victim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_OWNER_REQUIRED"));

        verifyNoInteractions(cvCoverLetterService);
    }

    private String validRequest() {
        return """
                {"userProfile":{"userId":"body-selected-victim"},"job":{"id":"job-456",
                "title":"Java Developer","company":"Example Ltd","description":"Build useful services"}}
                """;
    }
}
