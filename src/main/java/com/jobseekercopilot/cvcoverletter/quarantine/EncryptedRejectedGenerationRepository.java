package com.jobseekercopilot.cvcoverletter.quarantine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationNotFoundException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationQuarantineException;
import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterGatewayCredentials;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
public class EncryptedRejectedGenerationRepository {
    private static final byte[] MAGIC = "JSCQ1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AAD_PREFIX =
            "job-seeker-copilot/rejected-generation/v1/"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int MINIMUM_TOKEN_BYTES = 32;
    private static final int MINIMUM_ARTIFACT_BYTES = 16_384;
    private static final int MAXIMUM_ARTIFACT_BYTES = 1_048_576;
    private static final int MAXIMUM_ARTIFACTS = 10_000;
    private static final int MAXIMUM_REPLAY_EVENTS = 1_000;
    private static final Duration MINIMUM_RETENTION = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_RETENTION = Duration.ofDays(7);
    private static final Duration MINIMUM_CLEANUP_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_CLEANUP_INTERVAL = Duration.ofHours(24);
    private static final int OPERATION_LOCK_STRIPES = 64;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final RejectedGenerationQuarantineProperties properties;
    private final ObjectMapper objectMapper;
    private final CvCoverLetterGatewayCredentials gatewayCredentials;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Object[] operationLocks = IntStream.range(0, OPERATION_LOCK_STRIPES)
            .mapToObj(ignored -> new Object())
            .toArray();
    private final Object capacityLock = new Object();

    private Path directory;
    private SecretKey encryptionKey;

    @Autowired
    public EncryptedRejectedGenerationRepository(
            RejectedGenerationQuarantineProperties properties,
            ObjectMapper objectMapper,
            CvCoverLetterGatewayCredentials gatewayCredentials) {
        this(
                properties,
                objectMapper,
                gatewayCredentials,
                Clock.systemUTC(),
                new SecureRandom());
    }

    EncryptedRejectedGenerationRepository(
            RejectedGenerationQuarantineProperties properties,
            ObjectMapper objectMapper,
            CvCoverLetterGatewayCredentials gatewayCredentials,
            Clock clock,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.gatewayCredentials = gatewayCredentials;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) {
            return;
        }
        validateConfiguration();
        try {
            directory = Path.of(properties.getStorageDirectory()).normalize();
            if (!directory.isAbsolute()) {
                throw new IllegalStateException(
                        "Rejected generation quarantine directory must be absolute.");
            }
            if (directory.getNameCount() < 2) {
                throw new IllegalStateException(
                        "Rejected generation quarantine directory is too broad.");
            }
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(directory)) {
                throw new IllegalStateException(
                        "Rejected generation quarantine directory must not be a symbolic link.");
            }
            Files.createDirectories(directory);
            setPermissions(directory, DIRECTORY_PERMISSIONS);
            Path readinessProbe = Files.createTempFile(directory, ".readiness-", ".tmp");
            try {
                setPermissions(readinessProbe, FILE_PERMISSIONS);
            } finally {
                Files.deleteIfExists(readinessProbe);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Rejected generation quarantine storage is not writable.",
                    exception);
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Duration retention() {
        return properties.getRetention();
    }

    public int maxReplayEvents() {
        return properties.getMaxReplayEvents();
    }

    public RepositoryHealth health() {
        if (!properties.isEnabled()) {
            return new RepositoryHealth(false, true, 0, 0);
        }
        try {
            boolean directoryReady = Files.isDirectory(
                            directory,
                            LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(directory)
                    && Files.isWritable(directory);
            int count = artifactCount();
            long usableBytes = Files.getFileStore(directory).getUsableSpace();
            boolean ready = directoryReady
                    && count < properties.getMaxArtifacts()
                    && usableBytes > properties.getMaxArtifactBytes();
            return new RepositoryHealth(true, ready, count, usableBytes);
        } catch (IOException | RuntimeException exception) {
            return new RepositoryHealth(true, false, -1, -1);
        }
    }

    public RejectedGenerationArtifact create(RejectedGenerationArtifact artifact) {
        requireEnabled();
        synchronized (operationLock(artifact.operationId())) {
            synchronized (capacityLock) {
                purgeExpiredInternal();
                Path target = artifactPath(artifact.operationId());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    RejectedGenerationArtifact existing = readInternal(artifact.operationId());
                    if (MessageDigest.isEqual(
                                    existing.responseSha256().getBytes(StandardCharsets.US_ASCII),
                                    artifact.responseSha256().getBytes(StandardCharsets.US_ASCII))
                            && MessageDigest.isEqual(
                                    existing.generationRequestSha256()
                                            .getBytes(StandardCharsets.US_ASCII),
                                    artifact.generationRequestSha256()
                                            .getBytes(StandardCharsets.US_ASCII))
                            && MessageDigest.isEqual(
                                    existing.ownerId().getBytes(StandardCharsets.UTF_8),
                                    artifact.ownerId().getBytes(StandardCharsets.UTF_8))) {
                        return existing;
                    }
                    throw new RejectedGenerationQuarantineException(
                            "A different rejected response already exists for this operation.");
                }
                if (artifactCount() >= properties.getMaxArtifacts()) {
                    throw new RejectedGenerationQuarantineException(
                            "Rejected generation quarantine capacity has been reached.");
                }
                writeInternal(artifact, false);
                return artifact;
            }
        }
    }

