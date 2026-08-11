package com.jobseekercopilot.cvcoverletter.controller;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validSelectedRequest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationMetadataResponse;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationReplayResponse;
import com.jobseekercopilot.cvcoverletter.dto.RejectedSelectedGenerationReplayResponse;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.exception.GlobalExceptionHandler;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationDiagnostic;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationQuarantineService;
import com.jobseekercopilot.cvcoverletter.security.RejectedGenerationOperatorFilter;
import com.jobseekercopilot.cvcoverletter.service.CvCoverLetterService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RejectedGenerationOperatorControllerTest {
    private static final String OPERATOR_TOKEN = "o".repeat(32);
    private static final String OWNER = "owner-123";

    @Mock private CvCoverLetterService cvCoverLetterService;
    @Mock private RejectedGenerationQuarantineService quarantineService;

    private RejectedGenerationQuarantineProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        properties = new RejectedGenerationQuarantineProperties();
        properties.setEnabled(true);
        properties.setOperatorToken(OPERATOR_TOKEN);
    }

    @Test
    void hidesTheOperatorBoundaryWhenQuarantineIsDisabled() throws Exception {
        properties.setEnabled(false);
        MockMvc mvc = mockMvc();

        mvc.perform(get(path(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void requiresExactlyOneDedicatedOperatorCredentialAndOwner() throws Exception {
        MockMvc mvc = mockMvc();
        UUID operationId = UUID.randomUUID();

        mvc.perform(get(path(operationId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("OPERATOR_AUTHENTICATION_REQUIRED"));

        mvc.perform(get(path(operationId))
                        .header("X-Operator-Token", OPERATOR_TOKEN)
                        .header("X-Document-Owner", OWNER, "different-owner"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_OWNER_REQUIRED"));
    }

    @Test
    void returnsOnlyOwnerBoundMetadataThroughTheInternalEndpoint() throws Exception {
        MockMvc mvc = mockMvc();
        UUID operationId = UUID.randomUUID();
        when(quarantineService.metadata(OWNER, operationId)).thenReturn(
                new RejectedGenerationMetadataResponse(
                        operationId,
                        Instant.parse("2026-08-07T20:00:00Z"),
                        Instant.parse("2026-08-08T20:00:00Z"),
                        "cv-cover-letter-1.5.9",
                        "a".repeat(64),
                        "cv-cover-letter-output",
                        "3.8.0",
                        "b".repeat(64),
                        "1.5.7",
                        "3.5.2",
                        "2.13.0",
                        "c".repeat(64),
                        "gpt-4.1-mini-2025-04-14",
                        30_000L,
                        4_000L,
                        34_000L,
                        0,
                        new RejectedGenerationDiagnostic(
                                "CLAIM_EVIDENCE",
                                "$.claims[0].contentPaths[13]",
                                "Invalid content path.")));

        mvc.perform(get(path(operationId))
                        .header("X-Operator-Token", OPERATOR_TOKEN)
                        .header("X-Document-Owner", OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.replayCount").value(0))
                .andExpect(jsonPath("$.responseJson").doesNotExist());
    }

    @Test
    void replayResponseExplicitlyProvesThereWasNoProviderInvocation() throws Exception {
        MockMvc mvc = mockMvc();
        UUID operationId = UUID.randomUUID();
        when(cvCoverLetterService.replayRejectedDraft(
                any(), any(), any())).thenReturn(
                new RejectedGenerationReplayResponse(
                        operationId,
                        Instant.parse("2026-08-07T20:10:00Z"),
                        "REJECTED",
                        0,
                        "3.5.2",
                        "2.13.0",
                        new RejectedGenerationDiagnostic(
                                "CLAIM_EVIDENCE",
                                "$.claims[0]",
                                "Still rejected."),
                        null));

        mvc.perform(post(path(operationId) + "/replay")
                        .header("X-Operator-Token", OPERATOR_TOKEN)
                        .header("X-Document-Owner", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.providerInvocationCount").value(0));
        verify(cvCoverLetterService).replayRejectedDraft(
                any(), any(), any());
    }

    @Test
    void selectedReplayAcceptsTheSelectedRequestShapeAndProvesZeroProviderCalls()
            throws Exception {
        MockMvc mvc = mockMvc();
        UUID operationId = UUID.randomUUID();
        when(cvCoverLetterService.replayRejectedSelectedDraft(
                any(), any(), any(), any())).thenReturn(
                new RejectedSelectedGenerationReplayResponse(
                        operationId,
                        Instant.parse("2026-08-07T20:10:00Z"),
                        "REJECTED",
                        0,
                        "3.6.3",
                        "2.24.0",
                        new RejectedGenerationDiagnostic(
                                "CLAIM_EVIDENCE",
                                "$.claims[0]",
                                "Still rejected."),
                        null));

        mvc.perform(post(path(operationId) + "/replay/COVER_LETTER")
                        .header("X-Operator-Token", OPERATOR_TOKEN)
                        .header("X-Document-Owner", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                validSelectedRequest(
                                        DraftOutputType.COVER_LETTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.providerInvocationCount").value(0));
        verify(cvCoverLetterService).replayRejectedSelectedDraft(
                any(), any(), any(), any());
    }

    private MockMvc mockMvc() {
        var controller = new RejectedGenerationOperatorController(
                cvCoverLetterService,
                quarantineService);
        var filter = new RejectedGenerationOperatorFilter(properties, objectMapper);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    private String path(UUID operationId) {
        return "/internal/v1/cv-cover-letter/rejected-generations/" + operationId;
    }
}
