package com.jobseekercopilot.cvcoverletter.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationNotFoundException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationReplayConflictException;
import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterGatewayCredentials;
import com.jobseekercopilot.generated.llmgateway.model.GenerationAudit;
import com.jobseekercopilot.generated.llmgateway.model.GenerationLimits;
import com.jobseekercopilot.generated.llmgateway.model.GenerationOutputContract;
import com.jobseekercopilot.generated.llmgateway.model.GenerationRequest;
import com.jobseekercopilot.generated.llmgateway.model.GenerationResponse;
import com.jobseekercopilot.generated.llmgateway.model.GenerationUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RejectedGenerationQuarantineServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-07T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private RejectedGenerationQuarantineService service;
    private GenerationRequest request;
    private GenerationResponse response;
    private PromptGenerationMetadata metadata;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RejectedGenerationQuarantineProperties properties =
                new RejectedGenerationQuarantineProperties();
        properties.setEnabled(true);
        properties.setStorageDirectory(temporaryDirectory.toString());
        properties.setEncryptionKeyBase64(Base64.getEncoder().encodeToString(
                "k".repeat(32).getBytes(StandardCharsets.UTF_8)));
        properties.setOperatorToken("o".repeat(32));
        properties.setRetention(Duration.ofHours(1));
        properties.setMaxArtifactBytes(262_144);
        properties.setMaxArtifacts(10);
        properties.setMaxReplayEvents(10);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EncryptedRejectedGenerationRepository repository =
                new EncryptedRejectedGenerationRepository(
                        properties,
                        objectMapper,
                        new CvCoverLetterGatewayCredentials("g".repeat(32)),
                        clock,
                        new SecureRandom());
        repository.initialize();
        service = new RejectedGenerationQuarantineService(
                repository,
                objectMapper,
                clock);
        metadata = new PromptGenerationMetadata(
                "cv-cover-letter-1.5.9",
                "cv-cover-letter",
                "1.5.9",
                "a".repeat(64),
                "1.2.0",
                "b".repeat(64),
                "1.5.9",
                "c".repeat(64),
                "cv-cover-letter-output",
                "3.8.0",
                "d".repeat(64),
                "1.5.7",
                "e".repeat(64));
        request = new GenerationRequest()
                .contractVersion(GenerationRequest.ContractVersionEnum._2_0)
                .task("CV_COVER_LETTER_GENERATION")
                .trustedInstructions("trusted")
                .untrustedInput("evidence-secret-sentinel")
                .output(new GenerationOutputContract()
                        .format(GenerationOutputContract.FormatEnum.JSON_SCHEMA)
                        .schemaId("cv-cover-letter-output")
                        .schemaVersion("3.8.0")
                        .jsonSchema(new ObjectMapper().createObjectNode()))
                .limits(new GenerationLimits()
                        .temperature(0.0)
                        .maxOutputTokens(8192));
        response = new GenerationResponse()
                .contractVersion("2.0")
                .output("{\"response\":\"response-secret-sentinel\"}")
                .finishReason(GenerationResponse.FinishReasonEnum.COMPLETED)
                .schemaId("cv-cover-letter-output")
                .schemaVersion("3.8.0")
                .audit(new GenerationAudit()
                        .modelId("gpt-4.1-mini-2025-04-14")
                        .modelDeploymentVersion("deployment-1")
                        .admissionPolicyVersion("admission-1")
                        .pricingVersion("pricing-1")
                        .estimatedInputTokensAtAdmission(30_000L)
                        .estimatedCostMicroUsd(42_000L)
                        .currency("USD"))
                .usage(new GenerationUsage()
                        .inputTokens(30_000L)
                        .outputTokens(4_000L)
                        .totalTokens(34_000L));
    }

    @Test
    void capturesExactTypedResponseWithoutCopyingPromptOrEvidence() {
        UUID operationId = UUID.randomUUID();
        InvalidLlmResponseException rejection = new InvalidLlmResponseException(
                "LLM response failed claim evidence validation at "
                        + "$.claims[0].contentPaths[13]: invalid approved path");

        service.capture(context(operationId, rejection));
        var loaded = service.load("owner-123", operationId);

        assertEquals(response, loaded.response());
        assertEquals("CLAIM_EVIDENCE", loaded.artifact().auditEvents().get(0)
                .diagnostic().phase());
        assertEquals("$.claims[0].contentPaths[13]", loaded.artifact()
                .auditEvents().get(0).diagnostic().path());
        assertEquals(64, loaded.artifact().generationRequestSha256().length());
        assertEquals(64, loaded.artifact().responseSha256().length());
        assertThrows(
                RejectedGenerationNotFoundException.class,
                () -> service.load("different-owner", operationId));
    }

    @Test
    void replayContextMustRebuildTheExactOriginalGenerationRequest() {
        UUID operationId = UUID.randomUUID();
        RejectedGenerationArtifact artifact = service.capture(context(
                operationId,
                new InvalidLlmResponseException("unsafe")));

        service.requireMatchingReplayContext(artifact, request, metadata);

        GenerationRequest changed = new GenerationRequest()
                .contractVersion(GenerationRequest.ContractVersionEnum._2_0)
                .task("DIFFERENT")
                .trustedInstructions("trusted")
                .untrustedInput("evidence-secret-sentinel")
                .output(request.getOutput())
                .limits(request.getLimits());
        assertThrows(
                RejectedGenerationReplayConflictException.class,
                () -> service.requireMatchingReplayContext(
                        artifact,
                        changed,
                        metadata));
    }

    @Test
    void appendsTamperEvidentReplayEventsAndDeletesExplicitly() {
        UUID operationId = UUID.randomUUID();
        service.capture(context(
                operationId,
                new InvalidLlmResponseException("unsafe")));

        var replay = service.recordReplay(
                "owner-123",
                operationId,
                "ACCEPTED",
                metadata,
                "3.5.2",
                "2.13.0",
                null);
        var result = service.metadata("owner-123", operationId);

        assertEquals(2L, replay.sequence());
        assertEquals(1, result.replayCount());
        assertNull(result.latestDiagnostic());
        assertEquals(34_000L, result.totalTokens());

        service.delete("owner-123", operationId);
        assertThrows(
                RejectedGenerationNotFoundException.class,
                () -> service.load("owner-123", operationId));
    }

    private RejectedGenerationCaptureContext context(
            UUID operationId,
            RuntimeException rejection) {
        return new RejectedGenerationCaptureContext(
                operationId,
                "owner-123",
                metadata,
                "3.5.2",
                "2.13.0",
                request,
                response,
                rejection);
    }
}
