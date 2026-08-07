package com.jobseekercopilot.cvcoverletter.quarantine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationNotFoundException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationQuarantineException;
import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterGatewayCredentials;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedRejectedGenerationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T20:00:00Z");
    private static final String GATEWAY_TOKEN = "g".repeat(32);
    private static final String OPERATOR_TOKEN = "o".repeat(32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOnlyAuthenticatedCiphertextAndRoundTripsTheArtifact() throws Exception {
        byte[] key = "k".repeat(32).getBytes(StandardCharsets.UTF_8);
        EncryptedRejectedGenerationRepository repository = repository(key, 10, 262_144);
        RejectedGenerationArtifact artifact = artifact(
                UUID.randomUUID(),
                NOW.plus(Duration.ofHours(1)),
                "response-secret-sentinel");

        repository.create(artifact);

        Path stored = temporaryDirectory.resolve(artifact.operationId() + ".qdat");
        byte[] bytes = Files.readAllBytes(stored);
        assertFalse(new String(bytes, StandardCharsets.UTF_8)
                .contains("response-secret-sentinel"));
        assertFalse(new String(bytes, StandardCharsets.UTF_8)
                .contains("owner-secret-sentinel"));
        assertEquals(artifact, repository.read(artifact.operationId()));
        assertEquals(
                java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(stored));
    }

    @Test
    void rejectsCiphertextWhenTheConfiguredKeyDoesNotMatch() {
        RejectedGenerationArtifact artifact = artifact(
                UUID.randomUUID(),
                NOW.plus(Duration.ofHours(1)),
                "response-secret-sentinel");
        repository("a".repeat(32).getBytes(StandardCharsets.UTF_8), 10, 262_144)
                .create(artifact);
        EncryptedRejectedGenerationRepository wrongKey = repository(
                "b".repeat(32).getBytes(StandardCharsets.UTF_8),
                10,
                262_144);

        assertThrows(
                RejectedGenerationQuarantineException.class,
                () -> wrongKey.read(artifact.operationId()));
    }

    @Test
    void rejectsTamperedCiphertextWithoutReturningAnyPlaintext() throws Exception {
        EncryptedRejectedGenerationRepository repository = repository(
                "k".repeat(32).getBytes(StandardCharsets.UTF_8),
                10,
                262_144);
        RejectedGenerationArtifact artifact = artifact(
                UUID.randomUUID(),
                NOW.plus(Duration.ofHours(1)),
                "response-secret-sentinel");
        repository.create(artifact);
        Path stored = temporaryDirectory.resolve(artifact.operationId() + ".qdat");
        byte[] ciphertext = Files.readAllBytes(stored);
        ciphertext[ciphertext.length - 1] ^= 1;
        Files.write(stored, ciphertext);

        assertThrows(
                RejectedGenerationQuarantineException.class,
                () -> repository.read(artifact.operationId()));
    }

    @Test
    void expiredArtifactsAreDeletedAndBecomeNonEnumerable() {
        EncryptedRejectedGenerationRepository repository = repository(
                "k".repeat(32).getBytes(StandardCharsets.UTF_8),
                10,
                262_144);
        RejectedGenerationArtifact artifact = artifact(
                UUID.randomUUID(),
                NOW.minusSeconds(1),
                "expired");
        repository.create(artifact);

        assertThrows(
                RejectedGenerationNotFoundException.class,
                () -> repository.read(artifact.operationId()));
        assertFalse(Files.exists(
                temporaryDirectory.resolve(artifact.operationId() + ".qdat")));
    }

    @Test
    void duplicateCaptureIsIdempotentOnlyForTheSameRequestAndResponse() {
        EncryptedRejectedGenerationRepository repository = repository(
                "k".repeat(32).getBytes(StandardCharsets.UTF_8),
                10,
                262_144);
        RejectedGenerationArtifact original = artifact(
                UUID.randomUUID(),
                NOW.plusSeconds(60),
                "first");

        repository.create(original);

        assertEquals(original, repository.create(original));
        RejectedGenerationArtifact conflicting = new RejectedGenerationArtifact(
                original.formatVersion(),
                original.operationId(),
                original.ownerId(),
                original.capturedAt(),
                original.expiresAt(),
                original.promptReleaseId(),
                original.promptBundleSha256(),
                original.schemaId(),
                original.schemaVersion(),
                original.schemaSha256(),
                original.evaluationPolicyVersion(),
                original.evaluationPolicySha256(),
                original.parserVersion(),
                original.claimPolicyVersion(),
                original.generationRequestSha256(),
                "f".repeat(64),
                "different",
                original.modelId(),
                original.inputTokens(),
                original.outputTokens(),
                original.totalTokens(),
                original.auditEvents());
        assertThrows(
                RejectedGenerationQuarantineException.class,
                () -> repository.create(conflicting));
    }

    @Test
    void failsClosedAtTheConfiguredArtifactCountAndSizeLimits() {
        EncryptedRejectedGenerationRepository countLimited = repository(
                "k".repeat(32).getBytes(StandardCharsets.UTF_8),
                1,
                16_384);
        countLimited.create(artifact(
                UUID.randomUUID(), NOW.plusSeconds(60), "first"));

        assertFalse(countLimited.health().ready());
        assertEquals(1, countLimited.health().artifactCount());

        assertThrows(
                RejectedGenerationQuarantineException.class,
                () -> countLimited.create(artifact(
                        UUID.randomUUID(), NOW.plusSeconds(60), "second")));

        Path separateDirectory = temporaryDirectory.resolve("size");
        EncryptedRejectedGenerationRepository sizeLimited = repository(
                "z".repeat(32).getBytes(StandardCharsets.UTF_8),
                10,
                16_384,
                separateDirectory);
        assertThrows(
                RejectedGenerationQuarantineException.class,
                () -> sizeLimited.create(artifact(
                        UUID.randomUUID(),
                        NOW.plusSeconds(60),
                        "x".repeat(20_000))));
    }

    @Test
    void rejectsWeakReusedOrMalformedSecretsAtStartup() {
        RejectedGenerationQuarantineProperties malformedKey = properties(
                temporaryDirectory,
                Base64.getEncoder().encodeToString(new byte[31]),
                OPERATOR_TOKEN,
                10,
                262_144);
        assertThrows(
                IllegalStateException.class,
                () -> repository(malformedKey).initialize());

        RejectedGenerationQuarantineProperties reusedToken = properties(
                temporaryDirectory,
                Base64.getEncoder().encodeToString(new byte[32]),
                GATEWAY_TOKEN,
                10,
                262_144);
        assertThrows(
                IllegalStateException.class,
                () -> repository(reusedToken).initialize());
    }

    private EncryptedRejectedGenerationRepository repository(
            byte[] key,
            int maxArtifacts,
            int maxArtifactBytes) {
        return repository(key, maxArtifacts, maxArtifactBytes, temporaryDirectory);
    }

    private EncryptedRejectedGenerationRepository repository(
            byte[] key,
            int maxArtifacts,
            int maxArtifactBytes,
            Path directory) {
        RejectedGenerationQuarantineProperties properties = properties(
                directory,
                Base64.getEncoder().encodeToString(key),
                OPERATOR_TOKEN,
                maxArtifacts,
                maxArtifactBytes);
        EncryptedRejectedGenerationRepository repository = repository(properties);
        repository.initialize();
        return repository;
    }

    private EncryptedRejectedGenerationRepository repository(
            RejectedGenerationQuarantineProperties properties) {
        return new EncryptedRejectedGenerationRepository(
                properties,
                new ObjectMapper().findAndRegisterModules(),
                new CvCoverLetterGatewayCredentials(GATEWAY_TOKEN),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom());
    }

    private RejectedGenerationQuarantineProperties properties(
            Path directory,
            String key,
            String operatorToken,
            int maxArtifacts,
            int maxArtifactBytes) {
        RejectedGenerationQuarantineProperties properties =
                new RejectedGenerationQuarantineProperties();
        properties.setEnabled(true);
        properties.setStorageDirectory(directory.toString());
        properties.setEncryptionKeyBase64(key);
        properties.setOperatorToken(operatorToken);
        properties.setRetention(Duration.ofHours(1));
        properties.setMaxArtifacts(maxArtifacts);
        properties.setMaxArtifactBytes(maxArtifactBytes);
        properties.setMaxReplayEvents(10);
        return properties;
    }

    private RejectedGenerationArtifact artifact(
            UUID operationId,
            Instant expiresAt,
            String responseJson) {
        RejectedGenerationDiagnostic diagnostic = new RejectedGenerationDiagnostic(
                "CLAIM_EVIDENCE",
                "$.claims[0].contentPaths[13]",
                "Invalid content path.");
        RejectedGenerationReplayAuditEvent event =
                new RejectedGenerationReplayAuditEvent(
                        1,
                        NOW,
                        "CAPTURED",
                        "REJECTED",
                        "a".repeat(64),
                        diagnostic,
                        null,
                        "b".repeat(64));
        return new RejectedGenerationArtifact(
                1,
                operationId,
                "owner-secret-sentinel",
                NOW,
                expiresAt,
                "cv-cover-letter-1.5.9",
                "c".repeat(64),
                "cv-cover-letter-output",
                "3.8.0",
                "d".repeat(64),
                "1.5.7",
                "e".repeat(64),
                "3.5.2",
                "2.13.0",
                "f".repeat(64),
                "0".repeat(64),
                responseJson,
                "gpt-4.1-mini-2025-04-14",
                30_000L,
                4_000L,
                34_000L,
                List.of(event));
    }
}
