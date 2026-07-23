package com.jobseekercopilot.cvcoverletter.controller;

import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.service.CvCoverLetterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CvCoverLetterController.class)
class CvCoverLetterControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private CvCoverLetterService cvCoverLetterService;

    @Test
    void validRequestReturnsGeneratedResponse() throws Exception {
        when(cvCoverLetterService.generate(any())).thenReturn(GenerateCvCoverLetterResponse.builder()
                .applicationId("application-1").cvDocumentId("cv-1")
                .coverLetterDocumentId("letter-1").cvContent("CV content").build());

        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("application-1"))
                .andExpect(jsonPath("$.cvDocumentId").value("cv-1"));
    }

    @Test
    void invalidRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/cv-cover-letter/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userProfile\":{},\"job\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private String validRequest() {
        return """
                {"userProfile":{"userId":"user-123"},"job":{"id":"job-456",
                "title":"Java Developer","company":"Example Ltd","description":"Build useful services"}}
                """;
    }
}
