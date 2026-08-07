package com.jobseekercopilot.cvcoverletter.quarantine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationDeletionResponse;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationMetadataResponse;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationNotFoundException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationQuarantineException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationReplayConflictException;
import com.jobseekercopilot.generated.llmgateway.model.GenerationAudit;
import com.jobseekercopilot.generated.llmgateway.model.GenerationRequest;
import com.jobseekercopilot.generated.llmgateway.model.GenerationResponse;
import com.jobseekercopilot.generated.llmgateway.model.GenerationUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
public class RejectedGenerationQuarantineService {
    private static final Pattern STRUCTURED_REJECTION = Pattern.compile(
            "^LLM response failed ([A-Za-z ]{1,64}) validation at (\\$[^:]{0,255}): (.+)$");
    private static final int MAXIMUM_DIAGNOSTIC_REASON_CHARACTERS = 512;

    private final EncryptedRejectedGenerationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RejectedGenerationQuarantineProperties properties;

    @Autowired
    public RejectedGenerationQuarantineService(
            EncryptedRejectedGenerationRepository repository,
            ObjectMapper objectMapper,
            RejectedGenerationQuarantineProperties properties) {
        this(repository, objectMapper, Clock.systemUTC(), properties);
    }

    RejectedGenerationQuarantineService(
            EncryptedRejectedGenerationRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            RejectedGenerationQuarantineProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return repository.isEnabled();
    }

