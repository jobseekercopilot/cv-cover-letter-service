package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.dto.ValidatedClaimLedger;
import com.jobseekercopilot.cvcoverletter.dto.ValidatedClaimLedger.ValidatedClaim;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ValidatedClaimLedgerFactory {

    private static final String DIGEST_FORMAT = "claim-ledger-v1";

    public ValidatedClaimLedger create(
            UUID operationId, List<GeneratedClaim> generatedClaims) {
        if (operationId == null) {
            throw new IllegalArgumentException(
                    "Generation operation ID is required.");
        }
        if (generatedClaims == null || generatedClaims.isEmpty()) {
            throw new IllegalStateException(
                    "Validated generation claim ledger is missing.");
        }
        List<ValidatedClaim> claims = generatedClaims.stream()
                .map(this::copy)
                .toList();
        String sha256 = digest(claims);
        return new ValidatedClaimLedger(
                ledgerId(operationId, sha256),
                sha256,
                ClaimEvidenceValidator.POLICY_VERSION,
                LlmResponseParser.PARSER_VERSION,
                claims);
    }

    private ValidatedClaim copy(GeneratedClaim source) {
        if (source == null
                || source.getClaimId() == null
                || source.getDisposition() == null
                || source.getEvidenceIds() == null
                || source.getContentPaths() == null
                || source.getReviewText() == null) {
            throw new IllegalStateException(
                    "Validated generation claim ledger is incomplete.");
        }
        return new ValidatedClaim(
                source.getClaimId(),
                source.getDisposition(),
                source.getEvidenceIds(),
                source.getContentPaths(),
                source.getReviewText());
    }

    private String digest(List<ValidatedClaim> claims) {
        MessageDigest digest = sha256();
        update(digest, DIGEST_FORMAT);
        update(digest, ClaimEvidenceValidator.POLICY_VERSION);
        update(digest, LlmResponseParser.PARSER_VERSION);
        update(digest, claims.size());
        for (ValidatedClaim claim : claims) {
            update(digest, claim.claimId());
            update(digest, claim.disposition().name());
            update(digest, claim.evidenceIds());
            update(digest, claim.contentPaths());
            update(digest, claim.reviewText());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private UUID ledgerId(UUID operationId, String ledgerSha256) {
        MessageDigest digest = sha256();
        update(digest, "claim-ledger-id-v1");
        update(digest, operationId.toString());
        update(digest, ledgerSha256);
        byte[] bytes = digest.digest();
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private void update(MessageDigest digest, List<String> values) {
        update(digest, values.size());
        values.forEach(value -> update(digest, value));
    }

    private void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value)
                .array());
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.", impossible);
        }
    }
}