    public RejectedGenerationArtifact read(UUID operationId) {
        requireEnabled();
        synchronized (operationLock(operationId)) {
            RejectedGenerationArtifact artifact = readInternal(operationId);
            if (!artifact.expiresAt().isAfter(clock.instant())) {
                deleteInternal(operationId);
                throw new RejectedGenerationNotFoundException();
            }
            return artifact;
        }
    }

    public RejectedGenerationArtifact replace(RejectedGenerationArtifact artifact) {
        requireEnabled();
        synchronized (operationLock(artifact.operationId())) {
            if (!Files.exists(artifactPath(artifact.operationId()), LinkOption.NOFOLLOW_LINKS)) {
                throw new RejectedGenerationNotFoundException();
            }
            writeInternal(artifact, true);
            return artifact;
        }
    }

    public void delete(UUID operationId) {
        requireEnabled();
        synchronized (operationLock(operationId)) {
            if (!deleteInternal(operationId)) {
                throw new RejectedGenerationNotFoundException();
            }
        }
    }

    public int purgeExpired() {
        requireEnabled();
        synchronized (capacityLock) {
            return purgeExpiredInternal();
        }
    }

    private int purgeExpiredInternal() {
        int purged = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.qdat")) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    continue;
                }
                UUID operationId = operationId(entry);
                if (operationId == null) {
                    continue;
                }
                try {
                    RejectedGenerationArtifact artifact = readInternal(operationId);
                    if (!artifact.expiresAt().isAfter(clock.instant())
                            && deleteInternal(operationId)) {
                        purged++;
                    }
                } catch (RejectedGenerationQuarantineException exception) {
                    log.error(
                            "Rejected generation quarantine artifact could not be inspected operationId={} failureType={}",
                            operationId,
                            exception.getClass().getSimpleName());
                }
            }
            return purged;
        } catch (IOException exception) {
            throw storageFailure("Rejected generation quarantine cleanup failed.", exception);
        }
    }

    private void validateConfiguration() {
        if (properties.getRetention() == null
                || properties.getRetention().compareTo(MINIMUM_RETENTION) < 0
                || properties.getRetention().compareTo(MAXIMUM_RETENTION) > 0) {
            throw new IllegalStateException(
                    "Rejected generation quarantine retention must be between 5 minutes and 7 days.");
        }
        if (properties.getCleanupInterval() == null
                || properties.getCleanupInterval().compareTo(MINIMUM_CLEANUP_INTERVAL) < 0
                || properties.getCleanupInterval().compareTo(MAXIMUM_CLEANUP_INTERVAL) > 0) {
            throw new IllegalStateException(
                    "Rejected generation quarantine cleanup interval must be between 1 minute and 24 hours.");
        }
        if (properties.getMaxArtifactBytes() < MINIMUM_ARTIFACT_BYTES
                || properties.getMaxArtifactBytes() > MAXIMUM_ARTIFACT_BYTES) {
            throw new IllegalStateException(
                    "Rejected generation quarantine artifact limit must be between 16 KiB and 1 MiB.");
        }
        if (properties.getMaxArtifacts() < 1
                || properties.getMaxArtifacts() > MAXIMUM_ARTIFACTS) {
            throw new IllegalStateException(
                    "Rejected generation quarantine count limit must be between 1 and 10000.");
        }
        if (properties.getMaxReplayEvents() < 1
                || properties.getMaxReplayEvents() > MAXIMUM_REPLAY_EVENTS) {
            throw new IllegalStateException(
                    "Rejected generation replay event limit must be between 1 and 1000.");
        }
        String operatorToken = properties.getOperatorToken();
        if (operatorToken == null
                || operatorToken.isBlank()
                || operatorToken.getBytes(StandardCharsets.UTF_8).length
                        < MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(
                    "Rejected generation operator token must contain at least 32 bytes.");
        }
        if (MessageDigest.isEqual(
                gatewayCredentials.gatewayToken().getBytes(StandardCharsets.UTF_8),
                operatorToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                    "Rejected generation operator token must be distinct from the Gateway token.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(properties.getEncryptionKeyBase64());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "Rejected generation quarantine key must decode to 32 bytes.");
            }
            encryptionKey = new SecretKeySpec(decoded, "AES");
            Arrays.fill(decoded, (byte) 0);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Rejected generation quarantine key must be valid Base64.",
                    exception);
        }
    }

    private void writeInternal(RejectedGenerationArtifact artifact, boolean replace) {
        byte[] plaintext;
        try {
            plaintext = objectMapper.writeValueAsBytes(artifact);
        } catch (IOException exception) {
            throw storageFailure("Rejected generation artifact could not be serialized.", exception);
        }
        if (plaintext.length > properties.getMaxArtifactBytes()) {
            throw new RejectedGenerationQuarantineException(
                    "Rejected generation artifact exceeds the configured size limit.");
        }
        byte[] sealed = encrypt(artifact.operationId(), plaintext);
        Path target = artifactPath(artifact.operationId());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".write-", ".tmp");
            Files.write(
                    temporary,
                    sealed,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            setPermissions(temporary, FILE_PERMISSIONS);
            move(temporary, target, replace);
            temporary = null;
        } catch (IOException exception) {
            throw storageFailure("Rejected generation artifact could not be stored.", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary storage failure is preserved above.
                }
            }
        }
    }

    private RejectedGenerationArtifact readInternal(UUID operationId) {
        Path path = artifactPath(operationId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new RejectedGenerationNotFoundException();
        }
        try {
            long maximumSealedBytes = (long) properties.getMaxArtifactBytes()
                    + MAGIC.length
                    + NONCE_BYTES
                    + (GCM_TAG_BITS / 8);
            long size = Files.size(path);
            if (size <= MAGIC.length + NONCE_BYTES + (GCM_TAG_BITS / 8)
                    || size > maximumSealedBytes) {
                throw new RejectedGenerationQuarantineException(
                        "Rejected generation artifact has an invalid size.");
            }
            byte[] plaintext = decrypt(operationId, Files.readAllBytes(path));
            RejectedGenerationArtifact artifact =
                    objectMapper.readValue(plaintext, RejectedGenerationArtifact.class);
            if (artifact.formatVersion() != 1
                    || !operationId.equals(artifact.operationId())) {
                throw new RejectedGenerationQuarantineException(
                        "Rejected generation artifact identity is invalid.");
            }
            return artifact;
        } catch (RejectedGenerationQuarantineException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure("Rejected generation artifact could not be read.", exception);
        }
    }

    private byte[] encrypt(UUID operationId, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(operationId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(MAGIC.length + nonce.length + ciphertext.length)
                    .put(MAGIC)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw storageFailure("Rejected generation artifact encryption failed.", exception);
        }
    }

    private byte[] decrypt(UUID operationId, byte[] sealed) {
        ByteBuffer buffer = ByteBuffer.wrap(sealed);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!MessageDigest.isEqual(MAGIC, magic)) {
            throw new RejectedGenerationQuarantineException(
                    "Rejected generation artifact format is invalid.");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(operationId));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw storageFailure(
                    "Rejected generation artifact authentication failed.",
                    exception);
        }
    }

    private byte[] aad(UUID operationId) {
        byte[] operation = operationId.toString().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(AAD_PREFIX.length + operation.length)
                .put(AAD_PREFIX)
                .put(operation)
                .array();
    }

    private void move(Path source, Path target, boolean replace) throws IOException {
        StandardCopyOption[] atomicOptions = replace
                ? new StandardCopyOption[] {
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                }
                : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(source, target, atomicOptions);
        } catch (AtomicMoveNotSupportedException exception) {
            StandardCopyOption[] fallbackOptions = replace
                    ? new StandardCopyOption[] {StandardCopyOption.REPLACE_EXISTING}
                    : new StandardCopyOption[0];
            Files.move(source, target, fallbackOptions);
        }
    }

    private int artifactCount() {
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.qdat")) {
            for (Path entry : entries) {
                if (!Files.isSymbolicLink(entry)) {
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            throw storageFailure("Rejected generation quarantine capacity check failed.", exception);
        }
    }

    private boolean deleteInternal(UUID operationId) {
        try {
            return Files.deleteIfExists(artifactPath(operationId));
        } catch (IOException exception) {
            throw storageFailure("Rejected generation artifact could not be deleted.", exception);
        }
    }

    private Path artifactPath(UUID operationId) {
        if (operationId == null) {
            throw new RejectedGenerationNotFoundException();
        }
        Path path = directory.resolve(operationId + ".qdat").normalize();
        if (!path.getParent().equals(directory)) {
            throw new RejectedGenerationNotFoundException();
        }
        return path;
    }

    private Object operationLock(UUID operationId) {
        if (operationId == null) {
            throw new RejectedGenerationNotFoundException();
        }
        int index = (operationId.hashCode() & Integer.MAX_VALUE)
                % OPERATION_LOCK_STRIPES;
        return operationLocks[index];
    }

    private UUID operationId(Path entry) {
        String name = entry.getFileName().toString();
        if (!name.endsWith(".qdat")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - 5));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new RejectedGenerationNotFoundException();
        }
    }

    private RejectedGenerationQuarantineException storageFailure(
            String message,
            Exception cause) {
        return new RejectedGenerationQuarantineException(message, cause);
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems rely on their mounted ACL policy.
        }
    }

    public record RepositoryHealth(
            boolean enabled,
            boolean ready,
            int artifactCount,
            long usableBytes) {
    }
}