    @Scheduled(
            fixedDelayString =
                    "${rejected-generation-quarantine.cleanup-interval:PT15M}")
    public void purgeExpired() {
        if (!repository.isEnabled()) {
            return;
        }
        try {
            int purged = repository.purgeExpired();
            if (purged > 0) {
                log.info(
                        "Expired rejected generation artifacts purged count={}",
                        purged);
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Rejected generation quarantine cleanup failed failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    public RejectedGenerationArtifact capture(
            RejectedGenerationCaptureContext context) {
        if (!repository.isEnabled()) {
            return null;
        }
        requireCaptureContext(context);
        Instant capturedAt = clock.instant();
        String responseJson = writeJson(context.generationResponse());
        String requestSha256 = sha256(writeJson(context.generationRequest()));
        String responseSha256 = sha256(responseJson);
        PromptGenerationMetadata metadata = context.promptMetadata();
        RejectedGenerationDiagnostic diagnostic = diagnostic(context.rejection());
        String validatorFingerprint = validatorFingerprint(
                metadata,
                context.parserVersion(),
                context.claimPolicyVersion());
        List<RejectedGenerationReplayAuditEvent> events = List.of(newAuditEvent(
                1,
                capturedAt,
                "CAPTURED",
                "REJECTED",
                validatorFingerprint,
                diagnostic,
                null));
        GenerationResponse response = context.generationResponse();
        GenerationAudit audit = response.getAudit();
        GenerationUsage usage = response.getUsage();
        RejectedGenerationArtifact artifact = new RejectedGenerationArtifact(
                1,
                context.operationId(),
                context.ownerId(),
                capturedAt,
                capturedAt.plus(repository.retention()),
                metadata.releaseId(),
                metadata.bundleSha256(),
                metadata.schemaId(),
                metadata.schemaVersion(),
                metadata.schemaSha256(),
                metadata.evaluationPolicyVersion(),
                metadata.evaluationPolicySha256(),
                context.parserVersion(),
                context.claimPolicyVersion(),
                requestSha256,
                responseSha256,
                responseJson,
                audit == null ? null : audit.getModelId(),
                usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens(),
                usage == null ? null : usage.getTotalTokens(),
                events);
        RejectedGenerationArtifact stored = repository.create(artifact);
        log.warn(
                "Rejected generation response quarantined operationId={} responseSha256={} expiresAt={} rejectionPhase={} rejectionPath={}",
                stored.operationId(),
                stored.responseSha256(),
                stored.expiresAt(),
                diagnostic.phase(),
                diagnostic.path());
        return stored;
    }

    public LoadedRejectedGeneration load(String ownerId, UUID operationId) {
        RejectedGenerationArtifact artifact = repository.read(operationId);
        requireOwner(artifact, ownerId);
        verifyArtifact(artifact);
        try {
            GenerationResponse response = objectMapper.readValue(
                    artifact.responseJson(),
                    GenerationResponse.class);
            return new LoadedRejectedGeneration(artifact, response);
        } catch (JsonProcessingException exception) {
            throw new RejectedGenerationQuarantineException(
                    "Rejected generation response could not be decoded.",
                    exception);
        }
    }

    public void requireMatchingReplayContext(
            RejectedGenerationArtifact artifact,
            GenerationRequest request,
            PromptGenerationMetadata metadata) {
        String requestSha256 = sha256(writeJson(request));
        boolean metadataMatches =
                artifact.promptReleaseId().equals(metadata.releaseId())
                && artifact.promptBundleSha256().equals(metadata.bundleSha256())
                && artifact.schemaId().equals(metadata.schemaId())
                && artifact.schemaVersion().equals(metadata.schemaVersion())
                && artifact.schemaSha256().equals(metadata.schemaSha256())
                && artifact.evaluationPolicyVersion().equals(
                        metadata.evaluationPolicyVersion())
                && artifact.evaluationPolicySha256().equals(
                        metadata.evaluationPolicySha256());
        if (!metadataMatches) {
            throw new RejectedGenerationReplayConflictException(
                    "Replay input does not match the quarantined generation context.");
        }
        if (constantTimeEquals(
                artifact.generationRequestSha256(), requestSha256)) {
            return;
        }
        if (!properties.isAllowOperationBoundContextDrift()) {
            throw new RejectedGenerationReplayConflictException(
                    "Replay input does not match the quarantined generation context.");
        }
        log.warn(
                "Operation-bound rejected generation replay allowed after derived request drift operationId={} promptRelease={} providerInvocationCount=0",
                artifact.operationId(),
                artifact.promptReleaseId());
    }

    public synchronized RejectedGenerationReplayAuditEvent recordReplay(
            String ownerId,
            UUID operationId,
            String outcome,
            PromptGenerationMetadata metadata,
            String parserVersion,
            String claimPolicyVersion,
            RejectedGenerationDiagnostic diagnostic) {
        RejectedGenerationArtifact artifact = repository.read(operationId);
        requireOwner(artifact, ownerId);
        verifyArtifact(artifact);
        if (artifact.auditEvents().size() >= repository.maxReplayEvents()) {
            throw new RejectedGenerationReplayConflictException(
                    "Rejected generation replay audit capacity has been reached.");
        }
        List<RejectedGenerationReplayAuditEvent> events =
                new ArrayList<>(artifact.auditEvents());
        RejectedGenerationReplayAuditEvent previous = events.get(events.size() - 1);
        RejectedGenerationReplayAuditEvent event = newAuditEvent(
                previous.sequence() + 1,
                clock.instant(),
                "REPLAYED",
                outcome,
                validatorFingerprint(metadata, parserVersion, claimPolicyVersion),
                diagnostic,
                previous.eventSha256());
        events.add(event);
        repository.replace(artifact.withAuditEvents(events));
        log.info(
                "Rejected generation replay audited operationId={} outcome={} validatorFingerprint={} rejectionPhase={} rejectionPath={} providerInvocationCount=0",
                operationId,
                outcome,
                event.validatorFingerprint(),
                diagnostic == null ? null : diagnostic.phase(),
                diagnostic == null ? null : diagnostic.path());
        return event;
    }

    public RejectedGenerationMetadataResponse metadata(
            String ownerId,
            UUID operationId) {
        RejectedGenerationArtifact artifact = load(ownerId, operationId).artifact();
        RejectedGenerationReplayAuditEvent latest =
                artifact.auditEvents().get(artifact.auditEvents().size() - 1);
        return new RejectedGenerationMetadataResponse(
                artifact.operationId(),
                artifact.capturedAt(),
                artifact.expiresAt(),
                artifact.promptReleaseId(),
                artifact.promptBundleSha256(),
                artifact.schemaId(),
                artifact.schemaVersion(),
                artifact.schemaSha256(),
                artifact.evaluationPolicyVersion(),
                artifact.parserVersion(),
                artifact.claimPolicyVersion(),
                artifact.responseSha256(),
                artifact.modelId(),
                artifact.inputTokens(),
                artifact.outputTokens(),
                artifact.totalTokens(),
                Math.max(0, artifact.auditEvents().size() - 1),
                latest.diagnostic());
    }

    public RejectedGenerationDeletionResponse delete(
            String ownerId,
            UUID operationId) {
        RejectedGenerationArtifact artifact = repository.read(operationId);
        requireOwner(artifact, ownerId);
        repository.delete(operationId);
        Instant deletedAt = clock.instant();
        log.info(
                "Rejected generation artifact deleted operationId={} deletedAt={}",
                operationId,
                deletedAt);
        return new RejectedGenerationDeletionResponse(operationId, deletedAt);
    }

    public RejectedGenerationDiagnostic diagnostic(RuntimeException exception) {
        String message = exception == null ? "" : safeText(exception.getMessage());
        Matcher matcher = STRUCTURED_REJECTION.matcher(message);
        if (matcher.matches()) {
            return new RejectedGenerationDiagnostic(
                    matcher.group(1).trim().toUpperCase().replace(' ', '_'),
                    matcher.group(2),
                    bounded(matcher.group(3)));
        }
        String phase = message.startsWith("LLM gateway")
                || message.startsWith("LLM generation")
                ? "RESPONSE_METADATA"
                : "OUTPUT_VALIDATION";
        return new RejectedGenerationDiagnostic(
                phase,
                "$",
                bounded(message.isBlank()
                        ? "Rejected by deterministic validation."
                        : message));
    }

    private void verifyArtifact(RejectedGenerationArtifact artifact) {
        if (!constantTimeEquals(
                artifact.responseSha256(),
                sha256(artifact.responseJson()))) {
            throw new RejectedGenerationQuarantineException(
                    "Rejected generation response digest is invalid.");
        }
        List<RejectedGenerationReplayAuditEvent> events = artifact.auditEvents();
        if (events == null || events.isEmpty()) {
            throw new RejectedGenerationQuarantineException(
                    "Rejected generation replay audit is missing.");
        }
        String previous = null;
        for (int index = 0; index < events.size(); index++) {
            RejectedGenerationReplayAuditEvent event = events.get(index);
            if (event.sequence() != index + 1L
                    || !constantTimeNullable(previous, event.previousEventSha256())
                    || !constantTimeEquals(
                            event.eventSha256(),
                            auditEventSha256(
                                    event.sequence(),
                                    event.recordedAt(),
                                    event.action(),
                                    event.outcome(),
                                    event.validatorFingerprint(),
                                    event.diagnostic(),
                                    event.previousEventSha256()))) {
                throw new RejectedGenerationQuarantineException(
                        "Rejected generation replay audit is invalid.");
            }
            previous = event.eventSha256();
        }
    }

    private RejectedGenerationReplayAuditEvent newAuditEvent(
            long sequence,
            Instant recordedAt,
            String action,
            String outcome,
            String validatorFingerprint,
            RejectedGenerationDiagnostic diagnostic,
            String previousEventSha256) {
        String digest = auditEventSha256(
                sequence,
                recordedAt,
                action,
                outcome,
                validatorFingerprint,
                diagnostic,
                previousEventSha256);
        return new RejectedGenerationReplayAuditEvent(
                sequence,
                recordedAt,
                action,
                outcome,
                validatorFingerprint,
                diagnostic,
                previousEventSha256,
                digest);
    }

    private String auditEventSha256(
            long sequence,
            Instant recordedAt,
            String action,
            String outcome,
            String validatorFingerprint,
            RejectedGenerationDiagnostic diagnostic,
            String previousEventSha256) {
        return sha256(writeJson(new AuditEventDigestInput(
                sequence,
                recordedAt,
                action,
                outcome,
                validatorFingerprint,
                diagnostic,
                previousEventSha256)));
    }

    private String validatorFingerprint(
            PromptGenerationMetadata metadata,
            String parserVersion,
            String claimPolicyVersion) {
        return sha256(writeJson(new ValidatorFingerprintInput(
                metadata.releaseId(),
                metadata.bundleSha256(),
                metadata.schemaId(),
                metadata.schemaVersion(),
                metadata.schemaSha256(),
                metadata.evaluationPolicyVersion(),
                metadata.evaluationPolicySha256(),
                parserVersion,
                claimPolicyVersion)));
    }

    private void requireCaptureContext(RejectedGenerationCaptureContext context) {
        if (context == null
                || context.operationId() == null
                || context.ownerId() == null
                || context.ownerId().isBlank()
                || context.promptMetadata() == null
                || context.generationRequest() == null
                || context.generationResponse() == null
                || context.rejection() == null) {
            throw new IllegalArgumentException(
                    "Complete rejected generation capture context is required.");
        }
    }

    private void requireOwner(RejectedGenerationArtifact artifact, String ownerId) {
        if (ownerId == null
                || !constantTimeEquals(artifact.ownerId(), ownerId)) {
            throw new RejectedGenerationNotFoundException();
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private boolean constantTimeNullable(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return constantTimeEquals(left, right);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new RejectedGenerationQuarantineException(
                    "Rejected generation audit data could not be serialized.",
                    exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String bounded(String value) {
        String safe = safeText(value);
        return safe.length() <= MAXIMUM_DIAGNOSTIC_REASON_CHARACTERS
                ? safe
                : safe.substring(0, MAXIMUM_DIAGNOSTIC_REASON_CHARACTERS);
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Cc}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record LoadedRejectedGeneration(
            RejectedGenerationArtifact artifact,
            GenerationResponse response) {
    }

    private record AuditEventDigestInput(
            long sequence,
            Instant recordedAt,
            String action,
            String outcome,
            String validatorFingerprint,
            RejectedGenerationDiagnostic diagnostic,
            String previousEventSha256) {
    }

    private record ValidatorFingerprintInput(
            String promptReleaseId,
            String promptBundleSha256,
            String schemaId,
            String schemaVersion,
            String schemaSha256,
            String evaluationPolicyVersion,
            String evaluationPolicySha256,
            String parserVersion,
            String claimPolicyVersion) {
    }
}
